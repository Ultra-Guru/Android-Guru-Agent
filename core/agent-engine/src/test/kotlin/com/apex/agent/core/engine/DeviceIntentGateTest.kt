package com.apex.agent.core.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══ S03 统一风险门 —— 语义分级矩阵测试 ═══
 *
 * 穷举关键矩阵（手写 fake ask，无 mock 框架）：
 * - CRITICAL / 不可逆 在 BYPASS 下仍 Ask（不可静默全自动）；
 * - 用户 DENY 规则优先级最高（BYPASS 不豁免）；
 * - allow_session 只对可逆意图生效，不可逆只 allow_once；
 * - PLAN 写意图 Deny、dry_run 放行；
 * - 可逆 HIGH Ask / MEDIUM·LOW·READ 放行。
 */
class DeviceIntentGateTest {

    private class FakeAsk(val answer: String = "allow_once") {
        var asked: Int = 0
        var lastQuestion: AgentQuestion? = null
        val ask: suspend (AgentQuestion) -> AgentAnswer = { q ->
            asked++
            lastQuestion = q
            AgentAnswer(questionId = q.id, selectedOptionId = answer)
        }
    }

    private fun gate(
        answer: String = "allow_once",
        rules: List<DeviceGateRule> = emptyList(),
        mode: DeviceGateMode = DeviceGateMode.DEFAULT
    ): Pair<DeviceIntentGate, FakeAsk> {
        val fake = FakeAsk(answer)
        return DeviceIntentGate(
            ask = fake.ask,
            rules = { rules },
            mode = { mode }
        ) to fake
    }

    @Test
    fun `CRITICAL 且不可逆在 BYPASS 下仍 Ask`() = runTest {
        val (g, fake) = gate(mode = DeviceGateMode.BYPASS)
        val decision = g.decide(
            "package.uninstall", DeviceGateRisk.CRITICAL, reversible = false, dryRun = false
        )
        assertTrue("不可逆意图必须弹确认，BYPASS 也不例外", fake.asked == 1)
        assertTrue("用户允许后放行", decision is DeviceGateDecision.Allow)
    }

    @Test
    fun `用户拒绝后 Deny 且文案面向模型`() = runTest {
        val (g, fake) = gate(answer = "deny")
        val decision = g.decide(
            "package.uninstall", DeviceGateRisk.CRITICAL, reversible = false, dryRun = false
        )
        assertTrue(decision is DeviceGateDecision.Deny)
        assertTrue((decision as DeviceGateDecision.Deny).reason.contains("用户拒绝"))
        assertEquals(1, fake.asked)
    }

    @Test
    fun `用户 DENY 规则优先级最高且 BYPASS 不豁免`() = runTest {
        val (g, fake) = gate(
            rules = listOf(DeviceGateRule("package.uninstall", DeviceGateEffect.DENY)),
            mode = DeviceGateMode.BYPASS
        )
        val decision = g.decide(
            "package.uninstall", DeviceGateRisk.CRITICAL, reversible = false, dryRun = false
        )
        assertTrue(decision is DeviceGateDecision.Deny)
        assertTrue((decision as DeviceGateDecision.Deny).reason.contains("拒绝规则"))
        assertEquals("DENY 规则直接拒绝，不弹窗", 0, fake.asked)
    }

    @Test
    fun `尾缀星号规则命中前缀族意图`() = runTest {
        val (g, fake) = gate(
            rules = listOf(DeviceGateRule("package.*", DeviceGateEffect.DENY))
        )
        val decision = g.decide(
            "package.uninstall_for_user", DeviceGateRisk.CRITICAL, reversible = false, dryRun = false
        )
        assertTrue(decision is DeviceGateDecision.Deny)
        assertEquals(0, fake.asked)
    }

    @Test
    fun `ASK 规则触发弹窗且 allow_session 记会话记忆`() = runTest {
        // 答复模型对齐真实 UI：弹窗提供什么选什么 —— allow_session 可选时选它，
        // 不可逆意图的弹窗只提供 allow_once。
        val fake = FakeAsk()
        val g = DeviceIntentGate(
            ask = { q ->
                fake.asked++
                val preferred = if (q.options.any { it.id == "allow_session" }) "allow_session" else "allow_once"
                AgentAnswer(questionId = q.id, selectedOptionId = preferred)
            },
            rules = { listOf(DeviceGateRule("package.*", DeviceGateEffect.ASK)) },
            mode = { DeviceGateMode.BYPASS }
        )
        // 不可逆意图（package.clear 规则 ASK）：弹窗无 allow_session → allow_once 放行。
        val first = g.decide("package.clear", DeviceGateRisk.HIGH, reversible = false, dryRun = false)
        assertTrue(first is DeviceGateDecision.Allow)
        assertEquals(1, fake.asked)
        // 不可逆：allow_session 不计会话记忆，每次都要问。
        g.decide("package.clear", DeviceGateRisk.HIGH, reversible = false, dryRun = false)
        assertEquals(2, fake.asked)
        // 可逆 HIGH（file.write 不匹配规则）：HIGH 恒问；allow_session 生效计记忆。
        val reversible = g.decide("file.write", DeviceGateRisk.HIGH, reversible = true, dryRun = false)
        assertTrue(reversible is DeviceGateDecision.Allow)
        assertEquals(3, fake.asked)
        val again = g.decide("file.write", DeviceGateRisk.HIGH, reversible = true, dryRun = false)
        assertTrue(again is DeviceGateDecision.Allow)
        assertEquals("可逆意图会话记忆生效", 3, fake.asked)
    }

