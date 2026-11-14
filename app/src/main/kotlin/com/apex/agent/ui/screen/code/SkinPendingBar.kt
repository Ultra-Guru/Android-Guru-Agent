package com.apex.agent.ui.screen.code

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.theme.SkinApplyPolicy

/**
 * # SkinPendingBar — Code 屏外观待生效横幅（用户规格 2026-11「呈现立即」）
 *
 * 设置页改了皮肤 / 输出风格但尚未提升时，输入栏上方给一行非模态提示 +
 * 显式动作 —— 不再让用户对着旧界面猜「切了为什么没反应」。剩余的两种
 * 挂起形态（AUTO/IMMEDIATE idle 已内联提升，不出现本条）：
 *
 *  - IMMEDIATE 策略运行中挂起（H-6）：「外观已切换 · 本轮收束后生效」，
 *    「立即应用」= 中止本轮（复用现成 abort 收口）随即原地提升；
 *  - 显式 ON_NEW_CHAT 档：「外观已切换 · 新对话后生效」，「立即应用」=
 *    原地提前生效（不新建对话）。
 *
 * 「撤销」走 [CodeViewModel.discardPendingSkinChange]（FR-22：持久化一并
 * 回滚）。纯展示组件：状态与动作全部由调用方（CodeScreen）注入。
 */
@Composable
internal fun SkinPendingBar(
    activation: SkinActivation,
    isRunning: Boolean,
    onApplyNow: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!activation.hasPending) return
    val deferredToSettle = isRunning &&
        activation.effectivePolicy == SkinApplyPolicy.IMMEDIATE
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = if (deferredToSettle) {
                        stringResource(R.string.code_skin_pending_running)
                    } else {
                        stringResource(R.string.code_skin_pending_new_chat)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.code_skin_pending_discard))
                }
                TextButton(onClick = onApplyNow) {
                    Text(stringResource(R.string.code_skin_pending_apply_now))
                }
            }
        }
    }
}
