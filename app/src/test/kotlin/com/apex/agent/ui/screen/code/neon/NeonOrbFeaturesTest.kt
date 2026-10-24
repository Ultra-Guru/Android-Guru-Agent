package com.apex.agent.ui.screen.code.neon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ═══ 功能聚合 —— 纯逻辑层契约测试 ═══
 *
 * 锁三条底线：
 * 1. 目录契约：≥15 项功能 + 三项钦定能力在场（时间注入/结构化输出/
 *    网络搜索，用户需求原文点名）+ id 唯一；
 * 2. 注入真实性：每个功能开启后 [buildNeonSessionContext] 必须产出
 *    对应指令段（关 = 零注入，防「假开关」）；
 * 3. 组装口径：时间注入取调用时刻实时值；全开时 16 段全数在场。
 *
 * 纯 JVM（NeonOrbFeatures.kt 零 Android 依赖），CI 走
 * :app:testDebugUnitTest 本地单测通道。
 */
class NeonOrbFeaturesTest {

    // ── 目录契约 ─────────────────────────────────────────

    @Test
    fun `目录至少 15 项功能`() {
        assertTrue(
            "功能目录必须 ≥15 项（当前 ${FEATURE_IDS.size}）",
            FEATURE_IDS.size >= 15
        )
    }

    @Test
    fun `三项钦定能力在场`() {
        val ids = FEATURE_IDS.map { it.id }.toSet()
        MANDATORY_FEATURE_IDS.forEach { id ->
            assertTrue("钦定能力缺失：$id", id in ids)
        }
    }

    @Test
    fun `功能 id 全局唯一`() {
        val ids = FEATURE_IDS.map { it.id }
        assertEquals("功能 id 重复", ids.size, ids.toSet().size)
    }

    @Test
    fun `三组分区全覆盖`() {
        val groups = FEATURE_IDS.map { it.group }.toSet()
        assertEquals(
            "分组应为 会话增强/输出口径/编码偏好 三组",
            setOf(NeonFeatureGroup.SESSION, NeonFeatureGroup.STYLE, NeonFeatureGroup.CODING),
            groups
        )
    }

    // ── 空态零注入 ───────────────────────────────────────

    @Test
    fun `全关状态零注入`() {
        val ctx = buildNeonSessionContext(NeonOrbState())
        assertTrue("全关时必须返回空串", ctx.isEmpty())
    }

    @Test
    fun `全关状态激活计数为零`() {
        assertEquals(0, NeonOrbState().activeCount)
        assertTrue(!NeonOrbState().anyActive)
    }

    // ── 会话增强组（6 项）────────────────────────────────

