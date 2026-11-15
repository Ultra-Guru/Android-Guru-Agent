package com.apex.agent.ui.screen.code

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.PartSurface
import com.apex.agent.ui.theme.CodingPart
import com.kyant.backdrop.Backdrop

/**
 * # Code Error Bar — Coding 屏底部错误条
 *
 * 从 CodeScreen.kt 抽离（God-file 预算纪律：1200 行门禁）。
 *
 * v1.7 部件皮肤（PartSkin spec DEF-2/DEF-4）：
 *  - 容器走 `PartSurface(overlay.error_bar)` —— 新默认 PALE_LIQUID 浅白玻璃；
 *    红色**仅上图标**（FR-11 淘汰 accent=error 整卡粉红染色，消灭粉红条）；
 *  - DEF-4：`maxLines=3` 静默截断改为**可展开**（长消息出现 trailing 槽位的
 *    展开/收起控件，不再绝对定位压字）+ **长按复制全文**（422 请求体等长
 *    错误不再被吞成 "tools[0..."，一键取回原文排查）；
 *  - #209：可恢复错误提供「重试」；「忽略」常驻。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ErrorBar(
    message: String,
    backdrop: Backdrop?,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val longMessage = message.length > ERROR_BAR_COLLAPSE_CHARS
    PartSurface(
        part = CodingPart.OVERLAY_ERROR_BAR,
        backdrop = backdrop,
        classicStyle = GlassStyle.Floating,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    // DEF-4：折叠 3 行 + 展开控件放 trailing 槽位（不再绝对
                    // 定位压字）；长按复制全文（422 调试场景一键取回请求体）。
                    .combinedClickable(
                        onClick = { if (longMessage) expanded = !expanded },
                        onLongClick = {
                            clipboard.setText(AnnotatedString(message))
                        }
                    ),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis
            )
            // DEF-4：展开/收起控件（Row trailing 槽位；短消息不显示）
            if (longMessage) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.code_error_collapse else R.string.code_error_expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { expanded = !expanded }
                )
            }
            // #209：可恢复错误提供「重试」（复用错误条既有 action 按钮模式；
            // 文案复用 chat_retry，与 Agent 屏 RetryChip 同词）。
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.chat_retry))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.code_dismiss))
            }
        }
    }
}

/** ErrorBar 折叠态字符门槛：超过即出现展开控件（DEF-4）。 */
private const val ERROR_BAR_COLLAPSE_CHARS = 120
