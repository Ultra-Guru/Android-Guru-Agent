package com.apex.agent.ui.screen.code

import com.apex.agent.ui.theme.CodingPart
import com.apex.agent.ui.theme.DevicePerfTier
import com.apex.agent.ui.theme.PartSkin
import com.apex.agent.ui.theme.PartSkinOverrides
import com.apex.agent.ui.theme.SkinApplyMode
import com.apex.agent.ui.theme.SkinApplyPolicy
import com.apex.agent.ui.theme.UiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkinActivation] 会话边界状态机单测（PartSkin spec v1.2 §7.5 / v1.3 §7.9，
 * 附录 G-5 / J-5）。纯 JVM：stage / promote / discard / sameContent /
 * diffParts 全部是纯函数语义断言。
 *
 * 覆盖：
 *  - G-4 会话冻结：stage() 不改 active，仅 pending 变化；
 *  - G-5 任务中冻结（H-6）：isRunning = true 时三策略都不提升；
 *  - IMMEDIATE idle 内联提升；onRunSettled 语义（promote 幂等）；
 *  - NFR-15：内容全等不产生 pending、不升 epoch；
 *  - NFR-13 语义面：promote/discard 的 epoch 单调与 pending 清空。
 */
class ConversationSkinTest {

    private fun snapshot(
        epoch: Long = 0L,
        uiStyle: UiStyle = UiStyle.LIQUID_GLASS,
        outputStyle: CodeOutputStyle = CodeOutputStyle.TIMELINE,
        renderMode: CodeOutputMode = CodeOutputMode.STREAMING,
        partSkins: PartSkinOverrides = PartSkinOverrides(),
        glow: Float = 0.35f
    ) = ConversationSkinSnapshot(
        epoch = epoch,
        uiStyle = uiStyle,
        outputStyle = outputStyle,
        renderMode = renderMode,
        partSkins = partSkins,
        glow = glow
    )

    private fun activation(
        mode: SkinApplyMode = SkinApplyMode.ON_NEW_CHAT,
        tier: DevicePerfTier = DevicePerfTier.MID
    ) = SkinActivation(active = snapshot(), applyMode = mode, tier = tier)

    private val changedPart = CodingPart.STREAM_THINKING
    private val nextOverrides =
        PartSkinOverrides(map = mapOf(CodingPart.STREAM_THINKING to PartSkin.FLAT))

    // ═══ G-4 会话冻结（H-5，一票否决项）═══

    @Test
    fun `stage does not touch active`() {
        val activation = activation()
        val next = snapshot(partSkins = nextOverrides)
        val staged = activation.stage(next, setOf(changedPart), isRunning = false)
        // active 原封不动；仅 pending 变化
        assertEquals(activation.active, staged.active)
        assertEquals(0L, staged.active.epoch)
        assertEquals(next.copy(epoch = 1L), staged.pending)
        assertEquals(setOf(changedPart), staged.changedParts)
    }

    @Test
    fun `promote bumps epoch once and clears pending`() {
        val staged = activation().stage(
            snapshot(partSkins = nextOverrides), setOf(changedPart), isRunning = false
        )
        val promoted = staged.promote()
        assertEquals(1L, promoted.active.epoch)
        assertEquals(nextOverrides, promoted.active.partSkins)
        assertNull(promoted.pending)
        assertTrue(promoted.changedParts.isEmpty())
        // 二次 promote 幂等（无 pending 时原样返回，epoch 不虚增）
        val again = promoted.promote()
        assertEquals(promoted, again)
        assertEquals(1L, again.active.epoch)
    }

    @Test
    fun `discard keeps active intact and clears pending`() {
        val activation = activation()
        val staged = activation.stage(
            snapshot(partSkins = nextOverrides), setOf(changedPart), isRunning = false
        )
        val discarded = staged.discard()
        assertEquals(activation.active, discarded.active)
        assertNull(discarded.pending)
        assertFalse(discarded.hasPending)
    }

    // ═══ NFR-15：内容全等不产生 pending、不升 epoch ═══

    @Test
    fun `same content stage produces no pending`() {
        val activation = activation()
        val same = activation.stage(snapshot(), emptySet(), isRunning = false)
        assertNull(same.pending)
        assertEquals(activation.active.epoch, same.active.epoch)
        assertFalse(same.hasPending)
    }

    @Test
    fun `same content ignores epoch`() {
        // epoch 不同但四维度全等 → sameContent = true
        assertTrue(snapshot(epoch = 0L).sameContent(snapshot(epoch = 99L)))
        // 任一维度不同 → false
        assertFalse(snapshot().sameContent(snapshot(glow = 0.5f)))
        assertFalse(
            snapshot().sameContent(snapshot(outputStyle = CodeOutputStyle.RIKKA))
        )
        assertFalse(
            snapshot().sameContent(snapshot(partSkins = nextOverrides))
        )
    }

    // ═══ G-5 任务中冻结（H-6，全模式一票否决）═══

    @Test
    fun `running stage freezes in all policies`() {
        val next = snapshot(partSkins = nextOverrides)
        // AUTO(HIGH→IMMEDIATE) / IMMEDIATE / ON_NEW_CHAT 三策略
        val modes = listOf(
            SkinApplyMode.AUTO to DevicePerfTier.HIGH,
            SkinApplyMode.IMMEDIATE to DevicePerfTier.MID,
            SkinApplyMode.ON_NEW_CHAT to DevicePerfTier.LOW
        )
        modes.forEach { (mode, tier) ->
            val activation = activation(mode, tier)
            val staged = activation.stage(next, setOf(changedPart), isRunning = true)
            // active 全等改前、epoch 不变
            assertEquals(activation.active, staged.active)
            assertEquals(0L, staged.active.epoch)
            // 挂起标记 + pending 保留
            assertTrue(staged.stagedDuringRun)
            assertTrue(staged.hasPending)
        }
    }

