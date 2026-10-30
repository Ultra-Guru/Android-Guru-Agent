package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTemplateSnapshot
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateVariableAssignment
import com.apex.agent.core.engine.templates.runtime.TemplateVariableReport
import com.apex.agent.core.engine.templates.runtime.TemplateVariableSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 10 测试 · BUG 报告行为。
 */
class BugReportTemplateBehaviorTest {

    private val behavior = BugReportTemplateBehavior()

    private fun context(
        symptom: String = "提交后页面崩溃",
        reproduce: String = "1. 打开订单页\n2. 点击提交\n3. 应用闪退",
        expected: String = "提交成功并跳转列表",
        actual: String = "应用崩溃退出",
        environment: String = "设备：Pixel 8；系统：Android 15；版本：2.3.1"
    ): TemplateExecutionContext = TemplateExecutionContext(
        template = TemplateTemplateSnapshot(
            id = "builtin_bug_report",
            name = "BUG 报告",
            content = "请根据以下信息整理一份结构化的 BUG 报告。",
            declaredVariableNames = listOf("symptom", "reproduce", "expected", "actual", "environment"),
            categoryLabel = "CODING",
            isBuiltIn = true
        ),
        variables = TemplateVariableReport(
            listOf(
                TemplateVariableAssignment("symptom", symptom, TemplateVariableSource.USER),
                TemplateVariableAssignment("reproduce", reproduce, TemplateVariableSource.USER),
                TemplateVariableAssignment("expected", expected, TemplateVariableSource.USER),
                TemplateVariableAssignment("actual", actual, TemplateVariableSource.USER),
                TemplateVariableAssignment("environment", environment, TemplateVariableSource.USER)
            )
        )
    )

    private suspend fun analyzeFor(ctx: TemplateExecutionContext) = behavior.analyze(ctx, behavior.prepare(ctx))

    private fun requestStub() = TemplateTurnRequest(prompt = "stub", behaviorId = "builtin_bug_report")

    // ═══ 严重度 ═══

    @Test
    fun `severity inference escalates on crash and security words`() {
        assertEquals(TemplateSeverity.BLOCKER, BugReportAnalyzer.assessSeverity("提交后页面崩溃", "闪退").severity)
        assertEquals(TemplateSeverity.BLOCKER, BugReportAnalyzer.assessSeverity("接口存在注入", "数据可被篡改").severity)
        assertEquals(TemplateSeverity.BLOCKER, BugReportAnalyzer.assessSeverity("订单数据丢失", "").severity)
        assertEquals(TemplateSeverity.IMPORTANT, BugReportAnalyzer.assessSeverity("列表偶发排序不一致", "").severity)
        assertEquals(TemplateSeverity.MINOR, BugReportAnalyzer.assessSeverity("按钮文案拼写样式错位", "").severity)
    }

    @Test
    fun `severity evidence lists matched keywords`() {
        val assessment = BugReportAnalyzer.assessSeverity("提交后页面崩溃", "应用闪退")
        assertTrue(assessment.evidence.any { it.contains("崩溃") || it.contains("闪退") })
        assertTrue(assessment.rationale.contains("崩溃"))
    }

    @Test
    fun `severity falls back to important without keywords`() {
        val assessment = BugReportAnalyzer.assessSeverity("功能表现与设计稿不同", "")
        assertEquals(TemplateSeverity.IMPORTANT, assessment.severity)
    }

    // ═══ 复现质量 ═══

    @Test
    fun `repro quality scores steps determinism and artifacts`() {
        val good = """
            1. 打开订单页 /order/list
            2. 点击提交（每次必现）
            日志：OrderService.kt:128 抛出 NPE
        """.trimIndent()
        val quality = BugReportAnalyzer.assessReproQuality(good)
        assertTrue(quality.score >= 85)
        assertEquals(2, quality.stepCount)
        assertTrue(quality.hasDeterministicMarkers)
        assertTrue(quality.hasConcreteArtifacts)
        assertTrue(quality.suggestions.isEmpty())
    }

    @Test
    fun `repro quality penalizes vague and missing content`() {
        val vague = "偶尔会出问题"
        val quality = BugReportAnalyzer.assessReproQuality(vague)
        assertTrue(quality.score < 50)
        assertTrue(quality.suggestions.any { it.contains("频率") })
        assertTrue(quality.suggestions.any { it.contains("工件") || it.contains("步骤") })

        val empty = BugReportAnalyzer.assessReproQuality("")
        assertEquals(0, empty.score)
        assertTrue(empty.suggestions.first().contains("为空"))
    }

    @Test
    fun `step counting handles numbered and prose forms`() {
        assertEquals(3, BugReportAnalyzer.countSteps("1. 打开\n2. 点击\n3. 提交"))
        assertEquals(2, BugReportAnalyzer.countSteps("打开页面然后点击提交"))
        assertEquals(2, BugReportAnalyzer.countSteps("打开页面\n点击提交"))
    }

    // ═══ 环境解析 ═══

    @Test
    fun `environment parse extracts device os and version`() {
        val parsed = BugReportAnalyzer.parseEnvironment("设备：Pixel 8；系统：Android 15；版本：2.3.1")
        assertEquals("Pixel 8", parsed.device)
        assertEquals("Android 15", parsed.osVersion)
        assertNotNull(parsed.appVersion)
        assertTrue(parsed.appVersion!!.contains("2.3.1"))
    }

    @Test
    fun `environment parse handles blank input`() {
        val parsed = BugReportAnalyzer.parseEnvironment("")
        assertNull(parsed.device)
        assertNull(parsed.osVersion)
    }

    // ═══ 假设排序 ═══

