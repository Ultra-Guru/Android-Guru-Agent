package com.apex.agent.core.tools.pluginhost.claude

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # ClaudeCompatWiring —— Claude 兼容层 × install 链挂接器（plugin-host-2.0 Phase 6 收尾）
 *
 * 核查结论（docs/plugin-host-2.0.md「Phase 6 之后」清单第一条）：Phase 5 的
 * `ClaudeHookAdapter` / `ClaudeMcpBridge` / `ClaudeLspReport` 组件全部实现且
 * E2E 测绿，但 `ClaudeExtensionAdapter.install()` 只转译 skills/commands/agents
 * 三段——`hooks/hooks.json`、`.mcp.json`、`.lsp.json` 完全不被读取，组件
 * 0 生产调用点。本类把「装完」接到「生效」：
 *
 * - **`.mcp.json`** → [ClaudeMcpBridge.apply]（既有 McpManager + 来源归属账本：
 *   只注册 `enabled=false` 配置，连接由用户/模型经 McpConnect 显式发起；
 *   卸载只删插件带来的服务器，用户自建不动）；
 * - **`hooks/hooks.json`** → [ClaudeHooksManifest] 解析 + 按宿主事件映射表订阅
 *   [PluginEventBus]，事件到达时经 [ClaudeHookAdapter] 以官方协议调度
 *   （stdin JSON / exit code 语义 / `${CLAUDE_PLUGIN_ROOT}`）；
 * - **`.lsp.json`** → 只检测只报告（宿主 LSP 运行时尚未就绪，不启动不伪装）。
 *
 * 失败语义（诚实优先）：挂接失败**绝不炸安装**——技能/指令/人设是主产物，
 * 兼容层是增强面；每一步失败都进 notes 如实可见。
 *
 * 宿主事件 payload 契约（生产方：app 层执行器插槽；本类为消费方）：
 * - 工具事件：`{"tool_name": "...", "tool_input": {...}}`；
 * - 其余事件载荷任意（钩子按官方 stdin 形态补全 session_id / cwd 等）。
 */
