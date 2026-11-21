package com.apex.agent.ui.screen.code

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * #197 模型 API 未配置浮窗（Coding 屏版本；同 Agent 屏语义）。
 * 4.5s 自动消散；Coding 屏无直达设置页的路由参数——提示用户去抽屉设置。
 *
 * （2026-11 自 CodeScreen.kt 原样迁出——God-file 预算纪律：CodeScreen 逼近
 * 1200 行门禁，独立职责缝拆分；渲染零变化。）
 */
@Composable
internal fun ApiMissingFloatingNotice(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4_500)
        onDismiss()
    }
    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { -it }) +
            androidx.compose.animation.fadeIn(),
        modifier = modifier.padding(top = 8.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(horizontal = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_api_missing_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.chat_api_missing_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { onOpenSettings(); onDismiss() }) {
                    Text(stringResource(R.string.code_api_missing_got_it))
                }
            }
        }
    }
}
