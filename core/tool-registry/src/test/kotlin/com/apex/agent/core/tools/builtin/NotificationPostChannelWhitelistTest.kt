package com.apex.agent.core.tools.builtin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #354 ⑥ 回归：通知渠道白名单。
 *
 * 旧实现把模型给的 `channel_id` 直接传给 `NotificationManager`，宿主在渠道
 * 不存在时自动创建 —— 模型可以无限批量创建系统渠道，而 Android **只允许用户
 * 手动逐个删除**，这是不可逆的系统状态污染。
 *
 * 这些用例锁定：越界渠道在**调用宿主之前**就被拒绝，且失败信息里带可用清单
 * （模型能一次纠正成功，而不是撞墙重试）。
 */
class NotificationPostChannelWhitelistTest {

    private class Poster {
        val posted = mutableListOf<String>()
        suspend fun post(params: NotificationPostTool.NotificationParams): Boolean {
            posted += params.channelId
            return true
        }
    }

    private fun tool(
        poster: Poster,
        allowed: Set<String> = setOf("apex_general", "apex_task_done")
    ) = NotificationPostTool(poster = { poster.post(it) }, allowedChannelIds = allowed)

    private fun args(channel: String? = null): String = buildString {
        append("""{"title":"t","text":"body"""")
        if (channel != null) append(""","channel_id":"""").append(channel).append('"')
        append('}')
    }

    @Test
    fun `白名单内渠道正常投递`() = runBlocking {
        val poster = Poster()
        val result = tool(poster).executeStructured(args("apex_task_done"))
        assertTrue(result.isSuccess)
        assertEquals(listOf("apex_task_done"), poster.posted)
    }

    @Test
    fun `省略渠道时用默认渠道`() = runBlocking {
        val poster = Poster()
        val result = tool(poster).executeStructured(args())
        assertTrue(result.isSuccess)
        assertEquals(listOf(NotificationPostTool.DEFAULT_CHANNEL), poster.posted)
    }

    @Test
    fun `越界渠道在调用宿主前被拒绝`() = runBlocking {
        val poster = Poster()
        val result = tool(poster).executeStructured(args("agent_spam_1"))
        assertFalse(result.isSuccess)
        // 关键：宿主从未被调用 —— 渠道不会被自动创建。
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun `拒绝信息列出可用渠道`() = runBlocking {
        val poster = Poster()
        val result = tool(poster).executeStructured(args("agent_spam_2"))
        val text = result.render()
        assertTrue(text.contains("unsupported channel"))
        assertTrue(text.contains("apex_general"))
        assertTrue(text.contains("apex_task_done"))
    }

    @Test
    fun `默认白名单只含默认渠道`() = runBlocking {
        val poster = Poster()
        val defaultTool = NotificationPostTool(poster = { poster.post(it) })
        val rejected = defaultTool.executeStructured(args("apex_task_done"))
        assertFalse(rejected.isSuccess)
        assertTrue(poster.posted.isEmpty())

        val accepted = defaultTool.executeStructured(args())
        assertTrue(accepted.isSuccess)
    }
}
