package com.apex.agent.core.tools.pluginhost.opencode

import com.apex.agent.core.tools.pluginhost.PluginEcosystem
import com.apex.agent.core.tools.pluginhost.bridge.PluginAgentTool
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import com.apex.agent.core.tools.pluginhost.bridge.PluginToolBridge
import com.apex.agent.core.tools.pluginhost.worker.PluginWorker
import com.apex.agent.core.tools.pluginhost.worker.PluginWorkerSupervisor
import com.apex.agent.core.tools.pluginhost.worker.WorkerProtocol
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * # OpenCodePluginLoader —— V1/V2 插件加载编排器（spec §4.5）
 *
 * 完整链路（对齐 spec §4.5 的 13 步，本类负责 Kotlin 侧编排）：
 *
 * ```
 * 解析 package.json → ABI 探测（V1/V2/UNKNOWN）
 *   → 入口解析（exports/main/module/index，spec §4.3）
 *   → V2 SDK 垫片按需落盘（真实包优先 —— 禁止 node index.js 冒充加载器）
 *   → Worker 启动 + 生态化 load（ecosystem/options/storageFile）
 *   → 工具注册进 ToolRegistry（经 PluginToolBridge）
 *   → 钩子/事件转发订阅（宿主事件 → Worker → OpenCode 钩子）
 * ```
 *
 * 重启恢复：Supervisor 的 LoadSpec 携带生态载荷重放 load；重启后由
 * [rebindAfterRestart] 重放工具注册（新 Worker 的 lastLoaded 即新清单）。
 */
