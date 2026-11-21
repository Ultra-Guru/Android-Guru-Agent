package com.apex.agent.core.engine.templates.runtime.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 18 测试 · 重构风险模型与步骤编排。
 */
class RefactorRiskModelTest {

    private fun step(order: Int, kind: RefactorStep.StepKind) = RefactorStep(
        order = order,
        title = "步骤$order",
        kind = kind,
        scope = "",
        verification = "",
        risk = RiskAssessment(RiskAssessment.RiskLevel.LOW, "revert", "低")
    )

    // ═══ 安全序 DAG ═══

    @Test
    fun `safety net sorts before implementation steps`() {
        val ordering = RefactorRiskModel.computeOrdering(
            listOf(
                step(1, RefactorStep.StepKind.SPLIT_FUNCTION),
                step(2, RefactorStep.StepKind.SAFETY_NET),
                step(3, RefactorStep.StepKind.PARAMS_OBJECT)
            )
        )
        assertEquals(RefactorStep.StepKind.SAFETY_NET, ordering.safeOrder.first())
        assertEquals(RefactorStep.StepKind.PARAMS_OBJECT, ordering.safeOrder.last())
        assertTrue(ordering.dependencies.isNotEmpty())
        assertTrue(!ordering.hasUnmetDependency)
    }

    @Test
    fun `extract duplicate sorts before split function`() {
        val ordering = RefactorRiskModel.computeOrdering(
            listOf(
                step(1, RefactorStep.StepKind.SPLIT_FUNCTION),
                step(2, RefactorStep.StepKind.SAFETY_NET),
                step(3, RefactorStep.StepKind.EXTRACT_DUPLICATE)
            )
        )
        val extractIndex = ordering.safeOrder.indexOf(RefactorStep.StepKind.EXTRACT_DUPLICATE)
        val splitIndex = ordering.safeOrder.indexOf(RefactorStep.StepKind.SPLIT_FUNCTION)
        assertTrue(extractIndex < splitIndex)
    }

    @Test
    fun `missing safety net is reported as unmet dependency`() {
        val ordering = RefactorRiskModel.computeOrdering(
            listOf(step(1, RefactorStep.StepKind.SPLIT_FUNCTION))
        )
        assertTrue(ordering.hasUnmetDependency)
        assertTrue(ordering.unmetReasons.any { it.contains("保护网") })
    }

    @Test
    fun `empty steps yield empty ordering`() {
        val ordering = RefactorRiskModel.computeOrdering(emptyList())
        assertTrue(ordering.safeOrder.isEmpty())
    }

    // ═══ 四维风险矩阵 ═══

    @Test
    fun `safety net is lowest risk`() {
        val matrix = RefactorRiskModel.riskMatrixOf(step(1, RefactorStep.StepKind.SAFETY_NET))
        assertTrue(matrix.compositeScore <= 25)
        assertTrue(matrix.rollbackCost == 0)
        assertTrue(matrix.batchAdvice.contains("低风险"))
    }

    @Test
    fun `split function with huge code amplifies surface`() {
        val plain = RefactorRiskModel.riskMatrixOf(
            step(1, RefactorStep.StepKind.SPLIT_FUNCTION),
            longestFunctionLines = 50
        )
        val huge = RefactorRiskModel.riskMatrixOf(
            step(1, RefactorStep.StepKind.SPLIT_FUNCTION),
            longestFunctionLines = 150
        )
        assertTrue(huge.changeSurface > plain.changeSurface)
        assertTrue(huge.compositeScore >= plain.compositeScore)
    }

    @Test
    fun `risk matrices sort most dangerous first`() {
        val matrices = RefactorRiskModel.riskMatrices(
            listOf(
                step(1, RefactorStep.StepKind.SAFETY_NET),
                step(2, RefactorStep.StepKind.SPLIT_FUNCTION)
            )
        )
        assertEquals(RefactorStep.StepKind.SPLIT_FUNCTION, matrices.first().stepKind)
        assertTrue(matrices.first().compositeScore >= matrices.last().compositeScore)
    }

