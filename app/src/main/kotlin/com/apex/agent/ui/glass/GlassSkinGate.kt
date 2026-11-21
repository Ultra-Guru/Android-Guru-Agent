package com.apex.agent.ui.glass

/**
 * ═══════════════════════════════════════════════════════════════
 *  GlassSkinGate —— 玻璃短路判定 + 降级阶梯 + 对比度数学（纯 JVM）
 * ═══════════════════════════════════════════════════════════════
 *
 * Gate-0 D-10 修复的落点：生产 GlassSurface 的 MINIMAL 短路分支此前只在
 * 生产侧存在，玻璃实验室完全不消费会话快照 —— 实验室验证的材质路径 ≠
 * 生产实际路径，实验室结论对生产不可信。
 *
 * 本文件把「短路判定」提取为**纯函数**（String key 入参，零 Compose 依赖）：
 *  - 生产 [GlassSurface] 消费它（LocalConversationSkin 与 LocalUiStyle 的
 *    key 传入）；
 *  - 实验室并排台（GlassLabBench）消费**同一个函数**（显式 provide
 *    LocalConversationSkin 预置态后传入同样的 key）；
 *  - [Gate0ParityTest]（纯 JVM）断言两侧共用路径的判定语义 —— 同源不再
 *    靠口头声明。
 *
 * 附带 [GlassLadder]（运行时降级档判定）与 [ContrastMath]（WCAG 对比度 +
 * CIE76 色差）—— 都是 P-4 可量化验收的数学底座，纯函数可单测。
 */

/**
 * 玻璃材质短路判定（生产 GlassSurface 与玻璃实验室共用同一条路径）。
 *
 * 语义与 GlassSurface 原实现逐字等价：
 * `(conversation?.uiStyle ?: LocalUiStyle.current) == UiStyle.MINIMAL`
 * —— 会话快照（Coding 子树）优先，缺省回落全局风格；命中 MINIMAL 即把
 * 玻璃管线整体短路为扁平表面（forceGlass 豁免由调用方叠加）。
 */
object GlassSkinGate {
    /** 与 ui/theme 包 UiStyle.MINIMAL.key 镜像（theme 包与 Compose 耦合，
     * 无法进纯 JVM 文件；key 值由 Gate0ParityTest 锁定语义）。 */
    const val MINIMAL_KEY = "minimal"

    /** 会话风格 key 优先、全局风格兜底 —— 命中 minimal 即短路。 */
    fun shortCircuitsToFlat(
        conversationUiStyleKey: String?,
        globalUiStyleKey: String
    ): Boolean = (conversationUiStyleKey ?: globalUiStyleKey) == MINIMAL_KEY
}

/**
 * 玻璃渲染降级档 —— 运行时真实命中（禁止静态谎言）。
 *
 * 与 GlassSurface 的诚实声明同源：
 *  - [PALE_FLAT]：MINIMAL 短路（实色底 + 发丝描边，零玻璃管线）；
 *  - [FROSTED]：API < 31 无 RenderEffect（霜面渐变 + 边缘光，零管线）；
 *  - [BACKDROP]：真实采样源 + GPU blur（API 33+ 再加 AGSL lens）；
 *  - [AMBIENT]：程序化材质源走同一条 GPU 管线（无真实采样）。
 */
enum class GlassRenderTier { PALE_FLAT, FROSTED, BACKDROP, AMBIENT }

/**
 * 降级阶梯读数 —— 实验室「降级档」探针与验收清单的显示面。
 *
 * @param renderTier 实际命中的渲染档
 * @param blurOn RenderEffect GPU blur 可用（API 31+）
 * @param lensOn AGSL lens 折射/色散可用（API 33+）
 * @param powerSave 省电模式（影响皮肤提升时机与动效时长，不改变管线档位）
 * @param reducedMotion 系统动画时长归零（同上，如实显示不冒充）
 */
data class GlassLadderReadout(
    val renderTier: GlassRenderTier,
    val blurOn: Boolean,
    val lensOn: Boolean,
    val powerSave: Boolean,
    val reducedMotion: Boolean
)

/** 降级阶梯判定（纯函数）：API 级 + 采样源 + 风格短路 → 实际命中档。 */
object GlassLadder {

    /** RenderEffect（GPU blur）的硬门槛：Android 12 / API 31。 */
    const val MIN_API_BLUR = 31

    /** AGSL RuntimeShader（lens 折射与色散）的门槛：Android 13 / API 33。 */
    const val MIN_API_LENS = 33

