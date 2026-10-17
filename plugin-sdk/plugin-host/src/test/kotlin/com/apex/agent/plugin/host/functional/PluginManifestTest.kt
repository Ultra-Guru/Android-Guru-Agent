package com.apex.agent.plugin.host.functional

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginManifest] 宽松解析契约测试（Plugin System v2）。
 *
 * 锁死三条纪律：
 * 1. 未来字段向前兼容（ignoreUnknownKeys）——市场 / 热更下发的新 schema
 *    字段不炸旧宿主解析；
 * 2. 非法 JSON → null（调用方折成单插件 BROKEN 隔离，绝不向上抛）；
 * 3. 展示字段的回退链（name→id / versionName→version / kind→FUNCTIONAL）。
 */
class PluginManifestTest {

    private val fullManifest = """
        {
          "id": "functional.daily-briefing",
          "kind": "functional",
          "name": "每日简报",
          "version": 3,
          "versionName": "1.2.0",
          "minHostVersion": 1,
          "description": "组合浏览器与通知工具生成每日简报",
          "author": "Apex",
          "tags": ["productivity", "report"],
          "scope": "agent",
          "futureField": {"anything": true},
          "contributions": {
            "tools": [{
              "id": "briefing/run",
              "name": "Run Briefing",
              "description": "生成今日简报",
              "parametersSchema": "{\"type\":\"object\",\"properties\":{\"site\":{\"type\":\"string\"}}}",
              "steps": [
                {"tool": "BrowserNavigate", "args": {"url": "{{input.site}}"}, "output": "page"},
                {"tool": "BrowserExtractContent", "output": "body"}
              ]
            }],
            "slashCommands": [{
              "id": "briefing",
              "title": "每日简报",
              "description": "生成今日简报",
              "prompt": "使用 briefing/run 工具生成今日简报"
            }],
            "prompts": [{"id": "guide", "text": "简报输出保持精炼"}],
            "futureContribution": []
          }
        }
    """.trimIndent()

    @Test
    fun `完整 manifest 全字段解析`() {
        val manifest = PluginManifest.parseOrNull(fullManifest)

        assertNotNull(manifest)
        manifest!!
        assertEquals("functional.daily-briefing", manifest.id)
        assertEquals("每日简报", manifest.name)
        assertEquals(3, manifest.version)
        assertEquals("1.2.0", manifest.versionName)
        assertEquals(1, manifest.minHostVersion)
        assertEquals("agent", manifest.scope)
        assertEquals(listOf("productivity", "report"), manifest.tags)
        assertEquals("briefing/run", manifest.contributions.tools.single().id)
        assertEquals(2, manifest.contributions.tools.single().steps.size)
        assertEquals("BrowserNavigate", manifest.contributions.tools.single().steps[0].tool)
        assertEquals("{{input.site}}", manifest.contributions.tools.single().steps[0].args!!["url"]!!.jsonPrimitive.content)
        assertEquals("page", manifest.contributions.tools.single().steps[0].output)
        assertEquals("briefing", manifest.contributions.slashCommands.single().id)
        assertEquals("简报输出保持精炼", manifest.contributions.prompts.single().text)
    }

    @Test
    fun `未知字段宽松忽略不炸解析`() {
        // 未来 schema 演进的第一道防线：市场 / 热更下发的新字段（顶层与
        // contributions 内）不得破坏旧宿主。
        val manifest = PluginManifest.parseOrNull(fullManifest)

        assertNotNull(manifest)
    }

    @Test
    fun `非法 JSON 返回 null 不上抛`() {
        assertNull(PluginManifest.parseOrNull("not-json"))
        assertNull(PluginManifest.parseOrNull(""))
        assertNull(PluginManifest.parseOrNull("{broken"))
        // 数组顶层不是合法 manifest 形状。
        assertNull(PluginManifest.parseOrNull("[1,2,3]"))
    }

    @Test
    fun `字段缺省回退链`() {
        val manifest = PluginManifest.parseOrNull("""{"id":"functional.min"}""")

        assertNotNull(manifest)
        manifest!!
        assertEquals("functional.min", manifest.displayName)
        assertEquals("1", manifest.displayVersion)
        assertEquals(1, manifest.minHostVersion)
        assertEquals("", manifest.description)
        assertTrue(manifest.tags.isEmpty())
        assertTrue(manifest.contributions.tools.isEmpty())
        assertTrue(manifest.contributions.slashCommands.isEmpty())
    }

    @Test
    fun `kind 缺省与未知值都归一为 FUNCTIONAL`() {
        assertEquals(
            com.apex.agent.plugin.api.PluginKind.FUNCTIONAL,
            PluginManifest.parseOrNull("""{"id":"a.b"}""")?.resolvedKind
        )
        assertEquals(
            com.apex.agent.plugin.api.PluginKind.FUNCTIONAL,
            PluginManifest.parseOrNull("""{"id":"a.b","kind":"whatever"}""")?.resolvedKind
        )
        assertEquals(
            com.apex.agent.plugin.api.PluginKind.APK,
            PluginManifest.parseOrNull("""{"id":"a.b","kind":"APK"}""")?.resolvedKind
        )
    }

    @Test
    fun `name 空白回退 id 永不空标题`() {
        val manifest = PluginManifest.parseOrNull("""{"id":"a.b","name":"   "}""")

        assertEquals("a.b", manifest?.displayName)
    }
}
