package com.apex.agent.ui.icons

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

/**
 * # FUSION 王冠图标 —— 工具聚合体系的视觉身份
 *
 * 形制临摹自开源图标库 **Lucide** 的 `crown` 图标（ISC License，
 * https://lucide.dev/icons/crown，24×24 视口、圆角描边语言），路径
 * 逐锚点移植为 Compose 矢量：SVG 中的 6 段装饰性小圆弧（r=0.5 / r=1）
 * 以等价的二次贝塞尔逼近（锚点几何推导，apex 误差 < 0.25 单位 ——
 * 24dp 渲染下亚像素级），整体轮廓与原作一致：
 *
 * - 中央冠尖（12, 3.0）圆润宝顶；
 * - 左右冠尖外扩（2.23, 5.47）/（21.77, 4.97），三峰王冠剪影；
 * - 双内谷弧线内收，冠壁外撑至冠带；
 * - 冠带双圆角 + 底部横线（5→19, y=21）。
 *
 * 两级入口：
 * - [FusionCrown]：`ImageVector`（描边 + 淡金填充），徽章 / 流水线
 *   横幅 / 菜单标题等 `Icon(imageVector=…)` 场景；
 * - [FusionCrownCanvas]：Canvas 精修版 —— 王金渐变描边 + 三颗冠尖
 *   宝石 + 激活态宝石微光呼吸（infinite alpha 脉冲），输入栏王冠
 *   按钮与层级菜单头部使用。
 *
 * 配色：王金（amber 族 #FBBF24 → #D97706 渐变；实体色 #D97706）。
 * 与 PLUGIN（#F59E0B 扩展图标）同属琥珀族但形制迥异（王冠 vs 拼图），
 * 且王冠是 FUSION 体系唯一身份符号，视觉冲突可控。
 */

/** 王金亮端（渐变上端 / 宝石色）。 */
private val CrownGoldLight = Color(0xFFFBBF24)

/** 王金深端（渐变下端 / 描边实体色，与 repo 琥珀用色 #D97706 对齐）。 */
val CrownGold = Color(0xFFD97706)

/** 王冠主体路径（24×24 视口坐标；remember 复用，勿跨线程共享）。 */
private fun crownBodyPath(): Path = Path().apply {
    moveTo(11.562f, 3.266f)
    // 中央冠尖宝顶（SVG 弧 r=0.5 → 等价二次曲线，apex=(12, 3.007)）
    quadraticBezierTo(12.0f, 2.748f, 12.438f, 3.266f)
    // 右内谷下探
    lineTo(15.39f, 8.87f)
    // 右内谷弧（SVG 弧 r=1 → 谷底 apex=(16.079, 9.375)）
    quadraticBezierTo(16.01f, 9.733f, 16.906f, 9.164f)
    // 上行至右冠尖
    lineTo(21.183f, 5.5f)
    // 右冠尖（SVG 弧 r=0.5 → apex=(21.772, 4.968)）
    quadraticBezierTo(21.964f, 4.674f, 21.981f, 6.019f)
    // 右冠壁下探至冠带右圆角
    lineTo(19.147f, 16.265f)
    // 冠带右圆角（SVG 弧 r=1 → apex=(18.792, 16.792)）
    quadraticBezierTo(18.915f, 16.952f, 18.191f, 17.0f)
    // 冠带底边
    lineTo(5.81f, 17.0f)
    // 冠带左圆角（apex=(5.209, 16.792)）
    quadraticBezierTo(5.086f, 16.952f, 4.853f, 16.265f)
    // 左冠壁上探至左冠尖
    lineTo(2.02f, 6.02f)
    // 左冠尖（SVG 弧 r=0.5 → apex=(2.229, 5.469)）
    quadraticBezierTo(2.037f, 5.176f, 2.818f, 5.501f)
    // 左内谷下探
    lineTo(7.094f, 9.165f)
    // 左内谷弧（apex=(7.921, 9.376)）
    quadraticBezierTo(7.99f, 9.734f, 8.61f, 8.871f)
    close()
}

/** 冠带底线（5→19, y=21；单描边横线）。 */
private fun crownBaseLine(): Path = Path().apply {
    moveTo(5f, 21f)
    lineTo(19f, 21f)
}

