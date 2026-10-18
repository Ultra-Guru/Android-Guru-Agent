package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #205 单测：[McpManager] 连接链路的真实事件序列（BUILTIN 传输形态）。
 *
 * 用手写的 FakeJsonRpcTransportHandle 应答真实 JSON-RPC 报文 ——
 * initialize 回真实 serverInfo / capabilities，事件流里的每个 detail
 * 都来自对假件应答的真实解析（无模拟延时、无编造阶段）。
 */
class McpManagerStartupEventsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 应答 JSON-RPC 的假 transport：initialize → serverInfo fake-server/9.9.9。 */
    private class FakeJsonRpcTransportHandle(var nextId: Int = 1) : McpTransportHandle {
        val sentPayloads = CopyOnWriteArrayList<String>()
        override suspend fun send(id: Int?, payload: String): JsonObject? {
            sentPayloads += payload
            if (id == null) return null // 通知
            val obj = Json.parseToJsonElement(payload) as JsonObject
            val method = obj["method"]?.toString()?.trim('"') ?: ""
            return when (method) {
                "initialize" -> buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    putJsonObject("result") {
                        putJsonObject("capabilities") {
                            putJsonObject("tools") {}
                            putJsonObject("resources") {}
                        }
                        putJsonObject("serverInfo") {
                            put("name", "fake-server")
                            put("version", "9.9.9")
                        }
                    }
                }
                else -> buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    putJsonObject("result") { putJsonObject("tools") {} }
                }
            }
        }

        override fun isHealthy() = true
        override fun close() {}
    }

    private fun managerWithFake(handle: FakeJsonRpcTransportHandle): McpManager =
        McpManager(
            configDir = tmp.newFolder(),
            builtinTransports = mapOf("fake" to { handle })
        )

    // ═══ BUILTIN 事件序列 ═══

    @Test
    fun `builtin connect emits env spawn initialize initialized events`() = runTest {
        val manager = managerWithFake(FakeJsonRpcTransportHandle())
        manager.addServer(
            McpServerConfig(name = "fake", transport = McpTransport.BUILTIN)
        )
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        val result = manager.connect("fake") { events += it }
        assertTrue(result.isSuccess)

        val stages = events.map { it.stage }
        // 生命周期序（#206 修复：initialize() 先显式创建传输再报请求发出）：
        // ENV_CHECK → SPAWN（STDIO 真实 fork / BUILTIN 进程内标注）→
        // INITIALIZE_SENT → INITIALIZE_RESULT → INITIALIZED（无 FAILED）。
        assertEquals(
            listOf(
                McpStartupStage.ENV_CHECK,
                McpStartupStage.SPAWN,
                McpStartupStage.INITIALIZE_SENT,
                McpStartupStage.INITIALIZE_RESULT,
                McpStartupStage.INITIALIZED
            ),
            stages
        )
        assertTrue(events.first { it.stage == McpStartupStage.SPAWN }.detail.contains("进程内"))
    }

    @Test
    fun `initialize result carries real parsed serverInfo`() = runTest {
        val manager = managerWithFake(FakeJsonRpcTransportHandle())
        manager.addServer(McpServerConfig(name = "fake", transport = McpTransport.BUILTIN))
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        manager.connect("fake") { events += it }
        val detail = events.first { it.stage == McpStartupStage.INITIALIZE_RESULT }.detail
        // serverInfo 来自假件应答的真实解析
        assertTrue(detail.contains("fake-server"))
        assertTrue(detail.contains("9.9.9"))
        assertTrue(detail.contains("tools"))
    }

    @Test
    fun `unconfigured server emits no events and fails`() = runTest {
        val manager = managerWithFake(FakeJsonRpcTransportHandle())
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        val result = manager.connect("nobody") { events += it }
        assertTrue(result.isFailure)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `unregistered builtin factory fails with guided error`() = runTest {
        // BUILTIN 名字没有工厂 → 明确报「未注册 transport 工厂」，事件序列在 SPAWN 前失败。
        val manager = McpManager(configDir = tmp.newFolder())
        manager.addServer(McpServerConfig(name = "ghost", transport = McpTransport.BUILTIN))
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        val result = manager.connect("ghost") { events += it }
        assertTrue(result.isFailure)
        assertTrue(events.any { it.stage == McpStartupStage.FAILED })
    }

    // ═══ 沙箱就绪探针 ═══

    @Test
    fun `sandbox env check reports ready rootfs when probe true`() = runTest {
        val manager = McpManager(
            configDir = tmp.newFolder(),
            sandboxProcessLauncher = null,
            sandboxReadinessProbe = { true }
        )
        manager.addServer(
            McpServerConfig(
                name = "sbx", transport = McpTransport.STDIO,
                command = "npx", args = listOf("-y", "x"),
                runInSandbox = true
            )
        )
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        manager.connect("sbx") { events += it }
        val envDetail = events.first { it.stage == McpStartupStage.ENV_CHECK }.detail
        assertTrue(envDetail.contains("PRoot 沙箱"))
        assertTrue(envDetail.contains("就绪"))
    }

    @Test
    fun `sandbox env check reports not ready when probe false`() = runTest {
        val manager = McpManager(
            configDir = tmp.newFolder(),
            sandboxReadinessProbe = { false }
        )
        manager.addServer(
            McpServerConfig(
                name = "sbx", transport = McpTransport.STDIO,
                command = "npx", args = listOf("-y", "x"),
                runInSandbox = true
            )
        )
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        manager.connect("sbx") { events += it }
        val envDetail = events.first { it.stage == McpStartupStage.ENV_CHECK }.detail
        assertTrue(envDetail.contains("未就绪"))
    }

    @Test
    fun `no probe injected keeps legacy env detail`() = runTest {
        val manager = McpManager(configDir = tmp.newFolder())
        manager.addServer(
            McpServerConfig(
                name = "sbx", transport = McpTransport.STDIO,
                command = "npx", args = listOf("-y", "x"),
                runInSandbox = true
            )
        )
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        manager.connect("sbx") { events += it }
        val envDetail = events.first { it.stage == McpStartupStage.ENV_CHECK }.detail
        assertTrue(envDetail.contains("stdio"))
        // 未注入探针：不出现就绪/未就绪字样（不猜测）
        assertTrue(!envDetail.contains("就绪"))
    }

    // ═══ 沙箱运行时门禁（MCP 市场诚实化）═══

    @Test
    fun `runtime missing fails fast before spawn with actionable guidance`() = runTest {
        // rootfs 就绪但沙箱里没有 npx —— 旧链路：fork → execve 失败 → 秒退 →
        // 抽象报错。现在：fork 之前拦下，ENV_CHECK + FAILED 两事件，无 SPAWN。
        val manager = McpManager(
            configDir = tmp.newFolder(),
            sandboxReadinessProbe = { true },
            sandboxRuntimeProbe = { McpSandboxRuntimeProbe.Verdict.RUNTIME_MISSING }
        )
        manager.addServer(
            McpServerConfig(
                name = "seq", transport = McpTransport.STDIO,
                command = "npx", args = listOf("-y", "pkg"),
                runInSandbox = true
            )
        )
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        val result = manager.connect("seq") { events += it }
        assertTrue(result.isFailure)
        val stages = events.map { it.stage }
        assertEquals(listOf(McpStartupStage.ENV_CHECK, McpStartupStage.FAILED), stages)
        // 可行动指引：缺什么（npx）、怎么补（apt install）
        val guidance = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(guidance.contains("npx"))
        assertTrue(guidance.contains("apt install -y nodejs npm"))
        // ENV_CHECK detail 同步运行时缺失
        assertTrue(events.first().detail.contains("运行时缺失"))
    }

    @Test
    fun `rootfs missing gate points to terminal install`() = runTest {
        val manager = McpManager(
            configDir = tmp.newFolder(),
            sandboxReadinessProbe = { false },
            sandboxRuntimeProbe = { McpSandboxRuntimeProbe.Verdict.ROOTFS_MISSING }
        )
        manager.addServer(
            McpServerConfig(
                name = "sbx", transport = McpTransport.STDIO,
                command = "npx", runInSandbox = true
            )
        )
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        val result = manager.connect("sbx") { events += it }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("终端"))
        assertTrue(events.any { it.stage == McpStartupStage.FAILED })
        assertTrue(events.none { it.stage == McpStartupStage.SPAWN })
    }

    @Test
    fun `runtime ready gate passes through to real launch`() = runTest {
        // READY 不拦截：走真实 spawn（/bin/sh exit 7 → initialize 失败，属正常）
        val manager = McpManager(
            configDir = tmp.newFolder(),
            sandboxReadinessProbe = { true },
            sandboxRuntimeProbe = { McpSandboxRuntimeProbe.Verdict.READY }
        )
        manager.addServer(
            McpServerConfig(
                name = "sbx", transport = McpTransport.STDIO,
                command = "/bin/sh", args = listOf("-c", "exit 7"),
                runInSandbox = true
            )
        )
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        val result = manager.connect("sbx") { events += it }
        // 真实启动管线接管（不是门禁拦截）：有 SPAWN，失败来自 initialize
        assertTrue(events.any { it.stage == McpStartupStage.SPAWN })
        assertTrue(events.none { it.stage == McpStartupStage.FAILED && it.detail.contains("apt install") })
        assertTrue(result.isFailure)
        assertTrue(events.first().detail.contains("运行时就绪"))
    }

    @Test
    fun `host stdio config is not gated by sandbox runtime probe`() = runTest {
        // runInSandbox=false：探针不适用（宿主直接 fork），门禁必须放行
        val manager = McpManager(
            configDir = tmp.newFolder(),
            sandboxRuntimeProbe = { McpSandboxRuntimeProbe.Verdict.RUNTIME_MISSING }
        )
        manager.addServer(
            McpServerConfig(
                name = "host", transport = McpTransport.STDIO,
                command = "/bin/sh", args = listOf("-c", "exit 7")
            )
        )
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        manager.connect("host") { events += it }
        assertTrue(events.any { it.stage == McpStartupStage.SPAWN })
    }

    // ═══ 真实进程（/bin/sh）级 ═══

    @Test
    fun `real stdio process reports spawn pid and initialize failure stage`() = runTest {
        // /bin/sh -c 'exit 7'：进程真实 spawn（有 pid），但不会说 JSON-RPC
        // → INITIALIZE 阶段失败，事件里能看到真实进程死亡。
        val manager = McpManager(configDir = tmp.newFolder())
        manager.addServer(
            McpServerConfig(
                name = "sh-7", transport = McpTransport.STDIO,
                command = "/bin/sh", args = listOf("-c", "exit 7")
            )
        )
        val events = CopyOnWriteArrayList<McpStartupEvent>()
        val result = manager.connect("sh-7") { events += it }
        assertTrue(result.isFailure)

        val stages = events.map { it.stage }
        assertTrue(McpStartupStage.ENV_CHECK in stages)
        assertTrue(McpStartupStage.SPAWN in stages)
        assertTrue(McpStartupStage.INITIALIZE_SENT in stages)
        assertTrue(McpStartupStage.FAILED in stages)
        // spawn 事件携带真实 pid
        val spawn = events.first { it.stage == McpStartupStage.SPAWN }
        assertTrue(Regex("pid=\\d+").containsMatchIn(spawn.detail))
    }

    // ═══ 事件隔离 ═══

    @Test
    fun `listener of one server does not receive another servers events`() = runTest {
        val manager = managerWithFake(FakeJsonRpcTransportHandle())
        manager.addServer(McpServerConfig(name = "fake", transport = McpTransport.BUILTIN))
        val a = CopyOnWriteArrayList<McpStartupEvent>()
        val b = CopyOnWriteArrayList<McpStartupEvent>()
        manager.connect("fake") { a += it }
        manager.connect("fake") { b += it }
        // 各自完整，serverName 都是 fake
        assertTrue(a.all { it.serverName == "fake" })
        assertTrue(b.all { it.serverName == "fake" })
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
    }

    @Test
    fun `connect result carries capabilities from handshake`() = runTest {
        val manager = managerWithFake(FakeJsonRpcTransportHandle())
        manager.addServer(McpServerConfig(name = "fake", transport = McpTransport.BUILTIN))
        val caps = manager.connect("fake") { }.getOrThrow()
        assertTrue(caps.tools)
        assertTrue(caps.resources)
        assertTrue(!caps.prompts)
    }
}
