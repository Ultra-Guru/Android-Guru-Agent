package com.apex.agent.core.engine.templates.runtime

import com.apex.agent.core.engine.templates.ImportMode
import com.apex.agent.core.engine.templates.PromptTemplate
import com.apex.agent.core.engine.templates.PromptTemplateRegistry
import com.apex.agent.core.engine.templates.TemplateCategory
import com.apex.agent.core.engine.templates.TemplateLibrary
import com.apex.agent.core.engine.templates.TemplateVariable
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PR 4 测试 · 运行时编排器全链路。
 *
 * 覆盖：激活三态（成功/未知/缺必填）、变量三层合并、内置强制出厂定义、
 * 行为绑定与兜底回落、轮次组装、回包校验-修复-渲染闭环、崩溃折叠、
 * 使用计数联动。
 */
class TemplateRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var registry: PromptTemplateRegistry
    private val logs = mutableListOf<String>()
    private var now = 1000L

    /** 测试行为：带规则与契约的完整样例（模拟内置行为）。 */
    private class StubBehavior : TemplateBehaviorBase() {
        override val behaviorId = "builtin_code_review"
        override val primaryInputName = "code"

        override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
            behaviorId = behaviorId,
            displayName = "代码审查",
            summary = "测试行为",
            supportedPhases = TemplatePhaseKind.entries.toSet(),
            outputContract = TemplateOutputContract(
                requiredSections = listOf(
                    TemplateSectionRequirement("总体结论", aliases = listOf("结论"), required = true),
                    TemplateSectionRequirement("问题清单", listItemCountRange = 1..10, required = true)
                )
            ),
            deterministicPreAnalysis = true
        )

        override suspend fun analyze(
            context: TemplateExecutionContext,
            prepared: TemplatePreparedInput
        ): TemplateAnalysis = TemplateAnalysis.of(
            findings = listOf(
                TemplateFinding(
                    "stub.rule", TemplateFindingCategory.CORRECTNESS,
                    TemplateSeverity.BLOCKER, "样例规则命中", location = "第 1 行",
                    evidence = "val x = !!", suggestion = "改安全调用"
                )
            ),
            detectedLanguage = "kotlin",
            stats = TemplateAnalysisStats.empty().with(TemplateAnalysisStats.KEY_ANALYZED_LINES, prepared.facets.lineCount)
        )
    }

    @Before
    fun setup() {
        dir = tmp.newFolder("runtime-templates")
        registry = PromptTemplateRegistry(dir, clock = { now }, logger = { logs.add(it) })
    }

    private fun runtime(behaviors: Map<String, AgentTemplateBehavior> = mapOf("builtin_code_review" to StubBehavior())) =
        TemplateRuntime(
            registry = registry,
            behaviors = behaviors,
            clock = { now },
            logger = { logs.add(it) }
        )

    private suspend fun seedCustom(): String {
        val custom = PromptTemplate(
            id = "tpl_user_greet",
            name = "问候",
            content = "请以 {{style}} 风格问候 {{name}}",
            category = TemplateCategory.CUSTOM,
            variables = listOf(
                TemplateVariable("name", "对象", required = true),
                TemplateVariable("style", "风格", required = false, defaultValue = "正式")
            )
        )
        registry.save(custom)
        return custom.id
    }

    // ═══ 激活 ═══

    @Test
    fun `activate unknown template fails with reason`() = runTest {
        val activation = runtime().activate("tpl_ghost", emptyMap())
        assertFalse(activation.ok)
        assertEquals(TemplateRuntime.REASON_TEMPLATE_NOT_FOUND, activation.failureReason)
        assertNull(activation.template)
    }

    @Test
    fun `activate blocks missing required variables`() = runTest {
        registry.reseedBuiltIns()
        val activation = runtime().activate("builtin_code_review", mapOf("language" to "Kotlin"))
        assertFalse(activation.ok)
        assertEquals(TemplateRuntime.REASON_MISSING_REQUIRED, activation.failureReason)
        assertEquals(listOf("code"), activation.missingRequired)
    }

    @Test
    fun `activate merges variables in three tiers`() = runTest {
        val customId = seedCustom()
        // 用户值 > 默认值 > 缺省
        val activation = runtime().activate(customId, mapOf("name" to "老板"))
        assertTrue(activation.ok)
        assertEquals("老板", activation.variables.value("name"))
        assertEquals(TemplateVariableSource.USER, activation.variables.source("name"))
        assertEquals("正式", activation.variables.value("style"))
        assertEquals(TemplateVariableSource.DEFAULT, activation.variables.source("style"))
    }

    @Test
    fun `activate forces factory definition for built-ins even if disk copy drifted`() = runTest {
        // 磁盘上伪造被篡改的内置条目（REPLACE 导入通道塞入）
        val forged = TemplateLibrary(
            listOf(
                PromptTemplate(
                    id = "builtin_code_review",
                    name = "伪造",
                    content = "被篡改的内容",
                    isBuiltIn = true
                )
            )
        )
        val payload = Json { encodeDefaults = true }.encodeToString(TemplateLibrary.serializer(), forged)
        registry.importLibrary(payload, ImportMode.REPLACE)
        // reseed 恢复内置（模拟 DI 播种）
        registry.reseedBuiltIns()

        val activation = runtime().activate("builtin_code_review", mapOf("code" to "val a = 1"))
        assertTrue(activation.ok)
        // 运行时快照用出厂定义，不用磁盘副本（双保险：注册表自愈 + 运行时强制出厂）
        assertTrue(activation.template!!.content.contains("资深代码评审专家"))
        assertTrue(activation.template!!.isBuiltIn)
    }

    @Test
    fun `activate binds registered behavior and falls back for custom`() = runTest {
        registry.reseedBuiltIns()
        val customId = seedCustom()

        val builtinActivation = runtime().activate("builtin_code_review", mapOf("code" to "x"))
        assertTrue(builtinActivation.ok)
        assertEquals("builtin_code_review", builtinActivation.behavior!!.behaviorId)
        assertEquals("代码审查", builtinActivation.manifest!!.displayName)
        assertTrue(builtinActivation.manifest!!.deterministicPreAnalysis)

        val customActivation = runtime().activate(customId, mapOf("name" to "老板"))
        assertTrue(customActivation.ok)
        // 自定义模板回落兜底行为（变量替换管线）
        assertEquals(customId, customActivation.behavior!!.behaviorId)
        assertFalse(customActivation.manifest!!.deterministicPreAnalysis)
    }

    @Test
    fun `activate increments usage through registry`() = runTest {
        registry.reseedBuiltIns()
        val before = registry.get("builtin_code_review")!!.usageCount
        runtime().activate("builtin_code_review", mapOf("code" to "x"))
        runtime().activate("builtin_code_review", mapOf("code" to "y"))
        assertEquals(before + 2, registry.get("builtin_code_review")!!.usageCount)
    }

    // ═══ 轮次组装 ═══

    @Test
    fun `prepareTurn runs full pipeline with trace and enrichment`() = runTest {
        registry.reseedBuiltIns()
        val runtime = runtime()
        val activation = runtime.activate("builtin_code_review", mapOf("code" to "fun a() {\n    val x = 1\n}"))
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        val request = outcome.request!!
        // 变量替换 + 附录注入
        assertTrue(request.prompt.contains("fun a() {"))
        assertTrue(request.prompt.contains("确定性分析附录"))
        assertTrue(request.prompt.contains("样例规则命中"))
        // 轨迹：PREPARE → ANALYZE → COMPOSE 全绿
        assertEquals(3, outcome.trace.phases.size)
        assertTrue(outcome.trace.allOk)
        assertEquals(
            listOf(TemplatePhaseKind.PREPARE, TemplatePhaseKind.ANALYZE, TemplatePhaseKind.COMPOSE),
            outcome.trace.phases.map { it.phase }
        )
    }

    @Test
    fun `prepareTurn on failed activation is defensive`() = runTest {
        val outcome = runtime().prepareTurn(
            TemplateActivation(
                ok = false, template = null, variables = TemplateVariableReport.empty(),
                behavior = null, manifest = null,
                failureReason = TemplateRuntime.REASON_TEMPLATE_NOT_FOUND
            )
        )
        assertFalse(outcome.ok)
        assertEquals(TemplateRuntime.REASON_TEMPLATE_NOT_FOUND, outcome.failure)
    }

    @Test
    fun `prepareTurn folds crashing behavior phases`() = runTest {
        registry.reseedBuiltIns()
        val crashing = object : TemplateBehaviorBase() {
            override val behaviorId = "builtin_code_review"
            override val primaryInputName = "code"
            override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
                behaviorId = behaviorId, displayName = "崩", summary = "崩溃行为",
                supportedPhases = setOf(TemplatePhaseKind.PREPARE, TemplatePhaseKind.ANALYZE)
            )

            override suspend fun analyze(
                context: TemplateExecutionContext,
                prepared: TemplatePreparedInput
            ): TemplateAnalysis = throw IllegalStateException("boom")
        }
        val runtime = runtime(mapOf("builtin_code_review" to crashing))
        val activation = runtime.activate("builtin_code_review", mapOf("code" to "x"))
        val outcome = runtime.prepareTurn(activation)
        // ANALYZE 崩溃被折叠为空分析，COMPOSE 仍可用（管线不因单阶段崩溃全灭）
        assertTrue(outcome.ok)
        val analyzeTrace = outcome.trace.phases.first { it.phase == TemplatePhaseKind.ANALYZE }
        assertFalse(analyzeTrace.ok)
        assertTrue(logs.any { it.contains("ANALYZE") && it.contains("boom") })
    }

    // ═══ 回包处理 ═══

    @Test
    fun `processResponse validates repairs and renders`() = runTest {
        registry.reseedBuiltIns()
        val runtime = runtime()
        val activation = runtime.activate("builtin_code_review", mapOf("code" to "val a = 1"))
        val outcome = runtime.prepareTurn(activation)
        val raw = """
            好的，以下是评审结论。

            ## 总体结论
            有条件通过。

            ## 问题清单
            1. 变量命名过短
            2. 缺少文档

            希望对你有帮助！
        """.trimIndent()
        val report = runtime.processResponse(activation, raw, outcome.request)
        // 寒暄与尾随废话被自动修复
        assertTrue(report.wasRepaired)
        assertFalse(report.finalText.contains("好的，以下是"))
        // 契约章节都渲染为结构化输出
        assertTrue(report.sections.any { it.title == "总体结论" })
        assertTrue(report.sections.any { it.title == "问题清单" })
        // 轨迹包含 VALIDATE/REPAIR/RENDER
        assertTrue(report.trace!!.phases.map { it.phase }.containsAll(
            listOf(TemplatePhaseKind.VALIDATE, TemplatePhaseKind.REPAIR, TemplatePhaseKind.RENDER)
        ))
    }

    @Test
    fun `processResponse flags missing sections as residual`() = runTest {
        registry.reseedBuiltIns()
        val runtime = runtime()
        val activation = runtime.activate("builtin_code_review", mapOf("code" to "val a = 1"))
        val report = runtime.processResponse(activation, "## 总体结论\n通过。")
        assertTrue(report.residualViolations.any { it.kind == TemplateContractViolationKind.MISSING_SECTION })
    }

    @Test
    fun `processResponse blank response yields fallback report`() = runTest {
        registry.reseedBuiltIns()
        val runtime = runtime()
        val activation = runtime.activate("builtin_code_review", mapOf("code" to "val a = 1"))
        val report = runtime.processResponse(activation, "   ")
        assertEquals(0.0, report.qualityScore, 1e-9)
        assertTrue(report.residualViolations.isNotEmpty())
    }

    @Test
    fun `processResponse without repair option keeps raw text`() = runTest {
        registry.reseedBuiltIns()
        val runtime = runtime()
        val activation = runtime.activate(
            "builtin_code_review", mapOf("code" to "x"),
            options = TemplateRuntimeOptions(autoRepair = false)
        )
        val raw = "好的。\n## 总体结论\n通过。\n## 问题清单\n- 问题甲"
        val report = runtime.processResponse(activation, raw)
        assertFalse(report.wasRepaired)
        assertTrue(report.finalText.contains("好的"))
    }

    @Test
    fun `processResponse on bad activation is defensive`() = runTest {
        val report = runtime().processResponse(
            TemplateActivation(
                ok = false, template = null, variables = TemplateVariableReport.empty(),
                behavior = null, manifest = null
            ),
            "随便什么回包"
        )
        assertEquals(0.0, report.qualityScore, 1e-9)
    }

    // ═══ 目录与收尾 ═══

    @Test
    fun `behaviorManifests lists registered behaviors sorted`() {
        val manifests = runtime().behaviorManifests()
        assertEquals(1, manifests.size)
        assertEquals("builtin_code_review", manifests[0].behaviorId)
    }

    @Test
    fun `deactivate logs without side effects`() {
        runtime().deactivate(
            TemplateActivation(
                ok = true, template = null, variables = TemplateVariableReport.empty(),
                behavior = null, manifest = null
            )
        )
        assertTrue(logs.any { it.contains("deactivate") })
    }

    @Test
    fun `custom template full round trip uses fallback pipeline`() = runTest {
        val customId = seedCustom()
        val runtime = runtime() // 无该 id 的注册行为 → 兜底
        val activation = runtime.activate(customId, mapOf("name" to "老板"))
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        // 纯变量替换 + 默认值（无附录注入 —— 兜底行为 NONE 模式）
        assertTrue(outcome.request!!.prompt.contains("请以 正式 风格问候 老板"))
        assertFalse(outcome.request!!.prompt.contains("确定性分析附录"))
        assertNotNull(outcome.trace)
    }
}
