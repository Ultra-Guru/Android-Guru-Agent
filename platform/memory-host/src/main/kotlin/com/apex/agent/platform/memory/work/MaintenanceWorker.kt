package com.apex.agent.platform.memory.work

import android.content.Context
import android.os.BatteryManager
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.memkit.core.MemKit
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆维护周期 Worker（原 DreamRenderer 的 WorkManager 语义收编）。
 *
 * #851 修正后的调度语义（「充电 或 电量充足」替代互斥组合）：
 * - 旧约束 `setRequiresCharging(true)` ∧ `setRequiresDeviceIdle(true)`
 *   在 Doze 语义下**永无交集**（AOSP DeviceIdleController 在开始充电的
 *   瞬间退出任何 idle 状态）→ 周期维护在真机上从未运行过；
 * - 周期链路：充电中 + 电量不低（可满足的组合），去掉 idle 与 WiFi
 *   约束 —— 维护本体（衰减 / 剪枝 / 保鲜 / 晶化 / 别名发现 / 容量
 *   治理）全部住在库 MacroMaintenance，纯本地 CPU/磁盘工作，不联网；
 * - 兜底链路：每次 App 启动排一个一次性维护（[MaintenanceScheduler.
 *   scheduleStartupFallback]），Worker 内以电量门自适应（充电中 或
 *   电量 > 50% 才执行；不满足则静默跳过等周期链路）—— 双保险保证
 *   维护不再依赖「充电 + 深度空闲」的窄窗口。
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val memKit: MemKit,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 启动兜底链路的电量门：非充电且电量 ≤50% 时让位（周期链路兜底）。
        // 周期链路自带「充电中」约束，此处门必然放行（充电时 isCharging=true）。
        if (!powerSufficient()) {
            AppLogger.instance.info(
                LogCategory.CS_MEM, TAG,
                "记忆维护让位：未充电且电量不足 50%（等充电或下个周期）",
            )
            return Result.success()
        }
        val report = runCatching { memKit.runMaintenanceNow() }
            .getOrElse { e ->
                AppLogger.instance.error(LogCategory.CS_MEM, TAG, "维护周期执行失败（重试）", e)
                return Result.retry()
            }
        AppLogger.instance.info(
            LogCategory.CS_MEM, TAG,
            "记忆维护周期完成：${report.describe()}" +
                if (report.errors.isEmpty()) "" else "（异常 ${report.errors.size} 项：${report.errors.first()}）",
        )
        return if (report.errors.isEmpty()) Result.success() else Result.retry()
    }

    /** 电量门：充电中 或 电量 > 50%（读取失败按充足处理 —— 维护是纯本地工作）。 */
    private fun powerSufficient(): Boolean = runCatching {
        val bm = applicationContext.getSystemService(BatteryManager::class.java) ?: return true
        val charging = bm.isCharging
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        charging || level > 50
    }.getOrDefault(true)

    companion object {
        private const val TAG = "MaintenanceWorker"
    }
}

/** 维护周期的调度入口（Application.onCreate 调用；幂等 KEEP）。 */
@Singleton
class MaintenanceScheduler @Inject constructor() {

    /**
     * 周期维护（2 小时节拍）：充电中 + 电量不低。
     *
     * #851：去掉 `setRequiresDeviceIdle`（与充电互斥）与 WiFi 约束
     * （维护不联网）—— 原组合在 Doze 语义下永不满足，维护从未跑过。
     */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)          // 充电中（梦境语义：不打扰用户）
            .setRequiresBatteryNotLow(true)     // 电量不低于 ~20%
            .build()

        val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * 启动兜底（#851）：每次冷启动排一个一次性维护，无调度约束 ——
     * 电量门由 Worker 内部自适应（充电中 或 电量 > 50% 才执行，否则
     * 静默跳过）。KEEP 语义保证同一进程生命周期内不重复排队。
     */
    fun scheduleStartupFallback(context: Context) {
        val request = OneTimeWorkRequestBuilder<MaintenanceWorker>()
            .addTag(STARTUP_WORK_NAME)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            STARTUP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        private const val TAG = "MaintenanceScheduler"
        const val WORK_NAME = "memory_kit_maintenance"
        const val STARTUP_WORK_NAME = "memory_kit_maintenance_startup"

        /** 每 2 小时（沿用旧 DreamRenderer 节拍）。 */
        const val INTERVAL_MINUTES = 120L
    }
}
