package com.apex.agent.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.apex.agent.MainActivity
import com.apex.agent.R
import com.apex.agent.ui.language.LanguageManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知中心 —— 任务完成通知 + 通用通知的统一出口（v1.4.4 #4）。
 *
 * ## 背景
 *
 * 此前全 App 唯一的通知渠道是 [com.apex.agent.service.ApexCoreService] 的
 * `apex_core_service`（IMPORTANCE_LOW 常驻前台通知）。长任务/Agent 轮次在后台
 * 跑完后**没有任何提醒**——用户切走去干别的，回来才发现早就结束了。
 *
 * ## 渠道设计
 *
 * | 渠道 | 重要级 | 用途 |
 * |---|---|---|
 * | `apex_task_done` | HIGH（带声音/横幅） | 任务/回复完成 |
 * | `apex_general` | DEFAULT | 一般性提醒（预留） |
 *
 * 高重要级只给"任务完成"这一件事：它是用户主动发起、被动等待的结果，打扰
 * 得其所；其余一切提醒走 DEFAULT，保持克制的通知礼仪。
 *
 * ## 权限现实（targetSdk=28 的特殊姿态）
 *
 * - Manifest 已声明 `POST_NOTIFICATIONS`；
 * - 本应用 targetSdk 钉死 28（PRoot W^X 红线），在 Android 13+ 上的行为是：
 *   **系统在 App 首次创建通知渠道并启动 Activity 后自动弹授权对话框**，
 *   不需要（也不能可靠地）主动 `requestPermissions`；
 * - 因此一切通知发射前经 [canPost] 判定（`areNotificationsEnabled()`，覆盖
 *   Android 13 授权拒绝/用户在设置里关闭渠道等所有情形），未授权则**静默
 *   丢弃**——通知是锦上添花，绝不因权限问题在调用点制造崩溃或噪音。
 *
 * ## 通知礼仪
 *
 * - `setAutoCancel(true)`：点击即消；
 * - `setTimeoutAfter(AUTO_DISMISS_MS)`：**5 分钟后自动消失**（用户回来时锁屏
 *   不被陈年"已完成"刷屏；点开 App 看到结果才是正路）；
 * - 通知 id 递增：连续多个任务完成各自独立堆叠，不互相覆盖；
 * - 预览文本截断 60 字符：锁屏可见即止，长回答引导用户进 App。
 *
 * ## 线程模型
 *
 * 所有方法可从任意线程调用（NotificationManagerCompat 线程安全；
 * [LanguageManager.getString] 内部 resolvedContext 只读）。
 */
@Singleton
class ApexNotifications @Inject constructor(
    private val lang: LanguageManager
) {

    /**
     * 幂等建渠道（minSdk=26，NotificationChannel 无条件需要）。
     * 在 App 启动早期调用一次；重复调用由系统 createNotificationChannel 去重。
     */
    fun ensureChannels(context: Context) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TASK_DONE,
                    lang.getString(R.string.notif_channel_task_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = lang.getString(R.string.notif_channel_task_desc)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_GENERAL,
                    lang.getString(R.string.notif_channel_general_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = lang.getString(R.string.notif_channel_general_desc)
                }
            )
        }
    }

    /**
     * 通知是否可发（用户授权 + 渠道未关 + 系统通知总开关）。
     * 永不抛异常：任何判定失败都视为"不可发"（宁可漏通知，不可崩主流程）。
     */
    fun canPost(context: Context): Boolean = runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(false)

    /**
     * 任务完成通知（Agent 轮次跑完 / 长任务结束）。
     *
     * @param title 通知标题（一般是触发任务的用户消息摘要或任务名）
     * @param preview 结果预览（截 60 字符；空则显示通用完成文案）
     */
    fun notifyTaskDone(context: Context, title: String, preview: String) {
        if (!canPost(context)) return
        runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_TASK_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title.take(NOTIF_TITLE_MAX))
                .setContentText(
                    if (preview.isBlank()) lang.getString(R.string.notif_task_done_generic)
                    else preview.take(NOTIF_PREVIEW_MAX)
                )
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(preview.take(NOTIF_PREVIEW_BIG_MAX))
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(mainIntent(context))
                .setAutoCancel(true)
                .setTimeoutAfter(AUTO_DISMISS_MS)
                .build()
            NotificationManagerCompat.from(context).notify(nextId(), notification)
        }
    }

    /**
     * 通用通知（apex_general 渠道，供后续功能复用——下载完成/定时任务触发等）。
     * 同样静默失败语义。
     */
    fun notifyGeneral(context: Context, title: String, text: String) {
        if (!canPost(context)) return
        runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_GENERAL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title.take(NOTIF_TITLE_MAX))
                .setContentText(text.take(NOTIF_PREVIEW_MAX))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(mainIntent(context))
                .setAutoCancel(true)
                .setTimeoutAfter(AUTO_DISMISS_MS)
                .build()
            NotificationManagerCompat.from(context).notify(nextId(), notification)
        }
    }

    /** 通知 id：基址 + 递增序号（堆叠而非覆盖）。 */
    private fun nextId(): Int = NOTIFICATION_ID_BASE + idCounter.incrementAndGet()

    private fun mainIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        MAIN_INTENT_REQUEST_CODE,
        Intent(context, MainActivity::class.java).apply {
            // 单实例语境内已是 clear-top；显式 flag 保证叠栈场景也回到既有实例
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val CHANNEL_TASK_DONE = "apex_task_done"
        const val CHANNEL_GENERAL = "apex_general"

        /** 5 分钟自动消失：完成通知的价值随时间衰减，过期即清。 */
        private const val AUTO_DISMISS_MS = 5L * 60 * 1000
        private const val NOTIF_TITLE_MAX = 40
        private const val NOTIF_PREVIEW_MAX = 60
        private const val NOTIF_PREVIEW_BIG_MAX = 200
        private const val NOTIFICATION_ID_BASE = 2000
        private const val MAIN_INTENT_REQUEST_CODE = 2001

        private val idCounter = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
