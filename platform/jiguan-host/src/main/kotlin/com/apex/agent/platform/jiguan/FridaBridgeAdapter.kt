package com.apex.agent.platform.jiguan

import com.apex.agent.core.jiguan.CommandBlueprints
import com.apex.agent.core.jiguan.DisasmLine
import com.apex.agent.core.jiguan.JavaClassEntry
import com.apex.agent.core.jiguan.JiguanJavaPort
import com.apex.agent.core.jiguan.JiguanNativePort
import com.apex.agent.core.jiguan.ModuleEntry
import com.apex.agent.core.jiguan.ProcParsing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * # Frida 桥 + Native 域宿主适配器
 *
 * - **runScript**（Java/Native 共用）：脚本落工作区（PRoot 可见路径
 *   `/workspace`），经 PRoot frida-tools CLI 连 127.0.0.1:27042 的
 *   frida-server 注入目标进程，回收 console 输出作为事件流；
 * - **frida-server 生命周期**：探测（pgrep）→ 缺席时尝试拉起
 *   （/data/local/tmp/frida-server 常见位）→ 仍缺席返回结构化指引；
 * - **listModules**：maps 聚合（复用内存适配器的 maps 读取语义，零 Frida 依赖）；
 * - **disassemble**：内存段落盘 → PRoot objdump（binutils）裸二进制反汇编。
 */
