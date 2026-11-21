package com.apex.agent.platform.terminal.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder

/**
 * T91（D2-D4）：TerminalService 绑定客户端 —— 宿主 UI 消费 AIDL 契约的便捷门面。
 *
 * ## 用法（UI 迁移的接入点 —— 见 docs/terminal/TERMINAL_IPC_SERVICE_T91.md）
 *
 * ```kotlin
 * val client = TerminalServiceClient(context)
 * client.listener = object : TerminalServiceClient.Listener {
 *     override fun onOutput(sessionId: Long, data: ByteArray) { … }   // 追加到 VT/渲染层
 *     override fun onExit(sessionId: Long, exitCode: Int, cause: String) { … }
 *     override fun onSessionStateChanged(sessionId: Long, state: String) { … }
 * }
 * client.bind()                       // ON_START
 * client.service?.createSession("linux-ubuntu", 24, 80, null, null)
 * client.unbind()                     // ON_STOP
 * ```
 *
 * ## 边界诚实性
 *
 *  - **绑定 ≠ 前台**：纯 bind 在 UI 前台期间够用；进入长任务（Ubuntu 依赖安装、
 *    编译型 job）时调用 [TerminalService.start] 升前台，UI 退出后会话不中断；
 *  - **回调即 binder oneway**：慢消费只拖慢自己（binder 异步缓冲天然背压），
 *    不影响 PTY 泵；
 *  - **多进程预留**：本客户端对进程位置零假设 —— 未来 TerminalService 搬进
 *    `:terminal` 子进程时，本类一行不改。
 */
class TerminalServiceClient(private val context: Context) : ServiceConnection {

    /** 回调（客户端侧镜像 —— binder 桩在 [bind] 时注册）。 */
    interface Listener {
        fun onOutput(sessionId: Long, data: ByteArray)
        fun onExit(sessionId: Long, exitCode: Int, cause: String)
        fun onSessionStateChanged(sessionId: Long, state: String)
    }

    @Volatile
    var listener: Listener? = null

    @Volatile
    var onConnected: (() -> Unit)? = null

    @Volatile
    var onDisconnected: (() -> Unit)? = null

    /** 已连接的服务代理（null = 未绑定/断连 —— 调用方据此降级或等待重连）。 */
    val service: ITerminalService?
        get() = bound

    @Volatile
    private var bound: ITerminalService? = null

    /** 客户端侧 binder 回调桩（oneway；派发异常全吞 —— 单消费者崩溃不炸服务端）。 */
    private val callbackStub = object : ITerminalCallback.Stub() {
        override fun onOutput(sessionId: Long, data: ByteArray?) {
            if (data != null) listener?.onOutput(sessionId, data)
        }

        override fun onExit(sessionId: Long, exitCode: Int, cause: String?) {
            listener?.onExit(sessionId, exitCode, cause ?: "UNKNOWN")
        }

        override fun onSessionStateChanged(sessionId: Long, state: String?) {
            if (state != null) listener?.onSessionStateChanged(sessionId, state)
        }
    }

    private var boundOnce = false

    fun bind() {
        if (boundOnce) return
        boundOnce = true
        runCatching {
            context.bindService(
                TerminalService.bindIntent(context),
                this,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    fun unbind() {
        if (!boundOnce) return
        boundOnce = false
        bound?.let { runCatching { it.unregisterCallback(callbackStub) } }
        runCatching { context.unbindService(this) }
        bound = null
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val proxy = service?.let { ITerminalService.Stub.asInterface(it) } ?: return
        bound = proxy
        runCatching { proxy.registerCallback(callbackStub) }
        onConnected?.invoke()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        bound = null
        onDisconnected?.invoke()
    }

    // ─── Kotlin 便捷门面（null 安全转发；未绑定时安静 no-op） ───

    fun createSession(
        backendId: String,
        rows: Int = 24,
        cols: Int = 80,
        cwd: String? = null,
        envAssignments: List<String> = emptyList()
    ): String? = bound?.createSession(
        backendId, rows, cols, cwd,
        // 显式可变副本：与生成代理的平台类型参数在任何推断形态下都兼容
        ArrayList(envAssignments)
    )

    fun write(sessionId: Long, data: ByteArray) {
        bound?.write(sessionId, data)
    }

    fun writeText(sessionId: Long, text: String) {
        bound?.writeText(sessionId, text)
    }

    fun resize(sessionId: Long, rows: Int, cols: Int) {
        bound?.resize(sessionId, rows, cols)
    }

    fun closeSession(sessionId: Long, force: Boolean = false) {
        bound?.closeSession(sessionId, force)
    }

    fun listSessions(): String? = bound?.listSessions()

    fun ping(): String? = bound?.ping()
}
