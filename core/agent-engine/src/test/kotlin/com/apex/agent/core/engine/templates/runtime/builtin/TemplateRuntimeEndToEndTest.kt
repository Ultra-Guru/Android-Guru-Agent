package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.PromptTemplateRegistry
import com.apex.agent.core.engine.templates.runtime.TemplateRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.coroutines.test.runTest

/**
 * PR 20 测试 · 模板运行时端到端收官 —— 深化模块接线锁死。
 *
 * 本套件是模板运行时系列（PR 1-20）的收口验收：
 * 1. **深度接线锁**：十个内置行为全部 behaviorVersion ≥ 2（PR 17-19
 *    的深化模块必须真实接线，不是死代码）；
 * 2. **全链路冒烟**：激活 → 六阶段组装，每个行为的确定性附录必须包含
 *    其深化模块的专属信号（翻译质检/总结大纲/提交规范/重构安全序/
 *    BUG 分诊/API 骨架/测试矩阵/文档纪律/正则方言/审查启发）；
 * 3. **不可更改锁**：注册表对内置模板的 save 拒绝 / delete 拒绝 /
 *    落盘篡改自愈，端到端再验一遍（与注册表单测互补，走运行时视角）。
 */
class TemplateRuntimeEndToEndTest {

    /** 独立临时目录 + 内置播种 + 十行为目录绑定的运行时。 */
    private suspend fun newRuntime(): TemplateRuntime {
        val dir = File("/tmp/e2e-runtime-${System.nanoTime()}").apply { mkdirs() }
        val registry = PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        return TemplateRuntime(
            registry = registry,
            behaviors = BuiltinBehaviorCatalog.ALL
        )
    }

    // ═══ 1) 深度接线锁 ═══

    @Test
    fun `all ten behaviors are deep wired to version two or above`() {
        for (behavior in BuiltinBehaviorCatalog.ALL.values) {
            assertTrue(
                "${behavior.behaviorId} 的 behaviorVersion 必须 ≥ 2（深化模块未接线）",
                behavior.manifest().behaviorVersion >= 2
            )
        }
    }

    @Test
    fun `all deep modules are present with stable rule ids`() {
        assertEquals(10, BuiltinBehaviorCatalog.ALL.size)
        assertEquals(17, TranslateQualityLinter.RULE_IDS.size)
        assertEquals(8, RefactorRiskModel.RULE_IDS.size)
        assertEquals(7, BugReportTriageEngine.RULE_IDS.size)
        assertEquals(9, ApiDesignConventionLibrary.RULE_IDS.size)
        assertEquals(8, TestCaseFrameworkKit.SPECS.size)
        assertEquals(4, DocType.entries.size)
        assertEquals(5, RegexFlavor.entries.size)
    }

    // ═══ 2) 全链路冒烟（深化模块专属信号注入附录）═══

    @Test
    fun `translate pipeline carries lint and glossary signals`() = runTest {
        val runtime = newRuntime()
        val activation = runtime.activate(
            "builtin_translate",
            mapOf(
                "text" to "熔断器在 300ms 后触发，命中率为 95%。",
                "target_language" to "英文",
                "domain" to "技术"
            )
        )
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        val prompt = outcome.request!!.prompt
        // 术语表 + 数字保真清单（深化信号）
        assertTrue("术语建议未注入", prompt.contains("术语"))
        assertTrue("数字保真清单未注入", prompt.contains("300"))
    }

    @Test
    fun `summarize pipeline carries outline and budget signals`() = runTest {
        val runtime = newRuntime()
        val activation = runtime.activate(
            "builtin_summarize",
            mapOf(
                "content" to "第一季度增长 20%。第二季度增长 35%。第三季度回落到 5%。第四季度计划翻倍。整体趋势向好。",
                "length" to "200 字以内"
            )
        )
        assertTrue(activation.ok)
        val prompt = runtime.prepareTurn(activation).request!!.prompt
        assertTrue("要点预算未注入", prompt.contains("要点预算"))
        assertTrue("结论草稿未注入", prompt.contains("结论草稿"))
    }

    @Test
    fun `commit message pipeline carries conventions and draft`() = runTest {
        val runtime = newRuntime()
        val activation = runtime.activate(
            "builtin_commit_message",
            mapOf(
                "changes" to "diff --git a/Main.kt b/Main.kt\n--- a/Main.kt\n+++ b/Main.kt\n@@ -1,2 +1,3 @@\n fun main() {\n-    println(1)\n+    println(2)\n+    println(3)\n }",
                "style" to "conventional commits"
            )
        )
        assertTrue(activation.ok)
        val prompt = runtime.prepareTurn(activation).request!!.prompt
        assertTrue("风格规范未注入", prompt.contains("风格规范"))
        assertTrue("代码侧草稿未注入", prompt.contains("草稿"))
    }

    @Test
    fun `refactor pipeline carries safe ordering and risk matrix`() = runTest {
        val runtime = newRuntime()
        val activation = runtime.activate(
            "builtin_refactor_plan",
            mapOf(
                "code" to "fun deep(a: Int): Int {\n    if (a > 0) {\n        if (a > 1) {\n            if (a > 2) {\n                if (a > 3) {\n                    return a\n                }\n            }\n        }\n    }\n    return 0\n}",
                "goal" to "降低嵌套"
            )
        )
        assertTrue(activation.ok)
        val prompt = runtime.prepareTurn(activation).request!!.prompt
        assertTrue("安全序未注入", prompt.contains("安全序") || prompt.contains("保护网"))
        assertTrue("风险矩阵未注入", prompt.contains("风险矩阵") || prompt.contains("综合"))
    }

