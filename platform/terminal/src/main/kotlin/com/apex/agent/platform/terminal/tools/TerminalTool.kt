package com.apex.agent.platform.terminal.tools

/**
 * Bridge contract for terminal tools so they can be adapted into the engine's
 * [com.apex.agent.core.tools.AgentTool] without forcing `platform:terminal` to
 * depend on `core:tool-registry` (module boundary).
 *
 * Each terminal tool exposes a JSON-string contract:
 *  - [invoke] parses a JSON `arguments` object, runs the operation, and returns
 *    a JSON result string (serialized by the tool itself).
 *  - [parametersSchema] is a JSON-Schema string describing the `arguments` shape.
 *
 * The app module wraps a [TerminalTool] via `TerminalToolAdapter` to register it
 * in the [com.apex.agent.core.tools.ToolRegistry].
 *
 * ## 流式预览（可选能力，默认透明）
 *
 * 长时工具（如 TerminalExec）可覆写 [invokeStreaming]：执行期间经
 * [onPreview] 增量上报**人看**输出（命令 stdout 的实时流动），适配层转成
 * 预览事件推 UI（终端胶囊实时刷新）；返回值仍是最终的结构化 JSON ——
 * 模型可见契约零变化。默认实现回落 [invoke]（无预览，行为与旧版一致）。
 */
interface TerminalTool {
    val id: String
    val name: String
    val description: String
    val parametersSchema: String
    suspend fun invoke(arguments: String): String

    /**
     * 带实时预览的执行：[onPreview] 在执行期间被调用（增量文本，仅用于 UI
     * 实时呈现）；返回最终 JSON 结果（与 [invoke] 同一契约）。
     *
     * 默认实现 = [invoke]（不产预览）。实现方注意事项：
     * - [onPreview] 可能从任意 IO 线程回调（适配层保证线程安全投递）；
     * - 预览是尽力而为：过滤/限频/丢弃都不影响正确性（权威输出在返回值）；
     * - 协程取消语义与 [invoke] 一致（抛 CancellationException 即中止）。
     */
    suspend fun invokeStreaming(
        arguments: String,
        onPreview: (chunk: String) -> Unit
    ): String = invoke(arguments)
}
