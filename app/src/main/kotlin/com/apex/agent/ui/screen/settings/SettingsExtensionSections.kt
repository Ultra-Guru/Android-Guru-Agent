package com.apex.agent.ui.screen.settings

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import kotlinx.coroutines.launch
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext

/** BUG-33（设置 spec 02）：工作区根的真实内部路径 —— UI 用人话标签，路径仅经「复制」给需要的用户。 */
internal const val WORKSPACE_ROOT_PATH = "linux/workspaces/default"

/**
 * # Issue #164 / #165 —— Agent 分类的规则与钩子叶子页（挂载块抽出）。
 *
 * v2 抽屉化重构：原 AgentRulesAndHooksSections（规则+钩子一体挂载）拆为
 * 两个独立块，分别服务 `agent/rules` 与 `agent/hooks` 两个二级页面；
 * IO 纪律不变（见下）。
 *
 * ## IO 纪律
 * 规则文件命中（`projectRulesFileName`）与模板创建（`createProjectRulesTemplate`）
 * 都在 [SettingsViewModel] 的 IO 调度器上执行；Composable 侧经 produceState
 * 异步取值——不阻塞组合、不重复读盘。创建成功后以 [rulesRefreshKey] 触发
 * 重查（不用 Snackbar：本页无挂载的 Host，showSnackbar 会永久挂起）。
 */
@Composable
internal fun AgentRulesSectionBlock(
    agent: AgentSettings,
    onAgent: (AgentSettings.() -> AgentSettings) -> Unit,
    viewModel: SettingsViewModel
) {
    // ═══ 规则（#164）：全局规则（Agent/coding 双模式）+ 默认工作区 AGENTS.md ═══
    var rulesRefreshKey by remember { mutableStateOf(0) }
    val projectRulesFile by produceState<String?>(
        initialValue = null,
        key1 = rulesRefreshKey
    ) {
        value = viewModel.projectRulesFileName()
    }
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // BUG-33:人话标签(stringResource 不能在 let 回调里调,先提升)
    val workspaceLabel = stringResource(R.string.settings_workspace_label)
    val coroutineScope = rememberCoroutineScope()
    // stringResource 不能在 let 回调（非 Composable）里调——先提升格式串
    val loadedFmt = stringResource(R.string.code_rules_project_status_loaded_fmt)
    RulesSettingsSection(
        globalRules = agent.globalRules,
        onGlobalRulesChange = { rules -> onAgent { copy(globalRules = rules) } },
        // BUG-33（设置 spec 02）：内部路径不再直排展示 —— 人话标签 +
        // 「复制路径」按钮（真实路径经剪贴板给需要的用户）
        workspaceRootLabel = workspaceLabel,
        workspaceCopyPath = WORKSPACE_ROOT_PATH,
        onCopyWorkspacePath = {
            clipboard.setPrimaryClip(ClipData.newPlainText("workspace", WORKSPACE_ROOT_PATH))
        },
        projectRulesStatus = projectRulesFile?.let { fileName -> loadedFmt.format(fileName) },
        onCreateProjectRules = {
            coroutineScope.launch {
                viewModel.createProjectRulesTemplate()
                rulesRefreshKey++
            }
        }
    )
}

/** 钩子（#165）：声明式钩子启停（工具前后 / 会话生命周期）。 */
@Composable
internal fun AgentHooksSectionBlock(viewModel: SettingsViewModel) {
    val hooks by viewModel.hooks.collectAsStateWithLifecycle()
    // B2-BUG-21:启停失败提示有人消费了 —— 旧实现 _hookMessage 全模块无订阅
    // 点,开关失败(IO 写 hooks.json 失败)时 UI 毫无反应。
    val hookMessage by viewModel.hookMessage.collectAsStateWithLifecycle()
    var hookMsgShown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hookMessage) {
        hookMessage?.let {
            hookMsgShown = it
            viewModel.consumeHookMessage()
        }
    }
    hookMsgShown?.let { msg ->
        Text(
            msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
    HooksSettingsSection(
        hooks = hooks,
        onToggle = { id, enabled -> viewModel.setHookEnabled(id, enabled) }
    )
}
