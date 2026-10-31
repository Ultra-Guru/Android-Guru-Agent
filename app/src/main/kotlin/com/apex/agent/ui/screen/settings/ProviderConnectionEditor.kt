package com.apex.agent.ui.screen.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory only: never log or persist this object's string representation. */
internal class ProviderConnectionValues(val baseUrl: String, val apiKey: String) {
    fun copy(baseUrl: String = this.baseUrl, apiKey: String = this.apiKey) =
        ProviderConnectionValues(baseUrl, apiKey)
}

/** Provider-owned form state; persistence callbacks always receive the immutable owner. */
internal class ProviderConnectionEditor(
    val providerId: String,
    private val read: (String) -> ProviderConnectionValues?,
    private val writeUrl: (String, String) -> Unit,
    private val writeKey: (String, String) -> Unit
) {
    private val _state = MutableStateFlow(read(providerId) ?: ProviderConnectionValues("", ""))
    val state: StateFlow<ProviderConnectionValues> = _state.asStateFlow()

    fun editUrl(value: String) { _state.value = _state.value.copy(baseUrl = value) }
    fun editKey(value: String) { _state.value = _state.value.copy(apiKey = value) }

    // Preserves the previous form's same-ID refresh behavior for the RED regression run.
    fun refresh() = Unit

    fun flushUrl() {
        val persisted = read(providerId) ?: return
        if (providerId.isNotBlank() && state.value.baseUrl.trim() != persisted.baseUrl) {
            writeUrl(providerId, state.value.baseUrl)
        }
    }

    fun flushKey() {
        val persisted = read(providerId) ?: return
        if (providerId.isNotBlank() && state.value.apiKey != persisted.apiKey) {
            writeKey(providerId, state.value.apiKey)
        }
    }

    fun flush() { flushUrl(); flushKey() }
}
