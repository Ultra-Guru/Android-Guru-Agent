package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** No host proot or rootfs needed: only the process boundary is scripted. */
class PRootSmokeProbeTest {
    private val binary = AbsolutePath("/host/libproot.so")
    private val rootfs = RootfsDescriptor(
        id = "ubuntu", distribution = LinuxDistribution.UBUNTU, version = "24.04",
        architecture = CpuArchitecture.ARM64, location = AbsolutePath("/host/rootfs"),
        sizeBytes = null, checksum = null, readOnly = false
    )

    private class ScriptedProcess(private val finished: Boolean, private val code: Int = 0) : Process() {
        var destroyed = false
        var waitedMs = 0L
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitedMs = unit.toMillis(timeout)
            return finished
        }
        override fun waitFor(): Int = code
        override fun exitValue(): Int {
            if (!finished && !destroyed) throw IllegalThreadStateException("still running")
            return code
        }
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    }

    private fun probe(
        process: ScriptedProcess,
        clock: () -> Long = { 0L },
        rootfsReady: Boolean = true,
        onStart: (List<String>, Map<String, String>) -> Unit = { _, _ -> }
    ) = PRootSmokeProbe(
        binaryProvider = object : PRootBinaryProvider {
            override suspend fun locate() = Result.success(binary)
            override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> =
                error("runtime smoke must not run version/argv verification probes")
        },
        rootfsProvider = object : RootfsProvider {
            override suspend fun current() = if (rootfsReady) rootfs else null
            override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> =
                error("unused")
        },
        hostEnvironment = { mapOf("PROOT_TMP_DIR" to "/host/tmp") },
        clock = clock,
        processFactory = { argv, env -> onStart(argv, env); process }
    )

    @Test fun `success runs true inside the installed rootfs without changing backend`() = runTest {
        val process = ScriptedProcess(true)
        var argv: List<String>? = null
        var env: Map<String, String>? = null
        val probe = probe(process, onStart = { a, e -> argv = a; env = e })

        assertEquals(PRootSmokeProbe.Status.OK, probe.probe().status)
        assertEquals(listOf(binary.value, "-r", rootfs.location!!.value, "/bin/true"), argv)
        assertEquals("/host/tmp", env?.get("PROOT_TMP_DIR"))
        assertFalse(process.destroyed)
    }

    @Test fun `hung process is bounded and forcibly destroyed without claiming ptrace proof`() = runTest {
        val process = ScriptedProcess(false)
        val result = probe(process).probe()
        assertEquals(PRootSmokeProbe.Status.TIMED_OUT, result.status)
        assertEquals(5_000L, process.waitedMs)
        assertTrue(process.destroyed)
    }

    @Test fun `ptrace-related signal is distinct from ordinary binary failure`() = runTest {
        assertEquals(PRootSmokeProbe.Status.PTRACE_SIGNAL, probe(ScriptedProcess(true, 133)).probe().status)
        assertEquals(PRootSmokeProbe.Status.PTRACE_SIGNAL, probe(ScriptedProcess(true, 159)).probe().status)
        val ordinary = probe(ScriptedProcess(true, 127)).probe()
        assertEquals(PRootSmokeProbe.Status.BINARY_ERROR, ordinary.status)
        assertEquals(127, ordinary.exitCode)
    }

    @Test fun `probe skips missing rootfs without spawning`() = runTest {
        var starts = 0
        val result = probe(ScriptedProcess(true), rootfsReady = false, onStart = { _, _ -> starts++ }).probe()
        assertEquals(PRootSmokeProbe.Status.NOT_READY, result.status)
        assertEquals(0, starts)
    }

    @Test fun `ttl reuses result then expiry and explicit invalidation reprobe`() = runTest {
        var now = 100L
        var starts = 0
        val probe = probe(ScriptedProcess(true), clock = { now }, onStart = { _, _ -> starts++ })
        assertEquals(PRootSmokeProbe.Status.OK, probe.probe().status)
        now += 599_999L
        probe.probe()
        assertEquals(1, starts)
        now += 1L
        probe.probe()
        assertEquals(2, starts)
        probe.invalidate("rootfs reinstalled")
        probe.probe()
        assertEquals(3, starts)
    }

    @Test fun `cancellation interrupts wait and kills in-flight child`() = runTest {
        val started = CountDownLatch(1)
        val process = object : Process() {
            @Volatile var destroyed = false
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                started.countDown()
                CountDownLatch(1).await()
                return true
            }
            override fun waitFor(): Int = 0
            override fun exitValue(): Int {
                if (!destroyed) throw IllegalThreadStateException()
                return 0
            }
            override fun destroy() { destroyed = true }
            override fun destroyForcibly(): Process { destroyed = true; return this }
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        }
        val probe = PRootSmokeProbe(
            binaryProvider = object : PRootBinaryProvider {
                override suspend fun locate() = Result.success(binary)
                override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> = error("unused")
            },
            rootfsProvider = object : RootfsProvider {
                override suspend fun current() = rootfs
                override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> = error("unused")
            },
            hostEnvironment = { emptyMap() },
            processFactory = { _, _ -> process }
        )
        val job = launch(Dispatchers.Default) { probe.probe() }
        assertTrue("process did not start", started.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue("cancel must reap the child", process.destroyed)
    }
}
