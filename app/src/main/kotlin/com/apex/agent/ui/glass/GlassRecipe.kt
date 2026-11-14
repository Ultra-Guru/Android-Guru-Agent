package com.apex.agent.ui.glass

/**
 * ═══════════════════════════════════════════════════════════════
 *  GlassRecipe —— 玻璃配方单一数据源（Gate-0 §3.1 · P-1 同源）
 * ═══════════════════════════════════════════════════════════════
 *
 * Gate-0 规格书（玻璃实验室成品化）的 G0-1 落点：实验室与正式组件必须读
 * **同一份**配方 —— 此前 GlassStyle（生产）与 GlassLabSamples 的 GlassSpec
 * （实验态）是两套手写值，实验室调出的参数永远无法回流生产（D-9）。
 *
 * 本文件是**纯 Kotlin 模型**（零 Android / 零 Compose 依赖）：
 *  - 数值一律用 Float 存「dp 值」、Long 存 ARGB —— 可在 kotlinc 裸编译
 *    通道（CI static-analysis 同款）与 :app:testDebugUnitTest 双线运行，
 *    [GlassRecipeParityTest] 即 Gate-0 的同源锁；
 *  - Compose 侧映射（Dp / Color / GlassStyle / PartSkin）住在
 *    [GlassStyle.kt] 的 toGlassStyle 与 toPartSkin 扩展 —— GlassStyle 的
 *    9 个既有档位（7 档枚举 + Bubble + PaleLiquid）全部**从 GlassRecipeBook
 *    生成**，数值从此只有一处真源（H-7：改参数必须先过实验室同源断言）。
 *
 * 配方只管**几何与强度参数**；明暗双态的 palette 派生（glassPalette 与
 * palePalette 按 luminance 分支）不在配方内 —— 那是主题的职责，保持原样。
 *
 * 降级规则（与 GlassSurface 的诚实声明同源）：API 31+ 才有 RenderEffect
 * blur（之下整档 Frosted），API 33+ 才有 AGSL lens 折射 —— 运行时真实
 * 命中档由 GlassLadder（GlassSkinGate.kt）判定并显示，禁止静态谎言。
 */

/** 玻璃材质档位 —— 七档语义（自 GlassStyle.kt 迁入，包不变、引用零改动）。 */
enum class GlassTier { Subtle, Control, Card, Navigation, Floating, Dialog, Strong }

/** 部件皮肤 key 常量 —— 与 ui/theme 包 PartSkin 的 key 镜像（theme 包与
 * Compose 耦合，无法进纯 JVM 文件；key 语义由 toPartSkin 映射锁定）。 */
object RecipeSkinKeys {
    /** 浅白液态玻璃（PartSkin.PALE_LIQUID）。 */
    const val PALE_LIQUID = "pale_liquid"

    /** 经典玻璃（PartSkin.CLASSIC_GLASS）。 */
    const val CLASSIC_GLASS = "classic_glass"

    /** 扁平（PartSkin.FLAT）。 */
    const val FLAT = "flat"
}

/**
 * 单件玻璃配方的完整参数（Gate-0 §3.1）。
 *
 * 字段与 GlassStyle 一一对应（blurRadius / tintAlpha / lens / edge /
 * specular / elevation / pressedScale / scrim + vibrancy），另有四个
 * 配方专属维度：id（稳定标识）、corner（圆角基准）、accentArgb（状态
 * 强调色，0 = 未指定）、glow（光晕强度）、partSkinKey（部件皮肤映射）。
 *
 * @param id 稳定配方 ID（"dock" / "card" / "pale" / …），导出与回读的键
 * @param tier 档位语义（七档枚举）
 * @param blurRadiusDp Backdrop 模糊半径 dp（API 31+）
 * @param tintAlpha 材质着色强度 0..1
 * @param lensHeightDp lens 折射带高度 dp（API 33+；0 = 关闭折射）
 * @param lensAmountDp lens 折射位移量 dp
 * @param chromaticAberration lens 色散（边缘彩虹分离）
 * @param vibrancy 背景饱和度提升（API 31+ ColorFilter）
 * @param edgeAlpha 边缘高光强度 0..1
 * @param specularAlpha 顶部镜面高光强度 0..1
 * @param elevationDp 外阴影深度 dp
 * @param pressedScale 按压缩放系数
 * @param scrimAlpha Frosted 档底色浓度 0..1
 * @param cornerDp 圆角基准 dp
 * @param accentArgb 状态强调色 ARGB（0 = 未指定，同 Color.Unspecified 语义）
 * @param glow 光晕强度 0..1（PartSkin 体系同名字段）
 * @param partSkinKey 部件皮肤映射 key（RecipeSkinKeys）
 */
