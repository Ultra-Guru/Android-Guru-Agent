package com.apex.agent.ui.screen.code

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * # Playbook Handoff Store — 工坊 → Coding 屏手递手通道（task-playbooks PR-B）
 *
 * 补齐模板工坊「使用模板」的断环：生成结果此前只能复制到剪贴板，本
 * store 让「发送到 Coding 屏」把生成文本递到 Code 屏，落地为待发送条
 * （[发送] 直发 / [丢弃]）。@Singleton 进程内单通道：新写入覆盖旧待递。
 *
 * 纯内存（不持久化）：工坊生成结果本来就是即时消费物，落盘反而制造
 * 陈旧待办。
 */
@Singleton
class PlaybookHandoffStore @Inject constructor() {

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** 工坊侧写入（覆盖旧待递）。 */
    fun offer(text: String) {
        _pending.value = text.takeIf { it.isNotBlank() }
    }

    /** Coding 屏消费（发送时取走并清空）。 */
    fun consume(): String? = _pending.value.also { _pending.value = null }

    /** Coding 屏丢弃。 */
    fun discard() {
        _pending.value = null
    }
}
