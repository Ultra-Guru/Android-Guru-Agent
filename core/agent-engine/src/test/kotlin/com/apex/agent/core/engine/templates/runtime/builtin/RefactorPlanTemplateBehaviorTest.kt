package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateTemplateSnapshot
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateVariableAssignment
import com.apex.agent.core.engine.templates.runtime.TemplateVariableReport
import com.apex.agent.core.engine.templates.runtime.TemplateVariableSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 9 测试 · 重构方案行为。
 */
class RefactorPlanTemplateBehaviorTest {

    private val behavior = RefactorPlanTemplateBehavior()

    private fun context(code: String, goal: String = "提升可维护性", constraints: String = "保持公共 API 兼容"): TemplateExecutionContext =
        TemplateExecutionContext(
            template = TemplateTemplateSnapshot(
                id = "builtin_refactor_plan",
                name = "重构方案",
                content = "请为下面的代码制定重构方案。",
                declaredVariableNames = listOf("code", "goal", "constraints"),
                categoryLabel = "CODING",
                isBuiltIn = true
            ),
            variables = TemplateVariableReport(
                listOf(
                    TemplateVariableAssignment("code", code, TemplateVariableSource.USER),
                    TemplateVariableAssignment("goal", goal, TemplateVariableSource.USER),
                    TemplateVariableAssignment("constraints", constraints, TemplateVariableSource.USER)
                )
            )
        )

    private suspend fun analyzeFor(code: String, goal: String = "提升可维护性") = behavior.analyze(
        context(code, goal), behavior.prepare(context(code, goal))
    )

    private fun requestStub() = TemplateTurnRequest(prompt = "stub", behaviorId = "builtin_refactor_plan")

    /** 高复杂 + 重复 + 无测试的 Kotlin 样例。 */
    private val messyCode = run {
        val branches = (1..16).joinToString("\n") { "    if (cond$it) { work($it) }" }
        """
            fun tangledProcessor(alpha: Int, beta: String, gamma: Double, delta: List<String>, epsilon: Map<String, Int>, zeta: Boolean): String {
                $branches
                return "done"
            }

            fun otherOne() {
                val total = items.size
                val limited = items.take(20)
                val joined = limited.joinToString(",")
                println(joined)
            }

            fun otherTwo() {
                val total = items.size
                val limited = items.take(20)
                val joined = limited.joinToString(",")
                println(joined)
            }
        """.trimIndent()
    }

    // ═══ 规划器 ═══

    @Test
    fun `planner diagnoses and orders by impact`() {
        val plan = RefactorPlanPlanner.plan(messyCode, "kotlin", "降低复杂度", "无")
        val kinds = plan.diagnoses.map { it.kind }
        assertTrue(kinds.contains(RefactorDiagnosis.Kind.HIGH_COMPLEXITY))
        assertTrue(kinds.contains(RefactorDiagnosis.Kind.DUPLICATION))
        assertTrue(kinds.contains(RefactorDiagnosis.Kind.TOO_MANY_PARAMS))
        // 影响等级非递减
        val levels = plan.diagnoses.map { it.impactLevel }
        assertEquals(levels, levels.sorted())
    }

    @Test
    fun `planner puts safety net first when no test trace`() {
        val plan = RefactorPlanPlanner.plan(messyCode, "kotlin", "降低复杂度", "无")
        assertEquals(RefactorStep.StepKind.SAFETY_NET, plan.steps.first().kind)
        assertTrue(plan.steps.first().verification.contains("测试"))
    }

    @Test
    fun `planner skips safety net when test trace present`() {
        val codeWithTests = messyCode + "\n\n" + """
            class TangledProcessorTest {
                @Test
                fun snapshot() {}
            }
        """.trimIndent()
        val plan = RefactorPlanPlanner.plan(codeWithTests, "kotlin", "降低复杂度", "无")
        assertFalse(plan.steps.first().kind == RefactorStep.StepKind.SAFETY_NET)
    }

    @Test
    fun `planner steps carry risk taxonomy`() {
        val plan = RefactorPlanPlanner.plan(messyCode, "kotlin", "降低复杂度", "无")
        val splitStep = plan.steps.firstOrNull { it.kind == RefactorStep.StepKind.SPLIT_FUNCTION }
        assertTrue(splitStep != null)
        assertEquals(RiskAssessment.RiskLevel.MEDIUM, splitStep!!.risk.level)
        assertTrue(splitStep.risk.rollbackMethod.contains("commit") || splitStep.risk.rollbackMethod.contains("revert"))
        val extractStep = plan.steps.first { it.kind == RefactorStep.StepKind.EXTRACT_DUPLICATE }
        assertEquals(RiskAssessment.RiskLevel.LOW, extractStep.risk.level)
    }

    @Test
    fun `planner verification checklist is deterministic`() {
        val plan = RefactorPlanPlanner.plan(messyCode, "kotlin", "降低复杂度", "无")
        assertTrue(plan.verificationChecklist.any { it.contains("测试") })
        assertTrue(plan.verificationChecklist.any { it.contains("重复块") })
        assertTrue(plan.verificationChecklist.any { it.contains("破坏性变更") })
    }