data class GlassRecipe(
    val id: String,
    val tier: GlassTier,
    val blurRadiusDp: Float = 12f,
    val tintAlpha: Float = 0.42f,
    val lensHeightDp: Float = 0f,
    val lensAmountDp: Float = 0f,
    val chromaticAberration: Boolean = false,
    val vibrancy: Float = 0f,
    val edgeAlpha: Float = 0.18f,
    val specularAlpha: Float = 0.10f,
    val elevationDp: Float = 2f,
    val pressedScale: Float = 1f,
    val scrimAlpha: Float = 0.58f,
    val cornerDp: Float = 12f,
    val accentArgb: Long = 0L,
    val glow: Float = 0.35f,
    val partSkinKey: String = RecipeSkinKeys.CLASSIC_GLASS
) {
    /**
     * 标本参数标签行（等宽小字）—— 实验室唯一允许的参数排版出口
     * （P-1：GlassLabSamples 的 paramLines 从这里读取，禁止另写一套）。
     */
    fun paramLines(): List<String> = listOf(
        "blur=${blurRadiusDp.toInt()}dp",
        "tint=${fmtPct(tintAlpha)}",
        "lens=${lensHeightDp.toInt()}/${lensAmountDp.toInt()}dp",
        "edge=${fmtPct(edgeAlpha)} spec=${fmtPct(specularAlpha)}",
        "elev=${elevationDp.toInt()}dp scrim=${fmtPct(scrimAlpha)}",
        "corner=${cornerDp.toInt()}dp skin=$partSkinKey"
    )

    /**
     * 导出为可直接粘进 GlassRecipeBook 的 Kotlin 代码块（P-3 可导出）。
     * 粘回 GlassRecipeBook 后实验室与正式组件同步变化 —— 闭环成立。
     */
    fun exportKotlin(): String {
        val sb = StringBuilder()
        sb.append("val ").append(id.replaceFirstChar { it.uppercase() })
            .append(" = GlassRecipe(\n")
        sb.append("    id = \"").append(id).append("\",\n")
        sb.append("    tier = GlassTier.").append(tier.name).append(",\n")
        sb.append("    blurRadiusDp = ").append(fmtFloat(blurRadiusDp)).append(",\n")
        sb.append("    tintAlpha = ").append(fmtFloat(tintAlpha)).append(",\n")
        sb.append("    lensHeightDp = ").append(fmtFloat(lensHeightDp)).append(",\n")
        sb.append("    lensAmountDp = ").append(fmtFloat(lensAmountDp)).append(",\n")
        sb.append("    chromaticAberration = ").append(chromaticAberration).append(",\n")
        sb.append("    vibrancy = ").append(fmtFloat(vibrancy)).append(",\n")
        sb.append("    edgeAlpha = ").append(fmtFloat(edgeAlpha)).append(",\n")
        sb.append("    specularAlpha = ").append(fmtFloat(specularAlpha)).append(",\n")
        sb.append("    elevationDp = ").append(fmtFloat(elevationDp)).append(",\n")
        sb.append("    pressedScale = ").append(fmtFloat(pressedScale)).append(",\n")
        sb.append("    scrimAlpha = ").append(fmtFloat(scrimAlpha)).append(",\n")
        sb.append("    cornerDp = ").append(fmtFloat(cornerDp)).append(",\n")
        sb.append("    accentArgb = ").append(accentArgb).append("L,\n")
        sb.append("    glow = ").append(fmtFloat(glow)).append(",\n")
        sb.append("    partSkinKey = \"").append(partSkinKey).append("\"\n")
        sb.append(")")
        return sb.toString()
    }

    /**
     * 导出为扁平 JSON（外部工具 / 对照记录用；[fromJson] 可无损回读 ——
     * [GlassRecipeParityTest] 锁 round-trip）。
     */
    fun exportJson(): String {
        val sb = StringBuilder("{")
        sb.append("\"id\":\"").append(id).append("\",")
        sb.append("\"tier\":\"").append(tier.name).append("\",")
        sb.append("\"blurRadiusDp\":").append(blurRadiusDp).append(",")
        sb.append("\"tintAlpha\":").append(tintAlpha).append(",")
        sb.append("\"lensHeightDp\":").append(lensHeightDp).append(",")
        sb.append("\"lensAmountDp\":").append(lensAmountDp).append(",")
        sb.append("\"chromaticAberration\":").append(chromaticAberration).append(",")
        sb.append("\"vibrancy\":").append(vibrancy).append(",")
        sb.append("\"edgeAlpha\":").append(edgeAlpha).append(",")
        sb.append("\"specularAlpha\":").append(specularAlpha).append(",")
        sb.append("\"elevationDp\":").append(elevationDp).append(",")
        sb.append("\"pressedScale\":").append(pressedScale).append(",")
        sb.append("\"scrimAlpha\":").append(scrimAlpha).append(",")
        sb.append("\"cornerDp\":").append(cornerDp).append(",")
        sb.append("\"accentArgb\":").append(accentArgb).append(",")
        sb.append("\"glow\":").append(glow).append(",")
        sb.append("\"partSkinKey\":\"").append(partSkinKey).append("\"")
        sb.append("}")
        return sb.toString()
    }

    companion object {
        /**
         * 从 [exportJson] 的产物无损回读（防御式：任何形状不合法都折叠为
         * null，绝不抛 —— 与仓库「防御式 IO」纪律一致）。
         */
        fun fromJson(text: String): GlassRecipe? = runCatching {
            val body = text.trim().removePrefix("{").removeSuffix("}")
            val map = HashMap<String, String>()
            body.split(",").forEach { pair ->
                val idx = pair.indexOf(':')
                if (idx <= 0) return@forEach
                val key = pair.substring(0, idx).trim().trim('"')
                var value = pair.substring(idx + 1).trim()
                if (value.startsWith("\"")) value = value.trim('"')
                map[key] = value
            }
            GlassRecipe(
                id = map["id"] ?: return@runCatching null,
                tier = map["tier"]?.let { name ->
                    GlassTier.entries.firstOrNull { it.name == name }
                } ?: GlassTier.Card,
                blurRadiusDp = map["blurRadiusDp"]?.toFloatOrNull() ?: 12f,
                tintAlpha = map["tintAlpha"]?.toFloatOrNull() ?: 0.42f,
                lensHeightDp = map["lensHeightDp"]?.toFloatOrNull() ?: 0f,
                lensAmountDp = map["lensAmountDp"]?.toFloatOrNull() ?: 0f,
                chromaticAberration = map["chromaticAberration"]?.toBoolean() ?: false,
                vibrancy = map["vibrancy"]?.toFloatOrNull() ?: 0f,
                edgeAlpha = map["edgeAlpha"]?.toFloatOrNull() ?: 0.18f,
                specularAlpha = map["specularAlpha"]?.toFloatOrNull() ?: 0.10f,
                elevationDp = map["elevationDp"]?.toFloatOrNull() ?: 2f,
                pressedScale = map["pressedScale"]?.toFloatOrNull() ?: 1f,
                scrimAlpha = map["scrimAlpha"]?.toFloatOrNull() ?: 0.58f,
                cornerDp = map["cornerDp"]?.toFloatOrNull() ?: 12f,
                accentArgb = map["accentArgb"]?.toLongOrNull() ?: 0L,
                glow = map["glow"]?.toFloatOrNull() ?: 0.35f,
                partSkinKey = map["partSkinKey"] ?: RecipeSkinKeys.CLASSIC_GLASS
            )
        }.getOrNull()

        /** 百分比标签（"0.42" → "42%"；先取整到 0.1 再剥尾零，防浮点尾差）。 */
        internal fun fmtPct(v: Float): String {
            val rounded = kotlin.math.round(v * 1000f) / 10f
            return if (rounded % 1f == 0f) "${rounded.toInt()}%" else "${rounded}%"
        }

        /** Kotlin 字面量格式（14.0 → "14f"；0.42 → "0.42f"）。 */
        internal fun fmtFloat(v: Float): String =
            if (v % 1f == 0f) "${v.toInt()}f" else "${v}f"
    }
}

