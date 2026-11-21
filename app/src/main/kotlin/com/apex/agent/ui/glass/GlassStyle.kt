package com.apex.agent.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.theme.PartSkin

/**
 * ═══════════════════════════════════════════════════════════════
 *  Liquid Glass 材质系统 —— GlassStyle（kyant0/backdrop 底座）
 * ═══════════════════════════════════════════════════════════════
 *
 * 底层引擎：vendored kyant0/AndroidLiquidGlass（backdrop @ 1.0.0，Apache-2.0，
 * vendor/backdrop/）。真实能力分层（诚实声明，禁止冒充）：
 *  - API 33+（T）：AGSL RuntimeShader —— lens 折射/色散（真液态玻璃形变）
 *    + Default/Ambient 高光着色；
 *  - API 31+（S）：RenderEffect —— GPU blur 链 + ColorFilter（vibrancy）；
 *  - API < 31：无 RenderEffect —— GlassSurface 直接走 Frosted 档（霜面 +
 *    边缘光 + 阴影），不绘制未模糊的 backdrop。
 *
 * 设计原则（Selective Liquid Glass Spec §1/§4/§18 延续）：
 *  1. 不同组件不得使用完全相同的玻璃材质 —— 每个档位独立调参；
 *  2. tint / 高光 / 阴影全部从 MaterialTheme 动态派生，跟随 Light/Dark/Dynamic；
 *  3. 采样源由业务页面显式提供（rememberLayerBackdrop）—— 不再有无中生有的
 *    「假 backdrop」。
 *
 * Gate-0（玻璃实验室成品化）P-1 同源：本档位常量自 GlassRecipeBook 生成
 * （数值唯一真源在 [GlassRecipe.kt]），实验室与生产读同一份配方；
 * [GlassRecipeParityTest] 逐字段锁定。GlassTier 枚举亦已迁入 GlassRecipe.kt
 * （包不变，引用零改动）。
 */

/**
 * 玻璃材质参数集。字段与 kyant0 效果链的映射：
 *  - [blurRadius] → `effects { blur() }`（API 31+ GPU RenderEffect）
 *  - [lensHeight]/[lensAmount] → `effects { lens() }`（API 33+ 折射；0 = 不折射）
 *  - [tintAlpha] → `onDrawSurface` 霜面染色强度
 *  - [edgeAlpha]/[specularAlpha] → Highlight 边缘受光（BlurMaskFilter + 着色器）
 *  - [elevation] → Shadow 投影
 *  - [scrimAlpha] → Frosted 档（无采样）底色浓度
 */
@Immutable
data class GlassStyle(
    val tier: GlassTier,
    /** Backdrop 模糊半径（API 31+）*/
    val blurRadius: Dp,
    /** 材质着色强度 0..1 —— 玻璃霜面底色透明度 */
    val tintAlpha: Float,
    /** lens 折射带高度（API 33+；0.dp = 关闭折射）*/
    val lensHeight: Dp,
    /** lens 折射位移量（API 33+）*/
    val lensAmount: Dp,
    /** lens 色散（边缘彩虹分离；对话/卡片档默认关，悬浮件可开）*/
    val chromaticAberration: Boolean,
    /** 背景饱和度提升（vibrancy，API 31+ ColorFilter）*/
    val vibrancy: Float,
    /** 边缘高光强度 0..1 —— Highlight 受光的基准亮度 */
    val edgeAlpha: Float,
    /** 顶部镜面高光强度 0..1 */
    val specularAlpha: Float,
    /** 外阴影深度 —— 物理深度线索 */
    val elevation: Dp,
    /** 按压缩放系数 —— pressed 时的形变反馈（layerBlock 内实现，backdrop 采样自动反演）*/
    val pressedScale: Float,
    /** Frosted 档（state == null 或 API < 31）底色浓度 0..1 */
    val scrimAlpha: Float
) {
    companion object {
        // ═══ Gate-0 P-1 同源：九档全部从 GlassRecipeBook 生成（唯一真源），
        // 语义注释移至 GlassRecipe.kt；参数改动先过 GlassRecipeParityTest（H-7）。═══

        /** 极轻玻璃：小型静态状态组件（徽标）—— 不折射，避免小尺寸形变夸张。 */
        val Subtle = GlassRecipeBook.Subtle.toGlassStyle()

        /** 控制件玻璃：返回按钮 / 图标按钮 / 输入控制器 —— 轻折射（按压形变）。 */
        val Control = GlassRecipeBook.Control.toGlassStyle()

        /** 卡片玻璃：Agent 卡 / Tool 卡 / 状态卡 / 设置与诊断页卡片。 */
        val Card = GlassRecipeBook.Card.toGlassStyle()

        /** 导航项玻璃：Drawer 每一项 —— 正常态必须「非常轻」。 */
        val Navigation = GlassRecipeBook.Navigation.toGlassStyle()

        /** 悬浮件玻璃：FAB / 输入栏 / 快捷浮动操作 —— 最强折射与高光，色散可辨。 */
        val Floating = GlassRecipeBook.Floating.toGlassStyle()

        /** 对话框玻璃：强材质，但内容保持清晰。 */
        val Dialog = GlassRecipeBook.Dialog.toGlassStyle()

        /** 最强玻璃：低频、高聚焦的特殊场景。 */
        val Strong = GlassRecipeBook.Strong.toGlassStyle()

        /** 聊天气泡玻璃（档位语义沿用 Card，参数差异化 —— 详见 GlassRecipeBook.Bubble）。 */
        val Bubble = GlassRecipeBook.Bubble.toGlassStyle()

        /** 浅白液态玻璃（PartSkin spec §6.2，详见 GlassRecipeBook.PaleLiquid）。 */
        val PaleLiquid = GlassRecipeBook.PaleLiquid.toGlassStyle()
    }
}