    fun read(
        apiLevel: Int,
        hasBackdrop: Boolean,
        conversationUiStyleKey: String?,
        globalUiStyleKey: String,
        powerSave: Boolean = false,
        reducedMotion: Boolean = false
    ): GlassLadderReadout {
        val blurOn = apiLevel >= MIN_API_BLUR
        val lensOn = apiLevel >= MIN_API_LENS
        val renderTier = when {
            GlassSkinGate.shortCircuitsToFlat(conversationUiStyleKey, globalUiStyleKey) ->
                GlassRenderTier.PALE_FLAT
            !blurOn -> GlassRenderTier.FROSTED
            hasBackdrop -> GlassRenderTier.BACKDROP
            else -> GlassRenderTier.AMBIENT
        }
        return GlassLadderReadout(
            renderTier = renderTier,
            blurOn = blurOn,
            lensOn = lensOn,
            powerSave = powerSave,
            reducedMotion = reducedMotion
        )
    }
}

/**
 * WCAG 对比度 + CIE76 色差 —— P-4 量化探针的数学底座（纯函数）。
 *
 * 颜色一律用 ARGB Long（0xAARRGGBB）入参 —— 与 Compose Color.toArgb()
 * 的无符号 Int 互转时由调用方负责 `toLong() and 0xFFFFFFFF`。
 */
object ContrastMath {

    /** WCAG 相对亮度（sRGB → 线性化 → 加权）0..1。 */
    fun relativeLuminance(argb: Long): Double {
        val r = linearize(((argb shr 16) and 0xFF).toInt() / 255.0)
        val g = linearize(((argb shr 8) and 0xFF).toInt() / 255.0)
        val b = linearize((argb and 0xFF).toInt() / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG 对比度比值 1..21（黑/白 = 21；同色 = 1）。 */
    fun contrastRatio(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * 半透明前景合成到基色（source-over），返回不透明结果 —— 对比度
     * 探针用「霜面底色 合成 主题背景」估算最劣情况下的可读性。
     */
    fun compositeOver(foreArgb: Long, baseArgb: Long): Long {
        val fa = ((foreArgb shr 24) and 0xFF).toInt() / 255.0
        if (fa >= 1.0) return foreArgb
        if (fa <= 0.0) return baseArgb
        val ba = ((baseArgb shr 24) and 0xFF).toInt() / 255.0
        val outA = fa + ba * (1 - fa)
        if (outA <= 0.0) return 0L
        fun mix(fc: Long, bc: Long): Long {
            val f = ((fc shr 0) and 0xFF).toInt() / 255.0
            val b = ((bc shr 0) and 0xFF).toInt() / 255.0
            val v = (f * fa + b * ba * (1 - fa)) / outA
            return (v * 255.0 + 0.5).toLong().coerceIn(0, 255)
        }
        // 分通道混色（R/G/B 各自 mix 后重组；B 通道传 0 偏移位避免重复移位）
        val r = mix(foreArgb shr 16, baseArgb shr 16)
        val g = mix((foreArgb shr 8), (baseArgb shr 8))
        val b = mix(foreArgb, baseArgb)
        val a = (outA * 255.0 + 0.5).toLong().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** CIE76 色差（ΔE）—— 采样变化量探针：滚动前后同位置像素的色差。 */
    fun deltaE76(a: Long, b: Long): Double {
        val la = toLab(a)
        val lb = toLab(b)
        val dl = la[0] - lb[0]
        val da = la[1] - lb[1]
        val db = la[2] - lb[2]
        return kotlin.math.sqrt(dl * dl + da * da + db * db)
    }

    private fun linearize(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    private fun toLab(argb: Long): DoubleArray {
        // sRGB → 线性 RGB → XYZ(D65) → Lab
        val r = linearize(((argb shr 16) and 0xFF).toInt() / 255.0)
        val g = linearize(((argb shr 8) and 0xFF).toInt() / 255.0)
        val b = linearize((argb and 0xFF).toInt() / 255.0)
        val x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        val y = r * 0.2126 + g * 0.7152 + b * 0.0722
        val z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
        fun f(t: Double): Double = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }
}

/**
 * Gate-0 并排台的会话快照预置态（D-10）：实验室用这些预置 key 组合
 * provide LocalConversationSkin，验证生产同一条短路路径 ——
 * [Gate0ParityTest] 断言预置覆盖「短路 / 不短路」两分支。
 */
object Gate0SkinPresets {
    /** 液态玻璃（不短路）—— 会话 key。 */
    const val CONVERSATION_LIQUID = "liquid_glass"

    /** 极简黑白（短路）—— 会话 key。 */
    const val CONVERSATION_MINIMAL = "minimal"

    /** 全局风格 key（并排台固定液态玻璃基底，让会话维度单独可验）。 */
    const val GLOBAL_LIQUID = "liquid_glass"

    /** 预置组合：会话 key → 是否应短路（null = 会话快照缺席，回落全局）。 */
    val cases: List<Pair<String?, Boolean>> = listOf(
        null to false,                     // 会话缺席 + 全局液态 → 不短路
        CONVERSATION_LIQUID to false,      // 会话液态 → 不短路
        CONVERSATION_MINIMAL to true       // 会话极简 → 短路（覆盖生产 Coding 子树路径）
    )
}
