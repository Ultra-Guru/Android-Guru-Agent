package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.RootfsProvider
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Explicit, lazy runtime health hint for TerminalBackends, never an availability() check.
 * Running a guest command has side effects; it must not change the backend registry or routing.
 * A timeout/signal is only a *likely* ptrace issue, not proof of a particular kernel policy.
 */
class PRootSmokeProbe(
    private val binaryProvider: PRootBinaryProvider,
    private val rootfsProvider: RootfsProvider,
    /** Prepare PRootHostEnvironment before returning its host env; null means unavailable. */
    private val hostEnvironment: () -> Map<String, String>?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Process seam for JVM tests; production discards output so a full pipe cannot stall waitFor. */
    private val processFactory: (List<String>, Map<String, String>) -> Process = { argv, env ->
        ProcessBuilder(argv)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
            .apply { environment().clear(); environment().putAll(env) }
            .start()
    }
) {
    enum class Status { OK, TIMED_OUT, PTRACE_SIGNAL, BINARY_ERROR, NOT_READY }
    data class SmokeResult(val status: Status, val exitCode: Int? = null)

    private data class Cached(val atMs: Long, val generation: Long, val result: SmokeResult)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var cached: Cached? = null

    /** At most one 5s guest probe per TTL; not-ready skips are not cached. */
    suspend fun probe(): SmokeResult = mutex.withLock {
        val now = clock()
        val version = generation.get()
        cached?.takeIf { it.generation == version && now - it.atMs in 0L until TTL_MS }
            ?.let { return@withLock it.result }

        val result = try {
            withTimeout(TIMEOUT_MS) { runOnce() }
        } catch (_: TimeoutCancellationException) {
            SmokeResult(Status.TIMED_OUT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SmokeResult(Status.BINARY_ERROR)
        }
        if (result.status != Status.NOT_READY && version == generation.get()) {
            cached = Cached(clock(), version, result)
        }
        result
    }

    /** Rootfs reinstall or explicit settings changes can force the next discovery to recheck. */
    fun invalidate(@Suppress("UNUSED_PARAMETER") reason: String) {
        generation.incrementAndGet()
        cached = null
    }

    private suspend fun runOnce(): SmokeResult = withContext(Dispatchers.IO) {
        // Do not call verify(): that path runs unrelated option/version probes (up to 10s each).
        val binary = binaryProvider.locate().getOrNull() ?: return@withContext SmokeResult(Status.NOT_READY)
        val rootfs = rootfsProvider.current()?.location ?: return@withContext SmokeResult(Status.NOT_READY)
        val env = hostEnvironment() ?: return@withContext SmokeResult(Status.BINARY_ERROR)
        var child: Process? = null
        try {
            val process = processFactory(listOf(binary.value, "-r", rootfs.value, "/bin/true"), env)
            child = process
            if (!runInterruptible { process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS) }) {
                SmokeResult(Status.TIMED_OUT)
            } else {
                val exit = process.exitValue()
                val status = when (exit) {
                    0 -> Status.OK
                    // Linux shell-style 128+signal; some process adapters return -signal.
                    133, 159, -5, -31 -> Status.PTRACE_SIGNAL // SIGTRAP, SIGSYS
                    else -> Status.BINARY_ERROR
                }
                SmokeResult(status, exit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SmokeResult(Status.BINARY_ERROR)
        } finally {
            child?.let { process ->
                // runInterruptible cancels/interrupts waitFor; never leave a tracer alive.
                if (runCatching { process.isAlive }.getOrDefault(true)) {
                    runCatching { process.destroyForcibly() }
                }
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 5_000L
        const val TTL_MS = 600_000L
    }
}
