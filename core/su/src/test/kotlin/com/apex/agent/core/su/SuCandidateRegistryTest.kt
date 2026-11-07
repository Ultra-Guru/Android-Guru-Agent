package com.apex.agent.core.su

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuCandidateRegistryTest {
    @Test fun `ordered paths include every known manager location exactly once`() {
        assertEquals("/system/bin/su", SuCandidateRegistry.paths.first())
        assertEquals(9, SuCandidateRegistry.paths.distinct().size)
        assertTrue(SuCandidateRegistry.paths.containsAll(listOf(
            "/system/sbin/su", "/debug_ramdisk/su", "/magisk/.core/bin/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
        )))
    }
}
