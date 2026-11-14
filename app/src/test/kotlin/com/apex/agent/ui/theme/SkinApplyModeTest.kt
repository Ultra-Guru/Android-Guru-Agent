package com.apex.agent.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolvePolicy] / [DeviceMetrics.tier] / 三档枚举容错单测
 * （PartSkin spec v1.3 §7.9 / §11.1 / J-5）。纯 JVM —— theme 包零反向
 * 依赖（NFR-8）的直接证据。
 */
class SkinApplyModeTest {

    // ═══ resolvePolicy 9 组合全表（§7.9.1）═══

    @Test
    fun `resolve policy full 3x3 matrix`() {
        // AUTO：仅 HIGH 立即，MID/LOW 新对话
        assertEquals(SkinApplyPolicy.ON_NEW_CHAT, resolvePolicy(SkinApplyMode.AUTO, DevicePerfTier.LOW))
        assertEquals(SkinApplyPolicy.ON_NEW_CHAT, resolvePolicy(SkinApplyMode.AUTO, DevicePerfTier.MID))
        assertEquals(SkinApplyPolicy.IMMEDIATE, resolvePolicy(SkinApplyMode.AUTO, DevicePerfTier.HIGH))
        // IMMEDIATE：LOW 硬拒（NFR-16），MID/HIGH 立即
        assertEquals(SkinApplyPolicy.ON_NEW_CHAT, resolvePolicy(SkinApplyMode.IMMEDIATE, DevicePerfTier.LOW))
        assertEquals(SkinApplyPolicy.IMMEDIATE, resolvePolicy(SkinApplyMode.IMMEDIATE, DevicePerfTier.MID))
        assertEquals(SkinApplyPolicy.IMMEDIATE, resolvePolicy(SkinApplyMode.IMMEDIATE, DevicePerfTier.HIGH))
        // ON_NEW_CHAT：全分级新对话
        assertEquals(SkinApplyPolicy.ON_NEW_CHAT, resolvePolicy(SkinApplyMode.ON_NEW_CHAT, DevicePerfTier.LOW))
        assertEquals(SkinApplyPolicy.ON_NEW_CHAT, resolvePolicy(SkinApplyMode.ON_NEW_CHAT, DevicePerfTier.MID))
        assertEquals(SkinApplyPolicy.ON_NEW_CHAT, resolvePolicy(SkinApplyMode.ON_NEW_CHAT, DevicePerfTier.HIGH))
    }

    @Test
    fun `immediate plus low is hard rejected`() {
        // NFR-16：状态机不信任 UI 置灰 —— LOW 档即使手动切 IMMEDIATE 也被拒
        val policy = resolvePolicy(SkinApplyMode.IMMEDIATE, DevicePerfTier.LOW)
        assertEquals(SkinApplyPolicy.ON_NEW_CHAT, policy)
    }

    // ═══ DeviceMetrics.tier() 边界（附录 I-1）═══

    private fun metrics(
        lowRam: Boolean = false,
        memoryClass: Int = 256,
        totalMemMb: Long = 6144,
        cores: Int = 8,
        api: Int = 33,
        powerSave: Boolean = false,
        reducedMotion: Boolean = false
    ) = DeviceMetrics(
        lowRamDevice = lowRam,
        memoryClassMb = memoryClass,
        totalMemMb = totalMemMb,
        cpuCores = cores,
        apiLevel = api,
        powerSave = powerSave,
        reducedMotion = reducedMotion
    )

    @Test
    fun `tier boundaries memory class and total mem`() {
        // memoryClass 127 → LOW；128 恰好不触发 LOW
        assertEquals(DevicePerfTier.LOW, metrics(memoryClass = 127).tier())
        // totalMem 3071MB → LOW；3072 恰好不触发
        assertEquals(DevicePerfTier.LOW, metrics(totalMemMb = 3071).tier())
        // memoryClass 255 → MID；256 恰好满足 HIGH 条件之一
        assertEquals(DevicePerfTier.MID, metrics(memoryClass = 255).tier())
        assertEquals(DevicePerfTier.HIGH, metrics(memoryClass = 256).tier())
        // totalMem 6143 → MID；6144 → HIGH
        assertEquals(DevicePerfTier.MID, metrics(totalMemMb = 6143).tier())
        assertEquals(DevicePerfTier.HIGH, metrics(totalMemMb = 6144).tier())
    }

    @Test
    fun `tier boundaries cores api lowram powersave motion`() {
        // 4 核 → LOW；8 核（其余满足）→ HIGH
        assertEquals(DevicePerfTier.LOW, metrics(cores = 4).tier())
        // API 30 → LOW（无 RenderEffect blur）；31 恰好不触发；33 满足 HIGH
        assertEquals(DevicePerfTier.LOW, metrics(api = 30).tier())
        assertEquals(DevicePerfTier.MID, metrics(api = 31).tier())
        assertEquals(DevicePerfTier.HIGH, metrics(api = 33).tier())
        // 任一 LOW 条件命中即 LOW（附录 I-1「任一命中」）
        assertEquals(DevicePerfTier.LOW, metrics(lowRam = true).tier())
        assertEquals(DevicePerfTier.LOW, metrics(powerSave = true).tier())
        assertEquals(DevicePerfTier.LOW, metrics(reducedMotion = true).tier())
        // 省电 / 减少动效是动态状态 —— 即使内存再大也降 LOW（I-3 叠加）
        assertEquals(DevicePerfTier.LOW, metrics(powerSave = true, memoryClass = 512).tier())
    }

    @Test
    fun `mid falls between low triggers and high requirements`() {
        // 不触发任何 LOW、但 HIGH 条件不齐（API 32）→ MID
        assertEquals(DevicePerfTier.MID, metrics(api = 32).tier())
        // 6 核 → MID
        assertEquals(DevicePerfTier.MID, metrics(cores = 6).tier())
    }

    // ═══ 枚举容错（FR-25 零迁移）═══

    @Test
    fun `apply mode fromKey tolerates garbage`() {
        assertEquals(SkinApplyMode.AUTO, SkinApplyMode.fromKey(null))
        assertEquals(SkinApplyMode.AUTO, SkinApplyMode.fromKey(""))
        assertEquals(SkinApplyMode.AUTO, SkinApplyMode.fromKey("AUTO"))
        assertEquals(SkinApplyMode.IMMEDIATE, SkinApplyMode.fromKey(" Immediate "))
        assertEquals(SkinApplyMode.ON_NEW_CHAT, SkinApplyMode.fromKey("on_new_chat"))
        assertEquals(SkinApplyMode.AUTO, SkinApplyMode.fromKey("bogus"))
    }
}