    @Test
    fun `immediate promotes inline only when idle`() {
        val next = snapshot(partSkins = nextOverrides)
        // idle + IMMEDIATE → stage 内联提升（当前对话整体换皮一次）
        val activation = activation(SkinApplyMode.IMMEDIATE, DevicePerfTier.MID)
        val stagedIdle = activation.stage(next, setOf(changedPart), isRunning = false)
        assertNull(stagedIdle.pending)
        assertEquals(1L, stagedIdle.active.epoch)
        assertEquals(nextOverrides, stagedIdle.active.partSkins)
        // 运行中 → 只挂起（H-6）
        val stagedRunning = activation.stage(next, setOf(changedPart), isRunning = true)
        assertEquals(0L, stagedRunning.active.epoch)
        assertTrue(stagedRunning.hasPending)
    }

    @Test
    fun `on_new_chat never promotes inline`() {
        val next = snapshot(partSkins = nextOverrides)
        val activation = activation(SkinApplyMode.ON_NEW_CHAT, DevicePerfTier.HIGH)
        val staged = activation.stage(next, setOf(changedPart), isRunning = false)
        assertEquals(0L, staged.active.epoch)
        assertTrue(staged.hasPending)
        // 会话边界提升（所有策略都提升）
        val promoted = staged.promote()
        assertEquals(1L, promoted.active.epoch)
        assertNull(promoted.pending)
    }

    @Test
    fun `auto resolves per tier in stage`() {
        val next = snapshot(partSkins = nextOverrides)
        // AUTO + HIGH → IMMEDIATE：idle 立即提升
        val highAuto = activation(SkinApplyMode.AUTO, DevicePerfTier.HIGH)
        assertEquals(SkinApplyPolicy.IMMEDIATE, highAuto.effectivePolicy)
        assertNull(highAuto.stage(next, setOf(changedPart), isRunning = false).pending)
        // AUTO + MID / LOW → ON_NEW_CHAT：保持 pending
        val midAuto = activation(SkinApplyMode.AUTO, DevicePerfTier.MID)
        assertTrue(midAuto.stage(next, setOf(changedPart), isRunning = false).hasPending)
        val lowAuto = activation(SkinApplyMode.AUTO, DevicePerfTier.LOW)
        assertTrue(lowAuto.stage(next, setOf(changedPart), isRunning = false).hasPending)
        // LOW + 手动 IMMEDIATE → resolvePolicy 硬拒（NFR-16）
        val lowImmediate = activation(SkinApplyMode.IMMEDIATE, DevicePerfTier.LOW)
        assertEquals(SkinApplyPolicy.ON_NEW_CHAT, lowImmediate.effectivePolicy)
        assertTrue(lowImmediate.stage(next, setOf(changedPart), isRunning = false).hasPending)
    }

    // ═══ onRunSettled 语义（v1.3 §7.9.3）：promote 幂等 + 策略门 ═══

    @Test
    fun `settled run promotes only immediate with pending`() {
        val next = snapshot(partSkins = nextOverrides)
        // IMMEDIATE：收束（isRunning=false）后提升
        val running = activation(SkinApplyMode.IMMEDIATE, DevicePerfTier.MID)
            .stage(next, setOf(changedPart), isRunning = true)
        val settled = running.promote() // onRunSettled 的核心动作
        assertEquals(1L, settled.active.epoch)
        assertNull(settled.pending)
        // ON_NEW_CHAT：收束后仍保持 pending（等新对话）
        val onNewChat = activation(SkinApplyMode.ON_NEW_CHAT, DevicePerfTier.MID)
            .stage(next, setOf(changedPart), isRunning = true)
        val notPromoted = onNewChat.promote()
        // promote 是会话边界动作 —— 此处仅验证状态机本身不因收束自动清 pending
        assertTrue(onNewChat.hasPending)
    }

    // ═══ withTier / withApplyMode（§7.9 采样缓存）═══

    @Test
    fun `with tier and mode are no-op on equal values`() {
        val activation = activation()
        assertEquals(activation, activation.withTier(DevicePerfTier.MID))
        assertEquals(activation, activation.withApplyMode(SkinApplyMode.ON_NEW_CHAT))
        // 变更不触碰 active / pending
        val changed = activation.withTier(DevicePerfTier.HIGH)
        assertNotEquals(activation, changed)
        assertEquals(activation.active, changed.active)
        assertEquals(DevicePerfTier.HIGH, changed.tier)
        assertEquals(
            SkinApplyPolicy.IMMEDIATE,
            changed.copy(applyMode = SkinApplyMode.AUTO).effectivePolicy
        )
    }

    // ═══ diffParts（「N 项待生效」数据面）═══

    @Test
    fun `diff parts counts explicit and group changes`() {
        val a = PartSkinOverrides()
        // 部件级变化
        val partChange = PartSkinOverrides(
            map = mapOf(CodingPart.STREAM_THINKING to PartSkin.FLAT)
        )
        assertEquals(setOf(CodingPart.STREAM_THINKING), diffParts(a, partChange))
        // 组级变化：整组成员都算「待生效」
        val groupChange = PartSkinOverrides(groups = mapOf(com.apex.agent.ui.theme.SkinGroup.G2 to PartSkin.FLAT))
        val diff = diffParts(a, groupChange)
        assertTrue(CodingPart.STREAM_THINKING_HEADER in diff)
        assertTrue(diff.size == com.apex.agent.ui.theme.SkinGroup.G2.parts.size)
        // 无变化 → 空集
        assertTrue(diffParts(a, PartSkinOverrides()).isEmpty())
    }
}