/**
 * [FusionCrown] 的 `ImageVector` 形态：Lucide crown 描边 + 淡金填充。
 *
 * 用于 `Icon(imageVector = FusionCrown, tint = CrownGold)` 场景
 * （工具徽章 / 流水线横幅 / 菜单标题）。tint 交由调用方控制；传
 * `Color.Unspecified` 可保留内建王金色。
 */
val FusionCrown: ImageVector by lazy {
    ImageVector.Builder(
        name = "FusionCrown",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(CrownGold.copy(alpha = 0.13f)),
            stroke = SolidColor(CrownGold),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(11.562f, 3.266f)
            quadTo(12.0f, 2.748f, 12.438f, 3.266f)
            lineTo(15.39f, 8.87f)
            quadTo(16.01f, 9.733f, 16.906f, 9.164f)
            lineTo(21.183f, 5.5f)
            quadTo(21.964f, 4.674f, 21.981f, 6.019f)
            lineTo(19.147f, 16.265f)
            quadTo(18.915f, 16.952f, 18.191f, 17.0f)
            lineTo(5.81f, 17.0f)
            quadTo(5.086f, 16.952f, 4.853f, 16.265f)
            lineTo(2.02f, 6.02f)
            quadTo(2.037f, 5.176f, 2.818f, 5.501f)
            lineTo(7.094f, 9.165f)
            quadTo(7.99f, 9.734f, 8.61f, 8.871f)
            close()
        }
        path(
            stroke = SolidColor(CrownGold),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(5f, 21f)
            lineTo(19f, 21f)
        }
    }.build()
}

/**
 * # FUSION 王冠 Canvas 精修版（输入栏按钮 / 菜单头部用）
 *
 * 在 [FusionCrown] 剪影之上的三重精修：
 * 1. **王金渐变描边**：#FBBF24 → #D97706 纵向渐变（王冠自上而下
 *    由亮入深，日间如鎏金、暗间如熔金）；
 * 2. **三颗冠尖宝石**：左 / 中 / 右冠尖 apex 处 r≈0.72 的实心宝珠
 *    （中央主珠稍大），未激活为静态金珠；
 * 3. **激活态微光呼吸**：[active] = true 时宝珠与淡金填充以 1.6s
 *    周期柔和呼吸（alpha 0.55 ↔ 1.0）——「体系点亮，王冠生辉」，
 *    与斜杠按钮的渐变身份同为输入栏的动态锚点。
 *
 * @param active 是否有激活中的 FUSION 包（点亮呼吸动画）
 * @param modifier 尺寸由调用方给定（建议 20–28dp）
 */
@Composable
fun FusionCrownCanvas(
    active: Boolean,
    modifier: Modifier = Modifier
) {
    // 激活态微光：宝珠 + 填充 alpha 呼吸（1.6s 往返）
    val shimmer = if (active) {
        val t = rememberInfiniteTransition(label = "fusion-crown-shimmer")
        t.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "fusion-crown-alpha"
        ).let { it.value }
    } else {
        1f
    }

    val body = remember { crownBodyPath() }
    val base = remember { crownBaseLine() }

    Canvas(modifier = modifier) {
        val scale = minOf(size.width / 24f, size.height / 24f)
        // 渐变坐标在 withTransform 缩放后的局部 24 单位制空间内取值
        //（Skia shader 随画布矩阵变换）—— 0..24 恰覆盖冠体 y 3..21。
        val goldBrush = Brush.verticalGradient(
            colors = listOf(CrownGoldLight, CrownGold),
            startY = 0f,
            endY = 24f
        )
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            // 冠体淡金填充（激活时随呼吸加深）
            drawPath(
                path = body,
                color = CrownGold.copy(alpha = 0.10f * shimmer)
            )
            // 冠体渐变描边
            drawPath(
                path = body,
                brush = goldBrush,
                style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // 冠带底线
            drawPath(
                path = base,
                brush = goldBrush,
                style = Stroke(width = 1.8f, cap = StrokeCap.Round)
            )
            // 三颗冠尖宝石（中央主珠 r=0.82，双翼 r=0.68）
            drawCircle(color = CrownGoldLight.copy(alpha = shimmer), radius = 0.82f, center = Offset(12f, 3.0f))
            drawCircle(color = CrownGoldLight.copy(alpha = shimmer), radius = 0.68f, center = Offset(2.23f, 5.47f))
            drawCircle(color = CrownGoldLight.copy(alpha = shimmer), radius = 0.68f, center = Offset(21.77f, 4.97f))
        }
    }
}
