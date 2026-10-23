package com.apex.agent.tools

import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelCapabilityHeuristics
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.ReasoningEffort
import com.apex.agent.core.llm.WebSearchMode
import com.apex.agent.core.llm.share.ImportedModel
import com.apex.agent.core.llm.share.ModelConfigFormat
import com.apex.agent.core.llm.share.ModelConfigImporter
import com.apex.agent.core.tools.builtin.AppScreenHost
import com.apex.agent.core.tools.builtin.ModelConfigImportHost
import com.apex.agent.core.tools.builtin.ModelProfileUpdateHost
import com.apex.agent.core.tools.builtin.SystemDiagnoseTool
import com.apex.agent.core.tools.builtin.SystemDiagnosticsHost
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.ui.screen.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * # App Control Hosts（AppControlTools 的 Android 侧真实实现）
 *
 * 用户诉求：「模型是可以有能力帮我配置模型和等等，能帮我打开设置……
 * 不能用提示词注入，要给 agent 工具，是真实有能力会分析问题」。
 * 工具本体（core:tool-registry）只做参数白名单校验；本文件四个宿主
 * 把每个工具接到真实数据面：
 *
 * - [AppScreenHostImpl] → [AppNavigationBridge]（真实压栈导航）；
 * - [ModelProfileUpdateHostImpl] → [SettingsRepository]（与设置页同一落盘）；
 * - [ModelConfigImportHostImpl] → [ModelConfigImportApplier]（与设置页
 *   「导入」弹窗同一份识别与建档代码）；
 * - [SystemDiagnosticsHostImpl] → [McpManager] / [SkillRegistry] /
 *   [ToolAuditLogger]（真实运行时状态 + 审计落盘，含失败详情）。
 */
object AppControlHosts {

    /** 打开应用页面（经导航桥入队，ApexRoot 收集执行）。 */
    fun screenHost(bridge: AppNavigationBridge): AppScreenHost =
        AppScreenHost { route, section -> bridge.requestOpen(route, section) }

    /** 修改模型档案字段（与设置页同一持久层）。 */
    fun profileUpdateHost(repo: SettingsRepository): ModelProfileUpdateHost =
        ModelProfileUpdateHostImpl(repo)

    /** 导入模型配置文本（与设置页导入弹窗同一逻辑）。 */
    fun configImportHost(repo: SettingsRepository): ModelConfigImportHost =
        ModelConfigImportHostImpl(repo)

    /** 采集 MCP / Skills / 工具审计诊断快照。 */
    fun diagnosticsHost(
        mcpManager: McpManager,
        skillRegistry: SkillRegistry,
        auditLogger: ToolAuditLogger
    ): SystemDiagnosticsHost =
        SystemDiagnosticsHostImpl(mcpManager, skillRegistry, auditLogger)
}

// ═══════════════════════════════════════════════════════════════════════════════
// 模型档案更新宿主
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * [ModelProfileUpdateHost] 实现：patch 键为工具侧白名单字段（camelCase），
 * 逐字段应用到 [ModelProfile.copy]；`baseUrl` 特殊 —— 改的是 profile 挂靠
 * 的 Provider（Provider 与 Profile 解耦，端点属于前者）。
 *
 * 返回更新后的摘要 JSON（id/name/modelId/baseUrl/temperature/…），供
 * LLM 确认改动生效。
 */
