package com.apex.agent.core.tools

import kotlinx.coroutines.flow.Flow

/**
 * 工具流式输出事件。
 *
 * 一个流式工具在 [StreamingAgentTool.executeStream] 中发射零或多个
 * [Output] / [Progress] 事件，最后以一个 [Complete]（成功）或 [Error]
 * （失败）收尾。终端事件是可选的：Flow 自然结束也是合法的终止信号
 * （engine 把已累积的 [Output] 作为最终结果）。
 *
 * 该 sealed interface 镜像 engine 层的 `AgentEvent.ToolOutputChunk` /
 * `ToolProgress` / `ToolCallComplete`，但留在纯 JVM 的 tool-registry 模块，
 * 使工具可独立单测、无需 agent engine 或 Android 运行时。
 */
sealed interface ToolStreamEvent {

    /**
     * 普通输出片段。多个片段由 engine 拼接，并逐段转成
     * `AgentEvent.ToolOutputChunk` 推送到 UI，让用户在工具执行期间就能看到
     * 实时输出（而非等工具完成后一次性显示）。
     *
     * ## 预览片段（preview=true）
     *
     * 结构化工具（如 TerminalExec）执行期间的**人看**增量输出（命令 stdout
     * 的实时流动）：推送到 UI（终端胶囊 / 日志尾窗）但**不进入** engine 的
     * 累积输出 —— 该类工具的权威输出是 [Complete] 携带的最终结构化 JSON
     * （stdout/stderr/exit_code 一次性回灌 LLM）。
     *
     * 语义对照：
     *  - preview=false（默认）：片段即权威输出的增量，Complete.output 被忽略；
     *  - preview=true：片段仅供 UI 实时呈现，Complete.output 始终是权威结果。
     *
     * 这样长命令（构建/测试/apt install）执行期间终端面板不再空白，而模型
     * 可见信息保持结构化契约零变化。
     */
    data class Output(val chunk: String, val preview: Boolean = false) : ToolStreamEvent

    /**
     * 进度事件。用于长时间运行、能估算完成度的工具（如下载、大文件处理）。
     * [percent] 为 0..1；[message] 为可选的人类可读说明。
     * engine 把它转成 `AgentEvent.ToolProgress`，UI 显示进度条。
     */
    data class Progress(
        val percent: Float? = null,
        val message: String? = null
    ) : ToolStreamEvent

    /**
     * 工具正常完成。[output] 携带完整最终输出；当已发过**非预览** [Output]
     * 片段时，engine 忽略 [output]（以累积值为准），仅把它当作“成功”的终端
     * 信号。仅当工具完全没发 [Output]（非典型）时，engine 才把 [output]
     * 补发一次。预览片段（[Output.preview]=true）不影响此判定 —— 结构化
     * 工具的权威输出永远从这里取。
     *
     * [meta] 携带工具自知的结构化执行元数据（退出码 / 会话标注 / 通道）。
     * 关键语义：**非 null 的 meta.exitCode 是成败的权威信号** —— engine 以
     * `exitCode == 0` 判定成败，取代对 output 文本的 `"Error"` 前缀猜测。
     * 这修复了 TerminalExec 的最大误判：命令 `exit_code:127`（command not
     * found）/ 门禁拒绝（126）/ 超时强杀（-1）的 JSON 输出不以 "Error" 开头，
     * 旧判定把它们全部显示为绿色成功。output 文本本身**原样**回灌 LLM
     * （模型仍能看到完整 stdout/stderr/exit_code 自行决策），meta 只影响
     * engine 的 success 标志与 UI 呈现，不改变模型可见信息。
     */
    data class Complete(
        val output: String,
        val meta: ToolResultMeta = ToolResultMeta()
    ) : ToolStreamEvent

    /**
     * 工具失败。[message] 被追加到累积输出，engine 据此把工具调用标记为失败
     * （success=false）。
     */
    data class Error(val message: String) : ToolStreamEvent
}

/**
 * 工具执行的结构化元数据（v3：Agent-Shell UI 契约补强）。
 *
 * ## 为什么需要它
 *
 * 旧契约里工具成败只能靠 output 文本前缀（`"Error"`）猜测，会话标注（这次
 * 调用落在哪个终端会话 / 哪条执行通道）完全丢失。诊断确认的两类真实缺陷：
 * - TerminalExec 返回结构化 JSON（`{"exit_code":127,...}`），不以 "Error" 开头
 *   → command not found / 门禁拒绝 / 超时强杀全部显示为绿色成功；
 * - TerminalRun / TerminalCreate 的 session_id 只存在于 output 文本里，UI 无法
 *   把工具卡与会话页关联，挂起（INPUT_REQUIRED）也不可见。
 *
 * ## 语义约定
 *
 * - 全部字段可空 / 带默认值：非终端类工具零改动（[ToolResultMeta] 默认实例
 *   = 「无结构化信息」，engine 回退旧的文本前缀判定，行为不变）；
 * - [exitCode] 非 null 时是**成败权威信号**（0 = 成功；126 = spawn/门禁拒绝；
 *   127 = command not found；-1 = 超时强杀）；null = 工具无进程语义；
 * - [waitingInput] = 命令在前台等待用户输入（TerminalWait 的 INPUT_REQUIRED /
 *   交互会话的输入等待检测），UI 据此渲染「需要你操作」的可视标记。
 */
data class ToolResultMeta(
    /** 真实进程退出码（waitpid）；null = 非进程类工具 / 未知。 */
    val exitCode: Int? = null,
    /** 关联的终端会话标注（TerminalRun/TerminalCreate 的 session 标识）。 */
    val sessionId: String? = null,
    /** 执行通道（proot-ubuntu / root-su / shizuku / local-sh）。 */
    val channel: String? = null,
    /** 实际执行目录（guest 语义路径，如 /workspace/proj）。 */
    val cwd: String? = null,
    /** 结构化错误类别（spawn_failed / permission_denied / ...）。 */
    val errorKind: String? = null,
    /** 命令在前台等待用户输入（INPUT_REQUIRED）。 */
    val waitingInput: Boolean = false
)

/**
 * 支持流式输出的工具接口（可选能力）。
 *
 * 能增量产出输出的工具（如 `ShellExecute` 逐行读取进程 stdout）在实现
 * [AgentTool] 之外再实现本接口。[ToolExecutor] 在运行时通过 `tool is
 * StreamingAgentTool` 检测：命中则走 [executeStream]，未命中则把阻塞的
 * [AgentTool.execute] 结果透明包装成 “单个 Output + Complete” 事件序列。
 *
 * 因此实现本接口是工具获得实时 UI 输出的**唯一**改动点 —— 无需改 engine
 * 或 ViewModel。实现 [executeStream] 时应：
 * - 产出输出时发 [ToolStreamEvent.Output]；
 * - 以 [ToolStreamEvent.Complete]（或 [ToolStreamEvent.Error]）收尾；
 * - 响应协程取消，使 `abort()` 能停止底层工作（如 `Process.destroy()`）。
 *
 * 返回的 [Flow] 由 executor 在 IO 调度器上收集。
 */
interface StreamingAgentTool : AgentTool {

    /**
     * 流式执行工具。
     */
    fun executeStream(arguments: String): Flow<ToolStreamEvent>
}
