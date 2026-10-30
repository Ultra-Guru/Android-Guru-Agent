package com.apex.agent.core.jiguan

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全场景记忆（RAG）+ Omni-Hook 引擎回归 —— fake 端口驱动，无平台依赖。
 */
class JiguanKnowledgeTest {

    private class FakeStore : JiguanKnowledgeStore {
        val entries = mutableListOf<AppKnowledge>()
        override suspend fun all(): List<AppKnowledge> = entries.toList()
        override suspend fun save(entry: AppKnowledge) {
            entries.removeAll { it.appPackage == entry.appPackage && it.title == entry.title }
            entries += entry
        }
        override suspend fun delete(appPackage: String, title: String) {
            entries.removeAll { it.appPackage == appPackage && it.title == title }
        }
    }

    @Test
    fun `relevanceScore gives package exact match top rank`() = runTest {
        val store = FakeStore()
        val knowledge = JiguanKnowledge(store, now = { 1000L })
        knowledge.remember("com.a.app", "key", "hook-signature", "content", "aes encrypt login")
        knowledge.remember("com.b.app", "other", "recipe", "content", "aes other")
        val hits = knowledge.query("anything", "com.a.app")
        assertEquals(1, hits.size)
        assertEquals("com.a.app", hits[0].appPackage)
    }

    @Test
    fun `query matches by keywords`() = runTest {
        val store = FakeStore()
        val knowledge = JiguanKnowledge(store, now = { 1000L })
        knowledge.remember("com.a.app", "t1", "hook-signature", "content", "aes encrypt login sign")
        knowledge.remember("com.b.app", "t2", "recipe", "content", "bitmap render")
        val hits = knowledge.query("how to crack aes login", null)
        assertTrue(hits.isNotEmpty())
        assertEquals("com.a.app", hits[0].appPackage)
    }

    @Test
    fun `remember is idempotent per package-title and merges`() = runTest {
        val store = FakeStore()
        val knowledge = JiguanKnowledge(store, now = { 1000L })
        knowledge.remember("com.a", "title", "recipe", "v1 content")
        knowledge.remember("com.a", "title", "recipe", "v2 content")
        assertEquals(1, store.entries.size)
        assertTrue(store.entries[0].content.contains("v2"))
    }

    @Test
    fun `markSuccess increments counter`() = runTest {
        val store = FakeStore()
        val knowledge = JiguanKnowledge(store, now = { 1000L })
        knowledge.remember("com.a", "title", "recipe", "content")
        knowledge.markSuccess("com.a", "title")
        knowledge.markSuccess("com.a", "title")
        assertEquals(2, store.entries.single().successCount)
    }

    @Test
    fun `profile lists per package`() = runTest {
        val store = FakeStore()
        val knowledge = JiguanKnowledge(store, now = { 1000L })
        knowledge.remember("com.a", "one", "recipe", "c")
        knowledge.remember("com.a", "two", "protocol", "c")
        knowledge.remember("com.b", "one", "recipe", "c")
        assertEquals(2, knowledge.profile("com.a").size)
    }

    @Test
    fun `auto keywords derive from content words`() = runTest {
        val store = FakeStore()
        val knowledge = JiguanKnowledge(store, now = { 1000L })
        val entry = knowledge.remember("com.a", "t", "recipe", "encrypt decrypt encrypt verify token")
        assertTrue(entry.keywords.contains("encrypt"))
    }
}

class OmniHookEngineTest {

    @Test
    fun `resolveChannel prefers frida server`() = runTest {
        val engine = OmniHookEngine(
            scriptRunner = { _, _, _ -> Result.success("") },
            fridaServerAvailable = { true },
            zygiskAvailable = { true },
        )
        assertEquals(HookChannel.FRIDA_SERVER, engine.resolveChannel())
    }

    @Test
    fun `resolveChannel falls back to zygisk then unavailable`() = runTest {
        val zygiskOnly = OmniHookEngine(
            scriptRunner = { _, _, _ -> Result.success("") },
            fridaServerAvailable = { false },
            zygiskAvailable = { true },
        )
        assertEquals(HookChannel.ZYGISK, zygiskOnly.resolveChannel())

        val none = OmniHookEngine(
            scriptRunner = { _, _, _ -> Result.success("") },
            fridaServerAvailable = { false },
        )
        assertEquals(HookChannel.UNAVAILABLE, none.resolveChannel())
    }

    @Test
    fun `install returns structured degradation when channel offline`() = runTest {
        val engine = OmniHookEngine(
            scriptRunner = { _, _, _ -> Result.success("should not run") },
            fridaServerAvailable = { false },
        )
        val handle = engine.install(HookSpec(HookKind.JAVA_METHOD, 1, "com.a.B", "m"))
        assertEquals(HookChannel.UNAVAILABLE, handle.channel)
        assertTrue(handle.events.single().contains("frida-server"))
    }

    @Test
    fun `install rejects invalid custom script before running`() = runTest {
        var runnerCalled = false
        val engine = OmniHookEngine(
            scriptRunner = { _, _, _ ->
                runnerCalled = true
                Result.success("")
            },
            fridaServerAvailable = { true },
        )
        val handle = engine.install(
            HookSpec(HookKind.JAVA_METHOD, 1, "com.a.B", "m", customScript = "broken {"),
        )
        assertTrue(handle.events.single().contains("SandboxValidator rejected"))
        assertEquals(HookChannel.UNAVAILABLE, handle.channel)
        assertEquals(false, runnerCalled)
    }

    @Test
    fun `install captures console output as events`() = runTest {
        val engine = OmniHookEngine(
            scriptRunner = { _, _, _ ->
                Result.success("[ok] hooked com.a.B.m overloads=2\n[hook] args=[\"x\"] ret=y\n")
            },
            fridaServerAvailable = { true },
        )
        val handle = engine.install(HookSpec(HookKind.JAVA_METHOD, 1, "com.a.B", "m"))
        assertEquals(HookChannel.FRIDA_SERVER, handle.channel)
        assertEquals(2, handle.events.size)
        assertTrue(handle.events[0].contains("[ok]"))
        // 登记可枚举 / 可卸载。
        assertEquals(1, engine.installed().size)
        assertTrue(engine.uninstall(handle.id))
        assertTrue(engine.installed().isEmpty())
    }

    @Test
    fun `script runner failure becomes event not exception`() = runTest {
        val engine = OmniHookEngine(
            scriptRunner = { _, _, _ -> Result.failure(IllegalStateException("boom")) },
            fridaServerAvailable = { true },
        )
        val handle = engine.install(HookSpec(HookKind.NATIVE_FUNC, 1, className = "libx.so", symbolName = "sym"))
        assertTrue(handle.events.single().contains("[err]"))
    }
}