private class ModelProfileUpdateHostImpl(
    private val repo: SettingsRepository
) : ModelProfileUpdateHost {

    override suspend fun update(profileId: String, patchJson: String): String =
        withContext(Dispatchers.IO) {
            val profile = repo.profiles.value.firstOrNull { it.id == profileId }
                ?: throw IllegalArgumentException(
                    "profile '$profileId' not found — call AgentProfileList to see valid ids"
                )
            val patch = Json.parseToJsonElement(patchJson).jsonObject

            var next = profile.copy(
                name = patch.str("name") ?: profile.name,
                modelId = patch.str("modelId") ?: profile.modelId,
                temperature = patch.dbl("temperature")?.toFloat() ?: profile.temperature,
                maxOutputTokens = patch.int("maxOutputTokens") ?: profile.maxOutputTokens,
                contextWindow = patch.int("contextWindow") ?: profile.contextWindow,
                thinkingBudget = patch.int("thinkingBudget")?.takeIf { it > 0 }
                    ?: if (patch.containsKey("thinkingBudget")) null else profile.thinkingBudget,
                systemPromptPrefix = patch.str("systemPromptPrefix") ?: profile.systemPromptPrefix,
                enableTools = patch.bool("enableTools") ?: profile.enableTools,
                webSearch = patch.bool("webSearch")
                    ?.let { if (it) WebSearchMode.AUTO else WebSearchMode.OFF }
                    ?: profile.webSearch
            )
            patch.str("reasoningEffort")?.let { raw ->
                val name = raw.trim().uppercase()
                val effort = ReasoningEffort.entries.firstOrNull { it.name == name }
                    ?: throw IllegalArgumentException(
                        "reasoningEffort '$raw' is not one of " +
                            ReasoningEffort.entries.joinToString("/")
                    )
                next = next.copy(reasoningEffort = effort)
            }
            // base_url：改挂靠 Provider 的端点（Provider 解耦设计）
            var baseUrlNow = repo.providers.value
                .firstOrNull { it.id == profile.providerId }?.baseUrl.orEmpty()
            patch.str("baseUrl")?.takeIf { it.isNotBlank() }?.let { newUrl ->
                val provider = repo.providers.value.firstOrNull { it.id == profile.providerId }
                if (provider != null) {
                    repo.upsertProvider(provider.copy(baseUrl = newUrl.trim()))
                    baseUrlNow = newUrl.trim()
                }
            }
            repo.upsertProfile(next)

            // 摘要（回读式 —— upsert 后以持久层为准，诚实呈现）
            buildJsonObject {
                put("id", next.id)
                put("name", next.name)
                put("modelId", next.modelId)
                put("baseUrl", baseUrlNow)
                put("temperature", next.temperature.toDouble())
                put("maxOutputTokens", next.maxOutputTokens)
                put("contextWindow", next.contextWindow)
                put("reasoningEffort", next.reasoningEffort.name)
                put("isDefault", next.isDefault)
            }.toString()
        }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun kotlinx.serialization.json.JsonObject.dbl(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun kotlinx.serialization.json.JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun kotlinx.serialization.json.JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}

// ═══════════════════════════════════════════════════════════════════════════════
// 模型配置导入（共享逻辑：设置页弹窗与 Agent 工具同一份）
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * 模型配置导入落盘编排（自 SettingsViewModel.importModelConfig 提取 ——
 * 设置页弹窗与 [ModelConfigImportTool] 共用，预览所见即导入所得的唯一真源）。
 *
 * 识别失败原样抛 [com.apex.agent.core.llm.share.ModelConfigImportException]
 * 子类，由调用方决定文案形态（VM → 本地化用户文案；工具宿主 → 面向 LLM
 * 的英文错误）。
 */
object ModelConfigImportApplier {

    /** 导入摘要（count/firstProfileId/providerName 与 VM 的 ModelImportSummary 同形）。 */
    data class Summary(
        val count: Int,
        val firstProfileId: String,
        val providerName: String
    )

    suspend fun apply(
        repo: SettingsRepository,
        text: String,
        format: ModelConfigFormat
    ): Summary = withContext(Dispatchers.IO) {
        val cfg = ModelConfigImporter.import(text, format)

        val ts = System.currentTimeMillis()
        val providerName = cfg.providerName.ifBlank { "Imported" }
        val provider = ProviderConfig(
            id = "imported_$ts",
            displayName = providerName,
            baseUrl = cfg.baseUrl,
            defaultHeaders = cfg.headers,
            isBuiltIn = false,
        )
        repo.upsertProvider(provider)
        cfg.apiKey?.takeIf { it.isNotBlank() }?.let { repo.setProviderApiKey(provider.id, it) }

        // 空模型清单 → 单个空白档（用户稍后补 modelId），导入计数按 1 计
        val entries = cfg.models.ifEmpty {
            listOf(ImportedModel(modelId = "", displayName = providerName))
        }
        var firstProfileId = ""
        entries.forEachIndexed { index, m ->
            // 显式声明的能力位打底，再叠加命名启发式（只加不减）
            val caps = ModelCapabilityHeuristics.enrichCapabilities(
                m.modelId,
                ModelCapabilities(
                    vision = m.vision ?: false,
                    imageInput = m.vision ?: false,
                    toolCalling = m.toolCalling ?: true,
                    reasoning = m.reasoning ?: false,
                )
            )
            val profile = ModelProfile(
                id = "profile_${ts}_$index",
                name = m.displayName.ifBlank { m.modelId.ifBlank { "${providerName} ${index + 1}" } },
                providerId = provider.id,
                modelId = m.modelId,
                contextWindow = m.contextWindow ?: 128_000,
                capabilities = caps,
            )
            if (index == 0) firstProfileId = profile.id
            repo.upsertProfile(profile)
        }
        Summary(entries.size, firstProfileId, providerName)
    }
}

/** [ModelConfigImportHost] 实现：经共享 [ModelConfigImportApplier] 落盘。 */
private class ModelConfigImportHostImpl(
    private val repo: SettingsRepository
) : ModelConfigImportHost {

    override suspend fun import(configText: String, format: String): String {
        val fmt = when (format.trim().lowercase()) {
            "auto" -> ModelConfigFormat.AUTO
            "json" -> ModelConfigFormat.JSON
            "python" -> ModelConfigFormat.PYTHON
            "yaml" -> ModelConfigFormat.YAML
            "toml" -> ModelConfigFormat.TOML
            else -> ModelConfigFormat.AUTO
        }
        val summary = ModelConfigImportApplier.apply(repo, configText, fmt)
        return buildJsonObject {
            put("importedProfiles", summary.count)
            put("firstProfileId", summary.firstProfileId)
            put("providerName", summary.providerName)
            put(
                "hint",
                "profiles created and persisted; the first one can be activated via " +
                    "AgentProfileSetDefault, and its API key set via AgentProviderSetKey"
            )
        }.toString()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 系统诊断快照宿主
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * [SystemDiagnosticsHost] 实现：聚合 MCP 配置/连接快照、Skills 安装/启用
 * 快照、工具审计（全量 trail + 失败子集），全部来自真实运行时状态 ——
 * agent 拿到的是事实而非想象。
 */
private class SystemDiagnosticsHostImpl(
    private val mcpManager: McpManager,
    private val skillRegistry: SkillRegistry,
    private val auditLogger: ToolAuditLogger
) : SystemDiagnosticsHost {

    override suspend fun diagnose(includeToolCalls: Boolean, toolCallLimit: Int): String =
        withContext(Dispatchers.IO) {
            // ── MCP：配置快照 + 连接集（两者都是锁内快照读，零连接副作用）──
            val connected = mcpManager.getConnectedServers().toSet()
            val mcpRows = mcpManager.getConfigs().map { cfg ->
                SystemDiagnoseTool.Reports.mcpServer(
                    name = cfg.name,
                    transport = cfg.transport.name + if (cfg.runInSandbox) "+SANDBOX" else "",
                    enabled = cfg.enabled,
                    connected = cfg.name in connected,
                    endpoint = cfg.endpointLabel()
                )
            }

            // ── Skills：安装清单 + 双工位启用位 ──
            val skillRows = skillRegistry.getInstalled().map { s ->
                SystemDiagnoseTool.Reports.skill(
                    id = s.manifest.id,
                    name = s.manifest.name,
                    version = s.manifest.version,
                    enabledAgent = s.enabledAgent,
                    enabledCoding = s.enabledCoding,
                    toolCount = s.manifest.tools.size
                )
            }

            // ── 工具审计：trail（可选）+ 失败子集（含 command/detail 现场）──
            val trail = if (includeToolCalls) {
                auditLogger.recentEntries(toolCallLimit).map { it.toReport() }
            } else emptyList()
            val failures = auditLogger.recentFailures(toolCallLimit).map { it.toReport() }

            SystemDiagnoseTool.Reports.assemble(mcpRows, skillRows, trail, failures).toString()
        }

    private fun ToolAuditLogger.AuditEntry.toReport() =
        SystemDiagnoseTool.Reports.toolCall(
            ts = ts,
            tool = tool,
            decision = decision,
            tier = tier,
            success = success,
            durationMs = durationMs,
            command = command,
            detail = detail
        )

    private fun com.apex.agent.core.tools.mcp.McpServerConfig.endpointLabel(): String =
        when (transport) {
            McpTransport.HTTP, McpTransport.SSE -> url.ifBlank { "(no url)" }
            McpTransport.STDIO -> (command ?: "(no command)") + args.joinToString(" ", " ")
            McpTransport.BUILTIN -> "builtin"
        }
}
