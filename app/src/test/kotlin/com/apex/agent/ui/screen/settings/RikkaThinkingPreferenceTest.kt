package com.apex.agent.ui.screen.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RikkaThinkingPreferenceTest {
    @Test fun `legacy settings default to expanded thinking while new preference survives serialization`() {
        val legacy = Json.decodeFromString<AgentSettings>("{}")
        assertFalse(legacy.codeRikkaThinkingCollapsed)
        val saved = Json.encodeToString(legacy.copy(codeRikkaThinkingCollapsed = true))
        assertTrue(Json.decodeFromString<AgentSettings>(saved).codeRikkaThinkingCollapsed)
    }
}
