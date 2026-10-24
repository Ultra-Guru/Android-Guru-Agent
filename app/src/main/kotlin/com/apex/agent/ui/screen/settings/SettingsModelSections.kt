@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.llm.AuthType
import com.apex.agent.core.llm.KeyRotationMode
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRole
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.ReasoningEffort
import com.apex.agent.core.llm.StructuredOutputMode
import com.apex.agent.core.llm.ToolChoiceMode
import com.apex.agent.ui.component.BrandLogo
import com.apex.agent.ui.component.ModelBrands
import java.util.Locale

/**
 * 模型配置大分类的分区与对话框（v2 抽屉化重构自 SettingsScreen.kt 原样迁出，
 * 可见性 private → internal 供页面路由文件调用；零行为变更）。
 *
 * 归属叶子：model/setup · generation · reasoning · context · tools ·
 * network · prompt · advanced · providers-roles。
 */
@Composable
internal fun GenerationSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_generation), Icons.Outlined.Tune) {
        SliderRow("Temperature", p.temperature, 0f..2f, 20,
            description = stringResource(R.string.settings_temperature_desc),
            onValueChange = { onUpdate(p.copy(temperature = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        SliderRow("Top P", p.topP, 0f..1f, 10,
            description = stringResource(R.string.settings_top_p_desc),
            onValueChange = { onUpdate(p.copy(topP = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        SliderRow(stringResource(R.string.settings_top_k_label), p.topK.toFloat(), 0f..200f, 200,
            description = stringResource(R.string.settings_top_k_desc),
            onValueChange = { onUpdate(p.copy(topK = it.toInt())) }, fmt = { it.toInt().toString() })
        SliderRow("Min P", p.minP, 0f..1f, 20,
            description = stringResource(R.string.settings_min_p_desc),
            onValueChange = { onUpdate(p.copy(minP = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        SliderRow("Frequency Penalty", p.frequencyPenalty, -2f..2f, 40,
            description = stringResource(R.string.settings_freq_penalty_desc),
            onValueChange = { onUpdate(p.copy(frequencyPenalty = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        SliderRow("Presence Penalty", p.presencePenalty, -2f..2f, 40,
            description = stringResource(R.string.settings_presence_penalty_desc),
            onValueChange = { onUpdate(p.copy(presencePenalty = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        SliderRow("Repetition Penalty", p.repetitionPenalty, 0f..2f, 40,
            description = stringResource(R.string.settings_rep_penalty_desc),
            onValueChange = { onUpdate(p.copy(repetitionPenalty = it)) }, fmt = { String.format(Locale.US, "%.2f", it) })
        IntFieldRow("Seed (0=Auto)", p.seed?.toInt() ?: 0,
            description = stringResource(R.string.settings_seed_desc)) {
            onUpdate(p.copy(seed = if (it == 0) null else it.toLong()))
        }
        TextFieldRow(stringResource(R.string.settings_stop_seq_label), p.stopSequences.joinToString(","),
            description = stringResource(R.string.settings_stop_seq_desc)) {
            onUpdate(p.copy(stopSequences = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotBlank() }))
        }
        IntFieldRow("Max Output Tokens", p.maxOutputTokens,
            description = stringResource(R.string.settings_max_output_desc), min = 256, max = 1_000_000) {
            onUpdate(p.copy(maxOutputTokens = it))
        }
    }
}

@Composable
internal fun ReasoningSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_reasoning), Icons.Outlined.Psychology) {
        // 本地化（用户反馈：中文环境满屏英文；旧版硬编码 "Reasoning Effort"）
        Text(
            stringResource(R.string.settings_reasoning_effort_label),
            style = MaterialTheme.typography.labelMedium
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ReasoningEffort.values().toList(), key = { it.name }) { eff ->
                androidx.compose.material3.FilterChip(selected = p.reasoningEffort == eff,
                    onClick = { onUpdate(p.copy(reasoningEffort = eff)) },
                    label = { Text(localizedEffortLabel(eff)) })
            }
        }
        DescriptionText(stringResource(R.string.settings_effort_desc))
        DropdownRow(
            stringResource(R.string.settings_thinking_budget_label),
            listOf(
                null to stringResource(R.string.settings_thinking_budget_auto),
                1024 to "1024", 2048 to "2048", 4096 to "4096",
                8192 to "8192", 16384 to "16384", 32768 to "32768", 65536 to "65536"
            ),
            p.thinkingBudget,
            description = stringResource(R.string.settings_budget_desc)) {
            onUpdate(p.copy(thinkingBudget = it))
        }
        SwitchRow(
            stringResource(R.string.settings_show_thinking_label),
            p.showThinking,
            description = stringResource(R.string.settings_show_thinking_desc)) {
            onUpdate(p.copy(showThinking = it))
        }
    }
}

/**
 * ReasoningEffort → 本地化标签（默认/低/中/高/最大）。
 *
 * 复用聊天页 chat_effort_* 键（zh / en 对称已存在）；旧版直渲裸枚举名
 * NONE/LOW/MEDIUM/HIGH/MAX —— 中文环境下整行英文（用户反馈修复）。
 */
@Composable
private fun localizedEffortLabel(effort: ReasoningEffort): String = stringResource(
    when (effort) {
        ReasoningEffort.NONE -> R.string.chat_effort_none
        ReasoningEffort.LOW -> R.string.chat_effort_low
        ReasoningEffort.MEDIUM -> R.string.chat_effort_medium
        ReasoningEffort.HIGH -> R.string.chat_effort_high
        ReasoningEffort.MAX -> R.string.chat_effort_max
    }
)

@Composable
internal fun ContextSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_context), Icons.Outlined.Subject) {
        DropdownRow(
            stringResource(R.string.settings_ctx_window_label),
            listOf(
                8000 to "8K", 16_000 to "16K", 32_000 to "32K", 64_000 to "64K",
                128_000 to "128K", 200_000 to "200K", 262_144 to "256K",
                524_288 to "512K", 1_000_000 to "1M"
            ),
            p.contextWindow,
            description = stringResource(R.string.settings_ctx_window_desc)
        ) { onUpdate(p.copy(contextWindow = it)) }
        IntFieldRow(stringResource(R.string.settings_ctx_exact_label), p.contextWindow,
            description = stringResource(R.string.settings_ctx_exact_desc, p.displayContext()),
            min = 1000, max = 10_000_000) {
            onUpdate(p.copy(contextWindow = it))
        }
        IntFieldRow(stringResource(R.string.settings_reserved_label), p.reservedOutputTokens,
            description = stringResource(R.string.settings_reserved_desc),
            min = 0, max = 100_000) {
            onUpdate(p.copy(reservedOutputTokens = it))
        }
    }
}

@Composable
internal fun ToolsSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_tools), Icons.Outlined.Build) {
        SwitchRow(stringResource(R.string.settings_enable_tools), p.enableTools,
            description = stringResource(R.string.settings_enable_tools_desc)) {
            onUpdate(p.copy(enableTools = it))
        }
        DropdownRow("Tool Choice",
            ToolChoiceMode.values().map { it to it.name }, p.toolChoice,
            description = stringResource(R.string.settings_tool_choice_desc)) {
            onUpdate(p.copy(toolChoice = it))
        }
        SwitchRow("Parallel Tool Calls", p.parallelToolCalls,
            description = stringResource(R.string.settings_parallel_calls_desc)) {
            onUpdate(p.copy(parallelToolCalls = it))
        }
        IntFieldRow("Max Tool Calls / Turn", p.maxToolCalls,
            description = stringResource(R.string.settings_max_tool_calls_desc), min = 1, max = 100) {
            onUpdate(p.copy(maxToolCalls = it))
        }
        IntFieldRow("Tool Call Timeout (s)", p.toolTimeoutSeconds,
            description = stringResource(R.string.settings_tool_timeout_desc), min = 1, max = 3600) {
            onUpdate(p.copy(toolTimeoutSeconds = it))
        }
        IntFieldRow("Max Tool Result Tokens", p.maxToolResultTokens,
            description = stringResource(R.string.settings_tool_result_desc), min = 100, max = 100_000) {
            onUpdate(p.copy(maxToolResultTokens = it))
        }
    }
}

@Composable
internal fun NetworkSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_network), Icons.Outlined.Cloud) {
        IntFieldRow("Connect Timeout (ms)", p.connectTimeoutMs.toInt(),
            min = 1000, max = 300_000) { onUpdate(p.copy(connectTimeoutMs = it.toLong())) }
        IntFieldRow("Read Timeout (ms)", p.readTimeoutMs.toInt(),
            description = stringResource(R.string.settings_read_timeout_desc),
            min = 5_000, max = 600_000) { onUpdate(p.copy(readTimeoutMs = it.toLong())) }
        IntFieldRow("Write Timeout (ms)", p.writeTimeoutMs.toInt(),
            min = 1_000, max = 300_000) { onUpdate(p.copy(writeTimeoutMs = it.toLong())) }
        IntFieldRow("Request Timeout (ms)", p.requestTimeoutMs.toInt(),
            description = stringResource(R.string.settings_request_timeout_desc),
            min = 5_000, max = 600_000) { onUpdate(p.copy(requestTimeoutMs = it.toLong())) }
        IntFieldRow(stringResource(R.string.settings_retry_count), p.retryCount,
            description = stringResource(R.string.settings_retry_count_desc), min = 0, max = 10) {
            onUpdate(p.copy(retryCount = it))
        }
        IntFieldRow("Retry Delay (ms)", p.retryDelayMs.toInt(),
            description = stringResource(R.string.settings_retry_delay_desc), min = 0, max = 60_000) {
            onUpdate(p.copy(retryDelayMs = it.toLong()))
        }
        IntFieldRow("Max Retry Delay (ms)", p.maxRetryDelayMs.toInt(),
            min = 1_000, max = 300_000) { onUpdate(p.copy(maxRetryDelayMs = it.toLong())) }
        ChipMultiSelect("Retry On", listOf(408, 429, 500, 502, 503, 504), p.retryOnCodes) {
            val set = p.retryOnCodes.toMutableSet()
            if (set.contains(it)) set.remove(it) else set.add(it)
            onUpdate(p.copy(retryOnCodes = set))
        }
        SwitchRow(stringResource(R.string.settings_streaming), p.streaming,
            description = stringResource(R.string.settings_streaming_desc)) {
            onUpdate(p.copy(streaming = it))
        }
        SwitchRow(stringResource(R.string.settings_keep_alive_conn), p.keepAlive,
            description = stringResource(R.string.settings_keep_alive_desc)) {
            onUpdate(p.copy(keepAlive = it))
        }
    }
}

@Composable
internal fun PromptSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_prompt), Icons.Outlined.EditNote) {
        OutlinedTextField(p.systemPromptPrefix, { onUpdate(p.copy(systemPromptPrefix = it)) },
            label = { Text(stringResource(R.string.settings_prompt_prefix_label)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            maxLines = 6)
        // 修复：下拉选中值随用户选择联动（原硬编码 "default"，选后仍显示 Default）
        var presetChoice by remember(p.id) {
            mutableStateOf(
                when (p.systemPromptPrefix) {
                    PROMPT_PRESET_ANDROID -> "android_expert"
                    PROMPT_PRESET_CODING -> "coding"
                    PROMPT_PRESET_AUTOMATION -> "automation"
                    "" -> "default"
                    else -> "default" // 手改前缀无法反推预设，回退 default
                }
            )
        }
        DropdownRow("Prompt Preset",
            listOf(
                "default" to stringResource(R.string.settings_preset_default),
                "android_expert" to "Android Expert",
                "coding" to "Coding Agent",
                "automation" to "Automation Agent",
            ),
            presetChoice,
            description = stringResource(R.string.settings_preset_desc)
        ) { preset ->
            presetChoice = preset
            val template = when (preset) {
                "android_expert" -> PROMPT_PRESET_ANDROID
                "coding" -> PROMPT_PRESET_CODING
                "automation" -> PROMPT_PRESET_AUTOMATION
                else -> ""
            }
            onUpdate(p.copy(systemPromptPrefix = template))
        }
    }
}

// Prompt Preset 模板（供选中值反推复用）
private const val PROMPT_PRESET_ANDROID =
    "You are an expert Android engineer. Prefer adb, gradle and Kotlin; verify with build output before claiming success."
private const val PROMPT_PRESET_CODING =
    "You are a senior coding agent. Write clean, production-ready code; explain key decisions briefly."
private const val PROMPT_PRESET_AUTOMATION =
    "You are a device automation agent. Plan minimal, reliable UI steps and verify after each action."

@Composable
internal fun AdvancedSection(p: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_advanced), Icons.Outlined.Settings) {
        DropdownRow("Structured Output",
            StructuredOutputMode.values().map { it to it.name }, p.structuredOutputMode) {
            onUpdate(p.copy(structuredOutputMode = it))
        }
        SwitchRow("Strict Schema", p.structuredOutputStrict,
            description = stringResource(R.string.settings_strict_schema_desc)) {
            onUpdate(p.copy(structuredOutputStrict = it))
        }
        KeyValueEditor("Custom Headers", p.customHeaders) {
            onUpdate(p.copy(customHeaders = it))
        }
    }
}

// ───────────────────────────── 对话框（providers-roles 叶子页 + setup 卡入口）─────────────────────────────

@Composable
internal fun ProvidersDialog(
    providers: List<ProviderConfig>,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var deleteProvTarget by remember { mutableStateOf<ProviderConfig?>(null) } // 修复：删除 Provider 加确认（连带静默重指派其下档案）
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.settings_close)) } },
        title = { Text("Providers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { prov ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // #174：服务商品牌图标
                                BrandLogo(
                                    brand = ModelBrands.byProviderId(prov.id),
                                    size = 22.dp,
                                    fallbackInitial = prov.displayName
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(prov.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (prov.isBuiltIn) {
                                    // 修复：原 AssistChip(onClick={}) 渲染为可点击涟漪但无任何动作 —— 改为纯静态徽标
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            stringResource(R.string.settings_builtin),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            Text(prov.baseUrl, style = MaterialTheme.typography.bodySmall)
                            Text("Keys: ${prov.apiKeys.size}", style = MaterialTheme.typography.labelSmall)
                            Row {
                                TextButton(onClick = { editing = prov }) { Text(stringResource(R.string.settings_edit)) }
                                if (!prov.isBuiltIn) TextButton(onClick = { deleteProvTarget = prov }) { Text(stringResource(R.string.settings_delete)) }
                            }
                        }
                    }
                }
                Button(onClick = {
                    editing = ProviderConfig(id = "prov_${System.currentTimeMillis()}", displayName = "New Provider", baseUrl = "https://")
                }) { Text(stringResource(R.string.settings_add_provider)) }
            }
        }
    )
    editing?.let { prov ->
        ProviderEditorDialog(prov, viewModel) { editing = null }
    }

    deleteProvTarget?.let { prov ->
        AlertDialog(
            onDismissRequest = { deleteProvTarget = null },
            title = { Text(stringResource(R.string.settings_delete_provider_title)) },
            text = { Text(stringResource(R.string.settings_delete_provider_text, prov.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProvider(prov.id)
                    deleteProvTarget = null
                }) { Text(stringResource(R.string.settings_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteProvTarget = null }) { Text(stringResource(R.string.settings_cancel)) } }
        )
    }
}

