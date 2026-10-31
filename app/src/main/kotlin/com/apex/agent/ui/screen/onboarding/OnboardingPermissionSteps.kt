package com.apex.agent.ui.screen.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 新手引导 v2 —— 权限步骤模型 + 状态检测 + 授权入口构建。
 *
 * 纯 Kotlin（零 Compose 依赖）：UI 层 [OnboardingPermissionsPage] 只做渲染与
 * launcher 接线，所有「是否已授权 / 去哪授权」的判断收敛在这一个文件，
 * 与抽屉权限页（PermissionsScreen）的既有 API 用法保持同源。
 *
 * 官方依据（developer.android.com，逐项核对）：
 *  - 存储（READ/WRITE_EXTERNAL_STORAGE，运行时权限，maxSdk 32 已声明）：
 *    training/permissions/requesting —— ContextCompat.checkSelfPermission 判态，
 *    RequestMultiplePermissions 发起。
 *  - 所有文件访问（MANAGE_EXTERNAL_STORAGE，特殊权限）：
 *    training/data-storage/manage-all-files —— Environment.isExternalStorageManager()
 *    判态，ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION + package Uri 跳本应用
 *    专属授权页（API 30+；以下版本由传统存储权限覆盖）。
 *  - 修改系统设置（WRITE_SETTINGS，特殊权限）：Settings.System.canWrite(context)
 *    判态，ACTION_MANAGE_WRITE_SETTINGS + package Uri 跳「允许修改系统设置」页。
 *  - 安装未知应用（REQUEST_INSTALL_PACKAGES，特殊权限，API 26+）：
 *    PackageManager.canRequestPackageInstalls() 判态，
 *    ACTION_MANAGE_UNKNOWN_APP_SOURCES + package Uri 跳本应用「未知来源」页
 *    （Android 8+ 按应用逐一授权）。
 *  - 忽略电池优化（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 已声明）：
 *    training/monitoring-device-state/doze-standby ——
 *    PowerManager.isIgnoringBatteryOptimizations(packageName) 判态，
 *    ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 直发豁免确认对话框。
 *  - 悬浮窗（SYSTEM_ALERT_WINDOW 已声明）：Settings.canDrawOverlays(context)
 *    判态；API 30+ 用 MANAGE_APP_OVERLAY_SETTINGS 本应用专属页（常量在部分
 *    compileSdk 下不可见，按 PermissionsScreen 惯例用字面值），以下用
 *    ACTION_MANAGE_OVERLAY_PERMISSION。
 *  - 通知（POST_NOTIFICATIONS 已声明）：API 33+ 为运行时权限（本应用
 *    targetSdk 28 仍显式请求是正确做法），NotificationManagerCompat
 *    .areNotificationsEnabled() 统一判态（覆盖用户在系统设置手动关闭的场景）。
 *  - 无障碍（进阶，可选）：与 PermissionsScreen 同款 AccessibilityManager
 *    遍历判态，ACTION_ACCESSIBILITY_SETTINGS 跳系统列表页。
 */
enum class OnboardingPermissionKind {
    /** 传统存储读写（API < 33 才有意义；33+ 由所有文件访问覆盖）。 */
    STORAGE,

    /** 所有文件访问 / MANAGE_EXTERNAL_STORAGE（API 30+）。 */
    ALL_FILES,

    /** 修改系统设置 / WRITE_SETTINGS。 */
    WRITE_SETTINGS,

    /** 安装未知来源应用 / REQUEST_INSTALL_PACKAGES。 */
    INSTALL_UNKNOWN,

    /** 忽略电池优化白名单。 */
    BATTERY,

    /** 悬浮窗 / SYSTEM_ALERT_WINDOW。 */
    OVERLAY,

    /** 通知 / POST_NOTIFICATIONS。 */
    NOTIFICATION,

    /** 无障碍服务（进阶，可选 —— UI 自动化的眼睛和手）。 */
    ACCESSIBILITY
}

/**
 * 单项授权状态。
 *
 * @param granted 已授权（或运行时检测通过）。
 * @param notApplicable 当前系统版本无需此权限（不算入进度分母）——
 *   例如 API 33+ 的传统存储权限已废弃、API 30 以下不存在「所有文件访问」。
 */
