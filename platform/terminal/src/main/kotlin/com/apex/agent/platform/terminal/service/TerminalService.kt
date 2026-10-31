package com.apex.agent.platform.terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.apex.agent.platform.terminal.R

/**
 * T91（D2-D4）：Terminal 前台服务 —— terminal 运行时的进程托管壳（Operit
 * terminal-core 对齐：AIDL 契约 + specialUse 前台类型 + UI 经 bind 访问）。
 *
 * ## 生命周期语义
 *
 *  - **startService（ACTION_START）**：进入前台（specialUse 类型，Android 14+
 *    兼容 —— 与 app 的 ApexCoreService 同款模式）。用户离开 UI / 关屏后，
 *    PTY 会话与 Ubuntu 依赖安装任务继续运行；
 *  - **bindService**：UI / 未来的 ：terminal 子进程消费者经 [ITerminalService]
 *    AIDL 契约访问（输出推送 + 输入写入）。绑定本身不拉起前台 —— UI 前台期间
 *    纯 bind 即可，进入长任务时再 startService 升前台。
 *
 * ## 薄壳纪律（与 T88 View 同构）
 *
 * 本类只做两件机械事：binder 参数 → [TerminalIpcController] 调用、控制器回调 →
 * binder oneway 派发。全部语义（编码/防注入/事件桥/会话管理）在纯 JVM 的
 * 控制器里 —— 那里有完整单测覆盖，本壳不承载任何可测逻辑。
 *
 * ## runtime 来源
 *
 * [TerminalRuntimeRegistry]（ApexApp 启动时 install Hilt 单例 —— 同进程任何
 * Service.onCreate 都晚于 Application.onCreate，时序必然成立）。多进程路线
 * （android:process=":terminal"）已被契约预留但刻意未启用 —— 见 Registry KDoc
 * 与 docs/terminal/TERMINAL_IPC_SERVICE_T91.md 路线图。
 */
class TerminalService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var controller: TerminalIpcController

    override fun onCreate() {
        super.onCreate()
        controller = TerminalIpcController(
            runtimeProvider = { TerminalRuntimeRegistry.get() },
            scope = scope
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 停止走系统语义：调用方 context.stopService()（无需自定义 action ——
        // startForegroundService 启动的服务若在 startForeground 前收场会触发
        // ForegroundServiceDidNotStartInTimeException，自定义 shutdown action
        // 反而制造崩溃路径）。此处唯一职责：进入前台。
        createNotificationChannel()
        val notification = buildNotification()
        // 秒闪退防御（与 ApexCoreService 同款）：Android 14+ 部分 OEM 对两参
        // startForeground 的 manifest 类型解析不一致 —— API 34+ 显式 specialUse。
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        controller.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        createNotificationChannel()
        return TerminalServiceStub()
    }

    /** AIDL 壳：ITerminalService 的机械转发（逻辑全部在 [TerminalIpcController]）。 */
    private inner class TerminalServiceStub : ITerminalService.Stub() {

        override fun createSession(
            backendId: String?,
            rows: Int,
            cols: Int,
            cwd: String?,
            envAssignments: MutableList<String>?
        ): String = controller.createSession(
            backendId = backendId ?: "linux-ubuntu",
            rows = rows,
            cols = cols,
            cwd = cwd,
            envAssignments = envAssignments?.toList()
        )

        override fun write(sessionId: Long, data: ByteArray?) {
            if (data != null) controller.write(sessionId, data)
        }

        override fun writeText(sessionId: Long, text: String?) {
            if (text != null) controller.writeText(sessionId, text)
        }

        override fun resize(sessionId: Long, rows: Int, cols: Int) {
            controller.resize(sessionId, rows, cols)
        }

        override fun closeSession(sessionId: Long, force: Boolean) {
            controller.closeSession(sessionId, force)
        }

        override fun listSessions(): String = controller.listSessions()

        override fun ping(): String = controller.ping()

        override fun registerCallback(callback: ITerminalCallback?) {
            if (callback != null) controller.registerCallback(BinderCallback(callback))
        }

        override fun unregisterCallback(callback: ITerminalCallback?) {
            if (callback != null) controller.unregisterCallback(BinderCallback(callback))
        }
    }

    /**
     * binder 回调适配（oneway —— 派发从不阻塞 PTY 泵）。
     *
     * 相等性按底层 binder 代理（BinderProxy 以 native 身份实现 equals/hashCode
     * —— 同一客户端的两次注册/注销调用拿到的是同一 binder 身份）：register 与
     * unregister 各自 new 一个适配器，若按对象身份判等，注销永远匹配不到注册
     * 条目 → 回调泄漏。以 remote 为准，Set 语义成立。
     *
     * T92：回调抛异常（binder oneway 缓冲耗尽/客户端死亡）不再无限全吞 ——
     * 控制器对连续失败计数并剔除（[TerminalIpcController] 防御性纪律），
     * 单次失败仍吞（单客户端崩溃不得拖垮 PTY 泵）。
     */
    private class BinderCallback(private val remote: ITerminalCallback) : TerminalIpcController.Callback {
        override fun onOutput(sessionId: Long, data: ByteArray) {
            runCatching { remote.onOutput(sessionId, data) }
        }

        override fun onExit(sessionId: Long, exitCode: Int, cause: String) {
            runCatching { remote.onExit(sessionId, exitCode, cause) }
        }

        override fun onSessionStateChanged(sessionId: Long, state: String) {
            runCatching { remote.onSessionStateChanged(sessionId, state) }
        }

        override fun equals(other: Any?): Boolean =
            other is BinderCallback && other.remote == remote

        override fun hashCode(): Int = remote.hashCode()
    }

    // ─── 前台通知（框架 API —— 本模块零 androidx 依赖，minSdk 26 = 通道必在） ───

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.terminal_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.terminal_service_channel_desc)
                setShowBadge(false)
            }
            runCatching { nm.createNotificationChannel(channel) }
        }
    }

    private fun buildNotification(): Notification {
        // 点击回宿主 App（库模块不认识 MainActivity —— launch intent 泛化）
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setPackage(packageName)
        val contentIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.terminal_service_notif_title))
            .setContentText(getString(R.string.terminal_service_notif_text))
            .setSmallIcon(R.drawable.terminal_service_icon)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "terminal_runtime"
        private const val NOTIFICATION_ID = 0x54C1 // "TERM1" —— 与 ApexCoreService 通知位错开

        /** 进入前台（保持 PTY 会话与 Ubuntu 任务在 UI 退出后继续）。
         *  停止走系统语义 context.stopService()（无自定义 shutdown action ——
         *  startForegroundService 启动后 5s 内未 startForeground 即崩，自定义
         *  action 反而制造该崩溃路径）。 */
        const val ACTION_START = "com.apex.agent.platform.terminal.action.START"

        /** 绑定动作（bindIntent 携带 —— 纯语义标记，未挂 intent-filter）。 */
        const val ACTION_BIND = "com.apex.agent.platform.terminal.action.BIND"

        /** 便捷启动入口（宿主 UI 调用）。 */
        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, TerminalService::class.java).setAction(ACTION_START)
            )
        }

        /** 便捷显式绑定入口（宿主 UI 调用）。 */
        fun bindIntent(context: Context): Intent =
            Intent(context, TerminalService::class.java).setAction(ACTION_BIND)
    }
}
