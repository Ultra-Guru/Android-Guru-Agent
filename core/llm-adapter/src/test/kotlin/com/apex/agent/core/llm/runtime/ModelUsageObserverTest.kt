package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.Usage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * #221 回归：用量必须在**执行层**按真实 profile/model 上报。
 *
 * 旧口径由 UI 在 `AgentEvent.UsageUpdated` 到达时取「当前默认 Profile」记账：
 * 角色路由（VISION/SUMMARY）的消耗记到默认模型头上，子代理与压缩摘要这类
 * 不经主事件流的请求完全不入账。这些用例锁定新的观测契约。
 */
class ModelUsageObserverTest {

    private class RecordingObserver : ModelUsageObserver {
        data class Call(val role: String, val profileId: String, val modelId: String, val usage: Usage?)
        val calls = mutableListOf<Call>()

        override fun onUsage(
            requestId: String,
            role: String,
            profileId: String,
            modelId: String,
            usage: Usage?
        ) {
            calls += Call(role, profileId, modelId, usage)
        }
    }

    private val provider = ProviderConfig(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKeys = listOf("sk-x"),
        capabilities = ModelCapabilities(
            text = true, vision = true, imageInput = true, toolCalling = true, reasoning = true
        )
    )

    private fun profile(id: String, isDefault: Boolean = false) = ModelProfile(
        id = id,
        name = id,
        providerId = "openai",
        modelId = "m-$id",
        capabilities = ModelCapabilities(text = true),
        isDefault = isDefault
    )

    private val msgs = listOf(LlmMessage.User("hi"))

    @Test
    fun `非流式按实际模型上报用量`() = runBlocking {
        val observer = RecordingObserver()
        val usage = Usage(120, 30, 150, 0)
        val client = FakeLlmClient(responses = listOf(LlmResponse(content = "ok", usage = usage)))
        val store = FakeModelRuntimeStore(
            listOf(profile("p", isDefault = true)),
            listOf(provider),
            ModelRoleConfig(primaryProfileId = "p")
        )
        val runtime = DefaultModelRuntime(
            ModelRoleRouter(store),
            ModelRuntimeRegistry(clientFactory = { client }),
            usageObserver = observer
        )

        runtime.chat(LlmRequestContext.primary("test"), msgs)

        assertEquals(1, observer.calls.size)
        assertEquals("m-p", observer.calls[0].modelId)
        assertEquals("p", observer.calls[0].profileId)
        assertEquals("PRIMARY", observer.calls[0].role)
        assertEquals(usage, observer.calls[0].usage)
    }

    @Test
    fun `端点未返回用量时上报空值而不是伪造零`() = runBlocking {
        val observer = RecordingObserver()
        val client = FakeLlmClient(responses = listOf(LlmResponse(content = "ok", usage = null)))
        val store = FakeModelRuntimeStore(
            listOf(profile("p", isDefault = true)),
            listOf(provider),
            ModelRoleConfig(primaryProfileId = "p")
        )
        val runtime = DefaultModelRuntime(
            ModelRoleRouter(store),
            ModelRuntimeRegistry(clientFactory = { client }),
            usageObserver = observer
        )

        runtime.chat(LlmRequestContext.primary("test"), msgs)

        assertEquals(1, observer.calls.size)
        assertNull(observer.calls[0].usage)
    }

    @Test
    fun `流式多帧用量只上报一次且取末帧`() = runBlocking {
        val observer = RecordingObserver()
        val client = FakeLlmClient(
            streamResponses = listOf(
                listOf(
                    LlmStreamChunk(content = "a", usage = Usage(10, 1, 11, 0)),
                    LlmStreamChunk(content = "b", usage = Usage(10, 2, 12, 0)),
                    LlmStreamChunk(isFinish = true, usage = Usage(10, 3, 13, 0))
                )
            )
        )
        val store = FakeModelRuntimeStore(
            listOf(profile("p", isDefault = true)),
            listOf(provider),
            ModelRoleConfig(primaryProfileId = "p")
        )
        val runtime = DefaultModelRuntime(
            ModelRoleRouter(store),
            ModelRuntimeRegistry(clientFactory = { client }),
            usageObserver = observer
        )

        runtime.chatStream(LlmRequestContext.primary("test"), msgs).toList()

        assertEquals(1, observer.calls.size)
        assertEquals(Usage(10, 3, 13, 0), observer.calls[0].usage)
    }

    @Test
    fun `降级到备用模型后按备用模型上报`() = runBlocking {
        val observer = RecordingObserver()
        val primaryClient = FakeLlmClient(responses = listOf(IOException("primary down")))
        val backupClient = FakeLlmClient(
            responses = listOf(LlmResponse(content = "ok", usage = Usage(7, 8, 15, 0)))
        )
        // SUMMARY 链首 = "p"（失败），fallback = "p2"（默认，成功）。
        val store = FakeModelRuntimeStore(
            listOf(profile("p"), profile("p2", isDefault = true)),
            listOf(provider),
            ModelRoleConfig(primaryProfileId = "p2", summaryProfileId = "p")
        )
        val registry = ModelRuntimeRegistry(clientFactory = { config ->
            if (config.model == "m-p") primaryClient else backupClient
        })
        val runtime = DefaultModelRuntime(
            ModelRoleRouter(store), registry, usageObserver = observer
        )

        runtime.chat(LlmRequestContext.summary("test"), msgs)

        assertEquals(1, observer.calls.size)
        assertEquals("m-p2", observer.calls[0].modelId)
        assertEquals("SUMMARY", observer.calls[0].role)
    }
}
