package com.apex.agent.core.tools.pluginhost.bridge

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PluginEventBus 契约锁。
 *
 * 锁定 spec §13 的「必须具备」：异步分发 / 订阅管理 / 异常隔离 / 超时。
 * 分发跑在总线自己的真实调度器上 —— 等待送达必须用**真实时间**
 * （runTest 虚拟时钟会在真实调度器完工前虚假到点，正是 issue #276
 * 测试纪律禁的坑），因此沿用真实 IO harness 模式（CountDownLatch +
 * Dispatchers.Default，协程 delay 轮询）。
 */
class PluginEventBusTest {

    private class RecordingHandler : PluginEventBus.Handler {
        val received = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun onEvent(event: PluginEventBus.PluginHostEvent, payloadJson: String) {
            received += event.wireName to payloadJson
        }
    }

    /** 真实时间 harness（总线处理器在 Default 调度器上真实执行）。 */
    private fun realAsync(timeoutMs: Long = 15_000, block: suspend CoroutineScope.() -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        assertTrue("测试超时（${timeoutMs}ms）", latch.await(timeoutMs, TimeUnit.MILLISECONDS))
        failure?.let { throw it }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun `dispatch reaches subscriber asynchronously`() {
        realAsync {
            val bus = PluginEventBus(this, handlerTimeoutMs = 2_000)
            val handler = RecordingHandler()
            bus.subscribe("plugin.a", PluginEventBus.PluginHostEvent.SESSION_START, handler)

            bus.dispatch(PluginEventBus.PluginHostEvent.SESSION_START, """{"k":1}""")
            awaitCondition { handler.received.isNotEmpty() }

            assertEquals(listOf("session.start" to """{"k":1}"""), handler.received.toList())
            bus.shutdown()
        }
    }

    @Test
    fun `handler exception is isolated and other subscribers still receive`() {
        realAsync {
            val bus = PluginEventBus(this, handlerTimeoutMs = 2_000)
            val boom = PluginEventBus.Handler { _, _ -> error("handler exploded") }
            val healthy = RecordingHandler()
            bus.subscribe("plugin.bad", PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL, boom)
            bus.subscribe("plugin.good", PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL, healthy)

            repeat(3) {
                bus.dispatch(PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL, "{}")
            }
            awaitCondition { healthy.received.size >= 3 }
            // 异常订阅方没有打断任何人
            assertEquals(3, healthy.received.size)
            bus.shutdown()
        }
    }

    @Test
    fun `handler timeout does not kill the bus`() {
        realAsync {
            // 超时 50ms：挂死处理器被取消
            val bus = PluginEventBus(this, handlerTimeoutMs = 50)
            val stuck = PluginEventBus.Handler { _, _ -> delay(60_000) }
            val healthy = RecordingHandler()
            bus.subscribe("plugin.stuck", PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL, stuck)
            bus.subscribe("plugin.ok", PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL, healthy)

            bus.dispatch(PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL)
            awaitCondition { healthy.received.size >= 1 }
            // 总线还活着：再分发一次照样送达
            bus.dispatch(PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL)
            awaitCondition { healthy.received.size >= 2 }
            assertEquals(2, healthy.received.size)
            bus.shutdown()
        }
    }

    @Test
    fun `unsubscribeAll removes only the target plugin`() {
        realAsync {
            val bus = PluginEventBus(this)
            val a = RecordingHandler()
            val b = RecordingHandler()
            bus.subscribe("plugin.a", PluginEventBus.PluginHostEvent.SESSION_START, a)
            bus.subscribe("plugin.b", PluginEventBus.PluginHostEvent.SESSION_END, b)

            assertEquals(1, bus.unsubscribeAll("plugin.a"))
            assertEquals(0, bus.subscriberCount(PluginEventBus.PluginHostEvent.SESSION_START))
            assertEquals(1, bus.subscriberCount(PluginEventBus.PluginHostEvent.SESSION_END))
            // 重复取消幂等
            assertEquals(0, bus.unsubscribeAll("plugin.a"))
            bus.shutdown()
        }
    }

    @Test
    fun `dispatch without subscribers is a no-op`() {
        realAsync {
            val bus = PluginEventBus(this)
            bus.dispatch(PluginEventBus.PluginHostEvent.NOTIFICATION, "{}")
            assertEquals(0, bus.subscriberCount(PluginEventBus.PluginHostEvent.NOTIFICATION))
            bus.shutdown()
        }
    }

    @Test
    fun `resubscribe replaces previous handler for same plugin and event`() {
        realAsync {
            val bus = PluginEventBus(this)
            val first = RecordingHandler()
            val second = RecordingHandler()
            bus.subscribe("plugin.a", PluginEventBus.PluginHostEvent.USER_PROMPT_SUBMIT, first)
            bus.subscribe("plugin.a", PluginEventBus.PluginHostEvent.USER_PROMPT_SUBMIT, second)
            assertEquals(1, bus.subscriberCount(PluginEventBus.PluginHostEvent.USER_PROMPT_SUBMIT))

            bus.dispatch(PluginEventBus.PluginHostEvent.USER_PROMPT_SUBMIT)
            awaitCondition { second.received.isNotEmpty() }
            // 只有最新订阅方收到
            assertEquals(0, first.received.size)
            assertEquals(1, second.received.size)
            bus.shutdown()
        }
    }
}
