package com.apex.agent.core.tools.extension.dsh

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.DuplicateToolIdPolicy
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.extension.ExtensionTypes
import com.apex.agent.core.tools.extension.NodeInstallPort
import com.apex.agent.core.tools.pluginhost.bridge.PluginAgentTool
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # DshToolRegistrar —— DSH 能力快照 → 一等工具注册器（「装完 ≠ 生效」收尾）
 *
 * 核查结论（核查稿 §4.4 卡点）：`DshToolRegistrar` 此前只存在于 KDoc 引用
 * （`DshCapabilities.kt:18` / `DshCapabilitiesStore.kt:10`），没有类文件——
 * `capabilities.json` 的实际消费者只有 Doctor 账实探针与测试断言，**能力
 * 快照从未变成 ToolRegistry 里的一等工具**，模型在任务中无法直接调用
 * DSH 插件工具。
 *
 * 本注册器补上最后一公里（对齐 McpToolRegistrar 的竞态纪律）：
 *
 * - **红线继承**：只注册 `available=true` 的 observed 工具——静态线索不
 *   产生工具，`unavailableReason` 如实留在 notes；
 * - **快照新鲜度**：注册前用 [DshPackageFingerprint] 重算当前包指纹，
 *   [DshCapabilitiesStore.read] 按指纹校验——过期快照（插件已升级/被改）
 *   返回 null，绝不拿旧账注册；
 * - **冷启动执行**：DSH 的 `execute` 是插件进程内 JS 闭包，不能序列化
 *   搬运——每个工具经沙箱 `node dsh-run-tool.js <pkg> <tool>`（参数走
 *   stdin JSON，红线：结构化数据永不拼命令串）真实执行；有状态插件冷
 *   启动归零属 P0 已知降级（P2 持久 Worker 前的语义基线）；
 * - **竞态治理**：每插件一把 Mutex 串行化注册/注销（同 McpToolRegistrar
 *   修过的「异步发现期间卸载留下幽灵工具」）；
 * - **防劫持**：stale 清算只触碰自己注册的 id 集（`Plugin` 族前缀之外
 *   的工具绝不 unregister）。
 *
 * 触发路径：装后钩子（ExtensionInstaller → InstallLifecycleHook）单插件
 * 注册 + 宿主启动 sweep（app 层 ToolModule 对账本 dsh_plugin 记录调
 * [start]）。
 */
