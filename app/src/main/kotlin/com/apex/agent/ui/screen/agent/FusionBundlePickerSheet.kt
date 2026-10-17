package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.tools.fusion.FusionBundleDigest
import com.apex.agent.fusion.FusionBundleController
import kotlinx.coroutines.launch

/**
 * # FusionBundle 选择器（底部弹层）
 *
 * 输入栏 FusionBundleButton 的弹层：已装复合技能包清单 —— 名称/版本/
 * 描述/组件计数（技能·MCP·工具提示·引擎）；点「使用」即**激活**
 * （装技能 + 连 MCP + 注入 agent 工作流 —— 选择后直接以该体系 agent
 * 运行）；已激活的包显示「使用中」与「停用」。
 *
 * 挂载方式与 LoopSetupSheet 同款：`if (showFusionPicker) FusionBundlePickerSheet(...)`
 * —— 不动导航栈。弹层内激活动作经 [FusionBundleController]；结果反馈走
 * 调用方传入的 onResult（成功/失败文案由调用方经统一反馈层展示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FusionBundlePickerSheet(
    controller: FusionBundleController,
    onDismiss: () -> Unit,
    onResult: (ok: Boolean, message: String) -> Unit
) {
    val bundles by controller.bundles.collectAsStateWithLifecycle()
    val active by controller.activeBundle.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 异步回调里不可调 stringResource（非组合上下文）—— 模板在组合期预解析
    //（仓库 VM 层 strFmt 的 String.format 同款模式）。
    val activatedPattern = stringResource(R.string.fusion_result_activated)
    val skippedPattern = stringResource(R.string.fusion_result_skipped)
    val failedText = stringResource(R.string.fusion_result_failed)
    val deactivatedPattern = stringResource(R.string.fusion_result_deactivated)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // ── 标题 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Layers,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.fusion_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(R.string.fusion_picker_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            if (bundles.isEmpty()) {
                Text(
                    text = stringResource(R.string.fusion_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                bundles.forEach { digest ->
                    FusionBundleRow(
                        digest = digest,
                        isActive = active?.id == digest.id,
                        onActivate = {
                            scope.launch {
                                val r = controller.activate(digest.id)
                                r.fold(
                                    onSuccess = { act ->
                                        val skippedNote =
                                            if (act.skipped.isEmpty()) ""
                                            else String.format(skippedPattern, act.skipped.size)
                                        onResult(
                                            true,
                                            String.format(activatedPattern, act.bundleName) + skippedNote
                                        )
                                    },
                                    onFailure = {
                                        onResult(false, it.message ?: failedText)
                                    }
                                )
                            }
                        },
                        onDeactivate = {
                            controller.deactivate()
                            onResult(true, deactivatedPattern.format(digest.name))
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            // ── 说明脚注 ──
            Text(
                text = stringResource(R.string.fusion_picker_footnote),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * # FusionBundle 激活态横幅（输入栏内一行式）
 *
 * 当前激活包的常驻指示：包名 + agent 标记 + 停用钮。挂在附件预览条之下
 * （Loop 激活卡同位语义）—— 用户随时知道「这个体系的 agent 正在运行」。
 */
@Composable
fun FusionBundleActiveBanner(
    bundleName: String,
    onDeactivate: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Layers,
                contentDescription = stringResource(R.string.fusion_cd_bundle_active),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.fusion_banner_active, bundleName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onDeactivate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(
                    text = stringResource(R.string.fusion_action_deactivate),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun FusionBundleRow(
    digest: FusionBundleDigest,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = digest.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (digest.bundled) {
                    Text(
                        text = stringResource(R.string.fusion_badge_bundled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "v${digest.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isActive) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.fusion_cd_bundle_active),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = digest.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = stringResource(
                    R.string.fusion_row_components,
                    digest.skillCount, digest.mcpServerCount, digest.toolHintCount, digest.engineCount
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                if (isActive) {
                    OutlinedButton(onClick = onDeactivate) {
                        Text(stringResource(R.string.fusion_action_deactivate))
                    }
                } else {
                    FilledTonalButton(onClick = onActivate) {
                        Text(stringResource(R.string.fusion_action_activate))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = digest.trustLevel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
    }
}
