package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.BuiltinPromptTemplates
import com.apex.agent.core.engine.templates.PromptTemplateRegistry
import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplatePhaseKind
import com.apex.agent.core.engine.templates.runtime.TemplatePromptEnrichmentMode
import com.apex.agent.core.engine.templates.runtime.TemplateRuntime
import com.apex.agent.core.engine.templates.runtime.TemplateTemplateSnapshot
import com.apex.agent.core.engine.templates.runtime.TemplateVariableAssignment
import com.apex.agent.core.engine.templates.runtime.TemplateVariableReport
import com.apex.agent.core.engine.templates.runtime.TemplateVariableSource
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 15 测试 · 行为目录完整性。
 *
 * 锁死「十个内置模板 ↔ 十个代码行为」的 1:1 装订与行为自洽性 ——
 * 新增内置模板必须同步补行为，漏了直接红。
 */
class BuiltinBehaviorCatalogTest {

    // ═══ 1:1 完整性 ═══

    @Test
    fun `catalog keys match builtin template ids exactly`() {
        assertEquals(BuiltinPromptTemplates.BUILTIN_IDS, BuiltinBehaviorCatalog.ALL.keys)
        assertEquals(3, BuiltinBehaviorCatalog.ALL.size)
    }

    @Test
    fun `lookup and miss behave`() {
        assertNotNull(BuiltinBehaviorCatalog.behaviorFor("builtin_code_review"))
        assertEquals(null, BuiltinBehaviorCatalog.behaviorFor("tpl_user_defined"))
        assertTrue(BuiltinBehaviorCatalog.hasBehaviorFor("builtin_test_cases"))
        assertTrue(BuiltinBehaviorCatalog.hasBehaviorFor("builtin_browser_expert"))
        // 已删除的 Agent 族模板不再入目录
        assertTrue(!BuiltinBehaviorCatalog.hasBehaviorFor("builtin_translate"))
        assertTrue(!BuiltinBehaviorCatalog.hasBehaviorFor("builtin_ghost"))
    }

    // ═══ 行为清单自洽 ═══

    @Test
    fun `every behavior manifest is self consistent`() {
        for ((id, behavior) in BuiltinBehaviorCatalog.ALL) {
            val manifest = behavior.manifest()
            assertEquals("behaviorId 漂移：$id", id, manifest.behaviorId)
            assertEquals("behaviorId 与清单不一致：$id", id, behavior.behaviorId)
            assertTrue("$id displayName 为空", manifest.displayName.isNotBlank())
            assertTrue("$id summary 为空", manifest.summary.isNotBlank())
            assertTrue("$id 未声明任何阶段", manifest.supportedPhases.isNotEmpty())
            assertTrue("$id 声明了确定性分析但阶段不含 ANALYZE",
                !manifest.deterministicPreAnalysis || TemplatePhaseKind.ANALYZE in manifest.supportedPhases)
        }
    }

    @Test
    fun `payloadOnly contract not used by structural behaviors`() {
        // 翻译模板（纯载荷契约）已随 Agent 族删除；存续行为均为结构化章节契约
        for ((id, behavior) in BuiltinBehaviorCatalog.ALL) {
            val payloadOnly = behavior.manifest().outputContract.payloadOnly
            assertTrue("$id 不应使用纯载荷契约（需要结构化章节）", !payloadOnly)
            assertTrue(
                "$id 契约缺少必需章节",
                behavior.manifest().outputContract.requiredTitles.isNotEmpty()
            )
        }
    }

    @Test
    fun `append findings mode is used by all deterministic behaviors`() {
        for ((id, behavior) in BuiltinBehaviorCatalog.ALL) {
            val manifest = behavior.manifest()
            if (manifest.deterministicPreAnalysis) {
                assertTrue(
                    "$id 声明确定性分析但注入模式为 NONE（附录无法注入）",
                    manifest.promptEnrichmentMode != TemplatePromptEnrichmentMode.NONE
                )
            }
        }
    }

    // ═══ 确定性冒烟 ═══

    /** 每个内置模板的代表性输入（冒烟用：全链路跑通 + 确定性）。 */
    private val smokeInputs: Map<String, Map<String, String>> = mapOf(
        "builtin_code_review" to mapOf("code" to "fun a() {\n    val x = p!!.name\n}", "language" to "kotlin"),
        "builtin_test_cases" to mapOf("code" to "fun calc(amount: Int, coupon: String?): Int = 0", "framework" to "JUnit"),
        // 角色模板（无变量）：激活即授角色 + 注入纪律附录
        "builtin_browser_expert" to emptyMap()
    )

