package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * #488 聚合回归（GREEN 基座说明）：
 *
 * 初版测试用 runTest 虚拟时钟 + StandardTestDispatcher 包裹传输延迟 —— 与
 * [McpClient.listTools] 内部的 `withContext(Dispatchers.IO)` 真实线程跳转存在
 * 固有竞态：withTimeoutOrNull 的预算任务排在虚拟调度器上，测试体一挂起，
 * 调度器队列里只剩未来的超时任务，虚拟时钟立刻"传送"到预算点；而即时源的
 * 响应还在真实 IO 线程上飞（微秒级），永远输给传送 —— 任何正确实现都拿回
 * 空清单（CI 实测 `was:<[]>`，run 41260518705）。
 *
 * 改用 runBlocking 真实时钟 + 真实延迟：预算（withTimeoutOrNull）与传输
 * 延迟同在真实时间轴上，无传送竞态。规格语义 1:1 保留，且对旧串行实现
 * 依旧判 RED（慢源拖满 8s / 重叠计数恒 1）。
 */
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

    @Test fun `slow source cannot hold healthy tools beyond aggregation budget`() = runBlocking {
        val logs = CopyOnWriteArrayList<String>()
        val manager = McpManager(tmp.newFolder(), builtinTransports = linkedMapOf(
            // 真实 8s 挂起（远超 5s 预算）——旧串行实现会等满 8s 并带回慢源工具
            "slow" to { Transport("slow") { delay(8_000) } },
            "healthy" to { Transport("healthy") {} }
        ), errorLog = { logs += it })
        connect(manager, "slow", "healthy")
        val started = System.nanoTime()
        val tools = manager.getAllTools()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(listOf("healthy"), tools.map { it.name })
        assertTrue(
            "Slow source must be cut by the aggregation budget (took ${elapsedMs}ms)",
            elapsedMs < 7_500
        )
        assertTrue(logs.any { "slow" in it && "timeout" in it })
    }

    @Test fun `list requests overlap instead of adding server latency`() = runBlocking {
        // 重叠探测：统计同时在飞的 listTools 数 —— 串行实现恒为 1，
        // 并发实现两路 200ms 窗口必然相交（无需依赖墙钟数值断言，免 CI 抖动）
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val manager = McpManager(tmp.newFolder(), builtinTransports = listOf("first", "second").associateWith { name ->
            {
                Transport(name) {
                    val cur = active.incrementAndGet()
                    maxActive.accumulateAndGet(cur) { m, c -> maxOf(m, c) }
                    try {
                        delay(200)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        })
        connect(manager, "first", "second")
        val tools = manager.getAllTools()
        assertEquals(listOf("first", "second"), tools.map { it.name })
        assertTrue(
            "Independent sources must overlap (max concurrent listTools: ${maxActive.get()})",
            maxActive.get() >= 2
        )
    }
}
