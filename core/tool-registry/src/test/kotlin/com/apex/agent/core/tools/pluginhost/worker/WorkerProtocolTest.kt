package com.apex.agent.core.tools.pluginhost.worker

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Worker IPC 协议编解码契约锁（纯 JVM，无子进程）。
 *
 * 信封结构、请求关联、负载解析的形状由此锁定 —— 端到端行为由
 * [PluginWorkerEndToEndTest]（真实 node）补足。
 */
class WorkerProtocolTest {

    @Test
    fun `envelope carries protocol name and version`() {
        val line = WorkerProtocol.hello("req-1", "opencode.demo")
        val parsed = WorkerProtocol.Message.parse(line)
        assertNotNull(parsed)
        parsed!!
        assertEquals(WorkerProtocol.PROTOCOL_NAME, parsed.protocol)
        assertEquals(WorkerProtocol.VERSION, parsed.version)
        assertEquals(WorkerProtocol.Type.HELLO, parsed.type)
        assertEquals("req-1", parsed.requestId)
        assertEquals("opencode.demo", parsed.payload?.get("pluginId")?.jsonPrimitive?.content)
    }

    @Test
    fun `unknown protocol name is rejected`() {
        assertNull(
            WorkerProtocol.Message.parse(
                """{"protocol":"something-else","version":1,"type":"hello"}"""
            )
        )
        assertNull(WorkerProtocol.Message.parse("not json at all"))
        assertNull(WorkerProtocol.Message.parse(""))
    }

    @Test
    fun `tool execute carries name and raw args json`() {
        val line = WorkerProtocol.toolExecute("req-7", "echo", """{"text":"hi"}""")
        val parsed = WorkerProtocol.Message.parse(line)!!
        assertEquals(WorkerProtocol.Type.TOOL_EXECUTE, parsed.type)
        assertEquals("echo", parsed.payload?.get("toolName")?.jsonPrimitive?.content)
        assertEquals("""{"text":"hi"}""", parsed.payload?.get("argsJson")?.jsonPrimitive?.content)
    }

    @Test
    fun `ready payload parses runtime identity`() {
        val ready = WorkerProtocol.Ready.from(
            WorkerProtocol.Message.parse(
                """{"protocol":"plugin-host","version":1,"type":"ready","requestId":"r1",
                    "payload":{"runtime":"node","runtimeVersion":"v22.11.0","protocolVersion":1}}"""
            )!!
        )
        assertNotNull(ready)
        ready!!
        assertEquals("node", ready.runtime)
        assertEquals("v22.11.0", ready.runtimeVersion)
        assertEquals(1, ready.protocolVersion)
    }

    @Test
    fun `ready from wrong type yields null`() {
        assertNull(
            WorkerProtocol.Ready.from(
                WorkerProtocol.Message.parse(WorkerProtocol.ping("r1"))!!
            )
        )
    }

    @Test
    fun `loaded payload parses discovered tools`() {
        val loaded = WorkerProtocol.Loaded.from(
            WorkerProtocol.Message.parse(
                """{"protocol":"plugin-host","version":1,"type":"loaded","requestId":"r2",
                    "payload":{"tools":[
                        {"name":"echo","description":"Echo","parametersSchema":"{\"type\":\"object\"}"},
                        {"name":"fail","description":"Boom"}
                    ],"setupOk":true}}"""
            )!!
        )
        assertNotNull(loaded)
        loaded!!
        assertTrue(loaded.setupOk)
        assertEquals(2, loaded.tools.size)
        assertEquals("echo", loaded.tools[0].name)
        assertEquals("Echo", loaded.tools[0].description)
        assertNotNull(loaded.tools[0].parametersSchema)
        assertNull(loaded.tools[1].parametersSchema)
    }

    @Test
    fun `loaded with setup failure keeps error honest`() {
        val loaded = WorkerProtocol.Loaded.from(
            WorkerProtocol.Message.parse(
                """{"protocol":"plugin-host","version":1,"type":"loaded","requestId":"r3",
                    "payload":{"tools":[],"setupOk":false,"error":"entry not found: x"}}"""
            )!!
        )
        assertNotNull(loaded)
        loaded!!
        assertEquals(false, loaded.setupOk)
        assertEquals("entry not found: x", loaded.error)
    }

    @Test
    fun `tool result parses ok and error shapes`() {
        val ok = WorkerProtocol.ToolResult.from(
            WorkerProtocol.Message.parse(
                """{"protocol":"plugin-host","version":1,"type":"tool.result","requestId":"r4",
                    "payload":{"ok":true,"output":"echo: hi"}}"""
            )!!
        )
        ok!!
        assertEquals(true, ok.ok)
        assertEquals("echo: hi", ok.output)

        val err = WorkerProtocol.ToolResult.from(
            WorkerProtocol.Message.parse(
                """{"protocol":"plugin-host","version":1,"type":"tool.result","requestId":"r5",
                    "payload":{"ok":false,"error":"boom"}}"""
            )!!
        )
        err!!
        assertEquals(false, err.ok)
        assertEquals("boom", err.error)
    }

    @Test
    fun `tool result without request id is dropped`() {
        assertNull(
            WorkerProtocol.ToolResult.from(
                WorkerProtocol.Message.parse(
                    """{"protocol":"plugin-host","version":1,"type":"tool.result",
                        "payload":{"ok":true}}"""
                )!!
            )
        )
    }

    @Test
    fun `plugin event parses name and payload`() {
        val event = WorkerProtocol.PluginEvent.from(
            WorkerProtocol.Message.parse(
                """{"protocol":"plugin-host","version":1,"type":"plugin.event",
                    "payload":{"name":"config.changed","payloadJson":"{\"k\":1}"}}"""
            )!!
        )
        event!!
        assertEquals("config.changed", event.name)
        assertEquals("""{"k":1}""", event.payloadJson)
    }

    @Test
    fun `fatal reason falls back when payload absent`() {
        assertEquals(
            "unknown fatal",
            WorkerProtocol.fatalReason(
                WorkerProtocol.Message.parse(
                    """{"protocol":"plugin-host","version":1,"type":"fatal"}"""
                )!!
            )
        )
        assertEquals(
            "entry crashed",
            WorkerProtocol.fatalReason(
                WorkerProtocol.Message.parse(
                    """{"protocol":"plugin-host","version":1,"type":"fatal","payload":{"reason":"entry crashed"}}"""
                )!!
            )
        )
    }

    @Test
    fun `cancel targets a request id`() {
        val parsed = WorkerProtocol.Message.parse(WorkerProtocol.cancel("r6", "r5"))!!
        assertEquals(WorkerProtocol.Type.CANCEL, parsed.type)
        assertEquals("r5", parsed.payload?.get("targetRequestId")?.jsonPrimitive?.content)
    }
}
