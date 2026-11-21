package com.apex.agent.ui.screen.code

import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.ReasoningEffort
import com.apex.agent.ui.screen.settings.AgentSettings
import kotlinx.coroutines.flow.StateFlow

// ─────────────────────────────────────────────────────────────────────────────
// Coding 屏模型选择 / 界面设置暴露 —— CodeViewModel 的 internal 扩展
// （God-file 预算拆分，模式同 AgentChatModelSync.kt：VM 已开放
// settingsRepository（internal），本文件零侵入补齐「小大脑」数据面）
//
// 背景（用户反馈）：Coding 屏此前完全没有模型入口 —— 共享 LlmClient 恒用
// 默认 Profile，想换模型只能去设置页改默认，工位内无感知。
//
// 职责：
//  - codeProfiles / codeProviders / codeUiSettings：BrainMenuButton
//    （与 Agent 屏同款组件）的数据源 —— 三者直接转发 SettingsRepository 的
//    既有 StateFlow（同一实例，collectAsStateWithLifecycle 安全复用）；
//    当前 Profile id 由 CodeScreen 从 codeProfiles 派生（不走 stateIn ——
//    扩展属性无法缓存流实例，每次访问新建 Eagerly 流会在重组期反复重启
//    收集，见 AgentChatViewModel.currentProfileId 的 VM 内实现对照）；
//  - selectCodeModel / updateCodeModelParams：切换模型 + 采样参数调节。
//    生效链与 Agent 屏同构：setDefaultProfile + updateRoles →
//    DynamicLlmClient 监听 profiles 流重建委托（模型/密钥/端点/采样参数
//    全部随新 Profile 进请求，引擎温度走哨兵回退无需 patch）。
// ─────────────────────────────────────────────────────────────────────────────

/** 全部模型 Profile（「小大脑」菜单列表数据源；直接转发仓库流）。 */
internal val CodeViewModel.codeProfiles: StateFlow<List<ModelProfile>>
    get() = settingsRepository.profiles

/** 全部 Provider（模型列表展示 Provider 名）。 */
internal val CodeViewModel.codeProviders: StateFlow<List<ProviderConfig>>
    get() = settingsRepository.providers

/** 界面相关 Agent 设置（sendKeyBehavior 等 —— Coding 输入框 IME 行为数据源）。 */
internal val CodeViewModel.codeUiSettings: StateFlow<AgentSettings>
    get() = settingsRepository.agentSettings

/** 当前默认 Profile（点击时刻快照；UI 侧 id 从 codeProfiles 派生）。 */
private fun CodeViewModel.currentDefaultProfile(): ModelProfile? {
    val list = settingsRepository.profiles.value
    return list.firstOrNull { it.isDefault } ?: list.firstOrNull()
}

/**
 * Coding 屏切换当前模型：设默认 Profile + 同步角色映射（标准线
 * ModelRuntime 的 PRIMARY 解析链跟随）+ 原生 reasoning 强度对齐当前七档。
 *
 * 与 Agent 屏 selectProfile 的差异：code 引擎无 patchConfig 通道 —— 但
 * 引擎调用走温度哨兵（-1）回退 Profile 值，DynamicLlmClient 重建即全量
 * 生效，无需引擎侧 patch（Agent 屏的 patch 是当帧 UI 同相优化，非必需）。
 */
internal fun CodeViewModel.selectCodeModel(profileId: String) {
    val target = settingsRepository.getProfile(profileId) ?: return
    settingsRepository.setDefaultProfile(profileId)
    settingsRepository.updateRoles { copy(primaryProfileId = profileId) }
    // 原生 effort 对齐：新 Profile 可能带着旧档 effort（设置页手调残留），
    // 以当前七档映射覆盖，保证「所选即所得」——与 setThinkingLevel 的
    // upsert 通道一致（AUTO 档不动，实际档位由发送前预检决定）。
    val level = _uiState.value.thinkingLevel
    if (level != CodeThinkingLevel.AUTO) {
        val effort = level.toReasoningEffortName()
            ?.let { name -> runCatching { ReasoningEffort.valueOf(name) }.getOrNull() }
            ?: ReasoningEffort.NONE
        settingsRepository.upsertProfile(target.copy(reasoningEffort = effort))
    }
}

/** Coding 屏调节当前模型采样参数（Temperature / Top-P / Max Tokens）。 */
internal fun CodeViewModel.updateCodeModelParams(
    temperature: Float,
    topP: Float,
    maxTokens: Int
) {
    val cur = currentDefaultProfile() ?: return
    settingsRepository.upsertProfile(
        cur.copy(
            temperature = temperature,
            topP = topP,
            maxOutputTokens = maxTokens
        )
    )
}
