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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.screen.code.LocalConversationSkin
import com.apex.agent.ui.theme.CodingPart
import com.apex.agent.ui.theme.LocalParentSkin
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
 * 分派表（§5，v1.2 增 EMBEDDED）：
 *  - `PALE_LIQUID` → [PaleLiquidSurface]（MINIMAL 且无豁免时其内部自动
 *    降级 PaleFlat —— 实色 + 发丝描边 + 静态霜面渐变，NFR-5）；
 *  - `CLASSIC_GLASS` → [GlassCard]（[classicStyle] 指定七档中的某一档；
 *    accent 恒 Unspecified —— FR-11 对皮肤体系全局生效）；
 *  - `FLAT` → 实色 `surfaceContainer` + 1dp `outlineVariant` 发丝描边
 *    （[bareInFlat] = true 的细状态行/文件 chips 直接裸渲染，§4
 *    「细状态行不套玻璃」）；
 *  - `SOLID` → M3 `Surface + primaryContainer`（用户气泡专用）；
 *  - `NEON_TERMINAL` → [TerminalGlassSurface]（终端暗色霓虹独立语言）；
 *  - `EMBEDDED`（v1.2）→ **零容器** `Column(modifier.padding(contentPadding))
 *    { content() }` —— 不创建任何 Surface / GlassSurface，继承父卡背景，
 *    G2 内部件的默认形态（H-4 嵌套退化的终态，FR-19）；
 *  - `FOLLOW` → 解析终值恒非 FOLLOW（回退链兜底 FLAT），防御分支同 FLAT。
 *
 * 解析结果 `remember` 缓存（NFR-1 / NFR-10）：**键含会话 epoch** —— 当前
 * 对话内 epoch 恒定 ⇒ 零皮肤重组；`LocalPartSkins` / `LocalSkinContext` /
 * [LocalConversationSkin] 均为 `staticCompositionLocalOf`，皮肤变更只失效
 * 读取它们的本组件，不波及时间轴 Compose 树。
 *
 * H-4（v1.2）：容器分支外层 `CompositionLocalProvider(LocalParentSkin
 * provides skin.takeIf { it.isContainer })` 把已解析皮肤下发给内部件 ——
 * 父卡是玻璃时，子件经 resolveEffective 自动退化 EMBEDDED（用户显式
 * 覆盖则尊重），杜绝玻璃套玻璃 / 框中框复发。
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
    val conversation = LocalConversationSkin.current
    val parentSkin = LocalParentSkin.current
    // 回退链 + H-4 嵌套退化纯函数结果缓存（NFR-1 / NFR-10）：键含会话
    // epoch —— 当前对话内 epoch 恒定 ⇒ 零皮肤重组；PartSkinOverrides 是
    // @Immutable，等值重组不触发重解析。
    val skin = remember(conversation?.epoch, part, overrides, ctx, parentSkin) {
        overrides.resolveEffective(part, ctx, parentSkin)
    }

    // H-4：把已解析皮肤下发给内部件（仅容器型传播 —— 嵌套链上只有真实
    // 容器才参与嵌套退化判定）
    CompositionLocalProvider(LocalParentSkin provides skin.takeIf { it.isContainer }) {
        when (skin) {
            // ★ v1.2 EMBEDDED：零容器、零圆角、零阴影 —— 只透传 modifier
            // 与 contentPadding，继承父卡背景（G2 内部件默认形态，FR-19）
            PartSkin.EMBEDDED -> Column(
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