    @Test
    fun `hypotheses ranked by symptom keyword table`() {
        val crash = BugReportAnalyzer.rankHypotheses("提交后页面崩溃", "闪退")
        assertEquals(1, crash.first().priority)
        assertTrue(crash.first().category.contains("空值") || crash.first().category.contains("生命周期"))

        val perf = BugReportAnalyzer.rankHypotheses("列表滚动卡顿", "")
        assertTrue(perf.first().category.contains("主线程") || perf.first().category.contains("IO"))

        val net = BugReportAnalyzer.rankHypotheses("弱网下请求失败", "")
        assertTrue(net.first().category.contains("网络"))
    }

    @Test
    fun `hypotheses fall back to generic triage`() {
        val generic = BugReportAnalyzer.rankHypotheses("表现和预期不同", "")
        assertEquals(3, generic.size)
        assertTrue(generic.all { it.priority in 1..3 })
    }

    // ═══ 结构性质量 ═══

    @Test
    fun `quality issues detect structural contradictions`() {
        val issues = BugReportAnalyzer.qualityIssues(
            symptom = "卡",
            reproduce = "",
            expected = "正常",
            actual = "正常",
            environment = ""
        )
        val kinds = issues.map { it.kind }
        assertTrue(kinds.contains(BugReportQualityIssue.Kind.EMPTY_REPRO))
        assertTrue(kinds.contains(BugReportQualityIssue.Kind.SAME_EXPECTED_ACTUAL))
        assertTrue(kinds.contains(BugReportQualityIssue.Kind.ONE_WORD_SYMPTOM))
        assertTrue(kinds.contains(BugReportQualityIssue.Kind.NO_ENVIRONMENT))
    }

    // ═══ 行为 ═══

    @Test
    fun `analyze produces severity repro hypotheses findings`() = runTest {
        val analysis = analyzeFor(context())
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("bugreport.severity"))
        assertTrue(ruleIds.contains("bugreport.repro-quality"))
        assertTrue(ruleIds.contains("bugreport.hypothesis"))
        assertTrue(analysis.enrichedHints.any { it.contains("严重度基线") })
        assertTrue(analysis.metadata["severity"] == "BLOCKER" || analysis.metadata["severity"] == "IMPORTANT")
    }

    @Test
    fun `analyze flags low repro quality as important`() = runTest {
        val analysis = analyzeFor(context(reproduce = "偶尔会出问题"))
        val reproFinding = analysis.findings.first { it.ruleId == "bugreport.repro-quality" }
        assertEquals(TemplateSeverity.IMPORTANT, reproFinding.severity)
    }

    @Test
    fun `compose injects triage appendix`() = runTest {
        val ctx = context()
        val prepared = behavior.prepare(ctx)
        val analysis = behavior.analyze(ctx, prepared)
        val request = behavior.composePrompt(ctx, prepared, analysis)
        assertTrue(request.prompt.contains("确定性分析附录"))
        assertTrue(request.prompt.contains("严重度判定"))
        assertTrue(request.prompt.contains("复现质量评分"))
    }

    @Test
    fun `contract requires four report sections`() {
        assertEquals(
            listOf("标题", "严重程度评估", "可能原因分析", "下一步排查建议"),
            behavior.manifest().outputContract.requiredTitles
        )
    }

    @Test
    fun `validate flags missing sections`() {
        val raw = "## 标题\n提交崩溃\n## 严重程度评估\n阻断"
        val report = behavior.validateOutput(raw, context(), requestStub())
        assertTrue(report.violations.any { it.section == "可能原因分析" })
        assertTrue(report.violations.any { it.section == "下一步排查建议" })
    }

    @Test
    fun `render adds defect overview badge and summary`() {
        val raw = """
            ## 标题
            订单提交崩溃

            ## 严重程度评估
            🔴 阻断：崩溃级缺陷

            ## 可能原因分析
            1. 空指针：提交回调访问已释放对象
            2. 生命周期：异步返回晚于页面销毁

            ## 下一步排查建议
            附上崩溃堆栈定位首帧
        """.trimIndent()
        val rendered = behavior.renderReport(raw, context(), requestStub())
        val badge = rendered.sections.first { it.title == "缺陷概览" }
        assertTrue(badge.lines[0].contains("复现质量"))
        assertEquals("1", rendered.summary[com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport.KEY_BLOCKER_COUNT])
        assertTrue((rendered.summary["hypothesisCount"]?.toIntOrNull() ?: 0) >= 1)
    }

    // ═══ 端到端 ═══

    @Test
    fun `full turn through runtime`() = runTest {
        val dir = java.io.File("/tmp/bugreport-runtime-${System.nanoTime()}").apply { mkdirs() }
        val registry = com.apex.agent.core.engine.templates.PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        val runtime = com.apex.agent.core.engine.templates.runtime.TemplateRuntime(
            registry = registry,
            behaviors = mapOf(behavior.behaviorId to behavior)
        )
        val activation = runtime.activate(
            "builtin_bug_report",
            mapOf(
                "symptom" to "提交订单后应用崩溃",
                "reproduce" to "1. 打开订单页\n2. 点击提交（每次必现）",
                "expected" to "提交成功",
                "actual" to "应用闪退",
                "environment" to "Android 15 / 2.3.1"
            )
        )
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        assertTrue(outcome.request!!.prompt.contains("严重度基线"))
        val report = runtime.processResponse(
            activation,
            """
            ## 标题
            订单提交后应用崩溃

            ## 严重程度评估
            🔴 阻断

            ## 可能原因分析
            1. 空指针访问
            2. 生命周期竞态

            ## 下一步排查建议
            抓取崩溃堆栈
            """.trimIndent(),
            outcome.request
        )
        assertTrue(report.trace!!.allOk)
        assertEquals("1", report.summary[com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport.KEY_BLOCKER_COUNT])
    }
}
