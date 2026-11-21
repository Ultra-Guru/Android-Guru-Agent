package com.apex.agent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** #269/#268: warning container AND content color must travel together. */
class WarningContainerContrastTest {
    @Test
    fun `warning content is legible on both theme containers`() {
        for ((name, colors) in listOf("dark" to ExtendedColors.Dark, "light" to ExtendedColors.Light)) {
            val background: Color = colors.warningContainer
            val foreground: Color = colors.onWarningContainer
            val bright = maxOf(background.luminance(), foreground.luminance())
            val dim = minOf(background.luminance(), foreground.luminance())
            val contrast = (bright + 0.05f) / (dim + 0.05f)
            assertTrue("$name warning text contrast $contrast must be >= 4.5:1", contrast >= 4.5f)
        }
    }
}
