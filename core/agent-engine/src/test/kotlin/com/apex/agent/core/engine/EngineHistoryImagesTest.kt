package com.apex.agent.core.engine

import com.apex.agent.core.engine.compression.HistoryImageEvictor
import com.apex.agent.core.engine.orchestrator.FakeLlmClient
import com.apex.agent.core.engine.orchestrator.FakeToolExecutor
import com.apex.agent.core.engine.orchestrator.FakeToolRegistry
import com.apex.agent.core.llm.ImageContent
import com.apex.agent.core.llm.LlmMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #827 — 引擎级历史图片退场接线测试（buildMessages 每轮组装前收敛）。
 *
 * 场景：连续 4 个带图任务（每任务单轮文本收尾）。keep-last-K=2：
 * - 第 3 个携图轮进场后，第 1 个携图轮退场（占位替换，原文保留）；
 * - 第 4 个携图轮进场后，第 2 个携图轮退场；
 * - 最终请求只携带最近 2 轮的图片（旧图不再每轮重传/重计费）。
 */
class EngineHistoryImagesTest {

    private fun imageInput(text: String) = UserInput(
        text = text,
        images = listOf(ImageContent(base64Data = "AAAA", mimeType = "image/png"))
    )

    @Test
    fun `aged history images are evicted before request assembly`() = runBlocking {
        val fake = FakeLlmClient(
            responses = (1..4).map { FakeLlmClient.ScriptedResponse.Ok(content = "回复$it") }
        )
        val engine = ApexAgentEngine(
            llmClient = fake,
            toolRegistry = FakeToolRegistry(),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.BUILD, maxIterations = 2)
        )
        repeat(4) { i ->
            engine.execute(imageInput("第${i + 1}个带图任务")).toList()
        }

        // 历史：第 1、2 个携图任务退场（占位 + 原文保留），最近 2 轮保留图片。
        val users = engine.conversationHistory.filterIsInstance<LlmMessage.User>()
        assertEquals(4, users.size)
        assertTrue(
            "oldest image turn must be stripped, got ${users[0]}",
            users[0].images.isEmpty()
        )
        assertTrue(users[0].content.contains("第1个带图任务"))
        assertTrue(users[0].content.contains(HistoryImageEvictor.PLACEHOLDER))
        assertTrue(users[1].images.isEmpty())
        assertTrue(users[1].content.contains("第2个带图任务"))
        assertTrue(users[1].content.contains(HistoryImageEvictor.PLACEHOLDER))
        assertEquals(1, users[2].images.size)
        assertFalse(users[2].content.contains(HistoryImageEvictor.PLACEHOLDER))
        assertEquals(1, users[3].images.size)

        // 最后一次请求只携带最近 2 轮的图片（旧图零重传）。
        val lastRequestImages = fake.callLog.last().first
            .filterIsInstance<LlmMessage.User>()
            .sumOf { it.images.size }
        assertEquals(
            "outgoing request must carry only the last-K image turns",
            HistoryImageEvictor.DEFAULT_KEEP_LAST_K,
            lastRequestImages
        )
    }
}
