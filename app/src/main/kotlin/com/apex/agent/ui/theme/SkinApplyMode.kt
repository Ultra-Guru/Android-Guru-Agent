package com.apex.agent.ui.theme

import androidx.compose.runtime.Immutable

/**
 * ═══════════════════════════════════════════════════════════════
 *  SkinApplyMode — 皮肤生效策略与设备分级（Spec v1.3 · 纯模型）
 * ═══════════════════════════════════════════════════════════════
 *
 * 三档生效方式（FR-25） + 设备性能分级（FR-28） + 纯函数策略解析。
 *
 * 依赖纪律：本文件零 Android / 零 Compose-ui 依赖（仅 runtime @Immutable），
 * 可 JVM 单测（§11.1 / J-5）。**不要**把 `sameContent` 之类引用
 * `ConversationSkinSnapshot` 的函数放进本包（会反向依赖 ui/screen/code，
 * 破坏 theme 包零反向依赖，见 NFR-8 与生效模式增量清单 §4-5）。
 *
 * 采样纪律（NFR-14）：Android 侧采集（ActivityManager / PowerManager /
 * ANIMATOR_DURATION_SCALE）住 ui/screen/settings/DevicePerfTierDetector.kt，
 * 只在冷启动 / 会话边界采样一次并缓存进 `SkinActivation.tier`；禁止在
 * Composable 里每次重组调 getSystemService / getMemoryInfo。
 */

/**
 * 皮肤生效方式（用户三档选择，FR-25）。
 *
 * 持久化于 `AgentSettings.skinApplyMode`（字符串，缺省 `"auto"` → 旧 JSON
 * 零迁移）。**设置项本身立即写盘；它只决定后续皮肤变更的提升点，自身
 * 不触发换皮**（§7.5.1）。
 */
enum class SkinApplyMode(val key: String) {
    /** 默认：按设备分级解析（HIGH → 立即；MID/LOW → 新对话）。 */
    AUTO("auto"),

    /** 空闲时立即生效（当前对话整体换皮一次；运行中挂起到收束）。 */
    IMMEDIATE("immediate"),

    /** v1.2 行为：仅会话边界（新对话 / 恢复历史 / 切工作区）生效。 */
    ON_NEW_CHAT("on_new_chat");

    companion object {
        /** 持久化 key → 枚举（空/未知值回退 AUTO，防御式不抛）。 */
        fun fromKey(key: String?): SkinApplyMode =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: AUTO
    }
}

/** 策略终值（AUTO 解析后的实际执行档；状态机只读本值）。 */
enum class SkinApplyPolicy { IMMEDIATE, ON_NEW_CHAT }

/** 设备性能分级（FR-28 / 附录 I）。 */
enum class DevicePerfTier { LOW, MID, HIGH }

/**
 * 设备指标快照（§7.9.2）——Android 采集器一次性填充，判定纯函数消费。
 *
 * @param lowRamDevice `ActivityManager.isLowRamDevice()`
 * @param memoryClassMb `ActivityManager.getMemoryClass()`（MB）
 * @param totalMemMb `ActivityManager.MemoryInfo.totalMem`（MB）
 * @param cpuCores `Runtime.getRuntime().availableProcessors()`
 * @param apiLevel `Build.VERSION.SDK_INT`（< 31 无 RenderEffect blur）
 * @param powerSave `PowerManager.isPowerSaveMode()`（动态状态）
 * @param reducedMotion `Settings.Global.ANIMATOR_DURATION_SCALE == 0`（动态状态）
 */
@Immutable
data class DeviceMetrics(
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
    val totalMemMb: Long,
    val cpuCores: Int,
    val apiLevel: Int,
    val powerSave: Boolean,
    val reducedMotion: Boolean
)

/**
 * 分级判定（附录 I-1，纯函数，阈值集中一处便于按实测调参）：
 *
 *  - **任一** LOW 条件命中即 LOW：isLowRamDevice / memoryClass < 128MB /
 *    totalMem < 3GB / ≤4 核 / API < 31 / 省电 / 减少动效；
 *  - **全部** HIGH 条件满足才 HIGH：≥256MB / ≥6GB / ≥8 核 / API ≥ 33 /
 *    非省电 / 非减少动效；
 *  - 其余 MID。
 *
 * 省电 / 减少动效是**动态状态**，故在会话边界重采样（不在重组中轮询）；
 * LOW 档除策略降级外，`PALE_LIQUID` 另叠加 `Frosted` 静态帧（§10），
 * 两道保护互不替代。
 */
fun DeviceMetrics.tier(): DevicePerfTier = when {
    lowRamDevice || memoryClassMb < 128 || totalMemMb < 3072 ||
        cpuCores <= 4 || apiLevel < 31 || powerSave || reducedMotion -> DevicePerfTier.LOW
    memoryClassMb >= 256 && totalMemMb >= 6144 && cpuCores >= 8 &&
        apiLevel >= 33 && !powerSave && !reducedMotion -> DevicePerfTier.HIGH
    else -> DevicePerfTier.MID
}

/**
 * 策略解析（§7.9.1，纯函数，可 JVM 单测；NFR-16 状态机侧兜底）：
 *
 * | mode \ tier | LOW | MID | HIGH |
 * |---|---|---|---|
 * | AUTO | ON_NEW_CHAT | ON_NEW_CHAT | **IMMEDIATE** |
 * | IMMEDIATE | **ON_NEW_CHAT（硬拒）** | IMMEDIATE | IMMEDIATE |
 * | ON_NEW_CHAT | ON_NEW_CHAT | ON_NEW_CHAT | ON_NEW_CHAT |
 *
 * ★ LOW 硬拒：即使 UI 被绕过（深链接 / 备份导入 / 老数据）也不会在低端
 * 机上开启立即换皮 —— 状态机不信任 UI 置灰（NFR-16）。
 */
fun resolvePolicy(mode: SkinApplyMode, tier: DevicePerfTier): SkinApplyPolicy = when (mode) {
    SkinApplyMode.IMMEDIATE ->
        if (tier == DevicePerfTier.LOW) SkinApplyPolicy.ON_NEW_CHAT else SkinApplyPolicy.IMMEDIATE
    SkinApplyMode.ON_NEW_CHAT -> SkinApplyPolicy.ON_NEW_CHAT
    SkinApplyMode.AUTO ->
        if (tier == DevicePerfTier.HIGH) SkinApplyPolicy.IMMEDIATE else SkinApplyPolicy.ON_NEW_CHAT
}