    @Test
    fun `high risk steps get batch advice`() {
        val matrix = RefactorRiskModel.riskMatrixOf(
            step(1, RefactorStep.StepKind.DOMAIN_SPECIFIC),
            longestFunctionLines = 200,
            maxNestingDepth = 6
        )
        assertTrue(matrix.batchAdvice.isNotBlank())
        assertTrue(matrix.compositeScore in 0..100)
    }

    // ═══ 变更面估算 ═══

    @Test
    fun `change surface grows with function size`() {
        val small = RefactorRiskModel.estimateChangeSurface(
            step(1, RefactorStep.StepKind.SPLIT_FUNCTION), longestFunctionLines = 40
        )
        val large = RefactorRiskModel.estimateChangeSurface(
            step(1, RefactorStep.StepKind.SPLIT_FUNCTION), longestFunctionLines = 200
        )
        assertTrue(large.linesRange.last > small.linesRange.last)
        assertTrue(small.commitGranularity.isNotBlank())
    }

    // ═══ 回滚模板与验证矩阵 ═══

    @Test
    fun `rollback templates are kind specific`() {
        val net = RefactorRiskModel.rollbackTemplateOf(step(1, RefactorStep.StepKind.SAFETY_NET))
        assertTrue(net.contains("revert"))
        val split = RefactorRiskModel.rollbackTemplateOf(step(2, RefactorStep.StepKind.SPLIT_FUNCTION))
        assertTrue(split.contains("委托"))
    }

    @Test
    fun `verification matrix covers distinct kinds and finale`() {
        val checklist = RefactorRiskModel.verificationMatrixOf(
            listOf(
                step(1, RefactorStep.StepKind.SAFETY_NET),
                step(2, RefactorStep.StepKind.SPLIT_FUNCTION),
                step(2, RefactorStep.StepKind.SPLIT_FUNCTION)
            )
        )
        assertTrue(checklist.any { it.contains("保护网") })
        assertTrue(checklist.any { it.contains("最长函数") })
        assertTrue(checklist.last().contains("结构度量对比"))
    }

    // ═══ 安全序校验（回包门禁）═══

    @Test
    fun `out of order steps are flagged`() {
        val violations = RefactorRiskModel.checkOrdering(
            listOf(
                step(1, RefactorStep.StepKind.SPLIT_FUNCTION),
                step(2, RefactorStep.StepKind.SAFETY_NET)
            )
        )
        assertEquals(1, violations.size)
        assertTrue(violations.first().reason.contains("保护网"))
        assertTrue(
            RefactorRiskModel.violationAdvice(violations.first()).contains("之前")
        )
    }

    @Test
    fun `correctly ordered steps pass the gate`() {
        val violations = RefactorRiskModel.checkOrdering(
            listOf(
                step(1, RefactorStep.StepKind.SAFETY_NET),
                step(2, RefactorStep.StepKind.EXTRACT_DUPLICATE),
                step(3, RefactorStep.StepKind.SPLIT_FUNCTION)
            )
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `single step never violates ordering`() {
        assertTrue(
            RefactorRiskModel.checkOrdering(listOf(step(1, RefactorStep.StepKind.SPLIT_FUNCTION))).isEmpty()
        )
    }

    // ═══ 行为级接线 ═══

    @Test
    fun `behavior parses step kinds from raw output`() {
        val behavior = RefactorPlanTemplateBehavior()
        val raw = """
            ## 分步重构方案
            1. 拆分超长的处理函数
            2. 建立测试保护网
            3. 收敛参数列表
        """.trimIndent()
        val steps = behavior.parseStepsFromOutput(raw)
        assertEquals(3, steps.size)
        assertEquals(RefactorStep.StepKind.SPLIT_FUNCTION, steps[0].kind)
        assertEquals(RefactorStep.StepKind.SAFETY_NET, steps[1].kind)
        assertEquals(RefactorStep.StepKind.PARAMS_OBJECT, steps[2].kind)
        // 拆分在保护网之前 → 违序 1 条
        assertEquals(1, RefactorRiskModel.checkOrdering(steps).size)
    }
}
