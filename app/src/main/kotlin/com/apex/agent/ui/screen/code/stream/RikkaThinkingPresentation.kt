package com.apex.agent.ui.screen.code.stream

/** UI-only reveal cursor. The stream session remains the source of truth for the full thought. */
internal class RikkaThinkingReveal(initialVisibleCodePoints: Int = 0) {
    private var visibleCodePoints = initialVisibleCodePoints.coerceAtLeast(0)
    private var skipped = false

    /** One Unicode code point per pulse; completion and skip flush without rewriting the source. */
    fun advance(text: String, isStreaming: Boolean, skip: Boolean = false): String {
        if (skip) skipped = true
        val total = text.codePointCount(0, text.length)
        visibleCodePoints = when {
            !isStreaming || skipped -> total
            else -> (visibleCodePoints + 1).coerceAtMost(total)
        }
        return text.substring(0, text.offsetByCodePoints(0, visibleCodePoints))
    }
}

/** Honest fallback, not an LLM summary: first nonblank sentence/line of the stored full text. */
internal fun rikkaThinkingFirstSentence(text: String): String {
    val line = text.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty) ?: return ""
    val end = line.indexOfFirst { it == '。' || it == '！' || it == '？' || it == '.' || it == '!' || it == '?' }
    return if (end >= 0) line.substring(0, end + 1) else line
}