class ClaudeCompatWiring(
    /** `.mcp.json` → McpManager 桥（null = 宿主未提供，如实降级）。 */
    private val mcpBridge: ClaudeMcpBridge?,
    /** 钩子官方协议调度器（null = 未提供）。 */
    private val hookAdapter: ClaudeHookAdapter?,
    /** 宿主事件总线（null = 未提供 → 钩子无事件源，如实降级）。 */
    private val eventBus: PluginEventBus?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    /** attach 产物（notes 直接并入 InstallOutcome，模型/用户可见）。 */
    data class AttachReport(
        /** `.mcp.json` 注册成功的服务器名（enabled=false，待连接）。 */
        val mcpAddedServers: List<String>,
        /** 跳过的服务器（同名已存在 / 形态不支持）。 */
        val mcpSkippedServers: List<String>,
        /** 已订阅的官方事件名（PreToolUse 等）。 */
        val hookedEvents: Set<String>,
        /** 包内声明但宿主事件面无对应的官方事件（如实不分发）。 */
        val unmappedEvents: Set<String>,
        /** `.lsp.json` 检测到的语言服务器名（只识别）。 */
        val lspServerNames: List<String>,
        val notes: List<String>
    )

    /** 已挂接钩子的插件（manifest + 常驻包根）。 */
    private data class HookEntry(
        val manifest: ClaudeHooksManifest.Manifest,
        val pluginRoot: File
    )

    private val hookEntries = ConcurrentHashMap<String, HookEntry>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 安装期挂接（ClaudeExtensionAdapter.install 调用）。
     *
     * @param pluginRoot 常驻包根（hooks 命令经 `${CLAUDE_PLUGIN_ROOT}` 引用包内
     *   脚本——null = 适配器未持久化包根，有钩子声明时如实标注未挂接）
     */
    suspend fun attach(
        pluginId: String,
        pluginRoot: File?,
        hooksJson: String?,
        mcpJson: String?,
        lspServerNames: List<String> = emptyList()
    ): AttachReport {
        val notes = mutableListOf<String>()

        // ── 1) .mcp.json → 既有 McpManager（来源归属账本）─────────────────
        var addedServers: List<String> = emptyList()
        var skippedServers: List<String> = emptyList()
        if (mcpJson != null) {
            val bridge = mcpBridge
            if (bridge == null) {
                notes += "检测到 .mcp.json，但 MCP 桥未接线（宿主未提供）—— 服务器未注册"
            } else {
                bridge.apply(pluginId, mcpJson).fold(
                    onSuccess = { applied ->
                        addedServers = applied.addedServers
                        skippedServers = applied.skipped
                        if (addedServers.isNotEmpty()) {
                            notes += "MCP 服务器已注册（默认停用，McpConnect('<name>') 连接后工具生效）：" +
                                addedServers.joinToString(", ")
                        }
                        if (skippedServers.isNotEmpty()) {
                            notes += "MCP 服务器跳过（同名已存在或形态不支持）：${skippedServers.joinToString(", ")}"
                        }
                    },
                    onFailure = {
                        notes += ".mcp.json 注册失败（技能/指令不受影响）：${it.message}"
                    }
                )
            }
        }

        // ── 2) hooks/hooks.json → 事件订阅 → 官方协议调度 ──────────────────
        var hooked: Set<String> = emptySet()
        var unmapped: Set<String> = emptySet()
        if (hooksJson != null) {
            val parsed = ClaudeHooksManifest.parse(hooksJson)
            when {
                parsed == null ->
                    notes += "hooks.json 解析失败 —— 钩子未挂接（技能/指令不受影响）"
                parsed.groups.isEmpty() -> Unit // 合法空清单（官方形态），静默
                hookAdapter == null || eventBus == null ->
                    notes += "检测到 ${parsed.groups.size} 组钩子声明，但钩子执行组件未接线 —— 未挂接"
                pluginRoot == null ->
                    notes += "检测到钩子声明，但插件包根未持久化 —— 未挂接（重新安装可恢复）"
                else -> {
                    val mappable = parsed.groups.keys
                        .filter { it in ClaudeHooksManifest.Events.mappable }
                    unmapped = parsed.unmappedEvents
                    hookEntries[pluginId] = HookEntry(parsed, pluginRoot)
                    subscribePlugin(pluginId, mappable)
                    hooked = mappable.toSet()
                    notes += "钩子已挂接 ${mappable.size} 事件（${mappable.sorted().joinToString(", ")}）" +
                        "—— 经沙箱官方协议调度（exit 2 = 阻断，additionalContext 记入日志）"
                    if (unmapped.isNotEmpty()) {
                        notes += "未映射事件（宿主事件面无对应，如实不分发）：${unmapped.joinToString(", ")}"
                    }
                }
            }
        }

        // ── 3) .lsp.json → 只识别只报告 ──────────────────────────────────
        if (lspServerNames.isNotEmpty()) {
            notes += "已识别 ${lspServerNames.size} 个语言服务器声明（${lspServerNames.joinToString(", ")}）" +
                "—— 宿主 LSP 运行时尚未就绪（不启动、不伪装支持）"
        }

        return AttachReport(
            mcpAddedServers = addedServers,
            mcpSkippedServers = skippedServers,
            hookedEvents = hooked,
            unmappedEvents = unmapped,
            lspServerNames = lspServerNames,
            notes = notes
        )
    }

    /** 卸载期摘除：MCP 归属回收 + 钩子订阅取消（幂等）。 */
    suspend fun detach(pluginId: String): List<String> {
        val notes = mutableListOf<String>()
        val removedServers = mcpBridge?.removeOwned(pluginId) ?: 0
        if (removedServers > 0) {
            notes += "已移除插件带来的 $removedServers 台 MCP 服务器（用户自建不动）"
        }
        if (hookEntries.remove(pluginId) != null) {
            eventBus?.unsubscribeAll(pluginId)
            notes += "钩子订阅已取消"
        }
        return notes
    }

    /** 已挂接钩子的插件 id 快照（诊断）。 */
    fun hookedPluginIds(): Set<String> = hookEntries.keys.toSet()

    // ── 内部 ──────────────────────────────────────────────────────────────

    /** 按官方事件名 → 宿主事件（映射表以 ClaudeHookAdapter.toClaudeEvent 为单一真相源）。 */
    private suspend fun subscribePlugin(pluginId: String, claudeEvents: List<String>) {
        val bus = eventBus ?: return
        val hostEvents = PluginEventBus.PluginHostEvent.entries
            .filter { hostEvent -> claudeEvents.any { it == ClaudeHookAdapter.toClaudeEvent(hostEvent) } }
        for (hostEvent in hostEvents) {
            bus.subscribe(pluginId, hostEvent) { event, payloadJson ->
                dispatchToPlugin(pluginId, event, payloadJson)
            }
        }
    }

    private suspend fun dispatchToPlugin(
        pluginId: String,
        event: PluginEventBus.PluginHostEvent,
        payloadJson: String
    ) {
        val entry = hookEntries[pluginId] ?: return
        val adapter = hookAdapter ?: return
        val (toolName, toolInput, sessionId) = parseToolPayload(payloadJson)
        val outcome = adapter.dispatch(
            manifest = entry.manifest,
            event = event,
            pluginRoot = entry.pluginRoot,
            toolName = toolName,
            toolInputJson = toolInput,
            sessionId = sessionId
        )
        if (outcome.results.isEmpty()) return
        // 观测面：阻断/拒绝决策与 additionalContext 记入日志——同步回流通道
        // （PreToolUse deny 短路执行、context 注入对话）需要引擎插槽扩展，
        // 属后续工作（诚实边界，不静默吞掉）。
        if (outcome.blocked) {
            AppLogger.instance.warn(
                LogCategory.PLUGIN, TAG,
                "Claude 钩子阻断 $pluginId/${event.wireName}: " +
                    outcome.results.filter { it.blocking }.joinToString("; ") { it.stderr.take(200) }
            )
        }
        if (outcome.denied) {
            AppLogger.instance.warn(
                LogCategory.PLUGIN, TAG,
                "Claude 钩子拒绝工具调用 $pluginId/${event.wireName}"
            )
        }
        outcome.contexts.forEach { context ->
            AppLogger.instance.info(
                LogCategory.PLUGIN, TAG,
                "Claude 钩子 additionalContext $pluginId/${event.wireName}: ${context.take(400)}"
            )
        }
    }

    /** 工具事件 payload → (tool_name, tool_input, session_id)；解析失败全走缺省。 */
    private fun parseToolPayload(payloadJson: String): Triple<String?, String, String> {
        val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
            .getOrNull() ?: return Triple(null, "{}", DEFAULT_SESSION_ID)
        val toolName = obj["tool_name"]
            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val toolInput = obj["tool_input"]?.toString() ?: "{}"
        val sessionId = obj["session_id"]
            ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?: DEFAULT_SESSION_ID
        return Triple(toolName, toolInput, sessionId)
    }

    private companion object {
        const val TAG = "ClaudeCompatWiring"
        const val DEFAULT_SESSION_ID = "apex-session"
    }
}
