package com.apex.agent.core.jiguan

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令蓝图 + 编排器 + Runtime 高层编排回归 —— 纯 JVM fake 端口。
 */
class CommandBlueprintsTest {

    @Test
    fun `ddReadMemAligned enforces 4096 alignment`() {
        val cmd = CommandBlueprints.ddReadMemAligned(123, alignedStart = 0x1000L, alignedSize = 8192, outFile = "/tmp/x.bin")
        assertEquals(listOf("dd", "if=/proc/123/mem", "of=/tmp/x.bin", "bs=4096", "skip=1", "count=2"), cmd)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ddReadMemAligned rejects unaligned window`() {
        CommandBlueprints.ddReadMemAligned(1, alignedStart = 0x1001L, alignedSize = 4096, outFile = "/tmp/x")
    }

    @Test
    fun `ddWriteMem uses byte seek`() {
        assertEquals(
            listOf("dd", "if=/tmp/w.bin", "of=/proc/9/mem", "bs=1", "seek=4096", "conv=notrunc"),
            CommandBlueprints.ddWriteMem(9, 4096, "/tmp/w.bin"),
        )
    }

    @Test
    fun `tcpdumpStart appends filter only when present`() {
        val withFilter = CommandBlueprints.tcpdumpStart("/tmp/c.pcap", "port 443")
        assertTrue(withFilter.last() == "port 443")
        val bare = CommandBlueprints.tcpdumpStart("/tmp/c.pcap", "")
        assertEquals(7, bare.size)
    }

    @Test
    fun `fridaRunScript targets local server`() {
        val cmd = CommandBlueprints.fridaRunScript(42, "/tmp/s.js", 15)
        assertTrue(cmd.containsAll(listOf("frida", "-H", "127.0.0.1:27042", "-p", "42", "-l", "/tmp/s.js", "-q")))
        assertEquals("timeout", cmd.first())
        assertEquals("15", cmd[1])
    }

    @Test
    fun `inputTap and swipe argv shapes`() {
        assertEquals(listOf("input", "tap", "540", "1200"), CommandBlueprints.inputTap(540, 1200))
        assertEquals(
            listOf("input", "swipe", "1", "2", "3", "4", "300"),
            CommandBlueprints.inputSwipe(1, 2, 3, 4, 300),
        )
    }

    @Test
    fun `shellQuote escapes single quotes`() {
        assertEquals("'it'\\''s'", CommandBlueprints.shellQuote("it's"))
    }
}

class JiguanOrchestratorTest {

    private fun fakePorts(
        processesOk: Boolean = true,
    ): Triple<JiguanOrchestrator, FakeMemoryPort, String> {
        val memory = FakeMemoryPort(processesOk)
        val java = FakeChannelPort("frida-server")
        val native = FakeChannelPort("objdump")
        val network = FakeChannelPort("tcpdump")
        val system = FakeChannelPort("root-shell")
        val orchestrator = JiguanOrchestrator(memory, java, native, network, system)
        return Triple(orchestrator, memory, "ok")
    }

    @Test
    fun `capabilityReport marks all domains online with channels`() = runTest {
        val (orchestrator, _, _) = fakePorts()
        val report = orchestrator.capabilityReport()
        assertEquals(5, report.size)
        assertTrue(report.all { it.available })
        assertTrue(report.any { it.domain == "process-memory" && it.channel == "root-shell" })
    }

    @Test
    fun `capabilityReport degrades with guidance when probe fails`() = runTest {
        val (offlineOrchestrator) = run {
            val memory = FakeMemoryPort(processesOk = false)
            val none = FakeChannelPort("none")
            Triple(
                JiguanOrchestrator(memory, none, none, none, none),
                memory,
                "",
            ).let { Triple(it.first, it.second, "") }
        }
        val report = offlineOrchestrator.capabilityReport()
        assertTrue(report.none { it.available })
        assertTrue(report.first { it.domain == "process-memory" }.guidance.contains("root"))
        assertTrue(report.first { it.domain == "java-art" }.guidance.contains("frida-server"))
    }

    @Test
    fun `recipes cover three flagship scenarios`() {
        val (orchestrator, _, _) = fakePorts()
        val ids = orchestrator.recipes().map { it.id }
        assertTrue(ids.containsAll(listOf("analyze-login-encryption", "kill-splash-ads", "memory-cheat-walkthrough")))
        orchestrator.recipes().forEach { assertTrue(it.steps.size >= 5) }
    }

    @Test
    fun `systemPromptFragment names all four domains`() {
        val (orchestrator, _, _) = fakePorts()
        val prompt = orchestrator.systemPromptFragment()
        assertTrue(prompt.contains("进程与内存"))
        assertTrue(prompt.contains("Java/ART"))
        assertTrue(prompt.contains("Native"))
        assertTrue(prompt.contains("网络与系统"))
        assertTrue(prompt.contains("JiguanCapabilityReport"))
    }
}

class JiguanRuntimeScanTest {

    @Test
    fun `scan iterates regions and reports absolute hits`() = runTest {
        val memory = FakeMemoryPort(processesOk = true)
        val runtime = JiguanRuntime(
            memory, FakeChannelPort("frida"), FakeChannelPort("objdump"),
            FakeChannelPort("tcpdump"), FakeChannelPort("root"),
            OmniHookEngine({ _, _, _ -> Result.success("") }, { true }),
            JiguanKnowledge(FakeStoreAdapter()),
        )
        val hits = runtime.scan(ScanRequest(pid = 1, value = "1337", type = ScanValueType.INT32)).getOrThrow()
        assertEquals(1, hits.size)
        assertEquals(FakeMemoryPort.REGION_BASE + 8, hits[0].address)
    }

    @Test
    fun `scan tolerates region read failure`() = runTest {
        val memory = FakeMemoryPort(processesOk = true, failReads = true)
        val runtime = JiguanRuntime(
            memory, FakeChannelPort("frida"), FakeChannelPort("objdump"),
            FakeChannelPort("tcpdump"), FakeChannelPort("root"),
            OmniHookEngine({ _, _, _ -> Result.success("") }, { true }),
            JiguanKnowledge(FakeStoreAdapter()),
        )
        val hits = runtime.scan(ScanRequest(pid = 1, value = "1337", type = ScanValueType.INT32)).getOrThrow()
        assertTrue(hits.isEmpty())
    }
}

// ── fakes ────────────────────────────────────────────────────────

/** 内存端口 fake：单区域 16B，@+8 埋 int32 1337。 */
class FakeMemoryPort(
    private val processesOk: Boolean,
    private val failReads: Boolean = false,
) : JiguanMemoryPort {
    override val channelName = "root-shell"

    companion object {
        const val REGION_BASE = 0x10000L
    }

    private val region = MemoryRegion(
        start = REGION_BASE, end = REGION_BASE + 16, perms = "rw-p",
        offset = 0, dev = "00:00", inode = 0, path = "[heap]",
    )

    private val data = ByteArray(16).also { buffer ->
        val v = 1337
        for (i in 0 until 4) buffer[8 + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    override suspend fun listProcesses(): Result<List<ProcessEntry>> =
        if (processesOk) Result.success(listOf(ProcessEntry(1, "fake.app", "fake.app", 10100))) else Result.failure(IllegalStateException("no root"))

    override suspend fun regions(pid: Int): Result<List<MemoryRegion>> =
        if (processesOk) Result.success(listOf(region)) else Result.failure(IllegalStateException("no root"))

    override suspend fun read(pid: Int, address: Long, size: Int): Result<ByteArray> =
        if (failReads) Result.failure(IllegalStateException("read denied"))
        else {
            val offset = (address - REGION_BASE).toInt()
            if (offset < 0 || offset + size > data.size) Result.failure(IllegalStateException("out of region"))
            else Result.success(data.copyOfRange(offset, offset + size))
        }

    override suspend fun write(pid: Int, address: Long, data: ByteArray): Result<Unit> = Result.success(Unit)
}

/** 其余域端口 fake：channelName 可配置。 */
class FakeChannelPort(override val channelName: String) :
    JiguanJavaPort, JiguanNativePort, JiguanNetworkPort, JiguanSystemPort {

    /** 四接口各带默认 ping —— 多继承冲突，显式合并为一。 */
    override suspend fun ping(): Boolean = channelName != "none"

    override suspend fun listClasses(pid: Int, filter: String): Result<List<JavaClassEntry>> =
        Result.success(listOf(JavaClassEntry("com.fake.Encrypt")))

    override suspend fun runScript(pid: Int, script: String, timeoutMs: Long): Result<String> =
        Result.success("[ok] fake\n[class] com.fake.Encrypt")

    override suspend fun listModules(pid: Int): Result<List<ModuleEntry>> =
        Result.success(listOf(ModuleEntry("libfake.so", "/data/libfake.so", 0x7000, 4096, true, false, true)))

    override suspend fun disassemble(pid: Int, address: Long, size: Int): Result<List<DisasmLine>> =
        Result.success(listOf(DisasmLine(address, "20 00 80 52", "mov w0, #1")))

    override suspend fun startCapture(pid: Int?, filter: String): Result<CaptureSession> =
        Result.success(CaptureSession(1, pid, "/tmp/fake.pcap", true, "tcpdump"))

    override suspend fun stopCapture(sessionId: Int): Result<CaptureSession> =
        Result.success(CaptureSession(sessionId, null, "/tmp/fake.pcap", false, "tcpdump"))

    override suspend fun inspect(): Result<SystemInspectResult> =
        Result.success(SystemInspectResult("Permissive", "u:r:su:s0", true, listOf("activity", "package")))

    override suspend fun injectTouch(
        x: Int, y: Int, swipe: Boolean, x2: Int, y2: Int, durationMs: Long,
    ): Result<Unit> = Result.success(Unit)
}

class FakeStoreAdapter : JiguanKnowledgeStore {
    private val entries = mutableListOf<AppKnowledge>()
    override suspend fun all(): List<AppKnowledge> = entries.toList()
    override suspend fun save(entry: AppKnowledge) {
        entries.removeAll { it.appPackage == entry.appPackage && it.title == entry.title }
        entries += entry
    }
    override suspend fun delete(appPackage: String, title: String) {
        entries.removeAll { it.appPackage == appPackage && it.title == title }
    }
}
