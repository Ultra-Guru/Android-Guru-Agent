package com.apex.agent.ui.screen.code

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.workspace.explorer.WorkspaceExplorerScreen

/**
 * 工作区浏览器全屏页宿主（v1.6）：Coding 屏右上角文件图标 push 进入，
 * 库页面自持胶囊标签栏（文件夹 / Git / 变更和产物）+ 实时跟随开关 +
 * 右上角叉号返回（onClose = 统一返回链弹栈）。
 *
 * 控制器随 CodeViewModel 生命周期（hiltViewModel 同实例 —— 导航往返保持
 * 树/预览/跟随状态；start/stop 由库页面 DisposableEffect 按组合生命周期驱动）。
 * agentActive 传入 agent 运行态（跟随开关的视觉提示）。
 */
@Composable
fun WorkspaceBrowserHost(
    viewModel: CodeViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WorkspaceExplorerScreen(
        controller = viewModel.workspaceKit.controller,
        onClose = onClose,
        agentActive = state.isRunning,
    )
}
