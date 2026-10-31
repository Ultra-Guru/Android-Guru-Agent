package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class McpManagerToolAggregationTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Transport(val name: String = "tool", val list: suspend () -> Unit) : McpTransportHandle {
        override suspend fun send(id: Int?, payload: String): JsonObject? {
            if (id == null) return null
            val method = Json.parseToJsonElement(payload) as JsonObject
            val listing = method["method"]?.jsonPrimitive?.content == "tools/list"
            if (listing) list()
            return buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                putJsonObject("result") {
                    if (listing) putJsonArray("tools") {
                        add(buildJsonObject {
                            put("name", name)
                            putJsonObject("inputSchema") { put("type", "object") }
                        })
                    } else putJsonObject("capabilities") { putJsonObject("tools") {} }
                }
            }
        }
        override fun isHealthy() = true
        override fun close() {}
    }

    private suspend fun connect(manager: McpManager, vararg names: String) {
        for (name in names) {
            manager.addServer(McpServerConfig(name = name, transport = McpTransport.BUILTIN)).getOrThrow()
            manager.connect(name).getOrThrow()
        }
    }

    @Test fun `slow source cannot hold healthy tools beyond aggregation budget`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val manager = McpManager(tmp.newFolder(), builtinTransports = linkedMapOf(
            "slow" to { Transport("slow") { withContext(dispatcher) { delay(60_000) } } },
            "healthy" to { Transport("healthy") {} }
        ), errorLog = { logs += it })
        connect(manager, "slow", "healthy")
        val started = testScheduler.currentTime
        val tools = manager.getAllTools()
        assertEquals(listOf("healthy"), tools.map { it.name })
        assertTrue("Slow source must have a short aggregation budget", testScheduler.currentTime - started <= 5_000)
        assertTrue(logs.any { "slow" in it && "timeout" in it })
    }

    @Test fun `list requests overlap instead of adding server latency`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = McpManager(tmp.newFolder(), builtinTransports = listOf("first", "second").associateWith { name ->
            { Transport(name) { withContext(dispatcher) { delay(100) } } }
        })
        connect(manager, "first", "second")
        val started = testScheduler.currentTime
        val tools = manager.getAllTools()
        assertEquals(listOf("first", "second"), tools.map { it.name })
        assertEquals("Independent sources must overlap", 100L, testScheduler.currentTime - started)
    }
}