    @Test
    fun `时间注入携带实时时钟与时区`() {
        // 固定时钟（测试确定性）：2026-10-24 09:30:00 CST（星期六）。
        // 记录并还原默认时区 —— 不污染同 JVM 后续测试。
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val fixed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .parse("2026-10-24 09:30:00")!!
            val ctx = buildNeonSessionContext(
                NeonOrbState(timeInjection = true), now = fixed
            )
            assertTrue("时间注入必须含实时时间", ctx.contains("2026-10-24 09:30:00"))
            assertTrue("时间注入必须含时区", ctx.contains("Asia/Shanghai"))
            assertTrue("时间注入必须含星期", ctx.contains("星期六"))
            assertTrue("时间注入必须声明以此时钟为准", ctx.contains("以此为准"))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `网络搜索强制先搜后答`() {
        val ctx = buildNeonSessionContext(NeonOrbState(webSearch = true))
        assertTrue(ctx.contains("WebSearch"))
        assertTrue(ctx.contains("先调用"))
        assertTrue(ctx.contains("禁止仅凭训练知识"))
    }

    @Test
    fun `深度推理要求分步拆解`() {
        val ctx = buildNeonSessionContext(NeonOrbState(deepReasoning = true))
        assertTrue(ctx.contains("深度推理"))
        assertTrue(ctx.contains("分步"))
    }

    @Test
    fun `目标复述要求动手前复述`() {
        val ctx = buildNeonSessionContext(NeonOrbState(goalEcho = true))
        assertTrue(ctx.contains("目标复述"))
        assertTrue(ctx.contains("复述"))
    }

    @Test
    fun `诚实边界禁止编造`() {
        val ctx = buildNeonSessionContext(NeonOrbState(antiHallucination = true))
        assertTrue(ctx.contains("诚实边界"))
        assertTrue(ctx.contains("禁止编造"))
    }

    // ── 结构化输出档（5 种格式逐档）──────────────────────

    @Test
    fun `结构化输出 JSON 档`() {
        val ctx = buildNeonSessionContext(
            NeonOrbState(outputFormat = NeonOutputFormat.JSON)
        )
        assertTrue(ctx.contains("JSON"))
        assertTrue(ctx.contains("结构化输出"))
    }

    @Test
    fun `结构化输出 XML 档`() {
        val ctx = buildNeonSessionContext(
            NeonOrbState(outputFormat = NeonOutputFormat.XML)
        )
        assertTrue(ctx.contains("<response>"))
    }

    @Test
    fun `结构化输出 YAML 档`() {
        val ctx = buildNeonSessionContext(
            NeonOrbState(outputFormat = NeonOutputFormat.YAML)
        )
        assertTrue(ctx.contains("YAML"))
    }

    @Test
    fun `结构化输出表格档`() {
        val ctx = buildNeonSessionContext(
            NeonOrbState(outputFormat = NeonOutputFormat.MARKDOWN_TABLE)
        )
        assertTrue(ctx.contains("Markdown 表格"))
    }

    @Test
    fun `结构化输出 TOML 档`() {
        val ctx = buildNeonSessionContext(
            NeonOrbState(outputFormat = NeonOutputFormat.TOML)
        )
        assertTrue(ctx.contains("TOML"))
    }

    // ── 输出口径组（6 项）────────────────────────────────

    @Test
    fun `语言锁定中文档`() {
        val ctx = buildNeonSessionContext(
            NeonOrbState(languageLock = NeonLanguageLock.CHINESE)
        )
        assertTrue(ctx.contains("简体中文"))
    }

    @Test
    fun `语言锁定英文档`() {
        val ctx = buildNeonSessionContext(
            NeonOrbState(languageLock = NeonLanguageLock.ENGLISH)
        )
        assertTrue(ctx.contains("entirely in English"))
    }

    @Test
    fun `简洁模式砍掉客套`() {
        val ctx = buildNeonSessionContext(NeonOrbState(concise = true))
        assertTrue(ctx.contains("简洁模式"))
        assertTrue(ctx.contains("砍掉"))
    }

    @Test
    fun `详细讲解教学式展开`() {
        val ctx = buildNeonSessionContext(NeonOrbState(teachMode = true))
        assertTrue(ctx.contains("详细讲解"))
        assertTrue(ctx.contains("示例"))
    }

    @Test
    fun `步骤编号有序呈现`() {
        val ctx = buildNeonSessionContext(NeonOrbState(numberedSteps = true))
        assertTrue(ctx.contains("步骤编号"))
        assertTrue(ctx.contains("1. 2. 3."))
    }

    @Test
    fun `无表情符号禁用 emoji`() {
        val ctx = buildNeonSessionContext(NeonOrbState(noEmoji = true))
        assertTrue(ctx.contains("emoji"))
        assertTrue(ctx.contains("禁止"))
    }

    @Test
    fun `语气人设三档各就各位`() {
        NeonTone.entries.filter { it != NeonTone.OFF }.forEach { tone ->
            val ctx = buildNeonSessionContext(NeonOrbState(tone = tone))
            assertTrue("语气档 $tone 缺指令", ctx.contains("语气人设"))
            assertTrue("语气档 $tone 缺定位词", ctx.contains(tone.label))
        }
        // OFF 档零注入
        assertTrue(
            buildNeonSessionContext(NeonOrbState(tone = NeonTone.OFF)).isEmpty()
        )
    }

    // ── 编码偏好组（4 项）────────────────────────────────

    @Test
    fun `代码注释充分注释`() {
        val ctx = buildNeonSessionContext(NeonOrbState(richComments = true))
        assertTrue(ctx.contains("代码注释"))
        assertTrue(ctx.contains("注释"))
    }

    @Test
    fun `Diff 优先 unified diff 呈现`() {
        val ctx = buildNeonSessionContext(NeonOrbState(diffFirst = true))
        assertTrue(ctx.contains("unified diff"))
    }

    @Test
    fun `风险预警先亮红旗`() {
        val ctx = buildNeonSessionContext(NeonOrbState(riskWarnings = true))
        assertTrue(ctx.contains("风险预警"))
        assertTrue(ctx.contains("回滚"))
    }

    @Test
    fun `安全命令给替代方案`() {
        val ctx = buildNeonSessionContext(NeonOrbState(safeCommands = true))
        assertTrue(ctx.contains("安全命令"))
        assertTrue(ctx.contains("非破坏性"))
    }

    // ── 组装口径 ─────────────────────────────────────────

    @Test
    fun `全开状态 16 段全在场且计数为 16`() {
        val allOn = NeonOrbState(
            timeInjection = true, webSearch = true,
            outputFormat = NeonOutputFormat.JSON,
            deepReasoning = true, goalEcho = true, antiHallucination = true,
            languageLock = NeonLanguageLock.CHINESE, concise = true,
            teachMode = true, numberedSteps = true, noEmoji = true,
            tone = NeonTone.DIRECT, richComments = true, diffFirst = true,
            riskWarnings = true, safeCommands = true
        )
        assertEquals(16, allOn.activeCount)
        assertTrue(allOn.anyActive)
        val ctx = buildNeonSessionContext(allOn)
        // 16 段指令逐段在场（buildString 每功能一行「- …」）
        val directiveLines = ctx.lines().count { it.startsWith("- ") }
        assertEquals("全开应产出 16 条指令行", 16, directiveLines)
        assertTrue(ctx.startsWith("### 功能聚合"))
    }

    @Test
    fun `单开一项只注入一段`() {
        val ctx = buildNeonSessionContext(NeonOrbState(concise = true))
        assertEquals(1, ctx.lines().count { it.startsWith("- ") })
        assertEquals(1, NeonOrbState(concise = true).activeCount)
    }

    @Test
    fun `注入段以标题行开头且无空行粘连`() {
        val ctx = buildNeonSessionContext(
            NeonOrbState(webSearch = true, concise = true)
        )
        assertTrue(ctx.startsWith("### 功能聚合"))
        assertEquals(2, ctx.lines().count { it.startsWith("- ") })
    }
}
