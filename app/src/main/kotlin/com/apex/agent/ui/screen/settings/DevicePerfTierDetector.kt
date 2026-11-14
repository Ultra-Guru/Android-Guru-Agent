package com.apex.agent.ui.screen.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.apex.agent.ui.theme.DeviceMetrics
import com.apex.agent.ui.theme.DevicePerfTier
import com.apex.agent.ui.theme.tier
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════
 *  DevicePerfTierDetector — 设备性能分级采集（Spec v1.3 §7.9.2 / 附录 I）
 * ═══════════════════════════════════════════════════════════════
 *
 * 七项指标一次性采集（复用仓库既有采样先例）：
 *  - `ActivityManager.isLowRamDevice()` / `getMemoryClass()` /
 *    `MemoryInfo.totalMem`（`DiagnosticsCollector.kt:151-161` 先例）；
 *  - `PowerManager.isPowerSaveMode()`（`MainActivity.kt:279` 先例）；
 *  - `Settings.Global.ANIMATOR_DURATION_SCALE == 0`（`PaleLiquidSurface
 *    .rememberReducedMotion()` 先例）；
 *  - `Runtime.availableProcessors()` / `Build.VERSION.SDK_INT`。
 *
 * 采样纪律（NFR-14）：**只在冷启动（restoreActiveAtColdStart）与会话边界
 * （promotePendingAtConversationBoundary）调用**，结果缓存进
 * `SkinActivation.tier`；禁止在 Composable 里每次重组读系统服务 ——
 * 诊断页 DiagnosticsCollector 是按需一次性采集，不是每帧。
 *
 * 纯判定（[tier]）住 ui/theme/SkinApplyMode.kt，零 Android 依赖可 JVM
 * 单测；本文件只做 Android 采集与注入桥。
 */

/**
 * 分级提供者接口 —— VM 不直接持有 Context（保持可 JVM 测试），经 Hilt
 * 注入本接口；`currentTier()` 每次调用做一次实时采集（调用方负责只在
 * 冷启动 / 会话边界调用并缓存结果）。
 */
interface DeviceTierProvider {
    /** 采集设备指标并解析分级（一次系统服务查询 + 纯函数判定）。 */
    fun currentTier(): DevicePerfTier

    /** 最近一次采样指标（设置页「本机分级」徽标与解释的数据面）。 */
    fun currentMetrics(): DeviceMetrics
}

/** Android 采集器实现（@ApplicationContext，单例）。 */
@Singleton
class DeviceTierProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceTierProvider {

    override fun currentTier(): DevicePerfTier = currentMetrics().tier()

    override fun currentMetrics(): DeviceMetrics = detectDeviceMetrics(context)
}

/**
 * 一次性采集七项指标（容错：任一系统服务不可用时按保守值回填 ——
 * memoryClass 缺失按 128（LOW 门槛）、totalMem 缺失按 0（< 3GB → LOW），
 * 宁可降级不可冒进）。
 */
fun detectDeviceMetrics(context: Context): DeviceMetrics {
    val am = runCatching {
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }.getOrNull()
    val memoryInfo = am?.let { info ->
        runCatching {
            ActivityManager.MemoryInfo().also { info.getMemoryInfo(it) }
        }.getOrNull()
    }
    val power = runCatching {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }.getOrNull()
    val reducedMotion = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }.getOrDefault(false)
    return DeviceMetrics(
        lowRamDevice = am?.isLowRamDevice == true,
        memoryClassMb = am?.memoryClass ?: 128,
        totalMemMb = (memoryInfo?.totalMem ?: 0L) / (1024L * 1024L),
        cpuCores = Runtime.getRuntime().availableProcessors(),
        apiLevel = Build.VERSION.SDK_INT,
        powerSave = power?.isPowerSaveMode == true,
        reducedMotion = reducedMotion
    )
}

/** Hilt 绑定（SingletonComponent；接口 → Impl）。 */
@Module
@InstallIn(SingletonComponent::class)
internal object DeviceTierModule {

    @Provides
    @Singleton
    fun provideDeviceTierProvider(impl: DeviceTierProviderImpl): DeviceTierProvider = impl
}
