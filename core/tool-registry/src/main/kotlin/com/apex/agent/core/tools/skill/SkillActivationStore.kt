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
    /** 同时激活的技能数上限（FIFO 淘汰最旧；pinned 豁免见 [setPinned]）。 */
    private val maxActive: Int = 8,
    /**
     * 聚合策略索引（可选——null = 无策略零开销，既有测试/无聚合场景直通）。
     * 非空时全部激活路径先经 [SkillPolicyIndex.filterActivation] 互斥过滤。
     */
    private val policyIndex: SkillPolicyIndex? = null
) {
    private val lock = Any()

    /** 激活集（LinkedHashSet 维护 FIFO 序）。 */
    private val active = LinkedHashSet<String>()

    /** 常驻集（聚合 alwaysOn；FIFO 淘汰豁免——包作者显式声明的驻留语义）。 */
    private val pinned = mutableSetOf<String>()

    private val _activeIds = MutableStateFlow<Set<String>>(emptySet())
    /** 激活 id 快照（不可变拷贝）。 */
    val activeIds: StateFlow<Set<String>> = _activeIds.asStateFlow()

    /**
     * 激活一个技能 id。
     * @return true = 本次新激活；false = 已在激活集（幂等成功）或被策略拒绝。
     */
    fun activate(skillId: String): Boolean = synchronized(lock) {
        if (skillId in active) return false
        val allowed = policyIndex?.filterActivation(listOf(skillId), snapshot())?.first
        if (allowed != null && allowed.isEmpty()) return false
        active.add(skillId)
        evictWhileOverBudgetLocked(skillId)
        publishLocked()
        true
    }

    /** 批量激活（自动装备路径用）；返回本次真正新增的 id 列表（被策略拒绝的不计）。 */
    fun activateAll(ids: List<String>): List<String> = activateAllFiltered(ids).added

    /**
     * 批量激活 + 策略过滤明细（互斥拒绝的人话原因逐条返回 —— autoEquip /
     * 聚合激活的可观测面）。
     */
    fun activateAllFiltered(ids: List<String>): ActivationOutcome = synchronized(lock) {
        val candidates = policyIndex
            ?.filterActivation(ids, snapshot())
            ?.let { (allowed, rejected) -> allowed to rejected }
            ?: (ids.toList() to emptyList())
        val added = mutableListOf<String>()
        for (id in candidates.first) {
            if (id in active) continue
            active.add(id)
            added.add(id)
            evictWhileOverBudgetLocked(id)
        }
        if (added.isNotEmpty()) publishLocked()
        ActivationOutcome(added, candidates.second)
    }

    /**
     * FIFO 淘汰（预算满时）：先按包级预算（policyIndex.budgetFor 取新激活
     * 成员所在包的上限，0 = 全局 [maxActive]），再淘汰最旧的非 pinned。
     * 全员 pinned 时诚实超限（alwaysOn 是包作者显式声明的驻留语义）。
     */
    private fun evictWhileOverBudgetLocked(justActivated: String) {
        val budget = policyIndex?.budgetFor(justActivated)?.takeIf { it > 0 } ?: maxActive
        while (active.size > budget) {
            val evicted = active.firstOrNull { it !in pinned } ?: return
            active.remove(evicted)
        }
    }

    /** 设置常驻集（聚合 alwaysOn —— 免 FIFO 淘汰；重复调用整体覆盖）。 */
    fun setPinned(ids: Collection<String>) = synchronized(lock) {
        pinned.clear()
        pinned.addAll(ids)
    }

    /** 清空常驻集（聚合去激活）。 */
    fun clearPinned() = synchronized(lock) { pinned.clear() }

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

/** 批量激活结果：真正新增的 id + 被策略拒绝的 (id, 人话原因) 明细。 */
data class ActivationOutcome(
    val added: List<String>,
    val rejected: List<Pair<String, String>>
)
