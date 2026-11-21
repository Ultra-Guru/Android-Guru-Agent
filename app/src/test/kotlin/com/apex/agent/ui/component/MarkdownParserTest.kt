package com.apex.agent.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun `unfinished fence stays literal without swallowing stable prefix`() {
        val prefix = "Before **bold**\n\n"
        val unfinished = "```kotlin\n# not a heading\n\n**not bold**"
        val expectedPrefix = MarkdownNode.Paragraph(
            listOf(InlineSegment.Text("Before "), InlineSegment.Bold("bold"))
        )

        assertEquals(
            listOf(
                expectedPrefix,
                MarkdownNode.Paragraph(listOf(InlineSegment.Text(unfinished)))
            ),
            parseMarkdown(prefix + unfinished)
        )
    }

    @Test
    fun `closing fence turns literal tail into a code block and keeps following text`() {
        val unfinished = "```kotlin\n# not a heading\n\n**not bold**"
        val prefix = "Before **bold**\n\n"
        val expectedPrefix = MarkdownNode.Paragraph(
            listOf(InlineSegment.Text("Before "), InlineSegment.Bold("bold"))
        )

        assertEquals(
            listOf(
                expectedPrefix,
                MarkdownNode.CodeBlock("kotlin", "# not a heading\n\n**not bold**"),
                MarkdownNode.Paragraph(listOf(InlineSegment.Text("After")))
            ),
            parseMarkdown(prefix + unfinished + "\n```\nAfter")
        )
    }
}
