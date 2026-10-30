package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.apex.agent.R
import kotlinx.coroutines.flow.update

// ═══════════════════════════════════════════════════════════════
// 消息操作（2026-10 重定口径）：复制 / 编辑
// ═══════════════════════════════════════════════════════════════
// 原 UX-1 的气泡 overflow 菜单（复制全文/重生成/删除/从此删除）退役 —— 操作
// 以图标行常驻气泡卡片外下方（复制全量保留；编辑仅用户气泡，见 UserBubble）。
// 本文件现承载「编辑并重发」的 VM 扩展与编辑对话框（与 AgentChatQuestionHandler.kt
// 同款拆分模式，避免 AgentChatViewModel.kt 触 God-file 门禁）。

/**
 * 编辑一条用户消息并以新文本重发。
 *
 * 语义：截断到该 User 气泡（不含，一并移除）及其后全部消息，然后复用
 * [AgentChatViewModel.retry] 以新文本 + 原附件（localPath 已落盘，直接透传）
 * 重发 —— 最终 UI 呈现 = [..., User(新文本), 重新生成的回复...]。
 *
 * - 空白新文本直接忽略（对话框发送钮同口径禁用）；
 * - isLoading 门禁：生成中拒绝并反馈（retry 会取消在途收集器，交错态不可预期）。
 */
fun AgentChatViewModel.editAndResend(id: String, newText: String) {
    val trimmed = newText.trim()
    if (trimmed.isEmpty()) return
    if (_uiState.value.isLoading) {
        _uiFeedback.tryEmit(str(R.string.chat_edit_busy))
        return
    }
    val idx = _uiState.value.messages.indexOfFirst { it.id == id }
    if (idx < 0) return
    val user = _uiState.value.messages[idx] as? AgentUiMessage.User ?: return
    // 截断：移除该 User 气泡及其后全部（retry→runEngine 会重新追加 User 气泡）
    _uiState.update { s -> s.copy(messages = s.messages.take(idx)) }
    // 二轮审计 A-4 同款：保留用户正在输入的草稿 —— retry() 的文本来自历史消息，
    // 当前实现不触碰输入框；但 sendMessage 路径会 updateInputText("") 清草稿，
    // 若日后 retry 对齐该语义，此处捕获-回写保证编辑重发不连带清掉未发送草稿。
    val draft = inputText.value
    retry(trimmed, user.attachments)
    if (draft.isNotEmpty()) updateInputText(draft)
}

/**
 * 编辑消息对话框（M3 AlertDialog + OutlinedTextField 预填原文）。
 *
 * 确认（重新发送）时回调 trim 后的新文本；空白文本时发送钮禁用 ——
 * 与 [editAndResend] 的空文本门禁双保险。
 */
@Composable
internal fun EditMessageDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onResend: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    // 组合内预取文案（onClick 非组合上下文）
    val titleLabel = stringResource(R.string.chat_edit_title)
    val sendLabel = stringResource(R.string.chat_edit_send)
    val cancelLabel = stringResource(R.string.chat_edit_cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = titleLabel, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 10
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onResend(text) }
            ) {
                Text(sendLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelLabel)
            }
        }
    )
}