data class OnboardingPermissionStatus(
    val granted: Boolean,
    val notApplicable: Boolean = false
)

/** 各步骤的授权状态检测（全部为廉价调用，IO 线程统一执行只是沿用仓库惯例）。 */
object OnboardingPermissionChecks {

    fun check(kind: OnboardingPermissionKind, context: Context): OnboardingPermissionStatus =
        when (kind) {
            // Android 13 起 READ/WRITE_EXTERNAL_STORAGE 不再授予（Manifest 已
            // maxSdkVersion 32）—— 该步自动标记「无需」，由所有文件访问覆盖。
            OnboardingPermissionKind.STORAGE ->
                if (Build.VERSION.SDK_INT >= 33) {
                    OnboardingPermissionStatus(granted = true, notApplicable = true)
                } else {
                    OnboardingPermissionStatus(
                        granted = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                }

            // API 30+ 走 isExternalStorageManager；以下版本由传统存储权限覆盖。
            OnboardingPermissionKind.ALL_FILES ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    OnboardingPermissionStatus(granted = Environment.isExternalStorageManager())
                } else {
                    OnboardingPermissionStatus(granted = true, notApplicable = true)
                }

            OnboardingPermissionKind.WRITE_SETTINGS ->
                OnboardingPermissionStatus(granted = Settings.System.canWrite(context))

            OnboardingPermissionKind.INSTALL_UNKNOWN ->
                OnboardingPermissionStatus(granted = context.packageManager.canRequestPackageInstalls())

            OnboardingPermissionKind.BATTERY -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                OnboardingPermissionStatus(
                    granted = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                )
            }

            OnboardingPermissionKind.OVERLAY ->
                OnboardingPermissionStatus(granted = Settings.canDrawOverlays(context))

            OnboardingPermissionKind.NOTIFICATION -> {
                // areNotificationsEnabled 统一覆盖 API 33 运行时权限与
                // 旧版本「用户在系统设置手动关通知」两种未授权形态。
                val enabled = runCatching {
                    androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                }.getOrDefault(false)
                OnboardingPermissionStatus(granted = enabled)
            }

            OnboardingPermissionKind.ACCESSIBILITY ->
                OnboardingPermissionStatus(granted = context.isAccessibilityServiceEnabled())
        }

    /** 与 PermissionsScreen 同款检测：已启用的无障碍服务里是否有本应用的。 */
    private fun Context.isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabled = runCatching {
            am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
        }.getOrDefault(emptyList())
        return enabled.any { it.resolveInfo?.serviceInfo?.packageName == packageName }
    }
}

/** 各步骤的「去授权」入口构建（特殊权限一律跳系统设置页，运行时权限由 UI 层 launcher 处理）。 */
object OnboardingPermissionIntents {

    /**
     * 构建跳转系统设置的 Intent（调用方负责 runCatching + startActivity +
     * FLAG_ACTIVITY_NEW_TASK）。返回 null 表示该步骤在当前版本不需要跳转。
     */
    fun openSettings(kind: OnboardingPermissionKind, context: Context): Intent? {
        val pkg = Uri.parse("package:${context.packageName}")
        return when (kind) {
            OnboardingPermissionKind.STORAGE -> null // 运行时权限，走 launcher

            OnboardingPermissionKind.ALL_FILES ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
                } else null

            OnboardingPermissionKind.WRITE_SETTINGS ->
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)

            OnboardingPermissionKind.INSTALL_UNKNOWN ->
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg)

            OnboardingPermissionKind.BATTERY ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)

            OnboardingPermissionKind.OVERLAY ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // MANAGE_APP_OVERLAY_SETTINGS 常量在部分 compileSdk 平台下
                    // 不可见，按仓库既有惯例（PermissionsScreen）使用字面值，
                    // 直达本应用的悬浮窗授权页。
                    Intent("android.settings.action.MANAGE_APP_OVERLAY_SETTINGS", pkg)
                } else {
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
                }

            OnboardingPermissionKind.NOTIFICATION ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }

            OnboardingPermissionKind.ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
    }

    /** 统一的「打开设置页」动作：任何 ROM 差异都吞掉，不炸引导流程。 */
    fun launchSettings(kind: OnboardingPermissionKind, context: Context) {
        val intent = openSettings(kind, context) ?: return
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
