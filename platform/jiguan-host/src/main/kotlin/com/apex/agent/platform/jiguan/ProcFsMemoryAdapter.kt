package com.apex.agent.platform.jiguan

import com.apex.agent.core.jiguan.CommandBlueprints
import com.apex.agent.core.jiguan.JiguanMemoryPort
import com.apex.agent.core.jiguan.MemoryRegion
import com.apex.agent.core.jiguan.ProcParsing
import com.apex.agent.core.jiguan.ProcessEntry
import com.apex.agent.core.jiguan.PsParsing
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * # 进程与内存域宿主适配器
 *
 * 三级通道（与 jiguan-native Rust 核心互补 —— .so 在位时由工厂切换为
 * NativeMemoryAdapter，本类恒为无 native 依赖的可靠回落）：
 * 1. **进程枚举**：root 通道一次 `ps -A` 快照（全量含其它 UID）；无 root
 *    回落本进程直读 /proc（仅同 UID 可见 —— 语义在结果里如实声明）；
 * 2. **regions**：直读 /proc/<pid>/maps（同 UID 可读），失败回落 root cat；
 * 3. **read/write**：root 通道 dd 4096 对齐窗口（读）/ dd conv=notrunc（写），
 *    临时文件落 [workspaceDir]（root 可写、app 可读）。
 *
 * 全部失败折叠 Result.failure（工具层转结构化降级文案）。
 */
class ProcFsMemoryAdapter(
    private val commandRunner: JiguanCommandBridge,
    private val rootShellAvailable: () -> Boolean,
    /** root 通道一次性命令执行（PrivilegeManager.executeShell 语义）。 */
    private val rootShell: suspend (String, Long) -> Result<String>,
    private val scratchDir: File,
) : JiguanMemoryPort {

    override val channelName: String = "root-shell"

    private val ioMutex = Mutex()
    private var scratchSeq = 0

    // ── 进程枚举 ─────────────────────────────────────────────────

    override suspend fun listProcesses(): Result<List<ProcessEntry>> = withContext(Dispatchers.IO) {
        if (rootShellAvailable()) {
            val ps = rootShell("ps -A -o PID,UID,RSS,NAME", 15_000).getOrNull()
            if (ps != null) {
                val parsed = PsParsing.parse(ps)
                if (parsed.isNotEmpty()) return@withContext Result.success(parsed)
            }
        }
        // 回落：本进程视角直读 /proc（其它 UID 被 SELinux 遮蔽 —— 数量可能很少）。
        runCatching { readProcDirectory() }
    }

    private fun readProcDirectory(): List<ProcessEntry> {
        val out = ArrayList<ProcessEntry>()
        val proc = File("/proc")
        val dirs = proc.listFiles() ?: return emptyList()
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val cmdline = runCatching {
                File(dir, "cmdline").readText().replace('\u0000', ' ').trim()
            }.getOrNull() ?: ""
            val comm = runCatching { File(dir, "comm").readText().trim() }.getOrNull() ?: ""
            if (cmdline.isBlank() && comm.isBlank()) continue
            out.add(
                ProcessEntry(
                    pid = pid,
                    name = (comm.ifBlank { cmdline.substringBefore(' ') }).take(64),
                    cmdline = cmdline.take(160),
                    appPackage = ProcParsing.extractPackage(cmdline, comm),
                ),
            )
        }
        return out
    }

    // ── 内存映射 ─────────────────────────────────────────────────

    override suspend fun regions(pid: Int): Result<List<MemoryRegion>> = withContext(Dispatchers.IO) {
        // 同 UID 直读（零成本）；SELinux 拒绝时 root cat。
        val direct = runCatching {
            File("/proc/$pid/maps").readText().let(ProcParsing::parseMaps)
        }.getOrNull()
        if (direct != null && direct.isNotEmpty()) return@withContext Result.success(direct)
        if (rootShellAvailable()) {
            rootShell("cat /proc/$pid/maps", 10_000).mapCatching { ProcParsing.parseMaps(it) }
        } else {
            Result.failure(IllegalStateException("maps unreadable for pid $pid (need same-uid or root)"))
        }
    }

    // ── 内存读（dd 4096 对齐窗口 + 引擎侧裁剪） ───────────────────

    override suspend fun read(pid: Int, address: Long, size: Int): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            if (!rootShellAvailable()) {
                return@withContext Result.failure(IllegalStateException("memory read needs root (dd over proc-mem)"))
            }
            ioMutex.withLock {
                val alignedStart = (address / 4096) * 4096
                val alignedEnd = ((address + size + 4095) / 4096) * 4096
                val alignedSize = (alignedEnd - alignedStart).toInt()
                val scratch = scratchFile("mem-read")
                try {
                    val cmd = CommandBlueprints.ddReadMemAligned(
                        pid = pid,
                        alignedStart = alignedStart,
                        alignedSize = alignedSize,
                        outFile = scratch.absolutePath,
                    )
                    val run = rootShell(cmd.joinToString(" ") { CommandBlueprints.shellQuote(it) }, 30_000)
                    val output = run.getOrNull()
                    if (output == null || !scratch.isFile) {
                        return@withLock Result.failure(
                            IllegalStateException("dd read failed: ${run.exceptionOrNull()?.message ?: output?.take(120) ?: "no output"}"),
                        )
                    }
                    val window = scratch.readBytes()
                    val offsetInWindow = (address - alignedStart).toInt()
                    if (offsetInWindow + size > window.size) {
                        return@withLock Result.failure(
                            IllegalStateException("short read (${window.size}B window for ${alignedSize}B request)"),
                        )
                    }
                    Result.success(window.copyOfRange(offsetInWindow, offsetInWindow + size))
                } finally {
                    runCatching { scratch.delete() }
                }
            }
        }

    // ── 内存写（载荷文件 + root dd 精确落位） ─────────────────────

    override suspend fun write(pid: Int, address: Long, data: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!rootShellAvailable()) {
                return@withContext Result.failure(IllegalStateException("memory write needs root"))
            }
            ioMutex.withLock {
                val payload = scratchFile("mem-write")
                try {
                    payload.writeBytes(data)
                    val cmd = CommandBlueprints.ddWriteMem(pid, address, payload.absolutePath)
                    val run = rootShell(cmd.joinToString(" ") { CommandBlueprints.shellQuote(it) }, 15_000)
                    run.map { }
                        .onFailure {
                            AppLogger.instance.warn(
                                LogCategory.SYSTEM, TAG,
                                "内存写失败 pid=$pid addr=0x${address.toString(16)}: ${it.message}",
                            )
                        }
                } finally {
                    runCatching { payload.delete() }
                }
            }
        }

    // ── 内部 ─────────────────────────────────────────────────────

    private fun scratchFile(prefix: String): File {
        val seq = ++scratchSeq
        val file = File(scratchDir, "$prefix-$seq.bin")
        runCatching { scratchDir.mkdirs() }
        runCatching { file.delete() }
        return file
    }

    private companion object {
        const val TAG = "JiguanMem"
    }
}
