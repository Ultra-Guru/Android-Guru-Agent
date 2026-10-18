package com.apex.agent.ui.screen.about

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.BuildConfig
import com.apex.agent.R
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.update.UpdateHttp
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * ═══════════════════════════════════════════════════════════════
 *  关于页「问题反馈」—— 官方后端（Guru API）上线后的邮件直达通道
 * ═══════════════════════════════════════════════════════════════
 *
 * 表单：反馈内容（必填 2–2000 字，实时计数）+ 联系方式（选填 ≤120 字）。
 * 提交 POST 到 Guru API 上报端点 —— 后端自动把反馈转发到开发者邮箱；
 * 服务端限流（429）与参数拒绝（4xx）的 error 文案直接透传给用户。
 *
 * 设计约束（与 AboutUpdateDialogs 同族的对话框视觉 + UpdateChecker
 * 同款的网络纪律）：
 * - **零新依赖**：OkHttp 复用更新链路的进程级共享客户端（[UpdateHttp]），
 *   JSON 走项目既有 kotlinx.serialization；
 * - **可取消**：enqueue + invokeOnCancellation —— 关闭对话框即断流，
 *   不占用线程等到超时；响应体在 OkHttp 回调线程内读完关闭，主线程零阻塞；
 * - **防御式 IO**：任何响应形状都吞得下 —— 4xx 解析不出 error 文案时
 *   退化为「HTTP 状态码」，网络层失败折叠为通用网络错误（可重试）；
 * - **蜜罐**：website 字段正常提交永远传空字符串（后端命中非空即丢弃）；
 * - **无持久化**：表单与结果状态全部 remember 于对话框生命周期内，
 *   关闭即重置。
 *
 * 证书说明：Guru API 域名使用 Let's Encrypt 签发的正式证书，
 * HTTPS 正常校验即可，无需任何自定义信任策略。
 */

/** Guru API 官方域名 —— 官网与后端服务同域（相关链接区「官网」共用本常量）。 */
internal const val GURU_API_BASE_URL = "https://47.253.106.59.sslip.io"

/** 问题反馈上报端点（后端转发开发者邮箱）。 */
private const val FEEDBACK_REPORT_URL = "$GURU_API_BASE_URL/api/v1/report"

/** 反馈内容长度下限（与后端同规则：必填 2–2000 字）。 */
private const val MIN_MESSAGE_LENGTH = 2

/** 反馈内容长度上限。 */
private const val MAX_MESSAGE_LENGTH = 2000

/** 联系方式长度上限（选填，≤120 字）。 */
private const val MAX_CONTACT_LENGTH = 120

private val feedbackJson = Json { ignoreUnknownKeys = true }

/**
 * 上报载荷 —— 与 Guru API 的 /api/v1/report 契约一一对应。
 * [website] 是蜜罐字段：正常提交永远传空字符串。
 */
@Serializable
private data class FeedbackReport(
    val message: String,
    val contact: String = "",
    val appVersion: String = "",
    val device: String = "",
    val website: String = ""
)

/** 上报响应体：200 携带 ok=true；4xx 携带 error 原因文案。 */
@Serializable
private data class FeedbackAck(
    val ok: Boolean = false,
    val error: String? = null
)

/** 问题反馈上报结果三态 —— 对话框按类型渲染，无需解析错误码。 */
internal sealed interface FeedbackResult {

    /** 上报成功（后端已受理并转发开发者邮箱）。 */
    data object Success : FeedbackResult

    /** 后端拒绝（4xx，含 429 限流）—— [reason] 为服务端 error 文案。 */
    data class Rejected(val reason: String) : FeedbackResult

    /** 网络层失败（无网/超时/DNS）—— UI 显示通用网络错误并允许重试。 */
    data object NetworkError : FeedbackResult
}

/**
 * 提交问题反馈 —— [message] 为 trim 后的反馈内容，[contact] 为可选联系方式。
 * appVersion 与 device 由 BuildConfig / Build 自动携带（如 v1.4.9.36 ·
 * Google Pixel 8 · Android 14），无需用户填写。
 */
internal suspend fun submitFeedback(message: String, contact: String): FeedbackResult {
    val payload = feedbackJson.encodeToString(
        FeedbackReport.serializer(),
        FeedbackReport(
            message = message,
            contact = contact,
            appVersion = "v${BuildConfig.VERSION_NAME}",
            device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        )
    )
    val request = Request.Builder()
        .url(FEEDBACK_REPORT_URL)
        .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    return postAwait(request)
}

