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
        assertEquals(10, BuiltinBehaviorCatalog.ALL.size)
    }

    @Test
    fun `lookup and miss behave`() {
        assertNotNull(BuiltinBehaviorCatalog.behaviorFor("builtin_code_review"))
        assertEquals(null, BuiltinBehaviorCatalog.behaviorFor("tpl_user_defined"))
        assertTrue(BuiltinBehaviorCatalog.hasBehaviorFor("builtin_translate"))
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
    fun `payloadOnly contract only used by translate`() {
        for ((id, behavior) in BuiltinBehaviorCatalog.ALL) {
            val payloadOnly = behavior.manifest().outputContract.payloadOnly
            if (id == "builtin_translate") {
                assertTrue("翻译必须是纯载荷契约", payloadOnly)
            } else {
                assertTrue("$id 不应使用纯载荷契约（需要结构化章节）", !payloadOnly)
                assertTrue(
                    "$id 契约缺少必需章节",
                    behavior.manifest().outputContract.requiredTitles.isNotEmpty()
                )
            }
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
        "builtin_commit_message" to mapOf(
            "changes" to "diff --git a/x.kt b/x.kt\n--- a/x.kt\n+++ b/x.kt\n@@ -1,2 +1,3 @@\n ctx\n-old\n+new\n+add",
            "style" to "conventional commits"
        ),
        "builtin_translate" to mapOf("text" to "系统启用熔断与灰度发布。", "target_language" to "英文", "domain" to "技术"),
        "builtin_summarize" to mapOf("content" to "延迟从 320ms 降到 45ms。命中率提升 30%。不支持降级。", "length" to "200 字以内"),
        "builtin_refactor_plan" to mapOf(
            "code" to "fun tangled(a: Int): Int {\n    if (a > 1) {\n        if (a > 2) {\n            if (a > 3) {\n                return a\n            }\n        }\n    }\n    return 0\n}",
            "goal" to "降低嵌套"
        ),
        "builtin_bug_report" to mapOf(
            "symptom" to "提交后应用崩溃",
            "reproduce" to "1. 打开订单页\n2. 点击提交（每次必现）",
            "expected" to "提交成功",
            "actual" to "应用闪退",
            "environment" to "Android 15 / 2.3.1"
        ),
        "builtin_api_design" to mapOf("requirement" to "查询商品列表、创建订单、订单审核", "style" to "RESTful"),
        "builtin_test_cases" to mapOf("code" to "fun calc(amount: Int, coupon: String?): Int = 0", "framework" to "JUnit"),
        "builtin_tech_doc" to mapOf("topic" to "模板行为运行时", "audience" to "新成员"),
        "builtin_regex_explain" to mapOf("pattern" to "^(\\d{4})-(\\d{2})$", "sample" to "2024-01")
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
        "builtin_commit_message" -> "## 提交信息\n```\nfeat(x): add new line\n\n- 说明\n```"
        "builtin_translate" -> "The system enables circuit breaking and canary release."
        "builtin_summarize" -> "## 一句话结论\n性能显著改善。\n\n## 要点\n- 延迟从 320ms 降到 45ms\n- 命中率提升 30%\n- 不支持降级"
        "builtin_refactor_plan" -> "## 现状问题诊断\n- 嵌套过深\n\n## 分步重构方案\n1. 补测试\n2. 卫语句化\n\n## 每步的风险与回滚\n- 低风险\n\n## 重构完成后的验证清单\n- 测试全绿"
        "builtin_bug_report" -> "## 标题\n提交崩溃\n\n## 严重程度评估\n🔴 阻断\n\n## 可能原因分析\n1. 空指针\n\n## 下一步排查建议\n抓堆栈"
        "builtin_api_design" -> "## 资源与端点清单\n- GET /orders\n\n## 请求与响应结构\n{ id }\n\n## 错误码设计\n400\n\n## 横切关注点说明\n游标分页"
        "builtin_test_cases" -> "## 等价类与边界条件\n- 0/1/-1\n\n## 覆盖矩阵\n- 正常 1\n\n## 测试代码\n```\n@Test fun t() {}\n```"
        "builtin_tech_doc" -> "## 一句话概述与适用场景\n概述。\n\n## 背景与动机\n背景。\n\n## 核心概念与工作原理\n原理。\n\n## 使用示例\n```\nx()\n```\n\n## 常见问题与注意事项\n问题。"
        "builtin_regex_explain" -> "## 整体作用\n匹配日期。\n\n## 逐段拆解\n1. ^ 行首\n\n## 匹配与不匹配示例\n- 匹配：2024-01\n- 不匹配：24-1\n\n## 性能陷阱与简化建议\n无"
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
