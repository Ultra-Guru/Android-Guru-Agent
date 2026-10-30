package com.apex.agent.tools

import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.tools.builtin.AgentRoleConfigSnapshot
import com.apex.agent.core.tools.builtin.AgentSettingsHost
import com.apex.agent.core.tools.builtin.AgentSettingsPatch
import com.apex.agent.core.tools.builtin.AgentSettingsSnapshot
import com.apex.agent.core.tools.builtin.ModelProfileSummary
import com.apex.agent.ui.screen.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AgentSettingsHost] 的 Android 实现。
 *
 * 桥接 [SettingsRepository] 的运行时 Settings StateFlow —— 读取快照
 * 由 [AgentSettings] → [AgentSettingsSnapshot] 转换；写入补丁
 * 由 [AgentSettingsPatch] → [AgentSettings.update{}] 转换。
 *
 * **安全契约**：仅暴露 [AgentSettingsPatch] 中显式声明的字段，未知字段
 * （如 `permissionMode` / `permissionRules` / `mcpScopeIsolation` 等安全
 * 字段）永不被工具修改。
 */
class AgentSetupHost(
    private val settingsRepository: SettingsRepository
) : AgentSettingsHost {

    override suspend fun snapshot(): AgentSettingsSnapshot = withContext(Dispatchers.IO) {
        val s = settingsRepository.agentSettings.value
        AgentSettingsSnapshot(
            codeThinkingLogic = s.codeThinkingLogic,
            codeThinkingLevel = s.codeThinkingLevel,
            codeExecutionMode = s.codeExecutionMode,
            keepAlive = s.keepAlive,
            taskCompletionNotify = s.taskCompletionNotify,
            maxContextTokens = s.maxContextTokens,
            compressionThreshold = s.compressionThreshold,
            preserveRecentTurns = s.preserveRecentTurns,
            maxToolOutputLength = s.maxToolOutputLength,
            themeMode = s.themeMode,
            accentPalette = s.accentPalette,
            fontScale = s.fontScale,
            showTimestamps = s.showTimestamps,
            language = s.language,
            sendKeyBehavior = s.sendKeyBehavior
        )
    }

    override suspend fun apply(patch: AgentSettingsPatch): Boolean = withContext(Dispatchers.IO) {
        try {
            settingsRepository.updateAgentSettings {
                copy(
                    codeThinkingLogic = patch.codeThinkingLogic ?: codeThinkingLogic,
                    codeThinkingLevel = patch.codeThinkingLevel ?: codeThinkingLevel,
                    codeExecutionMode = patch.codeExecutionMode ?: codeExecutionMode,
                    keepAlive = patch.keepAlive ?: keepAlive,
                    taskCompletionNotify = patch.taskCompletionNotify ?: taskCompletionNotify,
                    maxContextTokens = patch.maxContextTokens ?: maxContextTokens,
                    compressionThreshold = patch.compressionThreshold ?: compressionThreshold,
                    preserveRecentTurns = patch.preserveRecentTurns ?: preserveRecentTurns,
                    maxToolOutputLength = patch.maxToolOutputLength ?: maxToolOutputLength,
                    themeMode = patch.themeMode ?: themeMode,
                    accentPalette = patch.accentPalette ?: accentPalette,
                    fontScale = patch.fontScale ?: fontScale,
                    showTimestamps = patch.showTimestamps ?: showTimestamps,
                    language = patch.language ?: language,
                    sendKeyBehavior = patch.sendKeyBehavior ?: sendKeyBehavior
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 列出模型 Profile 摘要——从 [SettingsRepository.profiles] 转换。
 */
suspend fun SettingsRepository.listProfileSummaries(): List<ModelProfileSummary> =
    withContext(Dispatchers.IO) {
        profiles.value.map { p: ModelProfile ->
            ModelProfileSummary(
                id = p.id,
                name = p.name,
                providerId = p.providerId,
                modelId = p.modelId,
                isDefault = p.isDefault,
                contextWindow = p.contextWindow,
                capabilities = p.capabilities.summary(),
                temperature = p.temperature.toDouble(),
                maxTokens = p.maxOutputTokens
            )
        }
    }

/**
 * 设置默认 Profile 的便捷封装（带返回值）。
 */
suspend fun SettingsRepository.setDefaultProfileAsync(id: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val exists = profiles.value.any { it.id == id }
            if (!exists) return@withContext false
            setDefaultProfile(id)
            true
        } catch (_: Throwable) {
            false
        }
    }

/**
 * 列出 Agent 角色绑定快照。
 */
suspend fun SettingsRepository.listRoleSnapshot(): AgentRoleConfigSnapshot =
    withContext(Dispatchers.IO) {
        val r = roles.value
        AgentRoleConfigSnapshot(
            primaryProfileId = r.primaryProfileId,
            visionProfileId = r.visionProfileId,
            reasoningProfileId = r.reasoningProfileId,
            fastProfileId = r.fastProfileId,
            summaryProfileId = r.summaryProfileId
        )
    }

/**
 * 修改角色绑定——把 role 字段映射到 ModelRoleConfig 对应字段。
 */
suspend fun SettingsRepository.activateRoleAsync(role: String, profileId: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val exists = profiles.value.any { it.id == profileId }
            if (!exists) return@withContext false
            updateRoles {
                when (role) {
                    "primary" -> copy(primaryProfileId = profileId)
                    "vision" -> copy(visionProfileId = profileId)
                    "reasoning" -> copy(reasoningProfileId = profileId)
                    "fast" -> copy(fastProfileId = profileId)
                    "summary" -> copy(summaryProfileId = profileId)
                    else -> this
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

/**
 * 设置 Provider API Key。
 */
suspend fun SettingsRepository.setProviderApiKeyAsync(providerId: String, apiKey: String): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val exists = providers.value.any { it.id == providerId }
            if (!exists) return@withContext false
            setProviderApiKey(providerId, apiKey)
            true
        } catch (_: Throwable) {
            false
        }
    }

// 模式预设扩展（listModePresetSummaries / selectModePresetAsync）已随
// AgentModePresetList/Select 工具与 Agent CUSTOM 模式移除（PR 4/6）；
// AgentSettings.selectedModePresetId / customModePresets 字段与快照/
// 补丁里的 Agent 模式字段已随设置域收口（PR 5/6）删除。