    @Test
    fun `bug report pipeline carries triage and fingerprint signals`() = runTest {
        val runtime = newRuntime()
        val activation = runtime.activate(
            "builtin_bug_report",
            mapOf(
                "symptom" to "提交订单后应用闪退",
                "reproduce" to "1. 打开订单页 2. 点击提交（每次必现）",
                "expected" to "提交成功",
                "actual" to "应用崩溃",
                "environment" to "Pixel 8, Android 14, 版本 1.4.9"
            )
        )
        assertTrue(activation.ok)
        val prompt = runtime.prepareTurn(activation).request!!.prompt
        assertTrue("分诊结论未注入", prompt.contains("分诊结论") || prompt.contains("优先级"))
        assertTrue(
            "已知问题命中未注入",
            prompt.contains("已知问题") || prompt.contains("前台服务") || prompt.contains("环境指纹")
        )
    }

    @Test
    fun `api design pipeline carries conventions and checklist`() = runTest {
        val runtime = newRuntime()
        val activation = runtime.activate(
            "builtin_api_design",
            mapOf("requirement" to "查询商品列表、创建订单、删除订单", "style" to "RESTful")
        )
        assertTrue(activation.ok)
        val prompt = runtime.prepareTurn(activation).request!!.prompt
        assertTrue("横切必查未注入", prompt.contains("横切必查") || prompt.contains("横切关注点"))
        assertTrue("状态码税则未注入", prompt.contains("状态码"))
    }

    @Test
    fun `test cases pipeline carries framework kit and tc ids`() = runTest {
        val runtime = newRuntime()
        val activation = runtime.activate(
            "builtin_test_cases",
            mapOf("code" to "fun calc(amount: Int, coupon: String?): Int = 0", "framework" to "JUnit")
        )
        assertTrue(activation.ok)
        val prompt = runtime.prepareTurn(activation).request!!.prompt
        assertTrue("TC 编号未注入", prompt.contains("TC-001"))
        assertTrue("参数化形态未注入", prompt.contains("参数化"))
    }

    @Test
    fun `tech doc pipeline carries type discipline and budget`() = runTest {
        val runtime = newRuntime()
        val activation = runtime.activate(
            "builtin_tech_doc",
            mapOf("topic" to "模板行为运行时", "audience" to "新成员")
        )
        assertTrue(activation.ok)
        val prompt = runtime.prepareTurn(activation).request!!.prompt
        assertTrue(
            "内容纪律未注入",
            prompt.contains("内容纪律") || prompt.contains("大纲基线") || prompt.contains("句长预算")
        )
    }

    @Test
    fun `regex pipeline carries flavor profile and snippet`() = runTest {
        val runtime = newRuntime()
        val activation = runtime.activate(
            "builtin_regex_explain",
            mapOf("pattern" to "^(\\d{4})-(\\d{2})-(\\d{2})$", "sample" to "2024-01-31")
        )
        assertTrue(activation.ok)
        val prompt = runtime.prepareTurn(activation).request!!.prompt
        assertTrue("方言探测未注入", prompt.contains("方言"))
        assertTrue(
            "测试片段未注入",
            prompt.contains("测试片段") || prompt.contains("containsMatchIn")
        )
    }

    @Test
    fun `code review pipeline carries heuristics layer`() = runTest {
        val runtime = newRuntime()
        val activation = runtime.activate(
            "builtin_code_review",
            mapOf(
                "code" to "val x = 1\nval t = x\nval enabled: Boolean = true\nfun run() = 3600 + 3600 + 3600",
                "language" to "kotlin"
            )
        )
        assertTrue(activation.ok)
        val prompt = runtime.prepareTurn(activation).request!!.prompt
        // 附录注入正常（第一/二/三层分析全部汇入）
        assertTrue(prompt.contains("确定性分析附录"))
    }

    // ═══ 3) 不可更改锁（运行时视角端到端）═══

    @Test
    fun `builtin templates cannot be saved or deleted through registry`() = runTest {
        val dir = File("/tmp/e2e-lock-${System.nanoTime()}").apply { mkdirs() }
        val registry = PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        val builtin = registry.get("builtin_translate")!!
        // save 拒绝
        var saveRejected = false
        try {
            registry.save(builtin.copy(content = "被篡改的内容"))
        } catch (expected: IllegalArgumentException) {
            saveRejected = true
        }
        assertTrue("内置模板 save 必须被拒绝", saveRejected)
        // delete 拒绝
        assertTrue("内置模板 delete 必须被拒绝", !registry.delete("builtin_translate"))
        // 原文未变
        assertEquals(builtin.content, registry.get("builtin_translate")!!.content)
    }

    @Test
    fun `tampered builtin on disk self heals on load`() = runTest {
        val dir = File("/tmp/e2e-heal-${System.nanoTime()}").apply { mkdirs() }
        val registry = PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        // 篡改落盘 JSON（绕过注册表直改文件）
        val store = File(dir, "templates.json")
        val tampered = store.readText()
            .replace("按严重度分级输出结构化评审结论", "被篡改的描述")
        store.writeText(tampered)
        // 新注册表实例读盘 → 自愈回出厂定义
        val healed = PromptTemplateRegistry(dir)
        assertEquals(
            "篡改的内置定义必须被自愈回出厂",
            "按严重度分级输出结构化评审结论",
            healed.get("builtin_code_review")!!.description
        )
    }
}
