package com.apex.agent.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectionEditorTest {
    @Test fun sameProviderExternalEditSurvivesDisposeWithoutRecomposition() {
        val store = Store()
        val editor = store.editor("a")
        store.values["a"] = ProviderConnectionValues("https://external", "external-placeholder")
        editor.flush()
        assertEquals("https://external", store.values.getValue("a").baseUrl)
        assertEquals("external-placeholder", store.values.getValue("a").apiKey)
        assertTrue("A clean form must not write stale credentials on dispose", store.writes.isEmpty())
    }

    private class Store {
        val values = mutableMapOf("a" to ProviderConnectionValues("https://a", "a-placeholder"))
        val writes = mutableListOf<String>()
        fun editor(id: String) = ProviderConnectionEditor(
            providerId = id,
            read = { values[it] },
            writeUrl = { owner, value ->
                writes += "$owner:url"
                values[owner] = values.getValue(owner).copy(baseUrl = value.trim())
            },
            writeKey = { owner, value ->
                writes += "$owner:key"
                values[owner] = values.getValue(owner).copy(apiKey = value.trim())
            }
        )
    }
}