@Composable
internal fun ProviderEditorDialog(
    provider: ProviderConfig,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    var p by remember { mutableStateOf(provider) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { viewModel.upsertProvider(p); onDismiss() }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
        title = { Text(stringResource(R.string.settings_edit_provider)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextFieldRow("Display Name", p.displayName) { p = p.copy(displayName = it) }
                TextFieldRow("Base URL", p.baseUrl, description = stringResource(R.string.settings_base_url_hint)) { p = p.copy(baseUrl = it) }
                DropdownRow("Auth Type", AuthType.values().map { it to it.name }, p.authType) { p = p.copy(authType = it) }
                TextFieldRow("Organization", p.organization) { p = p.copy(organization = it) }
                TextFieldRow("Project", p.project) { p = p.copy(project = it) }
                // 多 Key + 轮换策略
                Text(stringResource(R.string.settings_keys_hint), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    p.apiKeys.joinToString("\n"),
                    { p = p.copy(apiKeys = it.lines().map { s -> s.trim() }.filter { s -> s.isNotBlank() }) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), maxLines = 6
                )
                DropdownRow("Key Rotation",
                    listOf(
                        KeyRotationMode.DISABLED to stringResource(R.string.settings_rotation_disabled),
                        KeyRotationMode.SEQUENTIAL to stringResource(R.string.settings_rotation_sequential),
                        KeyRotationMode.ON_ERROR to stringResource(R.string.settings_rotation_on_error),
                        KeyRotationMode.ON_RATE_LIMIT to stringResource(R.string.settings_rotation_rate_limit),
                    ),
                    p.keyRotationMode,
                    description = stringResource(R.string.settings_rotation_desc)) {
                    p = p.copy(keyRotationMode = it)
                }
                KeyValueEditor(stringResource(R.string.settings_default_headers), p.defaultHeaders) {
                    p = p.copy(defaultHeaders = it)
                }
            }
        }
    )
}

@Composable
internal fun RolesDialog(
    roles: ModelRoleConfig,
    profiles: List<ModelProfile>,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    val options = listOf("" to stringResource(R.string.settings_not_set)) + profiles.map { it.id to it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.settings_done)) } },
        title = { Text(stringResource(R.string.settings_model_roles)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelRole.values().forEach { role ->
                    val current = roles.profileIdFor(role)
                    DropdownRow(role.label, options, current) {
                        viewModel.updateRoles {
                            when (role) {
                                ModelRole.PRIMARY -> copy(primaryProfileId = it)
                                ModelRole.VISION -> copy(visionProfileId = it)
                                ModelRole.REASONING -> copy(reasoningProfileId = it)
                                ModelRole.FAST -> copy(fastProfileId = it)
                                ModelRole.SUMMARY -> copy(summaryProfileId = it)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.settings_role_note),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    )
}