/**
 * ═══════════════════════════════════════════════════════════════
 *  GlassRecipeBook —— 配方真源（生产 9 档 + Gate-0 新增 Dock）
 * ═══════════════════════════════════════════════════════════════
 *
 * GlassStyle 的 9 个档位（Subtle / Control / Card / Navigation / Floating /
 * Dialog / Strong / Bubble / PaleLiquid）**从本 Book 生成**（GlassStyle.kt
 * companion 一行一档）—— 数值只有这一处真源，[GlassRecipeParityTest]
 * 逐字段锁定与规格书附录的期望值一致。
 *
 * Dock 是 Gate-0 §4 D-4 修复引入的新配方：酷安式深色 Dock 专用（此前误用
 * FAB 的 Floating 档 —— lens 10/20dp 折射 + 6dp 外阴影正是「框中框放大器」
 * 与「双底边」的根因）。取值：blur 14dp / tint 0.42 / lens 0（零折射）/
 * 色散关 / elevation 2dp / edge 0.18 / corner 34dp。
 */
object GlassRecipeBook {

    /** 极轻玻璃：小型静态状态组件（徽标）—— 不折射，避免小尺寸形变夸张。 */
    val Subtle = GlassRecipe(
        id = "subtle", tier = GlassTier.Subtle,
        blurRadiusDp = 8f, tintAlpha = 0.30f,
        lensHeightDp = 0f, lensAmountDp = 0f, chromaticAberration = false,
        vibrancy = 0f, edgeAlpha = 0.10f, specularAlpha = 0.05f,
        elevationDp = 0f, pressedScale = 1f, scrimAlpha = 0.42f,
        cornerDp = 8f, glow = 0.35f, partSkinKey = RecipeSkinKeys.CLASSIC_GLASS
    )

