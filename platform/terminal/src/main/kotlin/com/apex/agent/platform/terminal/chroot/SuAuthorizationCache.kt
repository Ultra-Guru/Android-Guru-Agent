package com.apex.agent.platform.terminal.chroot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Authorization is learned only during an explicit chroot create; availability never execs su. */
class SuAuthorizationCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val probe: (File) -> State = ::probeSu,
) {
    enum class State { UNKNOWN, GRANTED, DENIED }

    private data class Entry(val state: State, val at: Long)
    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun state(su: File): State {
        val entry = entries[su.absolutePath] ?: return State.UNKNOWN
        val age = clock() - entry.at
        return if (age in 0 until TTL_MS) entry.state else State.UNKNOWN
    }

    /** Serialized by path: simultaneous creates do not create multiple authorization prompts. */
    @Synchronized
    fun authorize(su: File): State {
        val cached = state(su)
        if (cached != State.UNKNOWN) return cached
        val result = runCatching { probe(su) }.getOrDefault(State.UNKNOWN)
        if (result == State.UNKNOWN) entries.remove(su.absolutePath)
        else entries[su.absolutePath] = Entry(result, clock())
        return result
    }

    suspend fun authorizeOnIo(su: File): State = withContext(Dispatchers.IO) { authorize(su) }

    @Synchronized
    fun invalidate() = entries.clear()

    companion object {
        const val TTL_MS = 30_000L
        private val ROOT_ID = Regex("^uid=0(?:\\(|\\s|$)")

        /** Nonzero/uid!=0 is denial; timeout/exec exception is unknown (not a false denial). */
        private fun probeSu(su: File): State {
            val process = ProcessBuilder(su.absolutePath, "-c", "id")
                .redirectErrorStream(true).start()
            return try {
                // A real user may need to respond to the manager's authorization prompt.
                if (!process.waitFor(15, TimeUnit.SECONDS)) return State.UNKNOWN
                val output = process.inputStream.bufferedReader().use { it.readText().take(4096) }
                val rootId = output.lineSequence().any { ROOT_ID.containsMatchIn(it.trim()) }
                if (process.exitValue() == 0 && rootId) State.GRANTED
                else State.DENIED
            } finally {
                if (process.isAlive) process.destroyForcibly()
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            }
        }
    }
}