    @Test
    fun `planner falls back to goal-driven plan on clean code`() {
        val clean = "fun add(a: Int, b: Int): Int = a + b\n"
        val plan = RefactorPlanPlanner.plan(clean, "kotlin", "接入新框架", "无")
        assertTrue(plan.diagnoses.none { it.kind == RefactorDiagnosis.Kind.HIGH_COMPLEXITY })
        assertTrue(plan.steps.isNotEmpty())
        assertTrue(plan.steps.all { it.kind == RefactorStep.StepKind.DOMAIN_SPECIFIC })
        assertTrue(plan.notes.any { it.contains("目标驱动") })
    }

    @Test
    fun `planner notes unknown language`() {
        val plan = RefactorPlanPlanner.plan("代码内容若干行\n\n行内容", null, "目标", "无")
        assertTrue(plan.diagnoses.any { it.kind == RefactorDiagnosis.Kind.UNDETECTED_LANGUAGE })
        assertTrue(plan.notes.any { it.contains("语言未识别") })
    }

    // ═══ 行为 ═══

    @Test
    fun `analyze produces diagnosis findings and step skeletons`() = runTest {
        val analysis = analyzeFor(messyCode)
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("refactor.diagnosis"))
        assertTrue(ruleIds.contains("refactor.step-skeleton"))
        assertTrue(analysis.enrichedHints.any { it.contains("测试保护网") || it.contains("保护网") })
        assertTrue(analysis.enrichedHints.any { it.contains("步骤骨架共") })
        assertEquals("true", analysis.metadata["hasSafetyNet"])
    }

    @Test
    fun `compose injects diagnosis and plan appendix`() = runTest {
        val ctx = context(messyCode)
        val prepared = behavior.prepare(ctx)
        val analysis = behavior.analyze(ctx, prepared)
        val request = behavior.composePrompt(ctx, prepared, analysis)
        assertTrue(request.prompt.contains("确定性分析附录"))
        assertTrue(request.prompt.contains("步骤 1"))
        assertTrue(request.prompt.contains("保持公共 API 兼容"))
    }

    @Test
    fun `contract requires four sections`() {
        val contract = behavior.manifest().outputContract
        assertEquals(
            listOf("现状问题诊断", "分步重构方案", "每步的风险与回滚", "重构完成后的验证清单"),
            contract.requiredTitles
        )
    }

    @Test
    fun `validate flags missing sections`() {
        val raw = "## 现状问题诊断\n复杂度过高。\n## 分步重构方案\n1. 拆分函数"
        val report = behavior.validateOutput(raw, context("x"), requestStub())
        assertTrue(report.violations.any { it.section == "每步的风险与回滚" })
        assertTrue(report.violations.any { it.section == "重构完成后的验证清单" })
    }

    @Test
    fun `render summary carries plan stats`() {
        val raw = """
            ## 现状问题诊断
            - 高复杂度 tangledProcessor

            ## 分步重构方案
            1. 先补特征测试
            2. 拆分 tangledProcessor

            ## 每步的风险与回滚
            - 步骤1：低风险，删测试即回滚

            ## 重构完成后的验证清单
            - 全量测试通过
        """.trimIndent()
        val rendered = behavior.renderReport(raw, context(messyCode), requestStub())
        assertTrue((rendered.summary["diagnosisCount"]?.toIntOrNull() ?: 0) >= 3)
        assertTrue((rendered.summary["stepCount"]?.toIntOrNull() ?: 0) >= 3)
        assertEquals("true", rendered.summary["hasSafetyNet"])
    }

    // ═══ 端到端 ═══

    @Test
    fun `full turn through runtime`() = runTest {
        val dir = java.io.File("/tmp/refactor-runtime-${System.nanoTime()}").apply { mkdirs() }
        val registry = com.apex.agent.core.engine.templates.PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        val runtime = com.apex.agent.core.engine.templates.runtime.TemplateRuntime(
            registry = registry,
            behaviors = mapOf(behavior.behaviorId to behavior)
        )
        val activation = runtime.activate(
            "builtin_refactor_plan",
            mapOf("code" to messyCode, "goal" to "降低复杂度", "constraints" to "保持 API 兼容")
        )
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        assertTrue(outcome.request!!.prompt.contains("步骤骨架"))
        val report = runtime.processResponse(
            activation,
            """
            ## 现状问题诊断
            - 🔴 tangledProcessor 圈复杂度 17

            ## 分步重构方案
            1. 补特征测试保护网
            2. 拆分 tangledProcessor 为三段
            3. 提取重复块

            ## 每步的风险与回滚
            - 步骤1：🟢 低，删测试即回滚
            - 步骤2：🟡 中，保留 Deprecated 委托

            ## 重构完成后的验证清单
            - 全量测试通过
            - 重复块归零
            """.trimIndent(),
            outcome.request
        )
        assertTrue(report.trace!!.allOk)
        assertEquals("true", report.summary["hasSafetyNet"])
    }
}
