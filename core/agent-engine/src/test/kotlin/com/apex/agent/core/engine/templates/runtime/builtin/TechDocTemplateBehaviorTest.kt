package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateTemplateSnapshot
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateVariableAssignment
import com.apex.agent.core.engine.templates.runtime.TemplateVariableReport
import com.apex.agent.core.engine.templates.runtime.TemplateVariableSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 13 测试 · 技术文档行为。
 */
class TechDocTemplateBehaviorTest {

    private val behavior = TechDocTemplateBehavior()

    private fun context(
        topic: String = "模板行为运行时",
        audience: String = "",
        code: String = ""
    ): TemplateExecutionContext = TemplateExecutionContext(
        template = TemplateTemplateSnapshot(
            id = "builtin_tech_doc",
            name = "技术文档",
            content = "请围绕主题撰写一篇技术文档。",
            declaredVariableNames = listOf("topic", "audience", "code"),
            categoryLabel = "WRITING",
            isBuiltIn = true
        ),
        variables = TemplateVariableReport(
            listOf(
                TemplateVariableAssignment("topic", topic, TemplateVariableSource.USER),
                TemplateVariableAssignment(
                    "audience", audience,
                    if (audience.isBlank()) TemplateVariableSource.MISSING else TemplateVariableSource.USER
                ),
                TemplateVariableAssignment(
                    "code", code,
                    if (code.isBlank()) TemplateVariableSource.MISSING else TemplateVariableSource.USER
                )
            )
        )
    )

    private suspend fun analyzeFor(topic: String = "模板行为运行时", audience: String = "", code: String = "") =
        behavior.analyze(context(topic, audience, code), behavior.prepare(context(topic, audience, code)))

    private fun requestStub() = TemplateTurnRequest(prompt = "stub", behaviorId = "builtin_tech_doc")

    // ═══ 受众 ═══

    @Test
    fun `audience normalization covers aliases`() {
        assertEquals(DocAudience.ONBOARDING, TechDocPlanner.resolveAudience("新加入团队的开发者"))
        assertEquals(DocAudience.ONBOARDING, TechDocPlanner.resolveAudience("onboarding"))
        assertEquals(DocAudience.TEAM, TechDocPlanner.resolveAudience("团队内部"))
        assertEquals(DocAudience.EXPERT, TechDocPlanner.resolveAudience("资深架构师"))
        // 未知回落新人档
        assertEquals(DocAudience.ONBOARDING, TechDocPlanner.resolveAudience("随便谁"))
    }

    // ═══ 大纲 ═══

    @Test
    fun `outline has five canonical sections plus audience tuning`() {
        val onboarding = TechDocPlanner.buildOutline(DocAudience.ONBOARDING)
        assertEquals(6, onboarding.size)
        assertTrue(onboarding.any { it.title == "术语表" })

        val expert = TechDocPlanner.buildOutline(DocAudience.EXPERT)
        assertTrue(expert.any { it.title == "设计取舍" })
        assertEquals(6, expert.size)

        val team = TechDocPlanner.buildOutline(DocAudience.TEAM)
        assertEquals(5, team.size)

        // 五段式核心章节齐全且有序
        val titles = team.map { it.title }
        assertEquals(
            listOf("一句话概述与适用场景", "背景与动机", "核心概念与工作原理", "使用示例", "常见问题与注意事项"),
            titles
        )
        assertTrue(team.all { it.required })
    }

    // ═══ 代码素材 ═══

    @Test
    fun `snippet extraction prefers fenced blocks`() {
        val code = """
            说明文字。
            ```kotlin
            fun activate(id: String) {
                registry.get(id)
            }
            ```
            结尾说明。
        """.trimIndent()
        val snippets = TechDocPlanner.extractSnippets(code)
        assertEquals(1, snippets.size)
        assertEquals("kotlin", snippets[0].language)
        assertTrue(snippets[0].firstLine.contains("activate"))
        assertEquals(3, snippets[0].lineCount)
    }

    @Test
    fun `snippet extraction falls back to signature lines`() {
        val code = """
            class Registry {
                fun activate(id: String) { }
                fun deactivate(id: String) { }
            }
        """.trimIndent()
        val snippets = TechDocPlanner.extractSnippets(code)
        assertTrue(snippets.isNotEmpty())
        assertTrue(snippets.any { it.firstLine.contains("activate") })
    }

    // ═══ 主题追加章节 ═══

    @Test
    fun `topic keywords trigger extra sections`() {
        val migration = TechDocPlanner.extraSectionsFor("从 v1 迁移到 v2 的指南")
        assertTrue(migration.any { it.contains("迁移指引") })
        val deploy = TechDocPlanner.extraSectionsFor("服务部署与配置手册")
        assertTrue(deploy.any { it.contains("环境要求与安装") })
        val perf = TechDocPlanner.extraSectionsFor("性能压测报告")
        assertTrue(perf.any { it.contains("性能特征") })
        val none = TechDocPlanner.extraSectionsFor("纯概念介绍")
        assertTrue(none.isEmpty())
    }

