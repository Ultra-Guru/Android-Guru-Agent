package com.apex.agent.platform.terminal.chroot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Authorization is learned only during an explicit chroot create; availability never execs su. */
class SuAuthorizationCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val outputDir: File = File(System.getProperty("java.io.tmpdir", ".")),
    private val probe: (File) -> State = { probeSu(it, outputDir) },
) {
    enum class State { UNKNOWN, GRANTED, DENIED }

    private data class Entry(val state: State, val at: Long)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val probeLocks = ConcurrentHashMap<String, Any>()
    private val generation = AtomicLong()
    private val updateLock = Any()

    /** Cheap even while another thread waits for a superuser authorization prompt. */
    fun state(su: File): State {
        val entry = entries[su.absolutePath] ?: return State.UNKNOWN
        val age = clock() - entry.at
        return if (age in 0 until TTL_MS) entry.state else State.UNKNOWN
    }

    /** Serialized by path: simultaneous creates do not create multiple authorization prompts. */
    fun authorize(su: File): State {
        val path = su.absolutePath
        val lock = probeLocks.computeIfAbsent(path) { Any() }
        return synchronized(lock) {
            val cached = state(su)
            if (cached != State.UNKNOWN) return@synchronized cached
            val version = generation.get()
            val result = runCatching { probe(su) }.getOrDefault(State.UNKNOWN)
            synchronized(updateLock) {
                if (version != generation.get()) State.UNKNOWN
                else {
                    if (result == State.UNKNOWN) entries.remove(path)
                    else entries[path] = Entry(result, clock())
                    result
                }
            }
        }
    }

    suspend fun authorizeOnIo(su: File): State = withContext(Dispatchers.IO) { authorize(su) }

    fun invalidate() {
        synchronized(updateLock) {
            generation.incrementAndGet()
            entries.clear()
        }
    }

    companion object {
        const val TTL_MS = 30_000L
        private const val MAX_CAPTURE_BYTES = 64 * 1024
        private val ROOT_ID = Regex("^uid=0(?:\\(|\\s|$)")

        /** A regular file avoids pipe backpressure and waiting for inherited stdout to close. */
        private fun probeSu(su: File, outputDir: File): State {
            val output = File.createTempFile("su-auth-", ".out", outputDir)
            try {
                val process = ProcessBuilder(su.absolutePath, "-c", "id")
                    .redirectErrorStream(true).redirectOutput(output).start()
                return try {
                    // A user may need time to respond to the manager's authorization prompt.
                    if (!process.waitFor(15, TimeUnit.SECONDS)) return State.UNKNOWN
                    // Bounded read loop: a single read() may return short, and EOF must
                    // yield the byte count actually captured (EOFException carries no
                    // transferred-byte field — that belongs to InterruptedIOException).
                    val bytes = ByteArray(MAX_CAPTURE_BYTES)
                    val n = output.inputStream().use { input ->
                        var captured = 0
                        while (captured < bytes.size) {
                            val read = runCatching {
                                input.read(bytes, captured, bytes.size - captured)
                            }.getOrDefault(-1)
                            if (read <= 0) break
                            captured += read
                        }
                        captured
                    }
                    val rootId = if (n > 0) String(bytes, 0, n, Charsets.UTF_8)
                        .lineSequence().any { ROOT_ID.containsMatchIn(it.trim()) } else false
                    val truncated = output.length() > n
                    when {
                        // uid=0 inside the captured prefix is conclusive even when a chatty
                        // manager overflowed the cap: truncation can only hide evidence,
                        // never fabricate it.
                        process.exitValue() == 0 && rootId -> State.GRANTED
                        // No uid=0 yet and the capture is incomplete: the id line may still
                        // be behind the noise — refuse to call that a denial.
                        process.exitValue() == 0 && truncated -> State.UNKNOWN
                        else -> State.DENIED
                    }
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    runCatching { process.outputStream.close() }
                }
            } finally {
                output.delete()
            }
        }
    }
}
