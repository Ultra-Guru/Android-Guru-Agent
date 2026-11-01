package com.apex.agent.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.theme.LocalExtendedColors


/**
 * ═══ 离线横幅 ═══
 *
 * 数据源：NetworkMonitor.isOnline（主控在 ApexRoot 接线：
 * `val isOnline by networkMonitor.isOnline.collectAsStateWithLifecycle()`，
 * 再 `OfflineBanner(isOnline = isOnline)` 置于 Scaffold 内容顶部）。
 *
 * 离线时从顶部展开琥珀警示条；恢复在线时收起 —— [AnimatedVisibility] 保证
 * 在线态完全不占位（exit 动画结束后 zero height，不留空白）。
 *
 * @param isOnline 当前是否在线（false = 展示横幅）
 * @param modifier 布局修饰
 * @param kindLabel 可选的网络类型小字（右侧），由调用方传入 —— 通常
 *   `stringResource(networkMonitor.currentSummaryRes())`；null 时不展示
 */
@Composable
fun OfflineBanner(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    kindLabel: String? = null
) {
    AnimatedVisibility(
        visible = !isOnline,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        // #269：底色与文字成对从主题取；深色亮琥珀底不能再配白字。
        val warningColors = LocalExtendedColors.current
        val onWarning = warningColors.onWarningContainer
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = warningColors.warningContainer,
            contentColor = onWarning
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.WifiOff,
                    // 无障碍完整描述：横幅语义对 TalkBack 可见（不依赖文字颜色对比）
                    contentDescription = stringResource(R.string.net_banner_icon_desc),
                    tint = onWarning,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.net_banner_offline),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = onWarning,
                    modifier = Modifier.weight(1f)
                )
                if (kindLabel != null) {
                    Text(
                        text = kindLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = onWarning
                    )
                }
            }
        }
    }
}
