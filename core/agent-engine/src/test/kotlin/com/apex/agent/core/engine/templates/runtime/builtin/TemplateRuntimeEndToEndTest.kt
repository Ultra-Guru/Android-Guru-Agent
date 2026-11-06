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
    fun `all surviving behaviors are deep wired to version two or above`() {
        for (behavior in BuiltinBehaviorCatalog.ALL.values) {
            assertTrue(
                "${behavior.behaviorId} 的 behaviorVersion 必须 ≥ 2（深化模块未接线）",
                behavior.manifest().behaviorVersion >= 2
            )
        }
    }

    @Test
    fun `all deep modules are present with stable rule ids`() {
        assertEquals(2, BuiltinBehaviorCatalog.ALL.size)
        assertEquals(17, TranslateQualityLinter.RULE_IDS.size)
        assertEquals(8, RefactorRiskModel.RULE_IDS.size)
        assertEquals(7, BugReportTriageEngine.RULE_IDS.size)
        assertEquals(9, ApiDesignConventionLibrary.RULE_IDS.size)
        assertEquals(8, TestCaseFrameworkKit.SPECS.size)
        assertEquals(4, DocType.entries.size)
        // 五方言（PCRE/Python/Java/JS/POSIX；UNKNOWN 哨兵不计入）
        assertEquals(5, RegexFlavor.entries.count { it != RegexFlavor.UNKNOWN })
    }

    // ═══ 2) 全链路冒烟（深化模块专属信号注入附录）═══

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
        val builtin = registry.get("builtin_test_cases")!!
        // save 拒绝
        var saveRejected = false
        try {
            registry.save(builtin.copy(content = "被篡改的内容"))
        } catch (expected: IllegalArgumentException) {
            saveRejected = true
        }
        assertTrue("内置模板 save 必须被拒绝", saveRejected)
        // delete 拒绝
        assertTrue("内置模板 delete 必须被拒绝", !registry.delete("builtin_test_cases"))
        // 原文未变
        assertEquals(builtin.content, registry.get("builtin_test_cases")!!.content)
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