    /** 控制件玻璃：返回按钮 / 图标按钮 / 输入控制器 —— 轻折射（按压形变）。 */
    val Control = GlassRecipe(
        id = "control", tier = GlassTier.Control,
        blurRadiusDp = 10f, tintAlpha = 0.38f,
        lensHeightDp = 6f, lensAmountDp = 12f, chromaticAberration = false,
        vibrancy = 0f, edgeAlpha = 0.16f, specularAlpha = 0.08f,
        elevationDp = 1f, pressedScale = 0.94f, scrimAlpha = 0.52f,
        cornerDp = 50f, glow = 0.35f, partSkinKey = RecipeSkinKeys.CLASSIC_GLASS
    )

    /** 卡片玻璃：Agent 卡 / Tool 卡 / 状态卡 / 设置与诊断页卡片。 */
    val Card = GlassRecipe(
        id = "card", tier = GlassTier.Card,
        blurRadiusDp = 12f, tintAlpha = 0.42f,
        lensHeightDp = 8f, lensAmountDp = 16f, chromaticAberration = false,
        vibrancy = 0f, edgeAlpha = 0.18f, specularAlpha = 0.10f,
        elevationDp = 2f, pressedScale = 1f, scrimAlpha = 0.58f,
        cornerDp = 12f, glow = 0.35f, partSkinKey = RecipeSkinKeys.CLASSIC_GLASS
    )

    /** 导航项玻璃：Drawer 每一项 —— 正常态必须「非常轻」。 */
    val Navigation = GlassRecipe(
        id = "navigation", tier = GlassTier.Navigation,
        blurRadiusDp = 10f, tintAlpha = 0.34f,
        lensHeightDp = 5f, lensAmountDp = 10f, chromaticAberration = false,
        vibrancy = 0f, edgeAlpha = 0.13f, specularAlpha = 0.06f,
        elevationDp = 0f, pressedScale = 0.98f, scrimAlpha = 0.46f,
        cornerDp = 12f, glow = 0.35f, partSkinKey = RecipeSkinKeys.CLASSIC_GLASS
    )

    /** 悬浮件玻璃：FAB / 输入栏 / 快捷浮动操作 —— 最强折射与高光，色散可辨。 */
    val Floating = GlassRecipe(
        id = "floating", tier = GlassTier.Floating,
        blurRadiusDp = 16f, tintAlpha = 0.46f,
        lensHeightDp = 10f, lensAmountDp = 20f, chromaticAberration = true,
        vibrancy = 0f, edgeAlpha = 0.24f, specularAlpha = 0.14f,
        elevationDp = 6f, pressedScale = 0.92f, scrimAlpha = 0.64f,
        cornerDp = 50f, glow = 0.35f, partSkinKey = RecipeSkinKeys.CLASSIC_GLASS
    )

