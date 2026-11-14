package com.apex.agent.core.tools.extension

/**
 * # ExtensionInstaller —— 统一安装闸门（resolve → inspect → install 编排）
 *
 * brief 的核心强制项在编排层落地：
 *
 * 1. **hash 门（先看后装）**：[install] 必须回传 [ExtensionManifest.manifestHash]
 *    ——即模型必须先走 [resolve]/[inspect] 拿到指纹才能装。跳过 inspect 直接装
 *    会在门上被拒（`PERMISSION_DENIED`），不是建议而是硬闸；
 * 2. **篡改检测**：回传 hash 还要与 manifest 本体重算指纹比对 —— 中途被换包
 *    （上下文投递篡改 / 会话回放错位）直接拒绝；
 * 3. **确认门**：含敏感权限或生命周期脚本的扩展，install 需显式
 *    `confirm="confirm"` 令牌（模型代表用户确认过 inspect 报告）。BYPASS
 *    权限模式**不豁免**本确认（不可信内容的目检与命令门是两回事）；
 * 4. **账本登记**：install 成功后写入 [ExtensionRegistry]（来源回溯 + 锁基础）；
 * 5. **装后钩子**（Phase 6 收尾：装完 ≠ 生效）：[InstallLifecycleHook] 在
 *    适配器安装成功后触发（opencode 装后自动启动 Worker、DSH 能力快照转
 *    一等工具等）。钩子失败**不炸安装**——安装是既成事实，启动/注册失败
 *    如实进 notes（模型可诊断后重装重试）。
 */