    @Test
    fun `不可逆意图的弹窗不提供 allow_session 选项`() = runTest {
        val (g, fake) = gate()
        g.decide("package.uninstall", DeviceGateRisk.CRITICAL, reversible = false, dryRun = false)
        val options = fake.lastQuestion?.options?.map { it.id } ?: emptyList()
        assertTrue("不可逆意图无 allow_session 选项", "allow_session" !in options)
        assertTrue(options.contains("allow_once"))
        assertTrue(options.contains("deny"))
    }

    @Test
    fun `PLAN 模式写意图 Deny 且文案含只读规划指引`() = runTest {
        val (g, fake) = gate(mode = DeviceGateMode.PLAN)
        val write = g.decide("package.clear", DeviceGateRisk.HIGH, reversible = false, dryRun = false)
        assertTrue(write is DeviceGateDecision.Deny)
        assertTrue((write as DeviceGateDecision.Deny).reason.contains("PLAN"))
        assertEquals(0, fake.asked)
    }

    @Test
    fun `PLAN 模式 dry_run 与只读意图放行`() = runTest {
        val (g, _) = gate(mode = DeviceGateMode.PLAN)
        val dryRun = g.decide("package.uninstall", DeviceGateRisk.CRITICAL, reversible = false, dryRun = true)
        assertTrue(dryRun is DeviceGateDecision.Allow)
        val read = g.decide("package.list", DeviceGateRisk.READ, reversible = true, dryRun = false)
        assertTrue(read is DeviceGateDecision.Allow)
    }

    @Test
    fun `BYPASS 放行可逆 MEDIUM 而 HIGH 可逆弹确认`() = runTest {
        val (g, fake) = gate(mode = DeviceGateMode.BYPASS)
        val medium = g.decide("package.force_stop", DeviceGateRisk.MEDIUM, reversible = true, dryRun = false)
        assertTrue(medium is DeviceGateDecision.Allow)
        assertEquals("MEDIUM 可逆不弹窗", 0, fake.asked)
        val high = g.decide("file.write", DeviceGateRisk.HIGH, reversible = true, dryRun = false)
        assertTrue(high is DeviceGateDecision.Allow)
        assertEquals("HIGH 可逆弹一次", 1, fake.asked)
    }

    @Test
    fun `DEFAULT 模式 MEDIUM 可逆直接放行`() = runTest {
        val (g, fake) = gate(mode = DeviceGateMode.DEFAULT)
        val decision = g.decide("system.appops", DeviceGateRisk.HIGH, reversible = true, dryRun = false)
        assertTrue(decision is DeviceGateDecision.Allow)
        assertEquals(1, fake.asked)
        val medium = g.decide("package.enable", DeviceGateRisk.MEDIUM, reversible = true, dryRun = false)
        assertTrue(medium is DeviceGateDecision.Allow)
        assertEquals("MEDIUM 不弹", 1, fake.asked)
    }

    @Test
    fun `弹窗描述包含影响面信息`() = runTest {
        val (g, fake) = gate()
        g.decide(
            "package.uninstall", DeviceGateRisk.CRITICAL, reversible = false, dryRun = false,
            description = "影响面：卸载应用\n当前状态：versionName=1.0\n将执行：pm uninstall com.x"
        )
        val desc = fake.lastQuestion?.description ?: ""
        assertTrue(desc.contains("影响面"))
        assertTrue(desc.contains("pm uninstall com.x"))
        assertTrue(desc.contains("不可逆"))
    }

    @Test
    fun `不可逆意图收到伪造的 allow_session 答案按拒绝处理`() = runTest {
        val fake = FakeAsk("allow_session")
        val g = DeviceIntentGate(ask = fake.ask, rules = { emptyList() }, mode = { DeviceGateMode.DEFAULT })
        val decision = g.decide(
            "package.uninstall", DeviceGateRisk.CRITICAL, reversible = false, dryRun = false
        )
        assertTrue("伪造的会话放行答案 fail-safe 拒绝", decision is DeviceGateDecision.Deny)
    }
}
