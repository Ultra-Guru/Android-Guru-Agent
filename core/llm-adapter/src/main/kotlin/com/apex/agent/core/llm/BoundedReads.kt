package com.apex.agent.core.llm

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads response bodies without allowing an untrusted endpoint to exhaust memory. */
internal object BoundedReads {
    fun readAtMost(stream: InputStream, maxBytes: Int): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive: $maxBytes" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (total < maxBytes) {
            val count = stream.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (count < 0) return output.toByteArray()
            output.write(buffer, 0, count)
            total += count
        }
        return if (stream.read() >= 0) null else output.toByteArray()
    }

    fun readHead(stream: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive: $maxBytes" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (total < maxBytes) {
            val count = stream.read(buffer, 0, minOf(buffer.size, maxBytes - total))
            if (count < 0) break
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private const val BUFFER_SIZE = 16 * 1024
}