    @Test
    fun `every builtin smoke input is present`() {
        // 冒烟输入必须覆盖全部十个模板（新增模板须补样例）
        assertEquals(BuiltinPromptTemplates.BUILTIN_IDS, smokeInputs.keys)
    }

    @Test
    fun `every behavior runs full pipeline deterministically`() = runTest {
        val dir = File("/tmp/catalog-smoke-${System.nanoTime()}").apply { mkdirs() }
        val registry = PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        val runtime = TemplateRuntime(
            registry = registry,
            behaviors = BuiltinBehaviorCatalog.ALL
        )
        for ((templateId, variables) in smokeInputs) {
            val activation = runtime.activate(templateId, variables)
            assertTrue("激活失败：$templateId（${activation.failureReason} ${activation.missingRequired}）", activation.ok)
            // 内置模板必须绑定代码行为（不能走兜底）
            assertEquals("内置模板未绑定代码行为：$templateId", templateId, activation.behavior!!.behaviorId)
            assertTrue("内置行为必须是确定性分析：$templateId", activation.manifest!!.deterministicPreAnalysis)

            val outcome1 = runtime.prepareTurn(activation)
            assertTrue("轮次组装失败：$templateId（${outcome1.failure}）", outcome1.ok)
            val prompt1 = outcome1.request!!.prompt
            assertTrue("附录未注入：$templateId", prompt1.contains("附录") || prompt1.contains("运行时提示"))

            // 确定性：同输入两次组装结果一致
            val outcome2 = runtime.prepareTurn(activation)
            assertEquals("确定性破坏：$templateId", prompt1, outcome2.request!!.prompt)

            // 回包链路（每行为用其契约构造一个合格回包冒烟）
            val report = runtime.processResponse(activation, smokeResponseFor(templateId), outcome1.request)
            assertTrue("回包处理崩溃：$templateId（trace=${report.trace?.phases?.filter { !it.ok }?.map { it.phase }}）",
                report.trace == null || report.trace.phases.none { !it.ok && it.phase == TemplatePhaseKind.VALIDATE })
        }
    }

    /** 冒烟回包（对多数行为是「不完美但可解析」的形态 —— 验证链路不崩即可）。 */
    private fun smokeResponseFor(templateId: String): String = when (templateId) {
        "builtin_code_review" -> "## 总体结论\n有条件通过。\n\n## 问题清单\n1. 🔴 阻断 非空断言风险"
        "builtin_test_cases" -> "## 等价类与边界条件\n- 0/1/-1\n\n## 覆盖矩阵\n- 正常 1\n\n## 测试代码\n```\n@Test fun t() {}\n```"
        "builtin_browser_expert" -> "## 任务理解与拆解\n登录并抓取订单列表。\n\n## BrowserTask 步骤编排\n1. NavigateAsync 打开登录页\n2. 填表提交（wait_for network_idle）\n3. BrowserSnapshot focused(订单表)\n\n## 风险与人工确认点\n- 登录失败：CHECKPOINT 暂停交用户"
        else -> "通用回包"
    }

    // ═══ 上下文快照适配（行为侧快照与模板声明一致性）═══

    @Test
    fun `behavior primary inputs align with template variables`() = runTest {
        // 主输入名必须是模板声明的变量（否则 prepare 拿不到输入）
        val templateVars = BuiltinPromptTemplates.ALL.associate { it.id to it.variables.map { v -> v.name } }
        for ((id, behavior) in BuiltinBehaviorCatalog.ALL) {
            val behaviorBase = behavior as? com.apex.agent.core.engine.templates.runtime.TemplateBehaviorBase
                ?: continue
            assertTrue(
                "行为主输入 ${behaviorBase.primaryInputName} 不是模板 $id 声明的变量",
                behaviorBase.primaryInputName in (templateVars[id] ?: emptyList())
            )
        }
    }

    private fun contextOf(templateId: String): TemplateExecutionContext = TemplateExecutionContext(
        template = TemplateTemplateSnapshot(
            id = templateId,
            name = templateId,
            content = "x",
            declaredVariableNames = emptyList(),
            categoryLabel = "TEST",
            isBuiltIn = true
        ),
        variables = TemplateVariableReport(
            listOf(TemplateVariableAssignment("code", "x", TemplateVariableSource.USER))
        )
    )
}
