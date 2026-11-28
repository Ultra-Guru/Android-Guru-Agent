package com.apex.agent.core.engine.compression

import com.apex.agent.core.llm.ImageContent
import com.apex.agent.core.llm.LlmMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #827 — [TokenEstimator] 图片计费测试：携图消息不再是压缩门口径下的
 * 「零成本」（旧实现只计文本，图片驻留永久不可见）。
 */
class TokenEstimatorTest {

    private fun img() = ImageContent(base64Data = "AAAA", mimeType = "image/png")

    @Test
    fun `user message with only images is estimated above zero`() {
        val msg = LlmMessage.User(content = "", images = listOf(img()))
        assertEquals(
            "empty text + one image = exactly the per-image charge",
            TokenEstimator.IMAGE_TOKEN_ESTIMATE,
            TokenEstimator.estimateMessage(msg)
        )
    }

    @Test
    fun `image charge is additive with text estimate`() {
        val text = "看一下这张截图，帮我把按钮改成圆角。"
        val textOnly = TokenEstimator.estimateMessage(LlmMessage.User(text))
        val withTwoImages = TokenEstimator.estimateMessage(
            LlmMessage.User(text, listOf(img(), img()))
        )
        assertEquals(
            "text estimate + 2 x per-image charge",
            textOnly + 2 * TokenEstimator.IMAGE_TOKEN_ESTIMATE,
            withTwoImages
        )
        assertTrue(textOnly > 0)
    }

    @Test
    fun `image-bearing history outweighs identical text-only history`() {
        val text = "看图改代码"
        val base = listOf<LlmMessage.User>(LlmMessage.User(text))
        val withImage = listOf<LlmMessage.User>(LlmMessage.User(text, listOf(img())))
        assertTrue(
            TokenEstimator.estimateHistory(withImage) > TokenEstimator.estimateHistory(base)
        )
    }

    @Test
    fun `per-image charge is a conservative order of magnitude`() {
        // 1000/图：介于 GPT-4o 低清（~85）与高清（~1100+）/Claude（~1.1k+）
        // 之间偏保守 —— 只锁量级，不锁精确值（详见 IMAGE_TOKEN_ESTIMATE KDoc）。
        assertTrue(TokenEstimator.IMAGE_TOKEN_ESTIMATE in 500..1500)
    }
}