/**
 * 配方 → 材质参数（Gate-0 P-1）：Compose 侧映射 —— Float dp 值升为 Dp，
 * 其余逐字段直传。GlassStyle 不承载 corner / accent / glow / partSkin
 * （那是配方与部件皮肤体系的维度），由消费方直接读配方。
 */
fun GlassRecipe.toGlassStyle(): GlassStyle = GlassStyle(
    tier = tier,
    blurRadius = blurRadiusDp.dp,
    tintAlpha = tintAlpha,
    lensHeight = lensHeightDp.dp,
    lensAmount = lensAmountDp.dp,
    chromaticAberration = chromaticAberration,
    vibrancy = vibrancy,
    edgeAlpha = edgeAlpha,
    specularAlpha = specularAlpha,
    elevation = elevationDp.dp,
    pressedScale = pressedScale,
    scrimAlpha = scrimAlpha
)

/**
 * 配方 → 部件皮肤（Gate-0 P-1）：key 镜像映射，未知 key 防御式回退
 * FOLLOW（跟随上级 —— 不冒充、不抛异常）。
 */
fun GlassRecipe.toPartSkin(): PartSkin = when (partSkinKey) {
    RecipeSkinKeys.PALE_LIQUID -> PartSkin.PALE_LIQUID
    RecipeSkinKeys.CLASSIC_GLASS -> PartSkin.CLASSIC_GLASS
    RecipeSkinKeys.FLAT -> PartSkin.FLAT
    else -> PartSkin.FOLLOW
}

/**
 * 主题派生颜色集 —— 组合期从 MaterialTheme 解析，Light/Dark 双态。
 * 深色主题玻璃偏「提亮」——近黑基底上的发光材质；
 * 浅色主题玻璃偏「白霜」——白基底上的乳白材质。
 */
@Immutable
internal data class GlassPalette(
    val dark: Boolean,
    /** Backdrop 档霜面染色（onDrawSurface 绘制在模糊采样之上）*/
    val glassTint: Color,
    /** Frosted 档底色（无采样的诚实降级底）*/
    val frostBase: Color,
    /** Frosted 档顶部提亮色 */
    val frostLift: Color,
    /** 边缘高光色 —— Highlight 受光 */
    val edgeColor: Color,
    /** 外阴影色 */
    val shadowColor: Color
)

/**
 * 当前主题下的玻璃调色板。跟随 Dynamic Color。
 */
@Composable
internal fun glassPalette(style: GlassStyle, accent: Color): GlassPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val base = if (dark) {
        GlassPalette(
            dark = dark,
            // 深色：玻璃 = 比基底略亮的材质，主色轻微浸染呼应强调色主题
            glassTint = scheme.surfaceContainerHigh.copy(alpha = style.tintAlpha)
                .compositeOverNeutral(scheme.primary.copy(alpha = 0.06f)),
            frostBase = scheme.surfaceContainerHigh.copy(alpha = style.scrimAlpha),
            frostLift = scheme.surfaceContainerHighest.copy(
                alpha = style.scrimAlpha * 0.6f + style.specularAlpha * 0.8f
            ),
            // #269 豁免说明：玻璃体系的白高光是物理语义 —— 光照在玻璃上的
            // 镜面反射就是白色，与主题明暗无关（刻意设计，非漏网）。
            edgeColor = Color.White.copy(alpha = style.edgeAlpha),
            shadowColor = Color.Black.copy(alpha = 0.30f)
        )
    } else {
        GlassPalette(
            dark = dark,
            // 浅色：乳白磨砂 —— surfaceVariant 比纯白深一档，白底上叠得出「一层玻璃」；
            // 再薄叠 primary（0.05）给玻璃一点主题色倾向 —— 只给倾向，不刷屏
            glassTint = scheme.surfaceVariant.copy(alpha = style.tintAlpha)
                .compositeOverNeutral(scheme.primary.copy(alpha = 0.05f)),
            frostBase = scheme.surfaceVariant.copy(
                alpha = (style.scrimAlpha + 0.10f).coerceAtMost(1f)
            ),
            frostLift = Color.White.copy(alpha = style.specularAlpha * 1.6f + 0.20f),
            edgeColor = Color.White.copy(alpha = style.edgeAlpha * 1.3f + 0.07f),
            // 白天投影更淡：白底上重阴影显脏
            shadowColor = Color.Black.copy(alpha = 0.12f)
        )
    }
    // 状态强调色：工具卡运行态 / 错误态等着色 —— 不用大面积高饱和，保持克制
    return if (accent.alpha > 0f) base.copy(
        glassTint = accent.copy(alpha = 0.10f + 0.14f * style.tintAlpha)
            .compositeOverNeutral(base.glassTint),
        edgeColor = accent.copy(alpha = style.edgeAlpha * 1.1f)
            .compositeOverNeutral(base.edgeColor)
    ) else base
}

