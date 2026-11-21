package com.apex.agent.ui.chatkit

import com.apex.agent.ui.screen.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 模型 API 是否已配置（#217 本地端点免鉴权）。
 *
 * #217：Ollama / LM Studio 等本地推理端点免鉴权——baseUrl 指向本机 / 内网
 *（或挂在内置本地 Provider ollama / lmstudio / vllm 下）时无需 API Key 即视为
 * 已配置，引导卡不再对本地用户误报（旧实现迫使用户伪造占位 Key 卡片才消失）。
 */
internal fun SettingsRepository.llmConfiguredFlow(scope: CoroutineScope): StateFlow<Boolean> =
    combine(profiles, providers) { _, _ -> defaultLlmConfig().isValid }
        // #277：Eagerly → WhileSubscribed(5000) —— 纯 UI 订阅消费
        //（聊天屏 collectAsStateWithLifecycle，无 .value
        // 命令式读点），引导卡不可见期间不占用常驻管道。
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), defaultLlmConfig().isValid)
