package com.apex.agent.ui.screen.code

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.CodeContextRole
import com.apex.agent.core.code.CodeContextSnapshot

/**
 * # Code Context Sheet — 会话上下文详情（底部弹层）
 *
 * 顶栏「上下文用量圆环」点击后打开：Token 用量卡（上下文占用百分比 /
 * 会话累计输入输出 / 缓存命中率）+ 发给模型的上下文全文（会话消息序列
 * （角色徽标 + 全文）+ 全局规则 / 会话附加注入段）。
 *
 * 数据源双通道：
 * - [snapshot]：[com.apex.agent.core.code.CodeEngineFacade.contextSnapshot]
 *   （点开时拉取一次）—— 深潜线全量导出；标准/织造/信使三条 kit 线的引擎
 *   未开放消息导出 API，仅统计 + 降级说明（诚实告知，不假装有内容）；
 * - [usage]：CodeUiState 派生的会话累计（UsageUpdated 逐轮累加）——
 *   圆环详情的输入/输出/缓存命中率数据面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeContextSheet(
    snapshot: CodeContextSnapshot?,
    usage: CodeSessionUsage? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // i18n：Toast / 角色名在组合内预取（onClick 非组合上下文）
    val copiedToast = stringResource(R.string.code_context_copied)
    val roleNames = CodeContextRole.entries.associateWith { role ->
        stringResource(
            when (role) {
                CodeContextRole.SYSTEM -> R.string.code_context_role_system
                CodeContextRole.USER -> R.string.code_context_role_user
                CodeContextRole.ASSISTANT -> R.string.code_context_role_assistant
                CodeContextRole.TOOL -> R.string.code_context_role_tool
            }
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // 标题 + 复制全部
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.code_context_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                // 复制全文的纯文本形态（角色名前缀 + 内容逐条）
                val copyText = snapshot?.let { s ->
                    buildList {
                        s.globalRules?.takeIf { it.isNotBlank() }?.let {
                            add("${roleNames.getValue(CodeContextRole.SYSTEM)}: $it")
                        }
                        s.sessionExtras?.takeIf { it.isNotBlank() }?.let { add(it) }
                        s.entries.forEach { entry ->
                            add("${roleNames.getValue(entry.role)}: ${entry.content}")
                        }
                    }.joinToString("\n\n")
                }.orEmpty()
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(copyText))
                        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                    },
                    enabled = copyText.isNotBlank(),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.code_context_copy_all),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (snapshot == null) {
                Text(
                    text = stringResource(R.string.code_context_no_engine),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
                return@Column
            }

            // ═══ Token 用量卡（圆环点击要看的「具体用了多少」）═══
            UsageStatsCard(snapshot = snapshot, usage = usage)
            Spacer(modifier = Modifier.padding(top = 8.dp))

            if (snapshot.entries.isEmpty() &&
                snapshot.globalRules.isNullOrBlank() &&
                snapshot.sessionExtras.isNullOrBlank()
            ) {
                // kit 三线降级态：统计真实，内容不可见（引擎未开放导出）
                Text(
                    text = stringResource(R.string.code_context_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                ) {
                    // 注入段置顶：全局规则 / 会话附加（系统语义）
                    snapshot.globalRules?.takeIf { it.isNotBlank() }?.let {
                        item(key = "global-rules") {
                            ContextEntryRow(
                                roleName = roleNames.getValue(CodeContextRole.SYSTEM),
                                badge = stringResource(R.string.code_context_rules),
                                content = it
                            )
                        }
                    }
                    snapshot.sessionExtras?.takeIf { it.isNotBlank() }?.let {
                        item(key = "session-extras") {
                            ContextEntryRow(
                                roleName = roleNames.getValue(CodeContextRole.SYSTEM),
                                badge = stringResource(R.string.code_context_extras),
                                content = it
                            )
                        }
                    }
                    itemsIndexed(snapshot.entries, key = { i, _ -> "msg-$i" }) { _, entry ->
                        ContextEntryRow(
                            roleName = roleNames.getValue(entry.role),
                            badge = null,
                            content = entry.content
                        )
                    }
                }
                Spacer(modifier = Modifier.padding(bottom = 16.dp))
            }
        }
    }
}

/**
 * Token 用量卡：上下文占用（圆环的数字形态）+ 会话累计输入/输出 + 缓存命中率。
 *
 * 口径说明：
 * - 上下文行：snapshot 的引擎统计优先，缺省回退 [CodeSessionUsage]（UI 流
 *   同源数据）；百分比 = used / max；
 * - 输入/输出：会话累计（服务端 usage 真实统计逐轮累加，UsageUpdated 事件）；
 * - 缓存命中率 = cached / input；端点不返回缓存统计（cached = 0）时显式
 *   显示「暂无数据」而非误导性的 0%。
 */
@Composable
private fun UsageStatsCard(snapshot: CodeContextSnapshot, usage: CodeSessionUsage?) {
    val used = snapshot.tokenUsed.takeIf { it > 0 } ?: usage?.contextUsedTokens ?: 0
    val max = snapshot.tokenMax.takeIf { it > 0 } ?: usage?.contextMaxTokens ?: 0
    val pct = if (max > 0) used * 100L / max else 0L
    val inTokens = usage?.sessionInputTokens ?: 0
    val outTokens = usage?.sessionOutputTokens ?: 0
    val cached = usage?.sessionCachedTokens ?: 0
    val cacheKnown = inTokens > 0 && cached > 0

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                text = stringResource(R.string.code_context_usage_section),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            UsageRow(
                label = stringResource(R.string.code_context_usage_context),
                value = stringResource(R.string.code_context_usage_ctx_fmt, used, max, pct)
            )
            UsageRow(
                label = stringResource(R.string.code_context_usage_in),
                value = stringResource(R.string.code_context_usage_tokens_fmt, inTokens)
            )
            UsageRow(
                label = stringResource(R.string.code_context_usage_out),
                value = stringResource(R.string.code_context_usage_tokens_fmt, outTokens)
            )
            UsageRow(
                label = stringResource(R.string.code_context_usage_cache),
                value = if (cacheKnown) {
                    stringResource(R.string.code_context_usage_pct_fmt, cached * 100L / inTokens)
                } else {
                    stringResource(R.string.code_context_usage_cache_na)
                }
            )
            UsageRow(
                label = stringResource(R.string.code_context_usage_messages),
                value = stringResource(R.string.code_context_usage_tokens_fmt, snapshot.entries.size)
            )
        }
    }
}

/** 用量行：标签 + 右对齐数值（monospace 对齐数字列）。 */
@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 单条上下文条目：角色徽标（可带小节标注）+ 全文（可选中复制）。 */
@Composable
private fun ContextEntryRow(roleName: String, badge: String?, content: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            ) {
                Text(
                    text = if (badge != null) "$roleName · $badge" else roleName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        SelectionContainer {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