class OpenCodePluginLoader(
    private val supervisor: PluginWorkerSupervisor,
    private val toolBridge: PluginToolBridge,
    private val eventBus: PluginEventBus
) {
    /** 加载产物。 */
    data class LoadOutcome(
        val ecosystem: PluginEcosystem,
        val abi: OpenCodeAbiDetector.Abi,
        val loaded: WorkerProtocol.Loaded,
        val registeredTools: List<String>,
        val rejectedTools: List<PluginToolBridge.BindResult.RejectedTool>,
        /** SDK 垫片是否本次落盘（真实包已装时 false）。 */
        val sdkShimVendored: Boolean,
        val entry: File
    )

    /** 已加载插件的元数据（重启重放绑定用）。 */
    private data class LoadMeta(
        val ecosystem: PluginEcosystem,
        val abi: OpenCodeAbiDetector.Abi,
        val entry: File,
        val sdkShimVendored: Boolean
    )

    private val metas = ConcurrentHashMap<String, LoadMeta>()

    /**
     * 加载 OpenCode 插件目录（npm 包形态或本地目录形态）。
     *
     * @param optionsJson 官方 options（opencode.jsonc 对象形式的 options 字段）
     */
    suspend fun load(
        pluginId: String,
        pkgDir: File,
        optionsJson: String = "{}"
    ): Result<LoadOutcome> {
        // ── 1) package.json → ABI 探测（spec §4.1：不允许按包名瞎猜）──────
        val pkgFile = File(pkgDir, "package.json")
        val pkgText = if (pkgFile.isFile) pkgFile.readText() else null
        val abi = OpenCodeAbiDetector.detect(pkgText).let { detected ->
            if (detected.version == OpenCodeAbiDetector.OpenCodeVersion.UNKNOWN) {
                // 本地单文件插件（官方 .opencode/plugins 目录形态）无 package.json：
                // 按官方 V2 本地插件约定尝试；V1 形状会在加载时被明确拒绝
                OpenCodeAbiDetector.Abi(
                    OpenCodeAbiDetector.OpenCodeVersion.V2,
                    "无 package.json —— 按官方本地插件（.opencode/plugins 目录）V2 约定尝试",
                    "esm"
                )
            } else {
                detected
            }
        }
        val ecosystem = when (abi.version) {
            OpenCodeAbiDetector.OpenCodeVersion.V1 -> PluginEcosystem.OPENCODE_V1
            else -> PluginEcosystem.OPENCODE_V2
        }

        // ── 2) 入口解析（spec §4.3：不能只读 main）──────────────────────
        val entry = OpenCodeAbiDetector.resolveEntry(pkgDir, pkgText)
            ?: return Result.failure(IllegalStateException("无法解析插件入口（exports/main/module/index 均未命中）"))

        // ── 3) V2 SDK 垫片（真实包优先，缺失才落盘）─────────────────────
        val shimVendored = abi.version == OpenCodeAbiDetector.OpenCodeVersion.V2 &&
            OpenCodeWorkerScript.vendorSdkShimIfMissing(pkgDir)

        // ── 4) Worker 启动 + 生态化加载（Supervisor 内 handshake+load）────
        val storageFile = File(pkgDir, ".apex-plugin-storage.json").canonicalFile.path
        val worker = supervisor.start(
            pluginId = pluginId,
            workingDir = pkgDir,
            entryPath = entry.canonicalFile.path,
            format = if (abi.moduleFormat == "esm") "esm" else "cjs",
            ecosystem = if (abi.version == OpenCodeAbiDetector.OpenCodeVersion.V1) {
                OpenCodeWorkerScript.ECOSYSTEM_V1
            } else {
                OpenCodeWorkerScript.ECOSYSTEM_V2
            },
            optionsJson = optionsJson,
            storageFile = storageFile
        ).getOrElse { return Result.failure(it) }
        val loaded = worker.lastLoaded()
            ?: return Result.failure(IllegalStateException("Worker 无加载记录（内部错误）"))

        metas[pluginId] = LoadMeta(ecosystem, abi, entry, shimVendored)
        return bindLoaded(pluginId, ecosystem, abi, entry, shimVendored, loaded)
    }

    /** Worker 崩溃重启后重放绑定（Supervisor Restarted 事件驱动）。 */
    suspend fun rebindAfterRestart(pluginId: String): Result<LoadOutcome> {
        val meta = metas[pluginId]
            ?: return Result.failure(IllegalStateException("插件 '$pluginId' 不在 OpenCode 加载器管辖内"))
        val worker = supervisor.worker(pluginId)
            ?: return Result.failure(IllegalStateException("插件 '$pluginId' 无运行中的 Worker"))
        val loaded = worker.lastLoaded()
            ?: return Result.failure(IllegalStateException("重启后的 Worker 无加载记录"))
        return bindLoaded(pluginId, meta.ecosystem, meta.abi, meta.entry, meta.sdkShimVendored, loaded)
    }

    /**
     * 停止并清理（spec §7.3 逆序）：注销工具 → 取消事件订阅 → 停 Worker
     * → 移除管辖记录。加载器自己启动的插件由本方法收尾（生命周期管理器
     * 的 stop 亦兼容 —— 两套入口幂等）。
     */
    suspend fun stop(pluginId: String) {
        toolBridge.unbind(pluginId)
        eventBus.unsubscribeAll(pluginId)
        supervisor.stop(pluginId)
        metas.remove(pluginId)
    }

    /** 是否由本加载器管辖（诊断）。 */
    fun isManaged(pluginId: String): Boolean = metas.containsKey(pluginId)

    /** 绑定：工具注册 + 钩子转发订阅。 */
    private suspend fun bindLoaded(
        pluginId: String,
        ecosystem: PluginEcosystem,
        abi: OpenCodeAbiDetector.Abi,
        entry: File,
        sdkShimVendored: Boolean,
        loaded: WorkerProtocol.Loaded
    ): Result<LoadOutcome> {
        if (!loaded.setupOk) {
            return Result.failure(IllegalStateException("插件 setup 失败: ${loaded.error}"))
        }
        val bind = toolBridge.bind(
            pluginId, ecosystem,
            loaded.tools.map {
                PluginAgentTool.ToolDescriptor(it.name, it.description, it.parametersSchema)
            },
            PluginAgentTool.PluginToolExecutor { pid, toolName, argsJson ->
                supervisor.callTool(pid, toolName, argsJson).fold(
                    onSuccess = { PluginAgentTool.ToolExecutionOutcome.success(it) },
                    onFailure = { PluginAgentTool.ToolExecutionOutcome.failure(it.message ?: "unknown") }
                )
            }
        )
        subscribeForwarding(pluginId, loaded)
        return Result.success(
            LoadOutcome(
                ecosystem = ecosystem,
                abi = abi,
                loaded = loaded,
                registeredTools = bind.registered,
                rejectedTools = bind.rejected,
                sdkShimVendored = sdkShimVendored,
                entry = entry
            )
        )
    }

    /** 宿主事件 → OpenCode 钩子转发订阅（映射表驱动，不伪造映射）。 */
    private suspend fun subscribeForwarding(
        pluginId: String,
        loaded: WorkerProtocol.Loaded
    ) {
        val registered = loaded.hooks + loaded.events
        if (registered.isEmpty()) return
        for (event in PluginEventBus.PluginHostEvent.entries) {
            val hookName = OpenCodeEventMapping.toOpenCodeHook(event) ?: continue
            if (hookName !in registered) continue
            eventBus.subscribe(pluginId, event) { _, payloadJson ->
                supervisor.worker(pluginId)?.emitEvent(hookName, payloadJson)
            }
        }
    }
}

/**
 * 宿主规范事件 → OpenCode 钩子名映射（spec §4.8：不存在对应关系的事件
 * 明确返回 null，不伪造）。
 */
object OpenCodeEventMapping {
    fun toOpenCodeHook(event: PluginEventBus.PluginHostEvent): String? = when (event) {
        PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL -> "tool.execute.before"
        PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL -> "tool.execute.after"
        PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL_FAILURE -> "tool.execute.after"
        PluginEventBus.PluginHostEvent.USER_PROMPT_SUBMIT -> "session.prompt"
        // session.start/end、notification、plugin.* 在 OpenCode V2 服务器钩子面
        // 无对应物（session.hook 是请求级而非会话级）—— 如实不映射
        else -> null
    }
}
