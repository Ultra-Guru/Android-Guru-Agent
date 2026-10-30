package com.apex.agent.core.jiguan

import com.apex.agent.core.jiguan.tools.JiguanCapabilityReportTool
import com.apex.agent.core.jiguan.tools.JiguanHookJavaMethodTool
import com.apex.agent.core.jiguan.tools.JiguanJavaClassesTool
import com.apex.agent.core.jiguan.tools.JiguanMemoryHexDumpTool
import com.apex.agent.core.jiguan.tools.JiguanMemoryScanTool
import com.apex.agent.core.jiguan.tools.JiguanMemoryWriteTool
import com.apex.agent.core.jiguan.tools.JiguanProcessesTool
import com.apex.agent.core.jiguan.tools.JiguanSslBypassTool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具层冒烟（fake 端口全链路）—— 参数解析 / 降级文案 / 结果渲染。
 */
class JiguanToolsSmokeTest {

    private fun runtime(): JiguanRuntime = JiguanRuntime(
        memory = FakeMemoryPort(processesOk = true),
        java = FakeChannelPort("frida-server"),
        native = FakeChannelPort("objdump"),
        network = FakeChannelPort("tcpdump"),
        system = FakeChannelPort("root-shell"),
        hooks = OmniHookEngine({ _, _, _ -> Result.success("[ok] hooked") }, { true }),
        knowledge = JiguanKnowledge(FakeStoreAdapter()),
    )

    private fun orchestrator() = JiguanOrchestrator(
        FakeMemoryPort(true), FakeChannelPort("frida-server"), FakeChannelPort("objdump"),
        FakeChannelPort("tcpdump"), FakeChannelPort("root-shell"),
    )

    @Test
    fun `JiguanProcesses lists fake app`() = runTest {
        val result = JiguanProcessesTool(runtime()).execute("""{"filter": "fake"}""")
        assertTrue(result.contains("pid=1"))
        assertTrue(result.contains("fake.app"))
    }

    @Test
    fun `JiguanProcesses degrades when offline`() = runTest {
        val offline = JiguanRuntime(
            FakeMemoryPort(processesOk = false), FakeChannelPort("none"), FakeChannelPort("none"),
            FakeChannelPort("none"), FakeChannelPort("none"),
            OmniHookEngine({ _, _, _ -> Result.success("") }, { false }),
            JiguanKnowledge(FakeStoreAdapter()),
        )
        val result = JiguanProcessesTool(offline).execute("{}")
        assertTrue(result.startsWith("Error:"))
        assertTrue(result.contains("root"))
    }

    @Test
    fun `JiguanMemoryScan finds embedded 1337`() = runTest {
        val result = JiguanMemoryScanTool(runtime()).execute("""{"pid": 1, "value": "1337", "type": "int32"}""")
        assertTrue(result.contains("1337"))
        assertTrue(result.contains("[heap]"))
    }

    @Test
    fun `JiguanMemoryScan validates aob requirements`() = runTest {
        val result = JiguanMemoryScanTool(runtime()).execute("""{"pid": 1, "type": "bytes"}""")
        assertTrue(result.contains("pattern"))
    }

    @Test
    fun `JiguanMemoryHexDump renders dump with interpretations`() = runTest {
        val result = JiguanMemoryHexDumpTool(runtime()).execute("""{"pid": 1, "address": "65536", "size": 16}""")
        assertTrue(result.contains("Hex dump"))
        assertTrue(result.contains("Interpretations"))
        assertTrue(result.contains("int32le"))
    }

    @Test
    fun `JiguanMemoryWrite validates hex and snapshot`() = runTest {
        val ok = JiguanMemoryWriteTool(runtime()).execute(
            """{"pid": 1, "address": "65544", "hex": "E8 03 00 00"}""",
        )
        assertTrue(ok.contains("Wrote 4B"))
        assertTrue(ok.contains("snapshot"))
        val bad = JiguanMemoryWriteTool(runtime()).execute("""{"pid": 1, "address": "65544", "hex": "ZZ"}""")
        assertTrue(bad.startsWith("Error:"))
    }

    @Test
    fun `JiguanJavaClasses requires filter and lists matches`() = runTest {
        val missing = JiguanJavaClassesTool(runtime()).execute("""{"pid": 1}""")
        assertTrue(missing.contains("filter"))
        val result = JiguanJavaClassesTool(runtime()).execute("""{"pid": 1, "filter": "Encrypt"}""")
        assertTrue(result.contains("com.fake.Encrypt"))
    }

    @Test
    fun `JiguanHookJavaMethod installs and reports events`() = runTest {
        val result = JiguanHookJavaMethodTool(runtime()).execute(
            """{"pid": 1, "class": "com.fake.Encrypt", "method": "aes", "action": "log"}""",
        )
        assertTrue(result.contains("FRIDA_SERVER"))
        assertTrue(result.contains("[ok] hooked"))
    }

    @Test
    fun `JiguanSslBypass reports layer outcomes`() = runTest {
        val result = JiguanSslBypassTool(runtime()).execute("""{"pid": 1}""")
        assertTrue(result.contains("SSL pinning bypass injected"))
    }

    @Test
    fun `JiguanCapabilityReport renders domains`() = runTest {
        val result = JiguanCapabilityReportTool(orchestrator()).execute("{}")
        assertTrue(result.contains("process-memory: AVAILABLE"))
        assertTrue(result.contains("java-art: AVAILABLE"))
    }
}
