package com.apex.agent.platform.jiguan

import com.apex.agent.core.jiguan.CaptureSession
import com.apex.agent.core.jiguan.CommandBlueprints
import com.apex.agent.core.jiguan.JiguanNetworkPort
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * # 网络域宿主适配器（tcpdump 落盘 + 会话管理）
 *
 * 通道决策（诚实降级）：
 * 1. **root 通道 tcpdump**（首选）：设备侧直接抓（PRoot 内 tcpdump 需要
 *    真实 CAP_NET_RAW，PRoot 伪造 uid 无法满足 —— 不作无谓尝试）。
 *    tcpdump 二进制可以是系统自带 / Magisk 模块 / 用户推到
 *    /data/local/tmp/tcpdump 的任意一种（PATH 可见即可）；
 * 2. 无 root / 无 tcpdump → 结构化失败（安装指引直达 Agent）。
 *
 * pcap 落工作区（host 可直读回传给引擎解析）。
 */
class PacketCaptureAdapter(
    private val rootShellAvailable: () -> Boolean,
    private val rootShell: suspend (String, Long) -> Result<String>,
    /** PRoot 工作区宿主目录（pcap 落点 —— 双侧可见）。 */
    private val workspaceDir: File,
) : JiguanNetworkPort {

    override val channelName: String = "tcpdump-root"

    private val mutex = Mutex()
    private var session: CaptureSession? = null
    private var seq = 0

    /** tcpdump 存在性缓存（root which 一次探测，进程内记忆）。 */
    @Volatile
    private var tcpdumpPresent: Boolean? = null

    override suspend fun ping(): Boolean {
        if (!rootShellAvailable()) return false
        val cached = tcpdumpPresent
        if (cached != null) return cached
        val present = rootShell("which tcpdump || ls /data/local/tmp/tcpdump", 6_000)
            .getOrNull()
            ?.let { it.contains("tcpdump") && !it.contains("not found") } == true
        tcpdumpPresent = present
        return present
    }

    override suspend fun startCapture(pid: Int?, filter: String): Result<CaptureSession> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (session?.running == true) {
                    return@withLock Result.success(
                        session!!.copy(),
                    )
                }
                if (!rootShellAvailable()) {
                    return@withLock Result.failure(
                        IllegalStateException("packet capture needs root (tcpdump on device)"),
                    )
                }
                val id = ++seq
                val captureDir = File(workspaceDir, "jiguan/captures")
                runCatching { captureDir.mkdirs() }
                val pcap = File(captureDir, "cap-$id.pcap")
                runCatching { pcap.delete() }
                val cmd = CommandBlueprints.tcpdumpStart(pcap.absolutePath, filter)
                    .joinToString(" ") { CommandBlueprints.shellQuote(it) }
                val start = rootShell(cmd, 6_000)
                // tcpdump 前台驻留 —— rootShell 超时即认为已启动（超时≠失败：
                // 落盘文件出现即证据）。验证 pcap 文件已生成。
                val running = pcap.isFile
                val newSession = CaptureSession(
                    id = id,
                    pid = pid,
                    pcapPath = pcap.absolutePath,
                    running = running,
                    channel = if (running) channelName else "unavailable",
                )
                session = newSession
                if (!running) {
                    val reason = start.exceptionOrNull()?.message ?: start.getOrNull()?.take(160) ?: "tcpdump not found"
                    // 尾随提示：start 失败原因里通常含 "not found"。
                    Result.failure(IllegalStateException("tcpdump start failed: $reason"))
                } else {
                    Result.success(newSession)
                }
            }
        }

    override suspend fun stopCapture(sessionId: Int): Result<CaptureSession> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = session ?: return@withLock Result.failure(IllegalStateException("no active session"))
                if (current.id != sessionId) {
                    return@withLock Result.failure(IllegalStateException("session $sessionId not found"))
                }
                val stop = CommandBlueprints.tcpdumpStop(current.pcapPath)
                    .joinToString(" ") { CommandBlueprints.shellQuote(it) }
                rootShell(stop, 6_000)
                // pkill 异步生效 —— 等待落盘文件停止增长（有界轮询，delay 让出调度）。
                var stable = false
                var lastSize = File(current.pcapPath).length()
                for (round in 0 until 6) {
                    kotlinx.coroutines.delay(250)
                    val size = File(current.pcapPath).length()
                    if (size == lastSize) { stable = true; break }
                    lastSize = size
                }
                val stopped = current.copy(running = false)
                session = stopped
                if (!stable) {
                    // tcpdump 可能仍在收尾 —— 不视为失败（pcap 仍可解析已落盘部分）。
                    com.apex.agent.core.logging.AppLogger.instance.warn(
                        com.apex.agent.core.logging.LogCategory.SYSTEM, "JiguanCap",
                        "pcap 文件仍在增长（pkill 生效中）—— 解析已落盘部分",
                    )
                }
                Result.success(stopped)
            }
        }
}