    // ═══ 可读性 ═══

    @Test
    fun `readability notes flag long sentences and missing code`() {
        val longSentence = "这是一句特别长的素材句子".repeat(12) + "。"
        val notes = TechDocPlanner.readabilityNotes(longSentence, "某主题")
        assertTrue(notes.any { it.contains("句长") })

        val noCode = TechDocPlanner.readabilityNotes("", "某主题")
        assertTrue(noCode.any { it.contains("无参考代码") })

        val emptyTopic = TechDocPlanner.readabilityNotes("", "")
        assertTrue(emptyTopic.any { it.contains("主题为空") })
    }

    // ═══ 行为 ═══

    @Test
    fun `analyze produces audience outline and snippet findings`() = runTest {
        val analysis = analyzeFor(
            topic = "缓存层迁移指南",
            audience = "新成员",
            code = "```kotlin\nfun migrate(old: Path) {}\n```"
        )
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("doc.audience"))
        assertTrue(ruleIds.contains("doc.outline"))
        assertTrue(ruleIds.contains("doc.snippets"))
        assertTrue(ruleIds.contains("doc.extra-section"))
        assertTrue(analysis.enrichedHints.any { it.contains("大纲基线") })
        assertEquals("ONBOARDING", analysis.metadata["audience"])
    }

    @Test
    fun `compose injects outline appendix`() = runTest {
        val ctx = context(topic = "模板行为运行时", audience = "新成员")
        val prepared = behavior.prepare(ctx)
        val analysis = behavior.analyze(ctx, prepared)
        val request = behavior.composePrompt(ctx, prepared, analysis)
        assertTrue(request.prompt.contains("确定性分析附录"))
        assertTrue(request.prompt.contains("文档大纲"))
        assertTrue(request.prompt.contains("术语表"))
    }

    @Test
    fun `contract requires five sections`() {
        assertEquals(
            listOf("一句话概述与适用场景", "背景与动机", "核心概念与工作原理", "使用示例", "常见问题与注意事项"),
            behavior.manifest().outputContract.requiredTitles
        )
    }

    @Test
    fun `validate flags missing sections and requires fenced example`() {
        val raw = "## 一句话概述与适用场景\n概述。\n## 背景与动机\n背景。\n" +
            "## 核心概念与工作原理\n原理。\n## 使用示例\n文字描述示例没有代码。\n## 常见问题与注意事项\n无"
        val report = behavior.validateOutput(raw, context(), requestStub())
        // 示例章节无围栏 → 违规
        assertTrue(report.violations.any { it.detail.contains("围栏") || it.detail.contains("可运行") })
    }

    @Test
    fun `validate accepts complete doc with fenced example`() {
        val raw = """
            ## 一句话概述与适用场景
            概述。

            ## 背景与动机
            背景。

            ## 核心概念与工作原理
            原理。

            ## 使用示例
            ```
            activate("id")
            ```

            ## 常见问题与注意事项
            常见问题。
        """.trimIndent()
        val report = behavior.validateOutput(raw, context(), requestStub())
        assertTrue(report.ok)
    }

    @Test
    fun `render summary carries plan stats`() {
        val raw = """
            ## 一句话概述与适用场景
            概述。

            ## 背景与动机
            背景。

            ## 核心概念与工作原理
            原理。

            ## 使用示例
            ```
            activate("id")
            ```

            ## 常见问题与注意事项
            问题。
        """.trimIndent()
        val rendered = behavior.renderReport(
            raw,
            context(topic = "部署配置", audience = "专家"),
            requestStub()
        )
        assertEquals("专家深读", rendered.summary["audience"])
        assertEquals("6", rendered.summary["outlineSectionCount"])
        assertTrue((rendered.summary["extraSectionCount"]?.toIntOrNull() ?: 0) >= 1)
    }

    // ═══ 端到端 ═══

    @Test
    fun `full turn through runtime`() = runTest {
        val dir = java.io.File("/tmp/techdoc-runtime-${System.nanoTime()}").apply { mkdirs() }
        val registry = com.apex.agent.core.engine.templates.PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        val runtime = com.apex.agent.core.engine.templates.runtime.TemplateRuntime(
            registry = registry,
            behaviors = mapOf(behavior.behaviorId to behavior)
        )
        val activation = runtime.activate(
            "builtin_tech_doc",
            mapOf(
                "topic" to "模板行为运行时",
                "audience" to "新加入团队的开发者",
                "code" to "```kotlin\nfun activate(id: String) {}\n```"
            )
        )
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        assertTrue(outcome.request!!.prompt.contains("大纲基线"))
        val report = runtime.processResponse(
            activation,
            """
            ## 一句话概述与适用场景
            概述。

            ## 背景与动机
            背景。

            ## 核心概念与工作原理
            原理。

            ## 使用示例
            ```
            activate("id")
            ```

            ## 常见问题与注意事项
            问题。

            ## 术语表
            - 模板：定义
            """.trimIndent(),
            outcome.request
        )
        assertTrue(report.trace!!.allOk)
        assertEquals("新人上手", report.summary["audience"])
    }
}
