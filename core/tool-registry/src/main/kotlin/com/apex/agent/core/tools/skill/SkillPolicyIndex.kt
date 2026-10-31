package com.apex.agent.core.tools.skill

import com.apex.agent.core.tools.fusion.FusionPolicies

/**
 * # 技能策略索引 —— 聚合包策略（因果规则）的宿主硬执行层
 *
 * 「混合双轨」的硬轨：聚合（FusionBundle）激活时把 manifest 的
 * [FusionPolicies] 注册进来，此后**所有**技能激活路径（模型 SkillActivate /
 * 斜杠 / SkillAutoActivator 自动装备 / 聚合激活本身）都经 [filterActivation]
 * 过滤 —— 互斥组先到先得、被拒者如实反馈；[budgetFor] 供预算查询。
 * 聚合去激活时 [unregister]，策略随包生命周期。
 *
 * 与 [SkillActivationStore] 的分工：Store 是激活集状态（FIFO/pin），
 * Index 是规则源（哪个 id 属于哪个包、包怎么约束成员）—— Store 消费
 * Index 的判定结果，两者都是 core 纯 JVM 单例（app 层 DI 装配）。
 *
 * 触发条件双轨的软轨（triggers.when 场景描述 → 激活总纲）不经过本类
 * —— 那是 FusionBundleActivator.buildActivationPrompt 的文本组装；
 * 本类只管 [triggerKeywordsOf] 的字面加权通道。
 */
class SkillPolicyIndex {

    private val lock = Any()

    /** bundleId → (策略, 成员 id 集)。 */
    private val policies = LinkedHashMap<String, Entry>()

    private data class Entry(
        val bundleId: String,
        val policies: FusionPolicies,
        val memberIds: Set<String>
    )

    /** 聚合激活时注册策略（幂等：同 bundleId 覆盖）。 */
    fun register(bundleId: String, policies: FusionPolicies, memberIds: Set<String>) {
        synchronized(lock) {
            this.policies[bundleId] = Entry(bundleId, policies, memberIds)
        }
    }

    /** 聚合去激活时注销（策略随包生命周期）。 */
    fun unregister(bundleId: String) {
        synchronized(lock) { policies.remove(bundleId) }
    }

    /** 某技能 id 所属包的策略（多包同名技能取先注册者；无策略返回 null）。 */
    fun policiesFor(skillId: String): FusionPolicies? = synchronized(lock) {
        policies.values.firstOrNull { skillId in it.memberIds }?.policies
    }

    /**
     * 激活过滤（互斥硬执行）：对候选 ids 逐个判定 ——
     * - 目标 id 已在激活集 = 幂等放行；
     * - 目标所在互斥组内已有**其他**激活者 → 拒绝（先到先得），原因如实；
     * - 其余放行。
     *
     * @return (放行列表, 拒绝列表) —— 拒绝项为 (id, 人话原因)。
     */
    fun filterActivation(
        ids: List<String>,
        currentActive: Set<String>
    ): Pair<List<String>, List<Pair<String, String>>> {
        val allowed = mutableListOf<String>()
        val rejected = mutableListOf<Pair<String, String>>()
        val effective = currentActive.toMutableSet()
        for (id in ids) {
            val group = conflictingGroupOf(id, effective)
            if (group == null) {
                allowed.add(id)
                effective.add(id)
            } else {
                rejected.add(id to "与已激活技能互斥（互斥组：${group.joinToString(" / ")}）")
            }
        }
        return allowed to rejected
    }

    /** 目标 id 所在的、与 [activeIds] 冲突的互斥组（无冲突返回 null）。 */
    private fun conflictingGroupOf(skillId: String, activeIds: Set<String>): List<String>? =
        synchronized(lock) {
            policies.values
                .firstOrNull { skillId in it.memberIds }
                ?.policies?.mutuallyExclusive
                ?.firstOrNull { group -> skillId in group && group.any { it != skillId && it in activeIds } }
        }

    /** 包级激活预算（0 = 无包级预算，用全局默认）。 */
    fun budgetFor(skillId: String): Int = synchronized(lock) {
        policies.values.firstOrNull { skillId in it.memberIds }?.policies?.maxActiveSkills ?: 0
    }

    /** 包声明的触发关键词（autoEquip 加权；无声明返回空集 —— tags/id 兜底）。 */
    fun triggerKeywordsOf(skillId: String): Set<String> = synchronized(lock) {
        policies.values
            .firstOrNull { skillId in it.memberIds }
            ?.policies?.triggers
            ?.firstOrNull { it.skillId == skillId }
            ?.keywords?.toSet()
            ?: emptySet()
    }
}
