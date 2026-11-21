package com.apex.agent.core.engine.templates.runtime.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 17 测试 · 提交信息规范体系库。
 */
class CommitMessageConventionsTest {

    // ═══ 风格解析 ═══

    @Test
    fun `style parsing recognizes aliases`() {
        assertEquals(CommitStyleKind.CONVENTIONAL, CommitStyleKind.parse("conventional commits"))
        assertEquals(CommitStyleKind.CONVENTIONAL, CommitStyleKind.parse("约定式提交"))
        assertEquals(CommitStyleKind.GITMOJI, CommitStyleKind.parse("gitmoji"))
        assertEquals(CommitStyleKind.GITMOJI, CommitStyleKind.parse("emoji 风格"))
        assertEquals(CommitStyleKind.KERNEL, CommitStyleKind.parse("linux 内核风格"))
        assertEquals(CommitStyleKind.PLAIN, CommitStyleKind.parse("自由格式"))
        assertNull(CommitStyleKind.parse("火星风格"))
        assertNull(CommitStyleKind.parse(""))
    }

    @Test
    fun `style specs carry distinct limits`() {
        val conventional = CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        val kernel = CommitStyleSpec.of(CommitStyleKind.KERNEL)
        assertEquals(72, conventional.subjectHardMax)
        assertEquals(75, kernel.bodyWrapColumns)
        assertTrue(conventional.requiresType)
        assertTrue(kernel.requiresLowercaseStart)
        assertTrue(CommitStyleSpec.of(CommitStyleKind.PLAIN).requiresType.not())
    }

    // ═══ 类型知识库 ═══

    @Test
    fun `type registry covers twelve types with verbs`() {
        assertEquals(12, CommitMessageConventions.TYPES.size)
        val feat = CommitMessageConventions.TYPE_INDEX["feat"]
        assertTrue(feat != null && feat.enVerbs.contains("add"))
        assertTrue(feat!!.zhVerbs.contains("新增"))
        assertEquals(":sparkles:", feat.gitmoji)
    }

    @Test
    fun `gitmoji reverse map resolves types`() {
        assertEquals("fix", CommitMessageConventions.GITMOJI_TO_TYPE[":bug:"])
        assertEquals("docs", CommitMessageConventions.GITMOJI_TO_TYPE[":memo:"])
    }

    // ═══ 头部解析 ═══

    @Test
    fun `conventional header parses type scope breaking and subject`() {
        val header = CommitMessageConventions.parseHeader(
            "feat(engine)!: 六阶段管线接入模板运行时", CommitStyleKind.CONVENTIONAL
        )
        assertTrue(header != null)
        assertEquals("feat", header!!.type)
        assertEquals("engine", header.scope)
        assertTrue(header.breaking)
        assertEquals("zh", header.subjectLanguage)
    }

    @Test
    fun `gitmoji header parses emoji prefix`() {
        val header = CommitMessageConventions.parseHeader(
            ":sparkles: add template runtime", CommitStyleKind.GITMOJI
        )
        assertTrue(header != null)
        assertEquals("feat", header!!.type)
        assertEquals(":sparkles:", header.gitmoji)
        assertEquals("en", header.subjectLanguage)
    }

    @Test
    fun `kernel header parses subsystem`() {
        val header = CommitMessageConventions.parseHeader(
            "net: fix packet leak in timer path", CommitStyleKind.KERNEL
        )
        assertTrue(header != null)
        assertEquals("net", header!!.scope)
        assertTrue(header.subject.startsWith("fix"))
    }

    @Test
    fun `bad header returns null`() {
        assertNull(
            CommitMessageConventions.parseHeader("没有前缀的主题", CommitStyleKind.CONVENTIONAL)
        )
    }

    // ═══ 校验 ═══

