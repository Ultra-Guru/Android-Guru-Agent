package com.apex.agent.platform.privilege.shizuku

import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku ADB 级进程通道（真实提权执行）。
 *
 * ## 为什么直接走 AIDL
 *
 * `rikka.shizuku.Shizuku.newProcess(...)` 在 Shizuku API 13.x 中是 private
 * （且标注 deprecated、计划在 API 14 移除），但底层 AIDL 方法
 * `moe.shizuku.server.IShizukuService.newProcess(cmd, env, dir)` 是稳定契约：
 * 客户端经 `Shizuku.getBinder()` 拿到服务端 binder，`asInterface` 后直接调用。
 * 服务端（`server-shared/.../Service.java#newProcess`）在 Shizuku 服务进程内
 * `Runtime.exec`，进程身份 = shell (uid=2000，ADB 级)，stdin/stdout/stderr 以
 * [android.os.ParcelFileDescriptor] 回传 —— 与 `adb shell` 等价。
 *
 * 注意：调用前须确保 binder 已收到且 app 已被授予 Shizuku 权限
 * （[ShizukuCommandExecutor.isAvailable] / [ShizukuCommandExecutor.hasPermission]）。
 * 服务端 `enforceCallingPermission("newProcess")` 会在未授权时抛
 * [SecurityException] —— 这是真实的权限边界，不做任何静默降级。
 */
object ShizukuProcessChannel {

    /**
     * 取得 Shizuku 服务端的 AIDL 接口。
     *
     * binder 未收到（Shizuku 未运行 / Provider 未初始化）或已死亡 → null。
     */
    fun service(): IShizukuService? {
        return try {
            val binder = Shizuku.getBinder() ?: return null
            if (!binder.pingBinder()) return null
            IShizukuService.Stub.asInterface(binder)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 以 ADB (uid=2000) 身份启动进程。
     *
     * @param argv 完整 argv（含 argv[0]），如 `["sh", "-c", "pm list packages"]`
     * @param env  环境变量（"K=V" 数组）；null = 继承 Shizuku 服务端环境
     * @param dir  工作目录；null = 服务端默认目录
     * @return 包装为 [Process] 的远端进程（流式读取/超时/销毁与本地进程同构）
     * @throws SecurityException 未授权（服务端 enforceCallingPermission 拒绝）
     * @throws IllegalStateException 服务端 exec 失败（如命令不存在）
     * @throws RuntimeException binder 死亡 / 通信失败
     */
    fun start(argv: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        val svc = service()
            ?: throw IllegalStateException("Shizuku service is not available (binder not received or dead)")
        val remote: IRemoteProcess = svc.newProcess(argv, env, dir)
        return ShizukuProcessAdapter(remote)
    }
}

/**
 * [IRemoteProcess] → [Process] 适配器。
 *
 * 语义对照（照抄 Shizuku 官方 `ShizukuRemoteProcess` 的映射，后者构造器为
 * 包私有无法直接复用）：
 * - stdin/stdout/stderr = ParcelFileDescriptor 上的自动关闭流
 * - [waitFor] / [exitValue] / [destroy] 直接转发远端 binder 调用
 * - [destroy] 为幂等 best-effort（binder 死亡后远端进程由服务端按
 *   client token 自动回收 —— Shizuku 会在调用方死亡时杀掉它创建的进程）
 *
 * ## #381 P1 修复：未退出的异常语义必须显式对齐
 *
 * `java.lang.Process` 的默认实现（`waitFor(long, TimeUnit)` / `isAlive()`）
 * 轮询 `exitValue()` 时**只捕获 `IllegalThreadStateException`**（本地进程的
 * “未退出”异常）；而 Shizuku 服务端的 `IRemoteProcess.exitValue()` 在进程
 * 未退出时抛的是 **`IllegalStateException("process hasn't exited")`** ——
 * 异常穿透默认实现 → 首次轮询即上抛 → 所有走 Shizuku 通道的命令 100%
 * 瞬间失败（连 `echo OK` 都到不了真正执行）。因此这两个带轮询语义的方法
 * 都必须重载，把两种“未退出”异常一视同仁。
 */
class ShizukuProcessAdapter(private val remote: IRemoteProcess) : Process() {

    private var stdin: OutputStream? = null
    private var stdout: InputStream? = null
    private var stderr: InputStream? = null

    override fun getOutputStream(): OutputStream {
        if (stdin == null) {
            stdin = android.os.ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)
        }
        return stdin!!
    }

    override fun getInputStream(): InputStream {
        if (stdout == null) {
            stdout = android.os.ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
        }
        return stdout!!
    }

    override fun getErrorStream(): InputStream {
        if (stderr == null) {
            stderr = android.os.ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)
        }
        return stderr!!
    }

    override fun waitFor(): Int = remote.waitFor()

    /**
     * 带超时等待进程退出（#381 P1 修复）。
     *
     * 不重载时落到 `java.lang.Process` 默认实现，它轮询 [exitValue] 只捕
     * `IllegalThreadStateException`；Shizuku 服务端未退出时抛的是
     * `IllegalStateException("process hasn't exited")`，穿透后命令 100%
     * 瞬间报错。这里轮询并同时捕获两种“未退出”异常，到期返回 false
     * （与超时语义一致，由调用方 destroy 收尾）。
     *
     * 注意只捕这两种：binder 死亡等真异常继续上抛（调用方的 catch-all
     * 会如实报错），不与“未退出”混淆。
     */
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
        while (true) {
            try {
                exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                // 本地 Process 语义的“仍在运行”
            } catch (_: IllegalStateException) {
                // Shizuku 服务端语义的“仍在运行”（process hasn't exited）
            }
            if (System.currentTimeMillis() >= deadline) return false
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                // 保持线程中断语义：恢复标志位，按超时处理
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /**
     * 进程是否存活（#381 P1 修复，与 [waitFor] 同因）：默认 `isAlive()` 轮询
     * [exitValue] 同样只捕 `IllegalThreadStateException`，Shizuku 的
     * `IllegalStateException` 会上抛（`ShizukuCommandExecutor` 的 finally
     * 块调用 `isAlive`，异常会吞掉真实结果）。
     */
    override fun isAlive(): Boolean {
        return try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        } catch (_: IllegalStateException) {
            true
        }
    }

    override fun exitValue(): Int = remote.exitValue()

    override fun destroy() {
        runCatching { remote.destroy() }
    }

    private companion object {
        /** 轮询间隔：exitValue 是一次 binder 调用，20ms 足够细且不淹没 binder。 */
        private const val POLL_INTERVAL_MS = 20L
    }
}