/** 将薄薄一层色叠加到中性基色 —— 让主色/强调色只提供“倾向”而不刷屏。 */
@Stable
private fun Color.compositeOverNeutral(overlay: Color): Color {
    if (overlay.alpha <= 0f) return this
    return Color(
        red = red * (1f - overlay.alpha) + overlay.red * overlay.alpha,
        green = green * (1f - overlay.alpha) + overlay.green * overlay.alpha,
        blue = blue * (1f - overlay.alpha) + overlay.blue * overlay.alpha,
        alpha = alpha
    )
}

/** 应用级玻璃圆角基准 —— 与既有设计令牌对齐：chip 6 / 卡片 12 / 气泡 18。 */
object GlassShapes {
    val button = RoundedCornerShape(50)
    val card = RoundedCornerShape(12.dp)
    val navItem = RoundedCornerShape(12.dp)
    val floating = RoundedCornerShape(50)
    val dialog = RoundedCornerShape(20.dp)
}

/**
 * 浅白液态玻璃调色板（PartSkin spec §6.3）—— 与 [glassPalette] 并列，
 * 原函数零改动。乳白霜面为底：浅色主题是「白基底上的乳白材质」，深色
 * 主题是「近黑基底上的一层淡白面纱」；主题色只以低饱和光晕形式在玻璃
 * 内部呼吸（A/B/C 三枚），文字永远是纯 onSurface（NFR-6 对比度）。
 */
@Immutable
internal data class PalePalette(
    /** 白霜垂直渐变顶（霜面提亮）。 */
    val frostTop: Color,
    /** 白霜垂直渐变底（霜面沉底）。 */
    val frostBottom: Color,
    /** 漂移彩光 A（primary 相位）。 */
    val glowA: Color,
    /** 漂移彩光 B（tertiary 相位）。 */
    val glowB: Color,
    /** 漂移彩光 C（secondary 相位）。 */
    val glowC: Color,
    /** 镜面扫掠光带色。 */
    val sheen: Color,
    /** 轮廓流光光眼色（borderBeam tint）。 */
    val eye: Color
)

/**
 * 当前主题下的浅白玻璃调色板（§6.3 表格逐项落地）。跟随 Dynamic Color /
 * 预设强调色 —— 光晕从 colorScheme 派生，霜面用固定色相保证跨主题一致。
 */
@Composable
internal fun palePalette(): PalePalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    return if (dark) {
        PalePalette(
            // 深色：近黑基底上的一层淡白面纱（提亮不刺眼）
            frostTop = Color(0xFFF3F5FF).copy(alpha = 0.14f),
            frostBottom = Color(0xFFE9EDFF).copy(alpha = 0.07f),
            // 光晕比浅色浓一档：暗底上同透明度几乎不可见
            glowA = scheme.primary.copy(alpha = 0.22f),
            glowB = scheme.tertiary.copy(alpha = 0.18f),
            glowC = scheme.secondary.copy(alpha = 0.16f),
            sheen = Color.White.copy(alpha = 0.14f),
            // 深色下光眼用白热（白高光物理语义，#269 同款豁免说明）
            eye = Color.White.copy(alpha = 0.85f)
        )
    } else {
        PalePalette(
            // 浅色：乳白磨砂（霜面 alpha 上限 0.72 —— NFR-6 对比度的上限钳）
            frostTop = Color.White.copy(alpha = 0.72f),
            frostBottom = Color(0xFFF2F5FF).copy(alpha = 0.52f),
            glowA = scheme.primary.copy(alpha = 0.13f),
            glowB = scheme.tertiary.copy(alpha = 0.11f),
            glowC = scheme.secondary.copy(alpha = 0.10f),
            sheen = Color.White.copy(alpha = 0.20f),
            // 浅色下光眼走主题色低饱和（primary @ 0.60）
            eye = scheme.primary.copy(alpha = 0.60f)
        )
    }
}
