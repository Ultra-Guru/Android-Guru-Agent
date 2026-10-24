package com.apex.agent.core.tools.claudeimport

import com.apex.agent.core.tools.fusion.FusionBundleManifest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * # Claude 插件包导入器测试
 *
 * 覆盖：.claude-plugin 元数据解析 / SKILL.md frontmatter + 正文映射
 * （kebab → PascalCase 命名归一）/ commands → plugins[] 斜杠指令搭载 /
 * agents → 人设 / 嵌套一层目录（GitHub zip 形态）/ 空包拒绝 /
 * frontmatter 解析边界（无围栏 / 引号 / 列表键忽略）。
 *
 * 纯 JVM（JUnit4 + TemporaryFolder，手写文件 fixtures，无 mock 框架）。
 */
class ClaudePluginImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 标准三段齐全的 Claude 插件包目录。 */
    private fun writeFullPackage(root: File) {
        val pkg = File(root, "my-plugin").apply { mkdirs() }
        File(pkg, ".claude-plugin").apply { mkdirs() }
        File(pkg, ".claude-plugin/plugin.json").writeText(
            """
            {
              "name": "my-plugin",
              "version": "2.1.0",
              "description": "Test plugin for importer",
              "author": {"name": "someone"},
              "license": "MIT"
            }
            """.trimIndent()
        )
        File(pkg, "skills/pdf-generation").apply { mkdirs() }
        File(pkg, "skills/pdf-generation/SKILL.md").writeText(
            """
            ---
            name: pdf-generation
            description: "Generate PDF documents from markdown"
            ---
            # PDF Generation
            Follow these steps to generate PDFs.
            """.trimIndent()
        )
        File(pkg, "skills/data_analysis").apply { mkdirs() }
        File(pkg, "skills/data_analysis/SKILL.md").writeText(
            """
            ---
            name: data_analysis
            description: Analyze datasets
            ---
            Analyze the dataset step by step.
            """.trimIndent()
        )
        File(pkg, "commands").apply { mkdirs() }
        File(pkg, "commands/build-report.md").writeText(
            """
            ---
            description: Build a report
            argument-hint: [topic]
            ---
            Create a report about ${'$'}ARGUMENTS.
            """.trimIndent()
        )
        File(pkg, "agents").apply { mkdirs() }
        File(pkg, "agents/researcher.md").writeText(
            """
            ---
            description: Deep researcher
            ---
            You are a thorough researcher.
            """.trimIndent()
        )
    }

    @Test
    fun `三段齐全的包完整导入且命名归一`() {
        val imported = ClaudePluginImporter.importFromDir(tmp.newFolder("pkg1").also(::writeFullPackage))
        val m = imported.manifest

        assertEquals("claude-my-plugin", m.id)
        assertEquals("my-plugin", m.name)
        assertEquals("2.1.0", m.version)
        // kebab / snake 技能名全部归一 PascalCase（v5 规范；按 id 稳定排序）
        assertEquals(listOf("DataAnalysis", "PdfGeneration"), m.skills.map { it.skillId })
        assertEquals("PdfGeneration", m.skills[1].manifest.id)
        assertEquals("Generate PDF documents from markdown", m.skills[1].manifest.description)
        assertEquals(
            "# PDF Generation\nFollow these steps to generate PDFs.",
            m.skills[1].manifest.promptInjection
        )
        // commands 搭载进 plugins[] 段
        assertEquals(1, m.plugins.size)
        assertEquals("claude.my-plugin.commands", m.plugins[0].pluginId)
        // agents → 人设
        assertEquals("Deep researcher", m.agent.persona)
        assertTrue(m.agent.activationPrompt.contains("thorough researcher"))
        // 产物 JSON 可被聚合注册表接受（parse 全防线通过）
        assertNotNull(FusionBundleManifest.parse(imported.manifestJson))
        assertEquals(imported.manifest.id, FusionBundleManifest.parse(imported.manifestJson)!!.id)
    }

    @Test
    fun `GitHub zip 一层包裹目录被自动定位`() {
        val root = tmp.newFolder("pkg2")
        writeFullPackage(root) // root/my-plugin/.claude-plugin/...
        val imported = ClaudePluginImporter.importFromDir(root)
        assertEquals("claude-my-plugin", imported.manifest.id)
    }

    @Test
    fun `无任何可导入内容的包被拒绝`() {
        val root = tmp.newFolder("pkg3")
        val pkg = File(root, "empty-plugin").apply { mkdirs() }
        File(pkg, ".claude-plugin").apply { mkdirs() }
        File(pkg, ".claude-plugin/plugin.json").writeText("""{"name": "empty-plugin"}""")
        try {
            ClaudePluginImporter.importFromDir(root)
            throw AssertionError("空包必须被拒绝")
        } catch (e: ClaudePluginImporter.ImportException) {
            assertTrue(e.message!!.contains("无 skills/commands/agents"))
        }
    }

    @Test
    fun `非 Claude 包结构被拒绝`() {
        try {
            ClaudePluginImporter.importFromDir(tmp.newFolder("pkg4"))
            throw AssertionError("无 .claude-plugin 的目录必须被拒绝")
        } catch (e: ClaudePluginImporter.ImportException) {
            assertTrue(e.message!!.contains(".claude-plugin"))
        }
    }

    @Test
    fun `frontmatter 解析边界`() {
        // 无围栏 → 全文正文
        val plain = "Just body text.".toClaudeDocOrThrow()
        assertEquals(emptyMap<String, String>(), plain.frontmatter)
        assertEquals("Just body text.", plain.body)
        // 引号剥除 + 列表键忽略
        val doc = """
            ---
            name: "quoted-name"
            description: 'single quoted'
            tags:
              - one
              - two
            ---
            Body here.
        """.trimIndent().toClaudeDocOrThrow()
        assertEquals("quoted-name", doc.frontmatter["name"])
        assertEquals("single quoted", doc.frontmatter["description"])
        assertNull(doc.frontmatter["tags"]) // 列表形态不认、忽略
        assertEquals("Body here.", doc.body)
        // 有开头无闭合围栏 → null（坏文档拒绝）
        assertNull("---\nname: broken".toClaudeDocOrNull())
    }

    @Test
    fun `SKILL.md 缺 name 回退目录名`() {
        val root = tmp.newFolder("pkg5")
        val pkg = File(root, "fallback").apply { mkdirs() }
        File(pkg, ".claude-plugin/plugin.json").writeText("""{"name": "fallback"}""")
        File(pkg, "skills/some-dir").apply { mkdirs() }
        File(pkg, "skills/some-dir/SKILL.md").writeText(
            "---\ndescription: No name field\n---\nDo things."
        )
        val m = ClaudePluginImporter.importFromDir(root).manifest
        assertEquals(listOf("SomeDir"), m.skills.map { it.skillId })
    }

    // ── 测试辅助（把 String.toClaudeDoc 的 null 折成断言友好形态）──

    private fun String.toClaudeDocOrNull(): ClaudePluginImporter.ClaudeDoc? =
        runCatching { ClaudePluginImporter.run { this@toClaudeDocOrNull.toClaudeDoc() } }.getOrNull()

    private fun String.toClaudeDocOrThrow(): ClaudePluginImporter.ClaudeDoc =
        ClaudePluginImporter.run { this@toClaudeDocOrThrow.toClaudeDoc() }
        ?: throw AssertionError("文档应可解析")
}