    /** 对话框玻璃：强材质，但内容保持清晰。 */
    val Dialog = GlassRecipe(
        id = "dialog", tier = GlassTier.Dialog,
        blurRadiusDp = 20f, tintAlpha = 0.52f,
        lensHeightDp = 10f, lensAmountDp = 20f, chromaticAberration = false,
        vibrancy = 0f, edgeAlpha = 0.24f, specularAlpha = 0.12f,
        elevationDp = 12f, pressedScale = 1f, scrimAlpha = 0.74f,
        cornerDp = 20f, glow = 0.35f, partSkinKey = RecipeSkinKeys.CLASSIC_GLASS
    )

    /** 最强玻璃：低频、高聚焦的特殊场景。 */
    val Strong = GlassRecipe(
        id = "strong", tier = GlassTier.Strong,
        blurRadiusDp = 24f, tintAlpha = 0.58f,
        lensHeightDp = 12f, lensAmountDp = 24f, chromaticAberration = true,
        vibrancy = 0f, edgeAlpha = 0.28f, specularAlpha = 0.16f,
        elevationDp = 4f, pressedScale = 1f, scrimAlpha = 0.82f,
        cornerDp = 28f, glow = 0.35f, partSkinKey = RecipeSkinKeys.CLASSIC_GLASS
    )

    /**
     * 聊天气泡玻璃：折射更轻（6/12dp）、tint 更透（0.34）、不开色散 ——
     * 档位语义沿用 Card（七档枚举保持稳定，仅参数差异化）。
     */
    val Bubble = GlassRecipe(
        id = "bubble", tier = GlassTier.Card,
        blurRadiusDp = 10f, tintAlpha = 0.34f,
        lensHeightDp = 6f, lensAmountDp = 12f, chromaticAberration = false,
        vibrancy = 0f, edgeAlpha = 0.20f, specularAlpha = 0.12f,
        elevationDp = 1f, pressedScale = 1f, scrimAlpha = 0.48f,
        cornerDp = 18f, glow = 0.35f, partSkinKey = RecipeSkinKeys.CLASSIC_GLASS
    )

    /**
     * 浅白液态玻璃：乳白霜面为底，零折射、零色散、1dp 檐口阴影的
     * 「阅读优先」材质 —— 思考过程卡与全部卡片族的默认皮。
     */
    val PaleLiquid = GlassRecipe(
        id = "pale", tier = GlassTier.Card,
        blurRadiusDp = 10f, tintAlpha = 0.30f,
        lensHeightDp = 0f, lensAmountDp = 0f, chromaticAberration = false,
        vibrancy = 0f, edgeAlpha = 0.20f, specularAlpha = 0.14f,
        elevationDp = 1f, pressedScale = 1f, scrimAlpha = 0.44f,
        cornerDp = 12f, glow = 0.35f, partSkinKey = RecipeSkinKeys.PALE_LIQUID
    )

    /**
     * 酷安式深色 Dock 专用配方（Gate-0 §4 D-4 修复值）：整条 94% 屏宽的
     * Dock 不折射（lens 0/0 —— Floating 档的 10/20dp 折射会把栏内文字边
     * 缘拉形变，且正是「框中框」放大器）、色散关、2dp 檐口阴影（6dp 会在
     * 栏底再画一圈边界 —— 双底边根因）、34dp 全圆角胶囊。
     */
    val Dock = GlassRecipe(
        id = "dock", tier = GlassTier.Navigation,
        blurRadiusDp = 14f, tintAlpha = 0.42f,
        lensHeightDp = 0f, lensAmountDp = 0f, chromaticAberration = false,
        vibrancy = 0f, edgeAlpha = 0.18f, specularAlpha = 0.10f,
        elevationDp = 2f, pressedScale = 1f, scrimAlpha = 0.58f,
        cornerDp = 34f, glow = 0.35f, partSkinKey = RecipeSkinKeys.CLASSIC_GLASS
    )

    /** 全部配方（生产 9 档 + Dock）—— 遍历与导出清单用。 */
    val all: List<GlassRecipe> = listOf(
        Subtle, Control, Card, Navigation, Floating, Dialog, Strong,
        Bubble, PaleLiquid, Dock
    )

    /** 按 id 查配方（未知 id 折叠为 null，防御式不抛）。 */
    fun byId(id: String?): GlassRecipe? = all.firstOrNull { it.id == id }
}
