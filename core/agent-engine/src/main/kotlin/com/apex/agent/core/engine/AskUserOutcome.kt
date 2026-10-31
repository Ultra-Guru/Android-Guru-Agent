package com.apex.agent.core.engine

/**
 * AskUser 等待结果的三态解释（#509）。
 *
 * 从 [handleAskUserToolCall] 抽出为纯函数，使「取消/中止」与「用户作答」
 * 可以被单测锁定 —— 旧实现把空答案当成正常回答回填
 * `User answered: `（success=true），取消被模型读成默认同意。
 */
internal sealed interface AskUserOutcome {
    /** 用户给出了实际文本。 */
    data class Answered(val answer: String) : AskUserOutcome

    /** 用户取消 / 运行被中止：没有任何答案，绝不可当作同意。 */
    data object NoAnswer : AskUserOutcome

    /** 等待超时（5 分钟未决）。 */
    data object TimedOut : AskUserOutcome

    /** 回填给模型的 ToolResult 文本 —— 三种结局各自诚实表述。 */
    val toolResultNote: String
        get() = when (this) {
            is Answered -> "User answered: $answer"
            NoAnswer -> "User cancelled the question without answering. " +
                "Do not treat cancellation as consent — proceed with the safest " +
                "default or re-ask the user later."
            TimedOut -> "User input timed out; the question was closed unanswered. " +
                "Do not treat silence as consent — proceed with the safest " +
                "default or re-ask the user later."
        }

    /** 该结局是否算作工具调用成功（取消与超时都不算）。 */
    val isSuccess: Boolean get() = this is Answered
}

/**
 * 把 [ApexAgentEngine.awaitUserInput] 的返回值翻译成三态。
 *
 * - `null` = 超时（awaitUserInput 已发射 UserInputExpired）；
 * - 空串 = [ApexAgentEngine.cancelUserInput] / abort 的唤醒语义 —— 无答案；
 * - 其余 = 用户作答。
 */
internal fun askUserOutcome(answer: String?): AskUserOutcome = when {
    answer == null -> AskUserOutcome.TimedOut
    answer.isEmpty() -> AskUserOutcome.NoAnswer
    else -> AskUserOutcome.Answered(answer)
}
