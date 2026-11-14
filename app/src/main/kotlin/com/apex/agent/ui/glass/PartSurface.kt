package com.apex.agent.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.theme.CodingPart
import com.apex.agent.ui.theme.LocalPartSkins
import com.apex.agent.ui.theme.LocalSkinContext
import com.apex.agent.ui.theme.PartSkin
import com.kyant.backdrop.Backdrop

/**
 * ═══════════════════════════════════════════════════════════════
 *  PartSurface — 部件级皮肤统一分派容器（PartSkin spec §7.3）
 * ═══════════════════════════════════════════════════════════════
 *
 * 业务组件从此**不再直接调 `GlassCard(...)` / `Surface(...)`**，只声明
 * `part = CodingPart.STREAM_THINKING` —— 换肤的全部判断集中在本单点
 * （UiStyle.kt「单点收敛」纪律的皮肤侧对偶：GlassSurface 是**材质**短路
 * 单点，PartSurface 是**皮肤分派**单点，业务组件零感知）。
 *
 * 分派表（§5）：
 *  - `PALE_LIQUID` → [PaleLiquidSurface]（MINIMAL 且无豁免时其内部自动
 *    降级 PaleFlat —— 实色 + 发丝描边 + 静态霜面渐变，NFR-5）；
 *  - `CLASSIC_GLASS` → [GlassCard]（[classicStyle] 指定七档中的某一档；
 *    accent 恒 Unspecified —— FR-11 对皮肤体系全局生效）；
 *  - `FLAT` → 实色 `surfaceContainer` + 1dp `outlineVariant` 发丝描边
 *    （[bareInFlat] = true 的细状态行/文件 chips 直接裸渲染，§4
 *    「细状态行不套玻璃」）；
 *  - `SOLID` → M3 `Surface + primaryContainer`（用户气泡专用）；
 *  - `NEON_TERMINAL` → [TerminalGlassSurface]（终端暗色霓虹独立语言）；
 *  - `FOLLOW` → 解析终值恒非 FOLLOW（回退链兜底 FLAT），防御分支同 FLAT。
 *
 * 解析结果 `remember` 缓存（NFR-1）：皮肤快照不变时零重复解析；
 * `LocalPartSkins` / `LocalSkinContext` 均为 `staticCompositionLocalOf`，
 * 皮肤变更只失效读取它们的本组件，不波及时间轴 Compose 树。
 *
 * @param part 部件稳定 ID（§4 注册表）
 * @param backdrop 页面提供的真实采样源（悬浮栈卡片传入；时间轴内嵌件
 *   传 null 走 Ambient 档）
 * @param active 光效是否流动（流式期 true；idle 卡片转静态帧）
 * @param classicStyle CLASSIC_GLASS 皮肤下使用的玻璃档（卡片 Card /
 *   输入栏 Floating / 弹层 Dialog）
 * @param bareInFlat FLAT 皮肤下是否裸渲染（无容器 —— 细状态行、文件
 *   chips 的 §4 默认形态）
 * @param onClick 卡片级点击（收在各分支的唯一内容 Column 上，涟漪经
 *   clip(shape) 跟随卡面圆角）
 * @param contentPadding 内容 padding 的唯一来源（FR-12 单层容器）
 */
@Composable
fun PartSurface(
    part: CodingPart,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    shape: Shape = GlassShapes.card,
    active: Boolean = true,
    classicStyle: GlassStyle = GlassStyle.Card,
    bareInFlat: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val overrides = LocalPartSkins.current
    val ctx = LocalSkinContext.current
    // 回退链纯函数结果缓存（NFR-1：PartSkinOverrides 是 @Immutable，
    // 等值重组不触发重解析；快照变更只失效本组件）
    val skin = remember(overrides, ctx, part) { overrides.resolve(part, ctx) }

    when (skin) {
        PartSkin.PALE_LIQUID -> PaleLiquidSurface(
            modifier = modifier,
            backdrop = backdrop,
            shape = shape,
            glow = ctx.glow,
            active = active,
            forceGlass = ctx.forceGlassInMinimal,
            onClick = onClick,
            contentPadding = contentPadding,
            content = content
        )

        PartSkin.CLASSIC_GLASS -> GlassCard(
            modifier = modifier,
            backdrop = backdrop,
            style = classicStyle,
            shape = shape,
            contentPadding = contentPadding,
            onClick = onClick,
            content = content
        )

        PartSkin.FLAT -> if (bareInFlat) {
            // 细状态行 / 文件 chips 的 §4 默认：FLAT = 不套容器（裸内容）
            FlatContent(
                modifier = modifier,
                onClick = onClick,
                contentPadding = contentPadding,
                content = content
            )
        } else {
            FlatPartSurface(
                modifier = modifier,
                shape = shape,
                onClick = onClick,
                contentPadding = contentPadding,
                content = content
            )
        }

        PartSkin.SOLID -> SolidPartSurface(
            modifier = modifier,
            shape = shape,
            onClick = onClick,
            contentPadding = contentPadding,
            content = content
        )

        PartSkin.NEON_TERMINAL -> TerminalGlassSurface(
            backdrop = backdrop,
            modifier = modifier,
            shape = shape
        ) {
            Column(
                modifier = Modifier
                    .then(
                        if (onClick != null) {
                            Modifier.clip(shape).clickable { onClick() }
                        } else Modifier
                    )
                    .padding(contentPadding)
            ) {
                content()
            }
        }

        // resolve() 终值恒非 FOLLOW（回退链兜底 FLAT）—— 防御分支，
        // 保证 when 穷尽且任何未知形态都不空白
        PartSkin.FOLLOW -> FlatPartSurface(
            modifier = modifier,
            shape = shape,
            onClick = onClick,
            contentPadding = contentPadding,
            content = content
        )
    }
}

/**
 * FLAT 皮肤的标准形态：实色 `surfaceContainer` + 1dp `outlineVariant`
 * 发丝描边（§5）。与 GlassSurface.MINIMAL 短路的 FlatSurface 同语言 ——
 * 发丝描边经裁剪后约半宽可见，克制的 hairline 观感。
 */
@Composable
private fun FlatPartSurface(
    modifier: Modifier,
    shape: Shape,
    onClick: (() -> Unit)?,
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(shape)
            .background(scheme.surfaceContainer, shape)
            .border(1.dp, scheme.outlineVariant, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else Modifier
            )
            .padding(contentPadding)
    ) {
        content()
    }
}

/** FLAT 皮肤的裸形态（bareInFlat）：不套容器，内容直铺（细状态行）。 */
@Composable
private fun FlatContent(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else Modifier
            )
            .padding(contentPadding)
    ) {
        content()
    }
}

/** SOLID 皮肤：M3 `Surface + primaryContainer`（用户气泡，右对齐实色）。 */
@Composable
private fun SolidPartSurface(
    modifier: Modifier,
    shape: Shape,
    onClick: (() -> Unit)?,
    contentPadding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .then(
                    if (onClick != null) {
                        Modifier.clip(shape).clickable { onClick() }
                    } else Modifier
                )
                .padding(contentPadding)
        ) {
            content()
        }
    }
}
