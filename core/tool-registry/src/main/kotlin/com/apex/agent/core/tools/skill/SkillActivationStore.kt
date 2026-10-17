package com.apex.agent.core.tools.skill

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 技能会话激活存储（技能渐进披露，对齐 Tool System v4 的 [ToolActivationStore] 模式）。
 *
 * ## 解决的问题
 *
 * [SkillRegistry.getPromptInjections] 会把**所有**启用技能的 promptInjection
 * 注入每一次系统提示词。内置技能扩到 46 个后全量注入 ≈ 150KB+，请求必然
 * 超限；而且「聊天气闲聊」根本用不上「SQL 优化」的方法论。技能方法论
 * 与工具目录同构：默认只给清单，需要时再装载。
 *
 * ## 语义
 *
 * - 每技能 manifest 的 promptInjection 只有在其 id 进入激活集后才注入
 *   系统提示词（见 [SkillRegistry.getActivePromptInjections]）；
 * - 激活来源三路：
 *   1. 模型调用 `SkillActivate(skill_id)` 工具（[SkillActivateTool]，
 *      工具结果即时返回全文，后续轮次持续注入）；
 *   2. 用户斜杠指令 `/skill:<id>`（app 层接线时同步激活）；
 *   3. [SkillAutoActivator] 按用户消息与技能 tags 的字面命中自动装备；
 * - FIFO 上限 [maxActive]（默认 8）：技能方法论普遍 2-4KB，8 个 ≈ 30KB
 *   封顶，兼顾覆盖与请求体积；
 * - 生命周期：进程级单例（DI @Singleton）——与引擎会话同寿。新会话
 *   （用户点「新对话」清空历史）不清激活：技能是能力装备不是对话状态，
 *   用户装上的方法论跨轮次保留符合直觉。
 *
 * ## 线程模型
 *
 * 全部状态由 [lock] 监视器锁保护（工具协程 / ViewModel 主线程 / 引擎
 * prompt 构建线程三路并发读写）；[activeIds] 是不可变快照 StateFlow，
 * 订阅方拿到的是防御性拷贝。
 */
class SkillActivationStore(
    /** 同时激活的技能数上限（FIFO 淘汰最旧）。 */
    private val maxActive: Int = 8
) {
    private val lock = Any()

    /** 激活集（LinkedHashSet 维护 FIFO 序）。 */
    private val active = LinkedHashSet<String>()

    private val _activeIds = MutableStateFlow<Set<String>>(emptySet())
    /** 激活 id 快照（不可变拷贝）。 */
    val activeIds: StateFlow<Set<String>> = _activeIds.asStateFlow()

    /**
     * 激活一个技能 id。
     * @return true = 本次新激活；false = 已在激活集（幂等成功）。
     */
    fun activate(skillId: String): Boolean = synchronized(lock) {
        if (skillId in active) return false
        active.add(skillId)
        while (active.size > maxActive) {
            active.remove(active.first())
        }
        publishLocked()
        true
    }

    /** 批量激活（自动装备路径用）；返回本次真正新增的 id 列表。 */
    fun activateAll(ids: List<String>): List<String> = synchronized(lock) {
        val added = mutableListOf<String>()
        for (id in ids) {
            if (id in active) continue
            active.add(id)
            added.add(id)
            while (active.size > maxActive) {
                val evicted = active.first()
                active.remove(evicted)
                added.remove(evicted)
            }
        }
        if (added.isNotEmpty()) publishLocked()
        added
    }

    /** 是否已激活。 */
    fun isActive(skillId: String): Boolean = synchronized(lock) { skillId in active }

    /** 清空激活集（测试与诊断用）。 */
    fun clear() = synchronized(lock) {
        active.clear()
        publishLocked()
    }

    /** 当前激活快照（FIFO 序）。 */
    fun snapshot(): Set<String> = synchronized(lock) { LinkedHashSet(active) }

    private fun publishLocked() {
        _activeIds.value = LinkedHashSet(active)
    }
}
