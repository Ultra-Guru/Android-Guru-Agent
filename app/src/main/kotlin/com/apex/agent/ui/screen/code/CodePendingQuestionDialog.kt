package com.apex.agent.ui.screen.code

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * 引擎 `AskUser` 提问弹窗（#509）。
 *
 * 三处语义由本组件负责，缺一即让用户「被锁在弹窗里」或让模型「把取消当同意」：
 *
 * 1. **可取消**：返回键 / 点击外部 / 取消按钮都走 [onCancel] —— 旧实现
 *    `onDismissRequest = { }` 是空 lambda，用户只能提交才能退出；
 * 2. **不伪造肯定答案**：空输入时提交按钮禁用，不再把空串替换成
 *    `code_question_default_yes`（「是」）—— 那会让模型收到一个它从未得到的同意；
 * 3. **答案不跨问题继承**：`rememberSaveable(question)` 以问题文本为 key，
 *    连续两次不同提问不会带上一条的残留答案，旋转/重建仍保留输入。
 */
@Composable
internal fun PendingQuestionDialog(
    question: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var answer by rememberSaveable(question) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.code_question_title)) },
        text = {
            Column {
                Text(question, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    placeholder = { Text(stringResource(R.string.code_question_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.code_question_cancel_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = answer.isNotBlank(),
                onClick = { onSubmit(answer.trim()) }
            ) { Text(stringResource(R.string.code_question_submit)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.code_question_cancel))
            }
        }
    )
}
