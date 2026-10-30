package com.apex.agent.ui.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.ui.chatkit.PendingPipelineCommand

/**
 * # FUSION 王冠入口 · Coding 工位接线（一行式）
 *
 * 用户钦定位（#397 后续修正）：FUSION 工具聚合体系的王冠入口属于
 * **Coding 模式**输入栏 —— 与斜杠 / GitHub / 小圆环 / 小大脑同列
 * （Agent 屏保留纯聊天体验，#197 工具入口迁 Coding 先例同口径）。
 *
 * 本文件把王冠三件套收敛成两个一行式 Composable：
 * - [FusionCodingButton]：王冠按钮（层级菜单按 coding 工位过滤，
 *   scope="all" 的体系包全部可见可选）；
 * - [FusionCodingBanner]：激活态横幅（王冠 + 包名 + 体系 agent
 *   运行中 + 停用钮）。
 *
 * controller 经 [rememberFusionBundleController] 取 Application 级
 * 单例（EntryPoint）—— CodeScreen 仅各插一行调用，零 VM 侵入；
 * 1185 行的它也无需撞 1200 行 CI 红线（接线外置本文件）。
 */

/**
 * Coding 输入栏王冠按钮：点开 FUSION 层级菜单（第一项体系缩写名 →
 * 第二级 REA / Superpowers / … 具体体系包），点选包即激活并以 chip
 * 回灌输入框（[onBundleChip] → CodeViewModel.addPendingCommand），
 * 发送时拼回 `/fusion:<id>` 走强制使用路由（与 Agent 屏同一管线）。
 */
@Composable
fun FusionCodingButton(
    onBundleChip: (PendingPipelineCommand) -> Unit
) {
    val controller = rememberFusionBundleController()
    val active by controller.activeBundle.collectAsStateWithLifecycle()
    FusionBundleButton(
        controller = controller,
        isActive = active != null,
        onBundleChip = onBundleChip,
        scope = "coding"
    )
}

/**
 * Coding 输入栏激活态横幅：当前激活包的常驻指示（王冠 + 包名 +
 * 停用钮）。挂在工具栏状态行之上 —— 用户随时知道「这个体系的
 * agent 正在运行」；未激活时不占位。
 */
@Composable
fun FusionCodingBanner() {
    val controller = rememberFusionBundleController()
    val active by controller.activeBundle.collectAsStateWithLifecycle()
    if (active == null) return
    val feedback = LocalFeedbackController.current
    // 异步回调里不可调 stringResource —— 组合期预解析（仓库惯例）
    val failedText = stringResource(R.string.fusion_result_failed)
    FusionBundleActiveBanner(
        bundleName = active!!.name,
        onDeactivate = {
            controller.deactivate()
                .onFailure {
                    feedback.show(it.message ?: failedText, FeedbackSeverity.WARNING)
                }
        }
    )
    Spacer(modifier = Modifier.height(4.dp))
}
