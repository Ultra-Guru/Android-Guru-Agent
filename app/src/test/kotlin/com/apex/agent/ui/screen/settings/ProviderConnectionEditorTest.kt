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

    @Test fun userDraftSurvivesExternalEditWhileUneditedFieldSyncs() {
        val store = Store()
        val editor = store.editor("a")
        editor.editUrl("https://draft")
        store.values["a"] = ProviderConnectionValues("https://external", "external-key")
        editor.refresh()
        assertEquals("User's in-progress URL draft must survive the external edit", "https://draft", editor.state.value.baseUrl)
        assertEquals("Unedited key field must adopt the external value", "external-key", editor.state.value.apiKey)
        editor.flush()
        assertEquals("https://draft", store.values.getValue("a").baseUrl)
        assertEquals("external-key", store.values.getValue("a").apiKey)
        assertEquals("Only the user-edited field may be written", listOf("a:url"), store.writes)
    }

    @Test fun flushedFieldsBecomeCleanAndLaterExternalEditsFlowIn() {
        val store = Store()
        val editor = store.editor("a")
        editor.editUrl("https://draft")
        editor.flush()
        store.values["a"] = ProviderConnectionValues("https://external", "external-key")
        editor.refresh()
        assertEquals("https://external", editor.state.value.baseUrl)
        editor.flush()
        assertEquals("A flushed form must not overwrite later external edits", "https://external", store.values.getValue("a").baseUrl)
        assertEquals("external-key", store.values.getValue("a").apiKey)
        assertEquals(listOf("a:url"), store.writes)
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
