package com.apex.agent.platform.memory.ports

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.memkit.core.ChatModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 聊天蒸馏 LLM 端口 —— [ModelRuntime] → 库 [ChatModel] 的适配器。
 *
 * 路由沿用旧 MemoryDistiller 的口径：SUMMARY 角色（便宜模型）+ 低温
 * （0.1）+ 紧 token（700）—— 蒸馏是后台批处理，不值得主力模型。
 * 失败 / 空响应返回 null，库侧保留队列待重试。
 */
@Singleton
class ModelRuntimeChatModel @Inject constructor(
    private val modelRuntime: ModelRuntime,
) : ChatModel {

    override suspend fun complete(system: String, user: String): String? = runCatching {
        val response: LlmResponse = modelRuntime.chat(
            context = LlmRequestContext.summary("chat_memory_distill"),
            messages = listOf(
                LlmMessage.System(system),
                LlmMessage.User(user),
            ),
            temperature = 0.1f,
            maxTokens = 700,
        )
        response.content
    }.getOrNull()
}
