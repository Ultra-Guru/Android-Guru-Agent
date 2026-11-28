package com.apex.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══ S10 审计兼容测试 ═══
 *
 * 历史格式 jsonl（DCP 扩展字段缺席）必须仍可解析 —— parseLineInternal
 * 按字段取值 + 默认值，旧行不炸读取方（recentEntries / recentFailures
 * 的既有消费者零改动）。新格式扩展字段全部可选。
 */
class ToolAuditLoggerCompatTest {

    @Test
    fun `旧格式行（v1_5 时代 9 字段）完整可读`() {
        val legacy =
            """{"ts":1730000000000,"tool":"ShellExecute","decision":"executed","tier":"shizuku","command":"ls","durationMs":12,"success":true,"exitCode":0}"""
        val entry = ToolAuditLogger.parseLineInternal(legacy)
        assertEquals("ShellExecute", entry?.tool)
        assertEquals("executed", entry?.decision)
        assertEquals("shizuku", entry?.tier)
        assertEquals("ls", entry?.command)
        assertEquals(12L, entry?.durationMs)
        assertEquals(true, entry?.success)
        assertEquals(0, entry?.exitCode)
        // DCP 扩展字段缺省为 null —— 不编造。
        assertNull(entry?.intentId)
        assertNull(entry?.risk)
        assertNull(entry?.auditId)
        assertNull(entry?.dryRun)
        assertEquals(false, entry?.isFailure)
    }

    @Test
    fun `新格式行（DCP 扩展字段齐全）完整可读`() {
        val modern =
            """{"ts":1730000000001,"tool":"DeviceOps","decision":"executed","tier":"SHIZUKU","command":"pm uninstall com.x","durationMs":88,"success":true,"exitCode":0,"intentId":"package.uninstall","risk":"CRITICAL","privilegeTier":"SHIZUKU","uid":2000,"dryRun":false,"confirmToken":"12345678","diffBefore":"installed=true","diffAfter":"installed=false","reversible":false,"rollbackHint":"重装","auditId":"dcp-a1b2c3d4"}"""
        val entry = ToolAuditLogger.parseLineInternal(modern)
        assertEquals("DeviceOps", entry?.tool)
        assertEquals("package.uninstall", entry?.intentId)
        assertEquals("CRITICAL", entry?.risk)
        assertEquals(2000, entry?.uid)
        assertEquals(false, entry?.dryRun)
        // 令牌只存前 8 位 —— 写入侧截断，读取侧原样（写入已保证）。
        assertEquals("12345678", entry?.confirmToken)
        assertEquals(false, entry?.reversible)
        assertEquals("dcp-a1b2c3d4", entry?.auditId)
    }

    @Test
    fun `损坏行与空白行返回 null 不炸读取方`() {
        assertNull(ToolAuditLogger.parseLineInternal(""))
        assertNull(ToolAuditLogger.parseLineInternal("   "))
        assertNull(ToolAuditLogger.parseLineInternal("not json at all"))
        assertNull(ToolAuditLogger.parseLineInternal("""{"broken":}"""))
    }

    @Test
    fun `denied_by_user 决策被判定为失败记录`() {
        val denied =
            """{"ts":1,"tool":"DeviceOps","decision":"denied_by_user","intentId":"package.uninstall","auditId":"dcp-x"}"""
        val entry = ToolAuditLogger.parseLineInternal(denied)
        assertTrue(entry != null && entry.isFailure)
    }

    @Test
    fun `dry_run preflight 事件不是失败记录`() {
        val preflight =
            """{"ts":1,"tool":"DeviceOps","decision":"preflight","intentId":"package.uninstall","dryRun":true,"auditId":"dcp-x"}"""
        val entry = ToolAuditLogger.parseLineInternal(preflight)
        assertTrue(entry != null && !entry.isFailure)
        assertEquals(true, entry?.dryRun)
    }
}
