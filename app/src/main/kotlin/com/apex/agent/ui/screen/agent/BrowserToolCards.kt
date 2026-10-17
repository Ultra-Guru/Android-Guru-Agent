package com.apex.agent.ui.screen.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.apex.agent.R

// ═══════════════════════════════════════════════════════════════════════════
// 浏览器工具卡渲染（从 AgentChatToolCards.kt 抽出：God-file 预算 + SRP ——
// 截图解码/缩略/放大属于「浏览器工具」专属职责，与通用工具卡解耦）。
// 同包 internal 可见性，AgentChatToolCards 直接调用，无需 import。
// ═══════════════════════════════════════════════════════════════════════════

/** BrowserScreenshot 工具 id（输出载荷判定的单一事实源）。 */
internal const val BROWSER_SCREENSHOT_TOOL_ID = "BrowserScreenshot"

/** PNG data URI 前缀 —— BrowserScreenshot 的标准输出形态（见工具实现）。 */
private const val PNG_DATA_URI_PREFIX = "data:image/png;base64,"

/** 合法 base64 体形状（NO_WRAP 编码：字符集受限 + 尾部至多两个 '='）。 */
private val BASE64_SHAPE = Regex("[A-Za-z0-9+/]+={0,2}")

/**
 * 判定输出是否为可渲染的截图载荷：
 * - 标准 data URI 前缀（BrowserScreenshot 的固定输出形态）；或
 * - 纯 base64 形态兜底（长度 ≥256 且为合法 base64 形状）——防截断/改写。
 *
 * 仅做结构性判断（不解码）；解码成败由 [decodeBrowserScreenshot] 决定，
 * 失败走调用方回退路径（原文渲染），绝不把 base64 文本墙硬画成图。
 */
internal fun isBrowserScreenshotPayload(output: String): Boolean {
    val text = output.trim()
    if (text.startsWith(PNG_DATA_URI_PREFIX)) return true
    return text.length >= 256 && text.length % 4 == 0 && BASE64_SHAPE.matches(text)
}

/**
 * 截图输出 → Bitmap（宽容失败 → null）：
 * 剥掉可选的 data URI 前缀后 Base64 解码 + BitmapFactory 解码。
 * 非法 base64（IllegalArgumentException）或非图片字节（decode 返回 null）
 * 一律折叠为 null，由 UI 回退到原文渲染。
 */
internal fun decodeBrowserScreenshot(output: String): Bitmap? = runCatching {
    val text = output.trim()
    val payload = if (text.startsWith(PNG_DATA_URI_PREFIX)) {
        text.substring(PNG_DATA_URI_PREFIX.length)
    } else text
    val bytes = Base64.decode(payload, Base64.NO_WRAP)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

/**
 * BrowserScreenshot 的输出渲染：base64 文本墙 → 圆角缩略图。
 *
 * - 缩略：fillMaxWidth + heightIn(max 260dp) + ContentScale.Fit（适配视口比）；
 * - 点击放大：Dialog 内全宽展示，再点图关闭；
 * - 内存纪律（用完即回收）：bitmap 生命周期收敛到本卡 —— 整卡离开组合
 *   （消息移除/列表滑出）时统一 recycle；放大 Dialog 与缩略图同卡共存、
 *   共享同一实例，不各自回收（避免「缩略图仍在绘制却已被 recycle」崩溃）。
 *   output 变化触发重新解码时，旧 effect 的 onDispose 先回收旧 bitmap。
 * - 解码失败：执行 [fallback]（调用方传原文渲染），不吞错误不留白卡。
 */
@Composable
internal fun BrowserScreenshotOutput(
    output: String,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val bitmap = remember(output) { decodeBrowserScreenshot(output) }
    if (bitmap == null) {
        fallback()
        return
    }
    DisposableEffect(output) {
        onDispose { bitmap.recycle() }
    }

    var zoomed by remember { mutableStateOf(false) }
    if (zoomed) {
        Dialog(onDismissRequest = { zoomed = false }) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.chat_cd_close_screenshot),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { zoomed = false },
                contentScale = ContentScale.Fit
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.chat_browser_screenshot_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.chat_cd_enlarge_screenshot),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { zoomed = true },
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.chat_browser_screenshot_size, bitmap.width, bitmap.height),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