class FridaBridgeAdapter(
    private val commandRunner: JiguanCommandBridge,
    private val memoryRegions: suspend (Int) -> Result<List<com.apex.agent.core.jiguan.MemoryRegion>>,
    private val memoryRead: suspend (Int, Long, Int) -> Result<ByteArray>,
    private val rootShellAvailable: () -> Boolean,
    private val rootShell: suspend (String, Long) -> Result<String>,
    /** PRoot 工作区宿主目录（guest /workspace —— 脚本/转储落点）。 */
    private val workspaceDir: File,
) : JiguanJavaPort, JiguanNativePort {

    override val channelName: String = "frida-bridge"

    private var scriptSeq = 0

    /** Java/ART 域可用性 = frida-server 存活（可拉起）且 PRoot 内 CLI 在位。 */
    override suspend fun ping(): Boolean = ensureFridaServer() && isFridaCliAvailable()

    // ── Frida 面板 ───────────────────────────────────────────────

    /** frida-server 是否在跑（root pgrep）。 */
    suspend fun isFridaServerUp(): Boolean {
        if (!rootShellAvailable()) return false
        val probe = rootShell("pgrep -f frida-server", 5_000).getOrNull()
        return probe != null && probe.trim().toIntOrNull() != null
    }

    /** 尝试拉起 frida-server（常见部署位；已带执行位检查）。 */
    suspend fun ensureFridaServer(): Boolean {
        if (isFridaServerUp()) return true
        if (!rootShellAvailable()) return false
        val start = rootShell(
            "/data/local/tmp/frida-server -D 2>/dev/null || su -c /data/local/tmp/frida-server -D 2>/dev/null",
            8_000,
        )
        return start.isSuccess && isFridaServerUp()
    }

    /** PRoot 里 frida CLI 是否可用（pip install frida-tools）。 */
    private suspend fun isFridaCliAvailable(): Boolean {
        val probe = commandRunner.run(listOf("which", "frida"), 10_000).getOrNull() ?: return false
        return probe.isOk() && probe.stdout.isNotBlank()
    }

    override suspend fun runScript(pid: Int, script: String, timeoutMs: Long): Result<String> =
        withContext(Dispatchers.IO) {
            // 1) frida-server（设备侧）与 frida CLI（PRoot 侧）双前置。
            if (!ensureFridaServer()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "frida-server not running (push to /data/local/tmp/frida-server, chmod +x, launch with root)",
                    ),
                )
            }
            if (!isFridaCliAvailable()) {
                return@withContext Result.failure(
                    IllegalStateException("frida CLI missing in sandbox (apt/pip install frida-tools in PRoot Ubuntu)"),
                )
            }
            // 2) 脚本落工作区（guest /workspace 可见）。
            val seq = ++scriptSeq
            val scriptDir = File(workspaceDir, "jiguan/scripts")
            runCatching { scriptDir.mkdirs() }
            val scriptFile = File(scriptDir, "s-$seq.js")
            scriptFile.writeText(script)
            try {
                // 3) 执行（timeout 由 CLI 外层 timeout 控制；退出码 124 = 窗口自然到期，
                //    输出仍然有效 —— 观察型脚本的正常终态）。
                val timeoutSec = (timeoutMs / 1000).toInt().coerceIn(5, 180)
                val cmd = CommandBlueprints.fridaRunScript(pid, scriptFile.absolutePath, timeoutSec)
                val result = commandRunner.run(cmd, timeoutMs + 20_000).getOrElse { return@withContext Result.failure(it) }
                if (result.stdout.isBlank() && result.exitCode != 0 && result.exitCode != 124) {
                    return@withContext Result.failure(
                        IllegalStateException("frida exit=${result.exitCode}: ${result.stderr.take(300)}"),
                    )
                }
                Result.success(result.stdout.ifBlank { result.stderr })
            } finally {
                runCatching { scriptFile.delete() }
            }
        }

    override suspend fun listClasses(pid: Int, filter: String): Result<List<JavaClassEntry>> =
        withContext(Dispatchers.IO) {
            val script = com.apex.agent.core.jiguan.FridaScriptLibrary.javaEnumerateClasses(filter)
            val output = runScript(pid, script, 30_000).getOrElse { return@withContext Result.failure(it) }
            val classes = output.lineSequence()
                .filter { it.startsWith("[class] ") }
                .map { JavaClassEntry(it.removePrefix("[class] ")) }
                .toList()
            Result.success(classes)
        }

    // ── Native 面 ────────────────────────────────────────────────

    override suspend fun listModules(pid: Int): Result<List<ModuleEntry>> = withContext(Dispatchers.IO) {
        memoryRegions(pid).mapCatching { ProcParsing.aggregateModules(it) }
    }

    override suspend fun disassemble(pid: Int, address: Long, size: Int): Result<List<DisasmLine>> =
        withContext(Dispatchers.IO) {
            // 1) 内存段读取 → 工作区落盘（objdump 在 PRoot 内只能见 rootfs/bind 路径）。
            val bytes = memoryRead(pid, address, size).getOrElse {
                return@withContext Result.failure(IllegalStateException("dump failed: ${it.message}"))
            }
            val dumpDir = File(workspaceDir, "jiguan/dumps")
            runCatching { dumpDir.mkdirs() }
            val dump = File(dumpDir, "d-${address.toString(16)}.bin")
            dump.writeBytes(bytes)
            try {
                // 2) objdump 裸二进制反汇编（--adjust-vma 恢复真实地址）。
                val cmd = CommandBlueprints.objdumpBinary(dump.absolutePath, adjustVma = address)
                val result = commandRunner.run(cmd, 30_000).getOrElse { return@withContext Result.failure(it) }
                if (!result.isOk()) {
                    return@withContext Result.failure(
                        IllegalStateException("objdump exit=${result.exitCode} (apt install binutils in PRoot): ${result.stderr.take(200)}"),
                    )
                }
                Result.success(DisasmParsing.parseObjdump(result.stdout))
            } finally {
                runCatching { dump.delete() }
            }
        }
}

/** objdump 输出解析（`  addr:\tbytes\tmnemonic` 三栏行）。 */
internal object DisasmParsing {

    fun parseObjdump(output: String): List<DisasmLine> {
        val out = ArrayList<DisasmLine>()
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // 形态："1000:\t20 00 80 52\tmov w0, #1"
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val address = trimmed.substring(0, colon).trim().toLongOrNull(16) ?: continue
            val rest = trimmed.substring(colon + 1).split('\t', limit = 3).map { it.trim() }
            if (rest.size < 2) continue
            val bytes = rest[0]
            val text = rest.drop(1).joinToString(" ").trim()
            out.add(DisasmLine(address = address, bytes = bytes, text = text))
        }
        return out
    }
}
