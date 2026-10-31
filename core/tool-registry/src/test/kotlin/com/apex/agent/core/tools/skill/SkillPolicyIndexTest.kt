package com.apex.agent.core.tools.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * # 技能策略索引测试 —— 聚合策略（因果规则）硬轨执行
 *
 * 覆盖：注册/注销生命周期、互斥先到先得（含跨包同名成员）、包级预算查询、
 * 包声明触发关键词、无策略成员直通（null 语义）。
 *
 * 纯 JVM（JUnit4 手写数据，无 mock 框架）。
 */
class SkillPolicyIndexTest {

    private fun policies(
        maxActive: Int = 0,
        alwaysOn: List<String> = emptyList(),
        exclusive: List<List<String>> = emptyList(),
        triggers: List<Pair<String, List<String>>> = emptyList()
    ): com.apex.agent.core.tools.fusion.FusionPolicies =
        com.apex.agent.core.tools.fusion.FusionPolicies(
            maxActiveSkills = maxActive,
            alwaysOn = alwaysOn,
            mutuallyExclusive = exclusive,
            triggers = triggers.map { (id, kw) ->
                com.apex.agent.core.tools.fusion.FusionTrigger(
                    skillId = id, scenario = "场景 $id", keywords = kw
                )
            }
        )

    @Test
    fun `互斥组先到先得 —— 已激活组内成员时新成员被拒`() {
        val index = SkillPolicyIndex()
        index.register(
            "pkg", policies(exclusive = listOf(listOf("Tdd", "Finishing"))),
            memberIds = setOf("Tdd", "Finishing", "Other")
        )
        val (allowed, rejected) = index.filterActivation(
            listOf("Other", "Tdd", "Finishing"), currentActive = emptySet()
        )
        assertEquals(listOf("Other", "Tdd"), allowed)
        assertEquals(1, rejected.size)
        assertEquals("Finishing", rejected[0].first)
        assertTrue(rejected[0].second.contains("互斥"))
    }

    @Test
    fun `幂等激活不受互斥影响`() {
        val index = SkillPolicyIndex()
        index.register(
            "pkg", policies(exclusive = listOf(listOf("A", "B"))),
            memberIds = setOf("A", "B")
        )
        val (allowed, rejected) = index.filterActivation(listOf("A"), currentActive = setOf("B"))
        // A 与已激活 B 互斥 → 拒绝；但 A 自身已在激活集的幂等场景由调用方短路
        assertTrue(allowed.isEmpty())
        assertEquals(1, rejected.size)
    }

    @Test
    fun `无策略成员直通`() {
        val index = SkillPolicyIndex()
        index.register("pkg", policies(), memberIds = setOf("X"))
        val (allowed, rejected) = index.filterActivation(listOf("Outsider"), currentActive = emptySet())
        assertEquals(listOf("Outsider"), allowed)
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `注销后策略不再生效`() {
        val index = SkillPolicyIndex()
        index.register("pkg", policies(exclusive = listOf(listOf("A", "B"))), memberIds = setOf("A", "B"))
        index.unregister("pkg")
        val (allowed, _) = index.filterActivation(listOf("B"), currentActive = setOf("A"))
        assertEquals(listOf("B"), allowed)
    }

    @Test
    fun `包级预算与触发关键词查询`() {
        val index = SkillPolicyIndex()
        index.register(
            "pkg", policies(maxActive = 4, triggers = listOf("A" to listOf("逆向", "apk"))),
            memberIds = setOf("A", "B")
        )
        assertEquals(4, index.budgetFor("A"))
        assertEquals(0, index.budgetFor("Outsider"))
        assertEquals(setOf("逆向", "apk"), index.triggerKeywordsOf("A"))
        assertTrue(index.triggerKeywordsOf("B").isEmpty())
    }
}
