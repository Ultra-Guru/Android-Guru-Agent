package com.apex.agent.platform.memory.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
 * 约束沿用旧口径（梦境语义：不打扰用户）：充电中 + 电量不低于 ~20% +
 * 设备空闲 + WiFi；节拍 2 小时。维护本体（衰减 / 剪枝 / 保鲜 / 晶化 /
 * 别名发现 / 容量治理）全部住在库 MacroMaintenance —— 宿主只剩调度。
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val memKit: MemKit,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
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

    companion object {
        private const val TAG = "MaintenanceWorker"
    }
}

/** 维护周期的调度入口（Application.onCreate 调用；幂等 KEEP）。 */
@Singleton
class MaintenanceScheduler @Inject constructor() {

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)          // 充电中
            .setRequiresBatteryNotLow(true)      // 电量不低于 ~20%
            .setRequiresDeviceIdle(true)        // 设备息屏/空闲（梦境语义）
            .setRequiredNetworkType(NetworkType.UNMETERED) // WiFi
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

    companion object {
        private const val TAG = "MaintenanceScheduler"
        const val WORK_NAME = "memory_kit_maintenance"

        /** 每 2 小时（沿用旧 DreamRenderer 节拍）。 */
        const val INTERVAL_MINUTES = 120L
    }
}
