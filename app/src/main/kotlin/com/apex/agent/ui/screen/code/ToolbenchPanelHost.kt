package com.apex.agent.ui.screen.code

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.ui.chatkit.ChatToolkitStore
import com.apex.agent.ui.chatkit.PendingPipelineCommand
import com.apex.agent.ui.component.FusionCodingButton
import com.apex.agent.ui.component.GithubIconButton
import com.apex.agent.ui.component.SlashCommandButton
import com.apex.agent.ui.component.SlashMenuProvider
import com.apex.agent.ui.chatkit.ToolkitRingButton
import com.apex.agent.ui.screen.code.neon.NeonOrbButton
import com.apex.agent.ui.screen.code.neon.NeonOrbStore

/**
 * 工具台五件套弹层锚点宿主（2026-10 工具行四件套重排的自 CodeScreen 抽出，
 * 保住该文件的 1200 行 SRP 预算）。
 *
 * triggerHidden = 锚点模式：五组件只作弹层宿主（matchParentSize 覆盖层
 * 不占行宽），弹层全部锚定展开按钮处 —— 由 [CodePanel] 驱动谁展开。
 */
@Composable
internal fun CodeToolbenchPanels(
    activePanel: CodePanel?,
    onPanelDismiss: () -> Unit,
    slashMenuProvider: SlashMenuProvider,
    pendingCommands: List<PendingPipelineCommand>,
    onChipsChange: (List<PendingPipelineCommand>) -> Unit,
    onAddPendingCommand: (PendingPipelineCommand) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    githubTokenManager: GithubTokenManager,
    mcpManager: McpManager,
    toolkit: ChatToolkitStore,
    toolkitState: ToolkitUiState,
    neonOrbStore: NeonOrbStore?
) {
    Box(Modifier.matchParentSize()) {
        SlashCommandButton(
            slashMenuProvider = slashMenuProvider,
            scope = "coding",
            isSelected = { item ->
                PendingPipelineCommand.fromCommand(item.command, item.label)
                    ?.let { c -> pendingCommands.any { it.type == c.type && it.id == c.id } }
                    ?: false
            },
            onItemSelected = { item ->
                val capsule = PendingPipelineCommand.fromCommand(item.command, item.label)
                if (capsule != null) {
                    val exists = pendingCommands.any { it.type == capsule.type && it.id == capsule.id }
                    if (exists) {
                        onChipsChange(pendingCommands.filterNot { it.type == capsule.type && it.id == capsule.id })
                    } else {
                        onAddPendingCommand(capsule)
                    }
                    true // 流水线条目：保持展开，继续多选
                } else {
                    val merged = if (draft.isBlank()) item.command
                    else draft.trimEnd() + " " + item.command
                    onDraftChange(merged)
                    false
                }
            },
            menuOpen = activePanel == CodePanel.SLASH,
            onMenuDismiss = onPanelDismiss,
            triggerHidden = true
        )
        FusionCodingButton(
            onBundleChip = onAddPendingCommand,
            menuOpen = activePanel == CodePanel.FUSION,
            onMenuDismiss = onPanelDismiss,
            triggerHidden = true
        )
        GithubIconButton(
            tokenManager = githubTokenManager,
            mcpManager = mcpManager,
            menuOpen = activePanel == CodePanel.GITHUB,
            onMenuDismiss = onPanelDismiss,
            triggerHidden = true
        )
        ToolkitRingButton(
            webSearchEnabled = toolkitState.webSearchEnabled,
            timeEnabled = toolkitState.timeEnabled,
            selectedFunctionIds = toolkitState.selectedFunctionIds,
            availableTools = toolkitState.availableTools,
            outputFormat = toolkitState.outputFormat,
            customSchema = toolkitState.customSchema,
            rules = toolkitState.rules,
            exposeAllTools = toolkitState.exposeAllTools,
            onToggleWebSearch = { toolkit.setWebSearchEnabled(it) },
            onToggleTime = { toolkit.setTimeEnabled(it) },
            onToggleFunction = { toolkit.toggleFunction(it) },
            onToggleExposeAllTools = { toolkit.setExposeAllTools(it) },
            onSelectFormat = { toolkit.setOutputFormat(it) },
            onSetCustomSchema = { toolkit.setCustomSchema(it) },
            onUpsertRule = { toolkit.upsertRule(it) },
            onDeleteRule = { toolkit.deleteRule(it) },
            onToggleRule = { id, enabled -> toolkit.setRuleEnabled(id, enabled) },
            menuOpen = activePanel == CodePanel.TOOLKIT,
            onMenuDismiss = onPanelDismiss,
            triggerHidden = true
        )
        neonOrbStore?.let { store ->
            NeonOrbButton(
                store = store,
                menuOpen = activePanel == CodePanel.NEON,
                onMenuDismiss = onPanelDismiss,
                triggerHidden = true
            )
        }
    }
}
