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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.CodeContextRole
import com.apex.agent.core.code.CodeContextSnapshot

/**
 * # Code Context Sheet — Coding 会话上下文查看（底部弹层）
 *
 * 展示当前激活逻辑线发给模型的上下文：会话消息序列（角色徽标 + 全文）
 * + 全局规则 / 会话附加注入段 + token 用量。数据来自
 * [com.apex.agent.core.code.CodeEngineFacade.contextSnapshot]（点开时
 * 拉取一次）：深潜线全量导出；标准/织造/信使三条 kit 线的引擎未开放
 * 消息导出 API，仅显示用量统计 + 降级说明（诚实告知，不假装有内容）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeContextSheet(
    snapshot: CodeContextSnapshot?,
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

            Text(
                text = stringResource(
                    R.string.code_context_stats_fmt,
                    snapshot.tokenUsed, snapshot.tokenMax, snapshot.entries.size
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                        .heightIn(max = 460.dp)
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
