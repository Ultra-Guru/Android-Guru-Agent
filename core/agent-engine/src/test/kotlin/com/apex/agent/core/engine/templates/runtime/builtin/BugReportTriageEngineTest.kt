package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 18 测试 · BUG 分诊引擎。
 */
class BugReportTriageEngineTest {

    // ═══ P0-P4 优先级模型 ═══

    @Test
    fun `blocker always reproducible full scope is p0`() {
        val verdict = BugReportTriageEngine.triage(
            TemplateSeverity.BLOCKER,
            symptom = "应用闪退，所有用户受影响",
            actual = "启动即崩溃",
            reproduce = "1. 打开应用 2. 观察闪退"
        )
        assertEquals(TriagePriority.P0, verdict.priority)
        assertTrue(verdict.compositeScore >= 80)
        assertTrue(verdict.rationale.contains("严重度"))
    }

    @Test
    fun `minor cosmetic issue lands in p3 or p4`() {
        val verdict = BugReportTriageEngine.triage(
            TemplateSeverity.MINOR,
            symptom = "文案对齐差一个像素",
            actual = "仅我观察到",
            reproduce = "1. 偶现于特定设备"
        )
        assertTrue(verdict.priority == TriagePriority.P3 || verdict.priority == TriagePriority.P4)
        assertFalse(verdict.requiresEscalation)
    }

    @Test
    fun `security semantics force unconditional p0 escalation`() {
        val verdict = BugReportTriageEngine.triage(
            TemplateSeverity.BLOCKER,
            symptom = "接口存在注入风险",
            actual = "未授权数据泄露",
            reproduce = "构造特定请求即可复现"
        )
        assertEquals(TriagePriority.P0, verdict.priority)
        assertTrue(verdict.requiresEscalation)
        assertTrue(verdict.escalationReason.contains("热修"))
    }

    @Test
    fun `frequency words lower composite score`() {
        val always = BugReportTriageEngine.triage(
            TemplateSeverity.IMPORTANT, "功能错误", "报错",
            "1. 每次操作都触发"
        )
        val sometimes = BugReportTriageEngine.triage(
            TemplateSeverity.IMPORTANT, "功能错误", "报错",
            "1. 偶现于部分场景"
        )
        assertTrue(sometimes.compositeScore < always.compositeScore)
    }

    @Test
    fun `severity weights map all levels`() {
        assertEquals(100, BugReportTriageEngine.severityWeightOf(TemplateSeverity.BLOCKER))
        assertEquals(70, BugReportTriageEngine.severityWeightOf(TemplateSeverity.IMPORTANT))
        assertEquals(40, BugReportTriageEngine.severityWeightOf(TemplateSeverity.MINOR))
        assertEquals(20, BugReportTriageEngine.severityWeightOf(TemplateSeverity.INFO))
    }

    // ═══ 环境指纹与已知问题库 ═══

    @Test
    fun `android 14 fingerprint hits fg service issue`() {
        val fingerprint = BugReportTriageEngine.fingerprint(
            "Pixel 8, Android 14, app版本 1.4.9"
        )
        assertEquals("android", fingerprint.platform)
        assertEquals("14", fingerprint.osMajor)
        assertEquals("Pixel", fingerprint.deviceFamily)
        assertEquals("1.4.9", fingerprint.appVersion)
        assertTrue(fingerprint.knownIssueHits.any { it.patternId == "known.android-14-fg-service-type" })
        assertTrue(fingerprint.signature().contains("android/14"))
    }

    @Test
    fun `xiaomi device hits autostart known issue`() {
        val fingerprint = BugReportTriageEngine.fingerprint("小米手机 Android 13")
        assertEquals("android", fingerprint.platform)
        assertEquals("Xiaomi", fingerprint.deviceFamily)
        assertTrue(fingerprint.knownIssueHits.any { it.patternId == "known.xiaomi-permission-autostart" })
    }

    @Test
    fun `unrecognizable environment degrades to unknown`() {
        val fingerprint = BugReportTriageEngine.fingerprint("测试机一台")
        assertEquals("unknown", fingerprint.platform)
        assertTrue(fingerprint.knownIssueHits.isEmpty())
        assertEquals("", fingerprint.appVersion)
    }

    @Test
    fun `ios 17 fingerprint hits webkit issue`() {
        val fingerprint = BugReportTriageEngine.fingerprint("iPhone 15, iOS 17.2")
        assertEquals("ios", fingerprint.platform)
        assertTrue(fingerprint.knownIssueHits.any { it.title.contains("WebKit") })
    }

    // ═══ 症状签名 ═══

    @Test
    fun `signature normalizes digits and punctuation`() {
        val a = BugReportTriageEngine.symptomSignature("上传 3 张图片后崩溃", "第 5 步闪退")
        val b = BugReportTriageEngine.symptomSignature("上传 9 张图片后崩溃", "第 8 步闪退")
        assertEquals(a, b)
        assertTrue(a.contains("上传"))
        assertTrue(BugReportTriageEngine.signatureIsQueryable(a))
    }

    @Test
    fun `blank signature is not queryable`() {
        val signature = BugReportTriageEngine.symptomSignature("", " ")
        assertEquals("empty-signature", signature)
        assertFalse(BugReportTriageEngine.signatureIsQueryable(signature))
    }

    // ═══ 根因排查树 ═══

    @Test
    fun `concurrency hypothesis maps to investigation tree`() {
        val tree = BugReportTriageEngine.investigationTreeFor("并发 / 竞态：共享状态未保护")
        assertEquals(3, tree.size)
        assertTrue(tree[0].action.contains("共享"))
        assertTrue(tree.all { it.expectedObservation.isNotBlank() })
    }

    @Test
    fun `unknown hypothesis category yields empty tree`() {
        assertTrue(BugReportTriageEngine.investigationTreeFor("神秘力量").isEmpty())
    }

    @Test
    fun `investigation categories cover six domains`() {
        val categories = BugReportTriageEngine.investigationCategories()
        assertEquals(6, categories.size)
        assertTrue(categories.any { it.contains("空指针") })
        assertTrue(categories.any { it.contains("资源泄漏") })
    }

    // ═══ 升级规则 ═══

    @Test
    fun `blocker with poor repro requires field escalation`() {
        val (need, reason) = BugReportTriageEngine.needsFieldEscalation(
            TemplateSeverity.BLOCKER, reproScore = 20
        )
        assertTrue(need)
        assertTrue(reason.contains("补现场"))
    }

    @Test
    fun `minor issue with good repro does not escalate`() {
        val (need, _) = BugReportTriageEngine.needsFieldEscalation(
            TemplateSeverity.MINOR, reproScore = 90
        )
        assertFalse(need)
    }

    // ═══ 回归测试指引 ═══

    @Test
    fun `null pointer hypothesis yields regression guide`() {
        val guide = BugReportTriageEngine.regressionGuideFor("空指针 / 未初始化")
        assertTrue(guide != null)
        assertTrue(guide!!.testName.contains("should not crash"))
        assertTrue(guide.boundaryConditions.size >= 3)
    }

    @Test
    fun `unknown hypothesis has no regression guide`() {
        assertTrue(BugReportTriageEngine.regressionGuideFor("玄学问题") == null)
    }

    // ═══ 规则目录 ═══

    @Test
    fun `rule catalog is stable`() {
        assertEquals(7, BugReportTriageEngine.RULE_IDS.size)
        assertTrue(BugReportTriageEngine.RULE_IDS.contains("triage.priority-composite"))
    }
}
