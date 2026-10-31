package com.apex.agent.core.engine.templates.runtime.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 19 测试 · 文档风格指南。
 */
class TechDocStyleGuideTest {

    // ═══ 文档类型 ═══

    @Test
    fun `doc type inference maps keywords`() {
        assertEquals(DocType.TROUBLESHOOT, DocType.inferFrom("登录报错排障指南"))
        assertEquals(DocType.API_REF, DocType.inferFrom("订单 api 接口文档"))
        assertEquals(DocType.HOW_TO, DocType.inferFrom("如何安装配置环境"))
        assertEquals(DocType.CONCEPT, DocType.inferFrom("什么是向量数据库"))
    }

    @Test
    fun `required sections differ by type`() {
        assertTrue(TechDocStyleGuide.requiredSectionsOf(DocType.HOW_TO).contains("前置条件"))
        assertTrue(TechDocStyleGuide.requiredSectionsOf(DocType.API_REF).contains("错误码"))
        assertTrue(TechDocStyleGuide.requiredSectionsOf(DocType.TROUBLESHOOT).contains("症状定位"))
        assertTrue(TechDocStyleGuide.contentDisciplineOf(DocType.CONCEPT).isNotEmpty())
    }

    // ═══ 文体规则 ═══

    @Test
    fun `long sentences are flagged`() {
        val longSentence = "这是一个特别特别长的句子".repeat(6) + "，包含了过多信息，让读者无法在一次阅读中抓住重点。"
        val issues = TechDocStyleGuide.checkStyle(longSentence)
        assertTrue(issues.any { it.kind == DocStyleIssue.Kind.SENTENCE_TOO_LONG })
    }

    @Test
    fun `filler words are flagged`() {
        val issues = TechDocStyleGuide.checkStyle("其实这个方案很简单。一般来说都能跑通。")
        assertTrue(issues.any { it.kind == DocStyleIssue.Kind.FILLER_WORDS })
        assertTrue(issues.all { it.repairable })
    }

    @Test
    fun `clean prose passes style check`() {
        val prose = "系统按事件驱动架构组织。事件总线负责分发。消费者按主题订阅。"
        assertTrue(TechDocStyleGuide.checkStyle(prose).isEmpty())
    }

    // ═══ 示例校验 ═══

    @Test
    fun `examples without language tag are flagged`() {
        val issues = TechDocStyleGuide.validateExamples("```\nval x = 1\n```")
        assertTrue(issues.any { it.kind == DocStyleIssue.Kind.EXAMPLE_NO_LANG })
    }

    @Test
    fun `examples with placeholders are flagged`() {
        val issues = TechDocStyleGuide.validateExamples("```kotlin\nval x = TODO(\"fill me\")\n```")
        assertTrue(issues.any { it.kind == DocStyleIssue.Kind.EXAMPLE_PLACEHOLDER })
    }

    @Test
    fun `good examples pass`() {
        val issues = TechDocStyleGuide.validateExamples("```kotlin\nval server = Server(port = 8080)\nserver.start()\n```")
        assertTrue(issues.isEmpty())
    }

    // ═══ 术语表 ═══

    @Test
    fun `undefined acronym is flagged`() {
        val doc = "系统由 MCP 总线连接。MCP 负责分发。"
        val issues = TechDocStyleGuide.undefinedTermIssues(doc)
        assertTrue(issues.isNotEmpty())
        assertTrue(issues.first().evidence == "MCP")
    }

    @Test
    fun `acronym defined inline passes`() {
        val doc = "系统由模型上下文协议（MCP）总线连接。MCP 负责分发。"
        val issues = TechDocStyleGuide.undefinedTermIssues(doc)
        // 「模型上下文协议（MCP）」括注在先 → 首次出现即有定义
        assertTrue(issues.isEmpty())
    }

    // ═══ 文档 lint ═══

    @Test
    fun `heading level jumps are detected`() {
        val doc = "# 标题一\n### 直接三级\n正文。"
        val jumps = TechDocStyleGuide.headingLevelJumps(doc)
        assertEquals(1, jumps.size)
        assertTrue(TechDocStyleGuide.lintDocument(doc).any { it.kind == DocStyleIssue.Kind.HEADING_LEVEL_JUMP })
    }

    @Test
    fun `duplicate headings are flagged`() {
        val doc = "## 安装\n内容。\n## 安装\n更多内容。"
        assertTrue(
            TechDocStyleGuide.lintDocument(doc).any { it.kind == DocStyleIssue.Kind.DUPLICATE_HEADING }
        )
    }

    @Test
    fun `empty section is flagged`() {
        val doc = "## 第一节\n\n## 第二节\n有内容。"
        assertTrue(
            TechDocStyleGuide.lintDocument(doc).any { it.kind == DocStyleIssue.Kind.EMPTY_SECTION }
        )
    }

    @Test
    fun `broken markdown link is flagged`() {
        val doc = "详见 [文档]( 与半边。"
        assertTrue(
            TechDocStyleGuide.lintDocument(doc).any { it.kind == DocStyleIssue.Kind.BROKEN_LINK }
        )
    }

    // ═══ 可读性评分 ═══

    @Test
    fun `readability score rewards balanced docs`() {
        val good = """
            # 概念文档

            ## 概述
            一句话讲清概念与场景。适用边界也说明。

            ## 背景
            现有方案有两个痛点。分别给出证据。

            ## 核心概念
            概念 A 与概念 B 的边界。

            ## 示例
            ```kotlin
            val engine = Engine()
            engine.run()
            ```

            ## 常见问题
            问题一与回答。
        """.trimIndent()
        val bad = "概述概述概述概述概述。" + "这是很长的没有结构没有示例的文档。".repeat(6)
        val goodScore = TechDocStyleGuide.scoreReadability(good, DocType.CONCEPT).score
        val badScore = TechDocStyleGuide.scoreReadability(bad, DocType.CONCEPT).score
        assertTrue("好文档应得分更高（$goodScore vs $badScore）", goodScore > badScore)
    }

    @Test
    fun `readability factors are reported`() {
        val report = TechDocStyleGuide.scoreReadability("# A\n\n正文一句话。\n", DocType.CONCEPT)
        assertTrue(report.factorScores.containsKey("sentenceLength"))
        assertTrue(report.factorScores.containsKey("exampleRatio"))
    }
}
