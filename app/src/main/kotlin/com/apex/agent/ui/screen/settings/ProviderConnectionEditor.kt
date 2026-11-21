package com.apex.agent.ui.screen.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory only: never log or persist this object's string representation. */
internal class ProviderConnectionValues(val baseUrl: String, val apiKey: String) {
    fun copy(baseUrl: String = this.baseUrl, apiKey: String = this.apiKey) =
        ProviderConnectionValues(baseUrl, apiKey)
}

/**
 * Provider-owned form state; persistence callbacks always receive the immutable owner.
 *
 * #510 修复（草稿归属 / 逐字段同步）：
 * - **干净表单零写回**：URL / Key 各自持有「用户真实输入过」的脏标记。
 *   用户从未触碰的字段在 flush 时绝不写回——同服务商被外部编辑
 *   （同步、导入、Providers 管理页）后，离开页面 / 防抖触发不再用过期
 *   表单值覆盖新配置；
 * - **逐字段外部同步**：[refresh] 只采纳未编辑字段的仓库最新值——
 *   用户正在输入的草稿按字段保留（Key 编辑中、URL 被外部改 → URL 跟随、
 *   Key 不动），旧实现整卡刷新会把两者一起冲掉；
 * - **脏标记写后即清**：flush 落盘后该字段回到干净态，之后的仓库变更
 *   可经 [refresh] 正常流入；同值编辑（重组重放的 onValueChange）不置脏。
 */
internal class ProviderConnectionEditor(
    val providerId: String,
    private val read: (String) -> ProviderConnectionValues?,
    private val writeUrl: (String, String) -> Unit,
    private val writeKey: (String, String) -> Unit
) {
    private val _state = MutableStateFlow(read(providerId) ?: ProviderConnectionValues("", ""))
    val state: StateFlow<ProviderConnectionValues> = _state.asStateFlow()

    /** 用户真实输入过 URL（值变更的 onValueChange 才置位；flush 后复位）。 */
    private var urlDirty = false

    /** 用户真实输入过 Key（同上）。 */
    private var keyDirty = false

    fun editUrl(value: String) {
        if (value == _state.value.baseUrl) return // 重组重放的同值编辑不置脏
        urlDirty = true
        _state.value = _state.value.copy(baseUrl = value)
    }

    fun editKey(value: String) {
        if (value == _state.value.apiKey) return
        keyDirty = true
        _state.value = _state.value.copy(apiKey = value)
    }

    /**
     * 逐字段采纳外部编辑（provider 仓库流每次发射时调用）：
     * 未编辑字段跟随最新持久值，编辑中字段保留用户草稿。
     * 无变化时不动 StateFlow（避免每次发射都触发重组）。
     */
    fun refresh() {
        val persisted = read(providerId) ?: return // 服务商已删除：保留表单现状
        val current = _state.value
        val nextUrl = if (urlDirty) current.baseUrl else persisted.baseUrl
        val nextKey = if (keyDirty) current.apiKey else persisted.apiKey
        if (nextUrl != current.baseUrl || nextKey != current.apiKey) {
            _state.value = ProviderConnectionValues(nextUrl, nextKey)
        }
    }

    /**
     * 冲刷 URL：仅当用户真实输入过且与持久值不同才写。
     * 干净表单（含被外部更新过的）直接跳过——这是 #510 的核心不变量。
     */
    fun flushUrl() {
        if (!urlDirty) return
        val persisted = read(providerId) ?: return // 服务商已删除：草稿保留待恢复
        if (providerId.isNotBlank() && state.value.baseUrl.trim() != persisted.baseUrl) {
            writeUrl(providerId, state.value.baseUrl)
        }
        urlDirty = false
    }

    /** 冲刷 Key：语义同 [flushUrl]。 */
    fun flushKey() {
        if (!keyDirty) return
        val persisted = read(providerId) ?: return
        if (providerId.isNotBlank() && state.value.apiKey != persisted.apiKey) {
            writeKey(providerId, state.value.apiKey)
        }
        keyDirty = false
    }

    fun flush() { flushUrl(); flushKey() }
}
