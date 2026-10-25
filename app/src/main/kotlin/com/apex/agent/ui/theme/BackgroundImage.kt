package com.apex.agent.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ═══ 自定义背景图层（主题背景 · 专属优化）═══
 *
 * 设置中心「界面外观 → 自定义背景」导入的图片在此渲染，垫在整个应用
 * 内容之下（MainActivity Surface 内首个子层）。
 *
 * **专属优化**（相对朴素 Image(uri) 方案的差异，全部为可感知的工程收益）：
 *
 * 1. **降采样解码**（[decodeSubsampledBackground]）：48MP 照片直解 ≈
 *    190MB ARGB 内存（稳 OOM）；按目标长边 1440px 求 inSampleSize 幂次
 *    降采样后 ≈ 8MB——大图常驻不爆内存，渲染成本也随分辨率同步下降；
 * 2. **亮度自适应纱罩**（[estimateLuminance] + [scrimAlphaFor]）：对
 *    降采样位图稀疏采样求平均亮度 → 暗色主题下图片越亮纱罩越深 /
 *    亮色主题下纱罩用白且亮度越低越厚——文字对比度有下限保证，
 *    不靠用户手调；
 * 3. **一图一解码**：`produceState(backgroundImageUri)` 以 URI 为 key，
 *    换图才重解码；重组（切页/滚动）零 IO 零解码；
 * 4. **失败诚实降级**：URI 失效（授权被回收/文件被删）→ 解码返回 null →
 *    背景层整体不渲染（回退纯色背景）+ WARN 日志，绝不崩、绝不白屏。
 *
 * @param uri SAF 持久授权的 content URI
 * @param darkTheme 当前深浅色（纱罩颜色与自适应基数随之切换）
 * @param dim 用户纱罩强度 0.1..0.8（叠加在自适应基数之上，见 [scrimAlphaFor]）
 */
@Composable
fun CustomBackgroundLayer(
    uri: String,
    darkTheme: Boolean,
    dim: Float,
    modifier: Modifier = Modifier
) {
    if (uri.isBlank()) return
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) { decodeSubsampledBackground(context, uri) }
    }
    val bmp = bitmap ?: return
    val luminance = remember(bmp) { estimateLuminance(bmp) }

    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = bmp,
            contentDescription = null, // 纯装饰背景，无语义
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // 专属优化 2：亮度自适应纱罩（可读性下限保证）。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    scrimColor(darkTheme).copy(
                        alpha = scrimAlphaFor(darkTheme, luminance, dim)
                    )
                )
        )
    }
}

/** 暗色主题纱罩基色（近黑微蓝，与终端原生基底同语言）。 */
internal fun scrimColor(darkTheme: Boolean): Color =
    if (darkTheme) Color(0xFF070A0E) else Color(0xFFF6F8FA)

/**
 * 自适应纱罩透明度：自适应基数（按图片亮度）+ 用户强度叠加，钳在
 * [MIN_SCRIM_ALPHA, MAX_SCRIM_ALPHA]。
 *
 * - 暗色主题：图片越亮基数越深（亮图上的浅色文字需要更深的底）；
 * - 亮色主题：图片越暗基数越厚（暗图上的深色文字需要更白的底）；
 * - 用户 dim（0.1..0.8）在此基础上叠加（默认 0.4 已足够多数照片）。
 */
internal fun scrimAlphaFor(darkTheme: Boolean, luminance: Float, userDim: Float): Float {
    val autoBase = if (darkTheme) luminance * 0.35f else (1f - luminance) * 0.35f
    return (autoBase + userDim).coerceIn(MIN_SCRIM_ALPHA, MAX_SCRIM_ALPHA)
}

private const val MIN_SCRIM_ALPHA = 0.10f
private const val MAX_SCRIM_ALPHA = 0.85f

/**
 * 降采样解码（专属优化 1）：bound 解码求原始尺寸 → 幂次 inSampleSize →
 * 目标长边 [TARGET_LONG_SIDE]px 内的实际解码。任何失败返回 null
 * （调用方整体不渲染背景层，见类注释第 4 点）。
 */
internal fun decodeSubsampledBackground(context: Context, uriString: String): ImageBitmap? {
    return try {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longSide / (sample * 2) >= TARGET_LONG_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        }?.asImageBitmap()
    } catch (e: Exception) {
        // 授权回收 / 文件被删 / 解码失败：诚实降级（无背景）+ 留痕。
        runCatching {
            AppLogger.instance.warn(
                LogCategory.SYSTEM, "CustomBackgroundLayer",
                "背景图解码失败，已回退纯色背景（${e.javaClass.simpleName}: ${e.message}）" +
                    "—— 若持续出现请在 设置 → 界面外观 清除背景"
            )
        }
        null
    }
}

/** 降采样的目标长边（px）：多数设备屏幕长边的安全上界，兼顾清晰与内存。 */
private const val TARGET_LONG_SIDE = 1440

/**
 * 位图平均亮度估算（专属优化 2 的信号源）：稀疏采样（步长 ≈ 边长/16）
 * 求感知亮度均值（0 = 纯黑，1 = 纯白）。空位图返回中性 0.5。
 */
internal fun estimateLuminance(bitmap: ImageBitmap): Float {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return 0.5f
    val stepX = (w / 16).coerceAtLeast(1)
    val stepY = (h / 16).coerceAtLeast(1)
    var sum = 0f
    var count = 0
    for (x in 0 until w step stepX) {
        for (y in 0 until h step stepY) {
            val c = bitmap.getPixel(x, y) // 稀疏采样，量级 ≤ 17×17 次读取
            sum += 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
            count++
        }
    }
    return if (count == 0) 0.5f else sum / count
}