class ExtensionInstaller(
    private val adapters: ExtensionAdapterRegistry,
    private val registry: ExtensionRegistry,
    /** 装后/卸前生命周期钩子（null = 无 —— 纯转译安装链保持原行为）。 */
    private val lifecycleHook: InstallLifecycleHook? = null
) {

    /**
     * resolve/inspect 产物缓存（hash → manifest）：install 只需回传指纹 ——
     * 指纹即「看过这件东西」的凭证，manifest 本体从本缓存按指纹取回。
     * 指纹由 manifest 全量内容 sha-256 而来，缓存命中 = 内容自洽，无篡改面。
     */
    private val resolvedCache = java.util.concurrent.ConcurrentHashMap<String, ExtensionManifest>()

    /** 认路：spec → manifest（类型未注册适配器时诚实报错）。 */
    suspend fun resolve(spec: String): Result<ExtensionManifest> {
        val draft = ExtensionSpecParser.parse(spec).getOrElse { return Result.failure(it) }
        if (draft.type != null && adapters.forType(draft.type) == null) {
            return Result.failure(
                UnsupportedOperationException(
                    "扩展类型 '${draft.type}' 的适配器尚未接入（当前支持：${adapters.availableTypes().sorted().joinToString(", ")}）"
                )
            )
        }
        // type = null（github: / 裸 URL 等待探测形态）：交给能接的适配器逐个试。
        val candidates = if (draft.type != null) {
            listOfNotNull(adapters.forType(draft.type))
        } else {
            adapters.availableTypes().mapNotNull { adapters.forType(it) }
        }
        var lastError: Throwable? = null
        for (adapter in candidates) {
            val outcome = adapter.resolve(draft)
            if (outcome.isSuccess) {
                val manifest = outcome.getOrThrow()
                resolvedCache[manifest.manifestHash] = manifest
                return outcome
            }
            lastError = outcome.exceptionOrNull()
        }
        return Result.failure(
            lastError ?: IllegalStateException("没有适配器能解析 spec '$spec'")
        )
    }

    /** 看清：manifest → inspect 报告（产物入缓存，install 可凭指纹取回）。 */
    suspend fun inspect(manifest: ExtensionManifest): Result<InspectReport> {
        val adapter = adapters.forType(manifest.type)
            ?: return Result.failure(notImplemented(manifest.type))
        resolvedCache[manifest.manifestHash] = manifest
        return adapter.inspect(manifest)
    }

    /** 按 spec 走 inspect（resolve 内联 —— 工具层便捷入口）。 */
    suspend fun inspectSpec(spec: String): Result<InspectReport> =
        resolve(spec).getOrElse { return Result.failure(it) }.let { inspect(it) }

    /**
     * 装：hash 门 + 篡改检测 + 确认门 + 适配器 install + 账本登记。
     *
     * @param manifestHash resolve/inspect 产物的指纹（**先看后装的硬凭证**）
     * @param manifestJson 可选的 manifest 原文；缺省从 resolve 缓存按指纹取回
     *   （上下文经济：模型只需传 hash，不必回传可能数十 KB 的 manifest）；
     *   传入时重算指纹必须与 [manifestHash] 一致（防中途换包）
     * @param confirmToken 敏感扩展的显式确认令牌（仅接受 "confirm"）
     */
    suspend fun install(
        manifestHash: String,
        manifestJson: String? = null,
        confirmToken: String? = null
    ): Result<InstallOutcome> {
        // ── hash 门：先看后装 ──
        if (manifestHash.isBlank()) {
            return Result.failure(
                SecurityException(
                    "缺少 manifest_hash —— 先 ExtensionResolve / ExtensionInspect 拿到指纹再安装（先看后装是硬闸，不可跳过）"
                )
            )
        }

        // ── manifest 取回：缓存命中 or 显式原文重算核验 ──
        val manifest = if (manifestJson.isNullOrBlank()) {
            resolvedCache[manifestHash]
                ?: return Result.failure(
                    SecurityException(
                        "指纹 ${manifestHash.take(12)}… 未在本次会话 resolve/inspect 过 —— 先 ExtensionInspect 再安装（防止凭空安装未看过内容的东西）"
                    )
                )
        } else {
            val parsed = ExtensionManifest.parse(manifestJson)
                ?: return Result.failure(IllegalArgumentException("manifest JSON 非法（schema ${ExtensionManifest.SCHEMA}）"))
            val actual = parsed.computeHash()
            if (!actual.equals(manifestHash, ignoreCase = true)) {
                return Result.failure(
                    SecurityException(
                        "manifest_hash 不匹配（回传 $manifestHash ≠ 重算 $actual）—— manifest 在 inspect 后被改动，拒绝安装；请重新 inspect"
                    )
                )
            }
            parsed
        }

        val adapter = adapters.forType(manifest.type)
            ?: return Result.failure(notImplemented(manifest.type))

        // ── 确认门：敏感权限 / 生命周期脚本（BYPASS 不豁免 —— 不可信内容目检 ≠ 命令门）──
        if (manifest.requiresConfirm() && confirmToken?.lowercase() != CONFIRM_TOKEN) {
            return Result.failure(
                SecurityException(
                    "该扩展需要显式确认（permissions=${manifest.permissions}, hasPostinstall=${manifest.hasPostinstall}）" +
                        "—— 向用户展示 inspect 报告并征得同意后，携带 confirm=\"confirm\" 重新调用"
                )
            )
        }

        // ── 适配器安装（幂等语义由适配器负责）──
        val outcome = adapter.install(manifest).getOrElse { return Result.failure(it) }

        // ── 装后钩子（Phase 6：装完 ≠ 生效 → 自动启动/注册；失败不炸安装）──
        val finalOutcome = lifecycleHook?.let { hook ->
            val hookNotes = runCatching { hook.onInstalled(manifest, outcome) }
                .getOrElse { listOf("装后钩子异常（安装本身已成功）：${it.message ?: it::class.simpleName}") }
            if (hookNotes.isEmpty()) outcome else outcome.copy(notes = outcome.notes + hookNotes)
        } ?: outcome

        // ── 账本登记（同 id 覆盖 = 升级）──
        // 版本以 InstallOutcome 为准（适配器回填安装产物真实版本）——
        // manifest.version 只是 spec 占位（修正四：真实版本不得只活在展示文字里）
        registry.record(
            ExtensionRegistry.Record(
                id = manifest.id,
                type = manifest.type,
                spec = manifest.spec,
                name = manifest.name,
                version = finalOutcome.version.ifBlank { manifest.version },
                runtime = manifest.runtime,
                sourceKind = manifest.sourceKind,
                trustLevel = manifest.trustLevel,
                manifestHash = manifest.manifestHash,
                installedAt = System.currentTimeMillis(),
                notes = finalOutcome.notes,
                pinnedSource = finalOutcome.pinned?.toDescriptor() ?: ""
            )
        )
        return Result.success(finalOutcome)
    }

    /**
     * 声明式批量链（ExtensionSync 专用）：spec 清单逐个 resolve → inspect →
     * install。inspect 报告经 [onInspect] 回调透出（sync 汇总进输出）——
     * 敏感扩展仍被确认门拦截（调用方按 [InstallOutcome]/异常分辨呈现）。
     * [filter] 返回 false = 该项跳过安装（以失败结果呈现跳过原因，如锁漂移）。
     */
    suspend fun installFromSpecs(
        specs: List<String>,
        confirmToken: String? = null,
        filter: ((ExtensionManifest) -> Boolean)? = null,
        onInspect: (suspend (String, InspectReport?) -> Unit)? = null
    ): List<Pair<String, Result<InstallOutcome>>> = specs.map { spec ->
        val outcome = resolve(spec).fold(
            onSuccess = { manifest ->
                if (filter?.invoke(manifest) == false) {
                    Result.failure<InstallOutcome>(
                        IllegalStateException("已跳过：版本锁定中，源指纹与锁不一致（update=true 重钉）")
                    )
                } else {
                    val report = inspect(manifest).getOrNull()
                    onInspect?.invoke(spec, report)
                    install(manifest.manifestHash, confirmToken = confirmToken)
                }
            },
            onFailure = { Result.failure<InstallOutcome>(it) }
        )
        spec to outcome
    }

    /** 卸：账本寻址 → 卸前钩子（停 Worker/注工具）→ 适配器卸载 → 清账。 */
    suspend fun uninstall(id: String): Result<Unit> {
        val record = registry.get(id)
            ?: return Result.failure(IllegalArgumentException("未安装的扩展 id '$id'（用 ExtensionList 查看已装清单）"))
        val adapter = adapters.forType(record.type)
            ?: return Result.failure(notImplemented(record.type))
        // 卸前钩子：停 Worker / 注销一等工具须先于目录清除（cwd/文件还在）。
        // 失败如实继续卸载——目录被清后残留进程由 Supervisor 看门狗收尾。
        runCatching { lifecycleHook?.onUninstalling(record) }
            .onFailure {
                com.apex.agent.core.logging.AppLogger.instance.warn(
                    com.apex.agent.core.logging.LogCategory.PLUGIN,
                    "ExtensionInstaller",
                    "卸前钩子失败 id=$id（继续卸载）: ${it.message}"
                )
            }
        val manifest = ExtensionManifest(
            id = record.id,
            type = record.type,
            spec = record.spec,
            name = record.name,
            version = record.version,
            sourceKind = record.sourceKind,
            runtime = record.runtime,
            trustLevel = record.trustLevel
        )
        adapter.uninstall(manifest).getOrElse { return Result.failure(it) }
        registry.remove(id)
        return Result.success(Unit)
    }

    private fun notImplemented(type: String): UnsupportedOperationException =
        UnsupportedOperationException(
            "扩展类型 '$type' 的适配器尚未接入（当前支持：${adapters.availableTypes().sorted().joinToString(", ")}）"
        )

    companion object {
        /** 显式确认令牌字面量（模型侧协议：confirm="confirm"）。 */
        const val CONFIRM_TOKEN = "confirm"
    }
}

/**
 * # InstallLifecycleHook —— 装后/卸前生命周期钩子（Phase 6：装完 ≠ 生效）
 *
 * 核查结论（docs/plugin-host-2.0.md「Phase 6 之后」）：`OpenCodePluginLoader.load()`
 * 与 `PluginLifecycleManager.start()` 此前 0 生产调用点——插件装完后 Worker
 * 不启动、工具不注册。本钩子把 install 闸门接到运行时：
 *
 * - `onInstalled`：适配器安装成功后触发；返回 notes 并入 [InstallOutcome]
 *   （账本与模型可见）。**实现方必须自行兑错**——异常在此被捕获为一条
 *   失败 note，绝不炸安装（安装是既成事实）；
 * - `onUninstalling`：账本寻址命中后、适配器卸载（目录清除）前触发——
 *   停 Worker / 注销一等工具需要 pkg 目录还在。失败只记日志，卸载继续
 *   （残留进程由 Supervisor 看门狗收尾）。
 */
interface InstallLifecycleHook {
    suspend fun onInstalled(
        manifest: ExtensionManifest,
        outcome: InstallOutcome
    ): List<String> = emptyList()

    suspend fun onUninstalling(record: ExtensionRegistry.Record) {}
}