    @Test
    fun `conventional validation passes clean message`() {
        val issues = CommitMessageConventions.validate(
            "feat(engine): 接入模板运行时\n\n六阶段管线完整接入，含确定性分析附录注入。",
            CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `unknown type is repairable issue`() {
        val issues = CommitMessageConventions.validate(
            "feature: 新功能", CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(issues.any { it.kind == CommitConventionIssue.Kind.UNKNOWN_TYPE })
    }

    @Test
    fun `missing type prefix is reported for required styles`() {
        val issues = CommitMessageConventions.validate(
            "新增了模板运行时\n\n正文说明。", CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(issues.any { it.kind == CommitConventionIssue.Kind.MISSING_TYPE_PREFIX })
    }

    @Test
    fun `plain style does not require type`() {
        val issues = CommitMessageConventions.validate(
            "新增了模板运行时\n\n正文说明。", CommitStyleSpec.of(CommitStyleKind.PLAIN)
        )
        assertTrue(issues.none { it.kind == CommitConventionIssue.Kind.MISSING_TYPE_PREFIX })
    }

    @Test
    fun `subject issue ref belongs in footer`() {
        val issues = CommitMessageConventions.validate(
            "fix: 修复崩溃 #123\n\n正文。", CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(issues.any { it.kind == CommitConventionIssue.Kind.SUBJECT_ISSUE_REF })
    }

    @Test
    fun `kernel style requires lowercase subject start`() {
        val issues = CommitMessageConventions.validate(
            "net: Fix the packet leak now", CommitStyleSpec.of(CommitStyleKind.KERNEL)
        )
        assertTrue(issues.any { it.kind == CommitConventionIssue.Kind.SUBJECT_CASE })
    }

    @Test
    fun `past tense english subject is flagged`() {
        val issues = CommitMessageConventions.validate(
            "feat: added new engine\n\nBody text.", CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(issues.any { it.kind == CommitConventionIssue.Kind.MISSING_IMPERATIVE })
    }

    @Test
    fun `imperative whitelist avoids false positives`() {
        val issues = CommitMessageConventions.validate(
            "feat: embed the engine into runtime\n\nBody text.",
            CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(issues.none { it.kind == CommitConventionIssue.Kind.MISSING_IMPERATIVE })
    }

    @Test
    fun `missing blank line is reported`() {
        val issues = CommitMessageConventions.validate(
            "feat: 主题\n正文直接跟上", CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(issues.any { it.kind == CommitConventionIssue.Kind.MISSING_BLANK_LINE })
    }

    @Test
    fun `breaking footer without mark is reported`() {
        val issues = CommitMessageConventions.validate(
            "feat: 主题\n\nBREAKING CHANGE: 接口签名变更。",
            CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(issues.any { it.kind == CommitConventionIssue.Kind.MISSING_BREAKING_FOOTER })
    }

    @Test
    fun `body line over wrap limit is reported`() {
        val longLine = "这是一个特别长的正文行".repeat(12)
        val issues = CommitMessageConventions.validate(
            "feat: 主题\n\n$longLine", CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(issues.any { it.kind == CommitConventionIssue.Kind.BODY_LINE_TOO_LONG })
    }

    @Test
    fun `bad footer format is reported`() {
        val issues = CommitMessageConventions.validate(
            "feat: 主题\n\nCloses 12345（缺井号与冒号格式）",
            CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(issues.any { it.kind == CommitConventionIssue.Kind.BAD_FOOTER_FORMAT })
    }

    // ═══ 修复 ═══

    @Test
    fun `repair normalizes type alias and strips period`() {
        val (repaired, notes) = CommitMessageConventions.repair(
            "feature: 新增了模板运行时。",
            CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(repaired.startsWith("feat: "))
        assertTrue(!repaired.endsWith("。"))
        assertTrue(notes.isNotEmpty())
    }

    @Test
    fun `repair moves issue ref out of subject`() {
        val (repaired, _) = CommitMessageConventions.repair(
            "fix: 修复崩溃 #123",
            CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertTrue(repaired.startsWith("fix: 修复崩溃"))
        assertTrue(!repaired.contains("#123") || repaired.lines().first().contains("#123").not())
    }

    @Test
    fun `repair inserts blank line between subject and body`() {
        val (repaired, _) = CommitMessageConventions.repair(
            "feat: 主题\n正文紧跟",
            CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        assertEquals("feat: 主题\n\n正文紧跟", repaired)
    }

    @Test
    fun `repair adds bang mark when breaking footer present`() {
        val (repaired, notes) = CommitMessageConventions.repair(
            "feat: 主题\n\nBREAKING CHANGE: 接口变更说明。",
            CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL)
        )
        val firstLine = repaired.lines().first()
        assertTrue(firstLine.startsWith("feat"))
        assertTrue(firstLine.contains("!") || firstLine.contains("!: "))
        assertTrue(repaired.contains("BREAKING CHANGE"))
        assertTrue(notes.isNotEmpty())
    }

    @Test
    fun `repair to gitmoji style rewrites header`() {
        val (repaired, _) = CommitMessageConventions.repair(
            "feat: add template runtime",
            CommitStyleSpec.of(CommitStyleKind.GITMOJI)
        )
        assertTrue(repaired.startsWith(":sparkles: "))
    }

    @Test
    fun `repair to kernel style lowercases subject start`() {
        val (repaired, _) = CommitMessageConventions.repair(
            "feat(net): Fix the packet leak",
            CommitStyleSpec.of(CommitStyleKind.KERNEL)
        )
        assertTrue(repaired.startsWith("net: "))
        assertTrue(repaired.contains("fix"))
    }

    // ═══ 正文折行 ═══

    @Test
    fun `wrap body respects columns and lists`() {
        val wrapped = CommitMessageConventions.wrapBody(
            "第一行很短。\n- 列表行保持原样不折行即使超过列宽限制很多很多很多很多很多\n正文第二行 " +
                "word1 word2 word3 word4 word5 word6 word7 word8 word9 word10 word11",
            columns = 40
        )
        val lines = wrapped.lines()
        assertEquals("第一行很短。", lines[0])
        assertTrue(lines[1].startsWith("- "))
        assertTrue(lines[2].length <= 40)
        assertTrue(lines.size > 3)
    }

    // ═══ 草稿合成 ═══

    @Test
    fun `draft subject uses zh verbs for chinese`() {
        val draft = CommitMessageConventions.draftSubject(
            type = "feat", scope = "engine", objectText = "模板运行时",
            spec = CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL), preferChinese = true
        )
        assertEquals("feat(engine): 新增模板运行时", draft)
    }

    @Test
    fun `draft subject uses en imperative for english`() {
        val draft = CommitMessageConventions.draftSubject(
            type = "fix", scope = null, objectText = "the crash on startup",
            spec = CommitStyleSpec.of(CommitStyleKind.CONVENTIONAL), preferChinese = false
        )
        assertEquals("fix: Fix the crash on startup", draft)
    }

    @Test
    fun `draft subject in gitmoji prefixes emoji`() {
        val draft = CommitMessageConventions.draftSubject(
            type = "fix", scope = null, objectText = "启动崩溃",
            spec = CommitStyleSpec.of(CommitStyleKind.GITMOJI), preferChinese = true
        )
        assertTrue(draft.startsWith(":bug: "))
    }

    @Test
    fun `draft subject in kernel uses subsystem`() {
        val draft = CommitMessageConventions.draftSubject(
            type = "fix", scope = "net", objectText = "packet leak",
            spec = CommitStyleSpec.of(CommitStyleKind.KERNEL), preferChinese = false
        )
        assertEquals("net: fix packet leak", draft)
    }

    // ═══ footer 合成 ═══

    @Test
    fun `breaking footer builder formats reasons`() {
        val single = CommitMessageConventions.buildBreakingFooter(listOf("删除了旧接口"))
        assertTrue(single.startsWith("BREAKING CHANGE: "))
        assertTrue(single.contains("删除了旧接口"))

        val multi = CommitMessageConventions.buildBreakingFooter(listOf("删除接口 A", "重命名配置 B"))
        assertTrue(multi.contains("- 删除接口 A"))
        assertTrue(multi.contains("- 重命名配置 B"))
    }

    @Test
    fun `issue footer builder maps action tokens`() {
        assertEquals("Fixes #42", CommitMessageConventions.buildIssueFooter("fix", 42))
        assertEquals("Closes #7", CommitMessageConventions.buildIssueFooter("closes", 7))
        assertEquals("Refs #9", CommitMessageConventions.buildIssueFooter("ref", 9))
    }

    // ═══ 风格互转 ═══

    @Test
    fun `conventional to gitmoji conversion keeps body`() {
        val converted = CommitMessageConventions.convertStyle(
            "feat: add runtime\n\nBody stays.",
            CommitStyleKind.CONVENTIONAL, CommitStyleKind.GITMOJI
        )
        assertTrue(converted.startsWith(":sparkles: add runtime"))
        assertTrue(converted.contains("Body stays."))
    }

    @Test
    fun `gitmoji to conventional conversion restores type`() {
        val converted = CommitMessageConventions.convertStyle(
            ":bug: fix the leak\n\nBody.",
            CommitStyleKind.GITMOJI, CommitStyleKind.CONVENTIONAL
        )
        assertTrue(converted.startsWith("fix: fix the leak"))
    }

    @Test
    fun `same style conversion is identity`() {
        val message = "feat: add runtime"
        assertEquals(
            message,
            CommitMessageConventions.convertStyle(message, CommitStyleKind.CONVENTIONAL, CommitStyleKind.CONVENTIONAL)
        )
    }
}