/**
 * 可取消的同步化 POST：协程取消 → call.cancel()（对话框关闭即断流）。
 * 与 UpdateChecker.awaitBody / ChangelogRepository 同款纪律 —— 唯一差别
 * 是不折叠错误码：200 → 成功；4xx → 解析服务端 error 文案透传给用户。
 */
private suspend fun postAwait(request: Request): FeedbackResult =
    suspendCancellableCoroutine { cont ->
        val call = UpdateHttp.client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val text = runCatching { resp.body?.string() }.getOrNull()
                    val outcome = parseOutcome(resp.code, text)
                    AppLogger.instance.info(
                        LogCategory.SYSTEM,
                        "Feedback",
                        "问题反馈上报${if (outcome is FeedbackResult.Success) "成功" else "被拒"}（HTTP ${resp.code}）"
                    )
                    if (cont.isActive) cont.resumeWith(Result.success(outcome))
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM,
                    "Feedback",
                    "问题反馈上报网络失败：${e.message}"
                )
                if (cont.isActive) cont.resumeWith(Result.success(FeedbackResult.NetworkError))
            }
        })
    }

/** 响应分诊：200 成功 / 4xx 透传服务端文案 / 其余（5xx、异常形状）按网络错误重试。 */
private fun parseOutcome(code: Int, body: String?): FeedbackResult = when (code) {
    200 -> FeedbackResult.Success
    in 400..499 -> FeedbackResult.Rejected(
        runCatching { feedbackJson.decodeFromString(FeedbackAck.serializer(), body ?: "") }
            .getOrNull()
            ?.error
            .takeUnless { it.isNullOrBlank() }
            ?: "HTTP $code"
    )
    else -> FeedbackResult.NetworkError
}

/**
 * 问题反馈对话框 —— [AboutScreen] 相关链接区之后的入口点击弹出。
 *
 * 三态流转：编辑（默认）→ 发送中（按钮 loading 禁用）→ 终态（成功展示
 * 致谢并可关闭；失败透传后端 error 或通用网络错误，表单保留可直接重试）。
 * 网络调用挂起在 rememberCoroutineScope —— 关闭对话框即取消在途请求。
 */
@Composable
internal fun AboutFeedbackDialog(onDismiss: () -> Unit) {
    var message by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FeedbackResult?>(null) }
    val scope = rememberCoroutineScope()

    // 提交门卫：内容 trim 后落在 2–2000 字区间才放行（与后端校验同规则）
    val trimmedMessage = message.trim()
    val canSubmit = !sending && trimmedMessage.length in MIN_MESSAGE_LENGTH..MAX_MESSAGE_LENGTH

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.about_feedback_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val state = result
                if (state is FeedbackResult.Success) {
                    // 成功态：对勾 + 致谢文案（表单退场，确认键即关闭）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            stringResource(R.string.about_feedback_success),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.about_feedback_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = message,
                        onValueChange = { if (it.length <= MAX_MESSAGE_LENGTH) message = it },
                        label = { Text(stringResource(R.string.about_feedback_message_label)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.about_feedback_counter,
                                    message.length,
                                    MAX_MESSAGE_LENGTH
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        isError = message.isNotEmpty() && trimmedMessage.length < MIN_MESSAGE_LENGTH,
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = contact,
                        onValueChange = { if (it.length <= MAX_CONTACT_LENGTH) contact = it },
                        label = { Text(stringResource(R.string.about_feedback_contact_label)) },
                        supportingText = {
                            Text(
                                stringResource(R.string.about_feedback_contact_hint),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // 失败提示：后端拒绝透传 error 文案；网络层失败给通用语 —— 均可重试
                    when (state) {
                        is FeedbackResult.Rejected -> Text(
                            stringResource(R.string.about_feedback_failed, state.reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        FeedbackResult.NetworkError -> Text(
                            stringResource(R.string.about_feedback_network_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        else -> Unit
                    }
                }
            }
        },
        confirmButton = {
            val state = result
            if (state is FeedbackResult.Success) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.about_feedback_done))
                }
            } else {
                TextButton(
                    onClick = {
                        sending = true
                        result = null
                        // 挂起上报：结果回填三态；对话框关闭 → scope 取消 → 断流
                        scope.launch {
                            val outcome = submitFeedback(trimmedMessage, contact.trim())
                            sending = false
                            result = outcome
                        }
                    },
                    enabled = canSubmit
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        stringResource(
                            if (sending) R.string.about_feedback_sending
                            else R.string.about_feedback_submit
                        )
                    )
                }
            }
        },
        dismissButton = {
            // 发送中不提供取消（防误触半途而废）；成功态只有确认键
            if (result !is FeedbackResult.Success && !sending) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.about_feedback_cancel))
                }
            }
        }
    )
}
