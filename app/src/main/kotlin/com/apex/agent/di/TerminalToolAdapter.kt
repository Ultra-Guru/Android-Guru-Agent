package com.apex.agent.di

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.StreamingAgentTool
import com.apex.agent.core.tools.ToolResultMeta
import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adapts a [TerminalTool] (defined in `platform:terminal`, which must not depend on
 * `core:tool-registry`) into the engine's [AgentTool] contract.
 *
 * Mirrors the existing [com.apex.agent.core.tools.skill.SkillToolAdapter] pattern:
 * all four metadata fields come straight from the terminal tool, and [execute]
 * delegates to its JSON-string [TerminalTool.invoke].
 *
 * ## v3：流式 + 结构化执行元数据（Agent-Shell UI 契约修复）
 *
 * 本适配器现在实现 [StreamingAgentTool]，在 Complete 事件上携带
 * [ToolResultMeta] —— 从终端工具的 JSON 输出里提取（字段缺席 = null，非终端
 * 语义的工具自然全空）：
 * - `exit_code` → [ToolResultMeta.exitCode]：engine 以真实退出码判成败，
 *   `exit_code:127` / 门禁拒绝 126 / 超时强杀 -1 不再显示为绿色成功；
 * - `session_id` / `sessionId` → 会话标注（TerminalRun / TerminalCreate）；
 * - `channel` → 执行通道（proot-ubuntu / root-su / shizuku / local-sh）；
 * - `cwd` / `guestCwd` → 实际执行目录；
 * - `error` → 结构化错误类别（spawn_failed / permission_denied / ...）；
 * - `status == "INPUT_REQUIRED"` → [ToolResultMeta.waitingInput]（挂起可见）。
 *
 * JSON 输出文本**原样**上抛（Output + Complete.output 均为原始串）—— 模型
 * 可见信息零变化；meta 只丰富 engine 的成败判定与 UI 呈现。
 *
 * ## v4：真流式预览（修复「假流式」）
 *
 * 旧实现的 executeStream 在 tool.invoke 完成后才 emit 一个 Output ——
 * TerminalExec 运行期间（构建/测试/apt，分钟级）UI 终端胶囊零事件、面板
 * 空白。现在经 [TerminalTool.invokeStreaming] 拿到执行期间的增量预览
 * （命令 stdout 的实时流动），立即转成 `preview=true` 的 Output 事件推送；
 * 工具完成后 Complete 携带最终结构化 JSON（权威输出 —— 预览不进引擎
 * 累积，模型契约零变化）。
 *
 * 并发模型（channelFlow）：
 *  - 工具本体跑在 producer 协程 —— 引擎侧取消沿 invokeStreaming →
 *    ExecEngine 的 runInterruptible → 底层进程 destroy 传播；
 *  - 预览回调在 drain 线程上执行，经 `trySend` 无锁投递（尽力而为：
 *    消费方迟滞时丢预览不丢权威输出）；所有事件同走一条 Channel，
 *    顺序天然保真（预览在前、最终 JSON + Complete 在后）。
 *
 * 解析失败（非 JSON 输出 / 字段类型异常）静默降级为空 meta —— 老工具与
 * 错误字符串输出不受影响，永不因解析抛错打断执行。
 */
class TerminalToolAdapter(
    private val tool: TerminalTool
) : StreamingAgentTool {
    override val id: String = tool.id
    override val name: String = tool.name
    override val description: String = tool.description
    override val parametersSchema: String = tool.parametersSchema

    override suspend fun execute(arguments: String): String = tool.invoke(arguments)

    override fun executeStream(arguments: String): Flow<ToolStreamEvent> = channelFlow {
        val result = tool.invokeStreaming(arguments) { chunk ->
            trySend(ToolStreamEvent.Output(chunk, preview = true))
        }
        // 权威输出收尾：非预览 Output（引擎累积为 rawOutput 的防御路径）+ Complete。
        if (result.isNotEmpty()) {
            send(ToolStreamEvent.Output(result))
        }
        send(ToolStreamEvent.Complete(result, meta = parseMeta(result)))
    }

    /** 从终端工具 JSON 输出提取结构化元数据；解析失败 → 空 meta（见类 KDoc）。 */
    internal fun parseMeta(result: String): ToolResultMeta = runCatching {
        val json = Json.parseToJsonElement(result).jsonObject
        fun str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { json[it]?.jsonPrimitive?.contentOrNull }
        ToolResultMeta(
            exitCode = json["exit_code"]?.jsonPrimitive?.intOrNull,
            sessionId = str("session_id", "sessionId"),
            channel = str("channel"),
            cwd = str("cwd", "guestCwd"),
            errorKind = str("error"),
            waitingInput = str("status") == "INPUT_REQUIRED"
        )
    }.getOrDefault(ToolResultMeta())
}
