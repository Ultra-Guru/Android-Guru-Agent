package com.apex.agent.marketplace

import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 执行宿主使用的真实编排、ModelScopeSource、SkillRegistry 和文件 IO；仅 HTTP 边界为脚本响应。 */
class ModelScopeSkillInstallerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val skill = ModelScopeSource.ModelScopeSkill(
        "demo", "Demo", "test", "skills/demo/SKILL.md",
        listOf("skills/demo/SKILL.md", "skills/demo/references/api.md")
    )

    private fun source(failResource: Boolean = false): ModelScopeSource = ModelScopeSource(
        OkHttpClient.Builder().addInterceptor { chain ->
            val resource = chain.request().url.encodedPath.endsWith("api.md")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (failResource && resource) 404 else 200).message("fixture")
                .body((if (resource) "new resource" else "# New prompt").toResponseBody()).build()
        }.build()
    )

    private fun installer(
        dir: File, registry: SkillRegistry, failResource: Boolean = false,
        install: suspend (String, String?) -> Result<String>
    ) = ModelScopeSkillInstaller(dir, registry, source(failResource), install)

    @Test fun `rejection never deletes preexisting resources`() = runBlocking {
        val dir = tmp.newFolder()
        val old = File(dir, "ms-demo/references/api.md").apply { parentFile.mkdirs(); writeText("user data") }
        val registry = SkillRegistry(dir)
        val result = installer(dir, registry) { _, _ -> Result.failure(Exception("rejected")) }.install(skill)
        assertTrue(result.isFailure)
        assertTrue("preexisting resources must survive rejected install", old.exists())
        assertEquals("user data", old.readText())
        assertNull(registry.get("ms-demo"))
    }

    @Test fun `duplicate install preserves manifest and resources without confirmation`() = runBlocking {
        val dir = tmp.newFolder()
        val registry = SkillRegistry(dir)
        val original = """{"id":"ms-demo","name":"Original","version":"1","description":"old","promptInjection":"old prompt"}"""
        registry.install(original).getOrThrow()
        val old = File(dir, "ms-demo/references/api.md").apply { parentFile.mkdirs(); writeText("user data") }
        var called = false
        val result = installer(dir, registry) { content, _ ->
            called = true
            registry.install(content).map { "installed" }
        }.install(skill)
        assertTrue("duplicate must be rejected", result.isFailure)
        assertFalse(called)
        assertEquals("Original", registry.get("ms-demo")?.manifest?.name)
        assertEquals(original, File(dir, "ms-demo.json").readText())
        assertEquals("user data", old.readText())
    }

    @Test fun `resource download failure aborts instead of installing partial skill`() = runBlocking {
        val dir = tmp.newFolder()
        val registry = SkillRegistry(dir)
        var called = false
        val result = installer(dir, registry, failResource = true) { content, _ ->
            called = true
            registry.install(content).map { "installed" }
        }.install(skill)
        assertTrue("partial download must fail", result.isFailure)
        assertFalse(called)
        assertNull(registry.get("ms-demo"))
        assertFalse(File(dir, "ms-demo").exists())
    }

    @Test fun `coroutine cancellation preserves existing files and propagates`() = runBlocking {
        val dir = tmp.newFolder()
        val old = File(dir, "ms-demo/references/api.md").apply { parentFile.mkdirs(); writeText("user data") }
        val registry = SkillRegistry(dir)
        val cancellation = CancellationException("cancelled")
        try {
            installer(dir, registry) { _, _ -> throw cancellation }.install(skill)
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertTrue("preexisting resources must survive coroutine cancellation", old.exists())
        assertEquals("user data", old.readText())
        assertNull(registry.get("ms-demo"))
    }

    @Test fun `new rejected install leaves no resource directory`() = runBlocking {
        val dir = tmp.newFolder()
        val registry = SkillRegistry(dir)
        assertTrue(installer(dir, registry) { _, _ -> Result.failure(Exception("rejected")) }.install(skill).isFailure)
        assertFalse(File(dir, "ms-demo").exists())
        assertNull(registry.get("ms-demo"))
    }

    @Test fun `successful install publishes resources and preserves scope argument`() = runBlocking {
        val dir = tmp.newFolder()
        val registry = SkillRegistry(dir)
        val result = installer(dir, registry) { content, scope ->
            assertEquals("coding", scope)
            registry.install(content).map { "installed" }
        }.install(skill, "coding")
        assertTrue(result.isSuccess)
        assertEquals("new resource", File(dir, "ms-demo/references/api.md").readText())
        assertEquals("# New prompt", registry.get("ms-demo")?.manifest?.promptInjection)
    }
}
