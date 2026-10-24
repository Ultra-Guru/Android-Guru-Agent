package com.apex.agent.ui.screen.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

// ═══════════════════════════════════════════════════════════════════════════
//  浏览器快照卡渲染（v1.8.0）—— BrowserSnapshot 的元素树文本 → 紧凑卡
// ═══════════════════════════════════════════════════════════════════════════

/** BrowserSnapshot 工具 id（输出载荷判定的单一事实源）。 */
internal const val BROWSER_SNAPSHOT_TOOL_ID = "BrowserSnapshot"

/** DomParser 摘要的固定首行标记（形状契约见 apex-kit-browser DomParser.buildSummary）。 */
private const val SNAPSHOT_HEADER_MARK = "⊕ 页面可交互元素"

/** 首行计数：「共 N 个」形态。 */
private val SNAPSHOT_COUNT_RE = Regex("共\\s*(\\d+)\\s*个")

/** 截断提示子串（DomParser 在触顶截断时写入首行）。 */
private const val SNAPSHOT_TRUNCATED_MARK = "已达单次抓取上限"

/**
 * 判定输出是否为 BrowserSnapshot 的快照摘要（结构性判断，只认首行标记）。
 */
internal fun isBrowserSnapshotPayload(output: String): Boolean =
    output.trimStart().startsWith(SNAPSHOT_HEADER_MARK)

/** 快照摘要的解析结果：交互元素数 / 是否触顶截断。 */
private data class SnapshotMeta(val count: Int, val truncated: Boolean)

private fun parseSnapshotMeta(output: String): SnapshotMeta {
    val header = output.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
    return SnapshotMeta(
        count = SNAPSHOT_COUNT_RE.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        truncated = header.contains(SNAPSHOT_TRUNCATED_MARK),
    )
}

/**
 * BrowserSnapshot 的输出渲染（v1.8.0）：元素树文本墙 → 紧凑快照卡。
 *
 * - 头部：标签 + 交互元素计数 chip（触顶截断时附「已截断」警示 chip）；
 * - 元素列表：等宽字体 + 限高滚动（max 300dp）——长列表不再把聊天流撑爆，
 *   也保留 [ref] 的对齐可读性；
 * - 解析失败（非快照形态）：执行 [fallback] 原文渲染，不吞错。
 */
@Composable
internal fun BrowserSnapshotOutput(
    output: String,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    if (!isBrowserSnapshotPayload(output)) {
        fallback()
        return
    }
    val meta = remember(output) { parseSnapshotMeta(output) }
    var expanded by remember(output) { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_browser_snapshot_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${meta.count}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
            if (meta.truncated) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.chat_browser_snapshot_truncated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(
                    if (expanded) R.string.chat_browser_snapshot_collapse
                    else R.string.chat_browser_snapshot_expand
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { expanded = !expanded }
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = output.trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            )
        }
    }
}
