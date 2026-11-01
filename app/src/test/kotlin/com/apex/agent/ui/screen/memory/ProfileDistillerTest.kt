package com.apex.agent.ui.screen.memory

import com.apex.agent.ui.screen.code.session.CodeSessionSnapshot
import com.apex.agent.ui.screen.code.session.CodeSessionStore
import com.apex.agent.ui.screen.code.session.StorableCodeMessage
import com.apex.agent.ui.screen.memory.profile.ProfileDistiller
import com.apex.agent.ui.screen.memory.profile.UserProfileStore
import com.apex.memkit.core.ChatModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 用户画像蒸馏器单测（手写 fake ChatModel，无 mock 框架）。
 *
 * 覆盖：无快照 noSignal / skip 响应 noSignal / LLM 空响应 noSignal /
 * 围栏与废话容错 / 正常动作落账（经真实 UserProfileStore）/ TOOL 消息
 * 入转写、SYSTEM 消息被滤除。
 */
class ProfileDistillerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 可编程 fake：构造时给定响应（null = 模拟空响应）。 */
    private class FakeChatModel(var response: String?) : ChatModel {
        var lastSystem: String? = null
        var lastUser: String? = null
        var calls = 0

        override suspend fun complete(system: String, user: String): String? {
            calls++
            lastSystem = system
            lastUser = user
            return response
        }
    }

    private fun sessionStoreWith(vararg messages: StorableCodeMessage): CodeSessionStore {
        val store = CodeSessionStore(tmp.newFolder())
        store.save(
            CodeSessionSnapshot(
                workspaceId = "ws-test",
                messages = messages.toList(),
                updatedAt = 1000L,
            )
        )
        return store
    }

    private fun user(text: String) = StorableCodeMessage(role = "USER", text = text)
    private fun assistant(text: String) = StorableCodeMessage(role = "ASSISTANT", text = text)
    private fun tool(name: String) =
        StorableCodeMessage(role = "TOOL", text = "ignored", toolName = name, toolSuccess = true)

    @Test
    fun `no snapshots yields noSignal without calling model`() = runTest {
        val chat = FakeChatModel("{\"actions\":[]}")
        val distiller = ProfileDistiller(
            chat, UserProfileStore(tmp.newFolder()), CodeSessionStore(tmp.newFolder())
        )
        val report = distiller.distill(now = 1L)
        assertTrue(report.noSignal)
        assertEquals(0, chat.calls)
    }

    @Test
    fun `skip payload yields noSignal`() = runTest {
        val chat = FakeChatModel("{\"skip\":true}")
        val distiller = ProfileDistiller(
            chat, UserProfileStore(tmp.newFolder()), sessionStoreWith(user("帮我写 Kotlin"))
        )
        val report = distiller.distill(now = 1L)
        assertTrue(report.noSignal)
        assertEquals(1, chat.calls)
    }

    @Test
    fun `blank model response yields noSignal`() = runTest {
        val chat = FakeChatModel(null)
        val distiller = ProfileDistiller(
            chat, UserProfileStore(tmp.newFolder()), sessionStoreWith(user("帮我写 Kotlin"))
        )
        assertTrue(distiller.distill(now = 1L).noSignal)
    }

    @Test
    fun `fenced and prose-wrapped payload is tolerated`() = runTest {
        val chat = FakeChatModel(
            "好的，提炼结果：\n```json\n" +
                "{\"actions\":[{\"op\":\"new\",\"aspect\":\"tech-stack\",\"content\":\"主用 Kotlin\",\"confidence\":0.9,\"evidence\":\"要求 Kotlin 示例\"}]}\n" +
                "```"
        )
        val store = UserProfileStore(tmp.newFolder())
        val distiller = ProfileDistiller(chat, store, sessionStoreWith(user("用 Kotlin 写一个示例")))
        val report = distiller.distill(now = 5000L)
        assertEquals(1, report.added)
        assertEquals("主用 Kotlin", store.list().single().content)
    }

    @Test
    fun `distill applies actions through real store`() = runTest {
        val chat = FakeChatModel(
            "{\"actions\":[" +
                "{\"op\":\"new\",\"aspect\":\"coding-style\",\"content\":\"偏好显式类型\",\"confidence\":0.85," +
                "\"evidence\":\"类型标注讨论\"}," +
                "{\"op\":\"new\",\"aspect\":\"bogus\",\"content\":\"未知维度应跳过\"}" +
                "]}"
        )
        val store = UserProfileStore(tmp.newFolder())
        val distiller = ProfileDistiller(
            chat, store, sessionStoreWith(user("这段代码补上类型标注"), assistant("已显式标注"))
        )
        val report = distiller.distill(now = 7000L)
        assertEquals(1, report.added)
        assertEquals(1, report.skipped)
        assertEquals("偏好显式类型", store.list().single().content)
    }

    @Test
    fun `transcript includes tool signal and filters system noise`() = runTest {
        var capturedUser: String? = null
        val chat = object : ChatModel {
            override suspend fun complete(system: String, user: String): String? {
                capturedUser = user
                return "{\"skip\":true}"
            }
        }
        val distiller = ProfileDistiller(
            chat,
            UserProfileStore(tmp.newFolder()),
            sessionStoreWith(
                StorableCodeMessage(role = "SYSTEM", text = "系统噪声不入转写"),
                user("用 Gradle 构建"),
                tool("gradle_build"),
            )
        )
        distiller.distill(now = 1L)
        val transcript = capturedUser.orEmpty()
        assertTrue(transcript.contains("用户：用 Gradle 构建"))
        assertTrue(transcript.contains("（工具：gradle_build 成功）"))
        assertTrue(!transcript.contains("系统噪声"))
    }
}
