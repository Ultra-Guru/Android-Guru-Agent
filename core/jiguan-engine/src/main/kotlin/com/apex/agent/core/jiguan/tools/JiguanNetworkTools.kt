package com.apex.agent.core.jiguan.tools

import com.apex.agent.core.jiguan.FridaScriptLibrary
import com.apex.agent.core.jiguan.JiguanRuntime
import com.apex.agent.core.jiguan.PcapHttpExtractor
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # 网络域工具（Network & Traffic）
 *
 * - [JiguanPacketCaptureTool]：start_packet_capture —— tcpdump 落盘 + 停止时
 *   纯 Kotlin pcap→HTTP 提取（小黄鸟对等的只读分析面）；
 * - [JiguanSslBypassTool]：bypass_ssl_pinning —— 四层统一绕过脚本注入。
 */
// ═══════════════════════════════════════════════════════════════════

@Singleton
class JiguanPacketCaptureTool @Inject constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanPacketCapture"
    override val name = "Jiguan: Packet Capture"
    override val description = """
        Device packet capture with HTTP extraction (start_packet_capture):
        action=start begins tcpdump to a pcap file; action=stop stops it and parses
        the pcap in-process (TCP reassembly + HTTP request/response extraction);
        action=status shows the current session. cleartext HTTP bodies/headers are
        extracted for analysis — pair with JiguanSslBypass for pinned TLS targets.

        Example: {"action": "start", "filter": "port 80"}
        Example: {"action": "stop"}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["start", "stop", "status"]},
                "filter": {"type": "string", "description": "tcpdump filter expression (optional, e.g. \"host 1.2.3.4\")"},
                "max_messages": {"type": "integer", "description": "Max HTTP messages extracted on stop (default 30)"}
            },
            "required": ["action"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.WEB)
        .risk(ToolRisk.MEDIUM)
        .tag("jiguan", "network", "pcap", "http")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        return when (JiguanArgs.string(args, "action")) {
            "start" -> startCapture(JiguanArgs.string(args, "filter"))
            "stop" -> stopCapture(JiguanArgs.int(args, "max_messages", 30))
            "status" -> statusCapture()
            else -> "Error: 'action' must be start | stop | status"
        }
    }

    private suspend fun startCapture(filter: String): String {
        val session = runtime.network.startCapture(pid = null, filter = filter).fold(
            onSuccess = { it },
            onFailure = {
                return "Error: capture start failed (${it.message ?: "channel offline"}) — " +
                    "needs tcpdump (PRoot: apt install tcpdump; root: toybox tcpdump)."
            },
        )
        return if (session.running) {
            "Capture started (session ${session.id}, channel ${session.channel}).\n" +
                "pcap: ${session.pcapPath}\n" +
                "Trigger the target behavior in the app, then call {\"action\":\"stop\"}."
        } else {
            "Capture session ${session.id} created but NOT running (channel ${session.channel}).\n" +
                "Install tcpdump and retry."
        }
    }

    private suspend fun stopCapture(maxMessages: Int): String {
        val sessionId = currentSessionId() ?: return "Error: no active capture session"
        val session = runtime.network.stopCapture(sessionId).fold(
            onSuccess = { it },
            onFailure = { return "Error: capture stop failed (${it.message ?: "offline"})" },
        )
        val pcap = runCatching { java.io.File(session.pcapPath).readBytes() }.getOrNull()
            ?: return "Capture stopped (pcap ${session.pcapPath}) but the file is unreadable — " +
                "check PRoot/workspace path mapping."
        val summary = PcapHttpExtractor.parse(pcap, maxMessages)
        return buildString {
            appendLine("Capture stopped. pcap=${session.pcapPath} (${pcap.size / 1024}K)")
            appendLine("packets=${summary.packetCount} malformed=${summary.malformedCount} http=${summary.httpMessages.size}")
            if (summary.httpMessages.isEmpty()) {
                appendLine("No cleartext HTTP extracted — TLS traffic needs JiguanSslBypass first, or the filter was too narrow.")
            } else {
                summary.httpMessages.forEach { message ->
                    appendLine("---")
                    appendLine(
                        when (message.direction) {
                            "request" -> "→ ${message.method} ${message.url} (host=${message.host})"
                            else -> "← HTTP ${message.status} (host=${message.host})"
                        },
                    )
                    if (message.headers.isNotBlank()) {
                        appendLine("headers: ${message.headers.lineSequence().take(6).joinToString(" | ")}")
                    }
                    if (message.bodyPreview.isNotBlank()) appendLine("body: ${message.bodyPreview.take(400)}")
                }
            }
        }
    }

    private suspend fun statusCapture(): String {
        val sessionId = currentSessionId()
        return if (sessionId == null) "No capture session." else "Active session: $sessionId"
    }

    private suspend fun currentSessionId(): Int? {
        // 会话 id 由端口实现分配；status 面向用户 —— 用 capability 探测不可靠，
        // 这里经 startCapture 的对偶 stopCapture(id) 由适配器管理生命周期。
        return lastSessionId
    }

    companion object {
        /** 最近一次 start 的会话 id（进程内单会话语义 —— 与 tcpdump 单实例对齐）。 */
        @Volatile
        var lastSessionId: Int? = null
    }
}

// ═══════════════════════════════════════════════════════════════════

@Singleton
class JiguanSslBypassTool @Inject constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanSslBypass"
    override val name = "Jiguan: SSL Pinning Bypass"
    override val description = """
        Inject the universal SSL-pinning bypass script into a target process
        (bypass_ssl_pinning): covers default TrustManager, OkHttp CertificatePinner
        and WebView SSL errors, each layer isolated. Run BEFORE triggering HTTPS
        requests you want to read with JiguanPacketCapture.

        Example: {"pid": 12345}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pid": {"type": "integer", "description": "Target process id"}
            },
            "required": ["pid"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.WEB)
        .risk(ToolRisk.HIGH)
        .tag("jiguan", "network", "ssl", "mitm")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pid = JiguanArgs.int(args, "pid", -1)
        if (pid <= 0) return "Error: missing required parameter 'pid'"
        val script = FridaScriptLibrary.sslUnpinning()
        val output = runtime.java.runScript(pid, script, 10_000).fold(
            onSuccess = { it },
            onFailure = {
                return "Error: frida bridge unavailable (${it.message ?: "channel offline"}) — " +
                    "SSL bypass is hook-based; see JiguanCapabilityReport for setup."
            },
        )
        val layers = output.lineSequence().filter { it.startsWith("[ok] layer") }.toList()
        val skipped = output.lineSequence().filter { it.startsWith("[skip] layer") }.toList()
        return buildString {
            appendLine("SSL pinning bypass injected (pid $pid):")
            if (layers.isEmpty() && skipped.isEmpty()) {
                appendLine("(no layer feedback captured — output below)")
                output.lineSequence().take(10).forEach { appendLine(it) }
            } else {
                layers.forEach { appendLine("$it ✓") }
                skipped.forEach { appendLine("$it (absent in target — fine)") }
            }
            appendLine("Now trigger HTTPS traffic in the app and run JiguanPacketCapture.")
        }
    }
}
