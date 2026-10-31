package com.apex.agent.core.code.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardLogicMode 持久化解析测试：
 * - 规范名 round-trip；
 * - 历史别名（deep/apex/custom/std）折叠；
 * - 脏值/空值 null（调用方兜底深潜）。
 */
class StandardLogicModeTest {

    @Test
    fun `canonical persistence names round-trip`() {
        StandardLogicMode.entries.forEach { mode ->
            assertEquals(mode, StandardLogicMode.fromName(mode.persistenceName))
            // 大写容忍
            assertEquals(mode, StandardLogicMode.fromName(mode.persistenceName.uppercase()))
        }
    }

    @Test
    fun `legacy aliases fold to expected modes`() {
        assertEquals(StandardLogicMode.DEEP_DIVE, StandardLogicMode.fromName("deep"))
        assertEquals(StandardLogicMode.DEEP_DIVE, StandardLogicMode.fromName("apex"))
        assertEquals(StandardLogicMode.DEEP_DIVE, StandardLogicMode.fromName("custom"))
        assertEquals(StandardLogicMode.STANDARD, StandardLogicMode.fromName("std"))
        assertEquals(StandardLogicMode.LOOM, StandardLogicMode.fromName("loom"))
        assertEquals(StandardLogicMode.LOOM, StandardLogicMode.fromName("weave"))
        assertEquals(StandardLogicMode.COURIER, StandardLogicMode.fromName("courier"))
        assertEquals(StandardLogicMode.COURIER, StandardLogicMode.fromName("express"))
    }

    @Test
    fun `blank and unknown values return null`() {
        assertNull(StandardLogicMode.fromName(null))
        assertNull(StandardLogicMode.fromName(""))
        assertNull(StandardLogicMode.fromName("   "))
        assertNull(StandardLogicMode.fromName("turbo"))
        assertNull(StandardLogicMode.fromName("copilot"))
    }

    @Test
    fun `isStandard and isLoom flags match kind`() {
        assertFalse(StandardLogicMode.DEEP_DIVE.isStandard)
        assertTrue(StandardLogicMode.STANDARD.isStandard)
        assertFalse(StandardLogicMode.STANDARD.isLoom)
        assertTrue(StandardLogicMode.LOOM.isLoom)
        assertFalse(StandardLogicMode.LOOM.isStandard)
    }

    @Test
    fun `isCourier flag matches kind`() {
        assertTrue(StandardLogicMode.COURIER.isCourier)
        assertFalse(StandardLogicMode.DEEP_DIVE.isCourier)
        assertFalse(StandardLogicMode.STANDARD.isCourier)
        assertFalse(StandardLogicMode.LOOM.isCourier)
        // 其他标志位互斥
        assertFalse(StandardLogicMode.COURIER.isStandard)
        assertFalse(StandardLogicMode.COURIER.isLoom)
    }

    @Test
    fun `default mode is deep dive (v1 2 行为零变化)`() {
        // 未设置（fromName → null）时调用方兜底深潜——历史行为不变式
        val restored = StandardLogicMode.fromName("") ?: StandardLogicMode.DEEP_DIVE
        assertEquals(StandardLogicMode.DEEP_DIVE, restored)
    }
}