class DshToolRegistrar(
    /**
     * 注册表惰性提供者：宿主 DI 里 ToolRegistry 的装配依赖本注册器（启动
     * sweep 挂在 provideToolRegistry），直接注入实例会成环——经函数参数
     * 把解析推迟到首次注册（DI 图构建完成之后），环在构造期断裂。
     */
    private val registryProvider: () -> ToolRegistry,
    private val store: DshCapabilitiesStore,
    private val layout: ExtensionDirLayout,
    private val port: NodeInstallPort,
    private val extensionRoot: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val registry: ToolRegistry get() = registryProvider()

    /** pluginId → 已注册的一等工具 id 集（stale 清算与卸载注销用）。 */
    private val registered = ConcurrentHashMap<String, Set<String>>()

    /** 每插件一把锁（注册/注销互斥——异步 sweep 与装后钩子可能并发）。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 注册单个 DSH 插件的 available 工具。
     *
     * @return notes（装后钩子并入 InstallOutcome；启动 sweep 走日志）
     */
    suspend fun registerPlugin(extId: String): List<String> = lockFor(extId).withLock {
        val pkgDir = layout.pkgDir(extId)
        if (!pkgDir.isDirectory) {
            return listOf("DSH 工具注册跳过：pkg 目录缺失（$extId）")
        }
        val pkg = try {
            DshPackageLayout.pluginRootOf(pkgDir)
        } catch (e: IllegalStateException) {
            return listOf("DSH 工具注册跳过：${e.message}")
        }
        // 快照新鲜度：当前包指纹与能力快照的 inputFingerprint 不一致 = 过期，
        // 绝不拿旧账注册（DshCapabilitiesStore 红线）。
        val currentFingerprint = DshPackageFingerprint.compute(pkg).fingerprint
        val caps = store.read(extId, currentFingerprint = currentFingerprint)
            ?: return listOf(
                "DSH 工具注册跳过：能力快照缺失或已过期（重新 ExtensionInstall 触发探测）"
            )
        val available = caps.availableTools
        if (available.isEmpty()) {
            return listOf("DSH 插件无可用工具（${caps.summaryLine()}）—— observed 面为空或服务依赖不满足")
        }
        // 冷执行器依赖宿主脚本（dsh-run-tool.js），幂等部署
        val scripts = DshHostScripts.deploy(extensionRoot).getOrElse {
            return listOf("DSH 工具注册跳过：宿主脚本部署失败（${it.message}）")
        }
        val runToolScript = File(scripts, "dsh-run-tool.js")

        val newIds = mutableListOf<String>()
        for (tool in available) {
            val agentTool = PluginAgentTool(
                pluginId = extId,
                ecosystem = com.apex.agent.core.tools.pluginhost.PluginEcosystem.DSH,
                tool = PluginAgentTool.ToolDescriptor(
                    name = tool.name,
                    description = tool.description.ifBlank { "DSH tool '${tool.name}' (cold-start, no description)." },
                    parametersSchema = tool.parametersSchema
                ),
                executor = coldStartExecutor(pkg, runToolScript)
            )
            registry.register(agentTool, DuplicateToolIdPolicy.REPLACE)
            newIds += agentTool.id
        }
        // stale 清算（插件升级后工具面缩水的差集；只动自己注册过的 id）
        (registered[extId].orEmpty() - newIds.toSet()).forEach { staleId ->
            registry.unregister(staleId)
        }
        registered[extId] = newIds.toSet()

        val unavailableNotes = caps.tools
            .filter { !it.available }
            .take(UNAVAILABLE_NOTE_CAP)
            .joinToString("; ") { "${it.name}: ${it.unavailableReason ?: "unavailable"}" }
        return buildList {
            add("DSH 工具已注册一等（cold-start）：${newIds.joinToString(", ")}")
            if (unavailableNotes.isNotEmpty()) {
                add("未注册（红线：observed 不可用）—— $unavailableNotes")
            }
        }
    }

    /** 注销插件全部工具（卸前钩子；幂等）。 */
    suspend fun unregisterPlugin(extId: String) {
        lockFor(extId).withLock {
            registered.remove(extId)?.forEach { registry.unregister(it) }
        }
    }

    /**
     * 宿主启动 sweep：对账本全部 DSH 记录补注册（修「装完重启后工具面丢失」
     * ——注册表是进程内的，重启即空）。IO scope 自持，失败只记日志。
     */
    fun start(ledger: ExtensionRegistry) {
        scope.launch {
            val dshRecords = ledger.all().filter { it.type == ExtensionTypes.DSH_PLUGIN }
            if (dshRecords.isEmpty()) return@launch
            for (record in dshRecords) {
                val notes = runCatching { registerPlugin(record.id) }
                    .getOrElse { listOf("注册异常：${it.message}") }
                notes.forEach { note ->
                    AppLogger.instance.info(LogCategory.PLUGIN, TAG, "sweep ${record.id}: $note")
                }
            }
        }
    }

    /** 已注册面快照（诊断）。 */
    fun registeredSnapshot(): Map<String, Set<String>> = registered.toMap()

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun lockFor(extId: String): Mutex =
        locks.getOrPut(extId) { Mutex() }

    /**
     * 冷启动执行器：`node dsh-run-tool.js <pkg> <tool>` + stdin JSON 参数。
     *
     * 输出契约（dsh-run-tool.js）：stdout 恰好一个 JSON envelope
     * `{ok, tool, result|error{message}}`——ok=false 走失败语义
     * （PluginAgentTool 的 "Error:" 前缀协议，引擎的成败判定是字符串级的）。
     */
    private fun coldStartExecutor(pkg: File, runToolScript: File): PluginAgentTool.PluginToolExecutor =
        PluginAgentTool.PluginToolExecutor { _, toolName, argsJson ->
            port.exec(
                command = listOf(
                    "node",
                    runToolScript.canonicalFile.absolutePath,
                    pkg.canonicalFile.absolutePath,
                    toolName
                ),
                cwd = pkg,
                timeoutMs = RUN_TOOL_TIMEOUT_MS,
                stdin = argsJson
            ).fold(
                onSuccess = { outcome -> parseRunToolEnvelope(outcome) },
                onFailure = {
                    PluginAgentTool.ToolExecutionOutcome.failure(
                        "cold-start 执行通道失败：${it.message ?: it::class.simpleName}"
                    )
                }
            )
        }

    private fun parseRunToolEnvelope(
        outcome: NodeInstallPort.ExecOutcome
    ): PluginAgentTool.ToolExecutionOutcome {
        val envelope = runCatching {
            json.parseToJsonElement(outcome.stdout.trim()).jsonObject
        }.getOrNull()
            ?: return PluginAgentTool.ToolExecutionOutcome.failure(
                "dsh-run-tool 输出非 JSON（exit ${outcome.exitCode}）：" +
                    "${(outcome.stderr.ifBlank { outcome.stdout }).take(200)}"
            )
        val ok = envelope["ok"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } == "true"
        return if (ok) {
            PluginAgentTool.ToolExecutionOutcome.success(
                envelope["result"]?.toString() ?: "(empty result)"
            )
        } else {
            val message = envelope["error"]?.jsonObject?.get("message")
                ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?: "exit ${outcome.exitCode}"
            PluginAgentTool.ToolExecutionOutcome.failure(message)
        }
    }

    companion object {
        private const val TAG = "DshToolRegistrar"

        /**
         * 冷执行宿主侧超时：JS 侧 execute 默认 120s（DSH_TOOL_TIMEOUT_MS），
         * 宿主 150s 兜底（JS 先自灭并输出 envelope，宿主超时是最后防线）。
         */
        const val RUN_TOOL_TIMEOUT_MS = 150_000L

        /** notes 里 unavailable 明细的截断（防话痨插件刷屏 InstallOutcome）。 */
        const val UNAVAILABLE_NOTE_CAP = 8
    }
}
