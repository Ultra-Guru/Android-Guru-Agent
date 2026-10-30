package com.apex.agent.core.jiguan.tools

import com.apex.agent.core.jiguan.JiguanRuntime
import com.apex.agent.core.jiguan.ProcParsing
import com.apex.agent.core.jiguan.ScanRequest
import com.apex.agent.core.jiguan.ScanValueType
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * # 进程与内存域工具（Process & Memory）
 *
 * - [JiguanProcesses]：get_running_processes —— 全进程枚举（UID/包名/RSS）；
 * - [JiguanMemoryScan]：memory_scan —— 全类型扫描（精确 / 模糊 ± / AOB）；
 * - [JiguanMemoryHexDump]：memory_hex_dump —— 三栏 Hex + 多类型解释；
 * - [JiguanMemoryWrite]：memory_write —— 原子写入（HIGH 风险门 + 前置快照）。
 */
// ═══════════════════════════════════════════════════════════════════

class JiguanProcessesTool constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanProcesses"
    override val name = "Jiguan: Running Processes"
    override val description = """
        List running processes with pid, name, uid, package and RSS (get_running_processes).
        Works without root for own-uid processes; full list with root. Use this first to
        locate the target pid before any memory or hook operation.

        Example: {"filter": "com.example", "limit": 30}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "filter": {"type": "string", "description": "Substring filter on name or cmdline (optional)"},
                "limit": {"type": "integer", "description": "Max entries (default 50)"}
            },
            "required": []
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.LOW)
        .tag("jiguan", "process", "memory")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments)
        val filter = JiguanArgs.string(args, "filter")
        val limit = JiguanArgs.int(args, "limit", 50).coerceIn(1, 300)
        val processes = runtime.memory.listProcesses().fold(
            onSuccess = { it },
            onFailure = {
                return "Error: process enumeration unavailable (${it.message ?: "channel offline"}) — " +
                    "process-memory domain needs root or Shizuku; see JiguanCapabilityReport guidance."
            },
        )
        val filtered = if (filter.isBlank()) {
            processes
        } else {
            processes.filter { p ->
                p.name.contains(filter, true) || p.cmdline.contains(filter, true) || p.appPackage?.contains(filter, true) == true
            }
        }
        if (filtered.isEmpty()) return "No processes matched \"$filter\"."
        return buildString {
            appendLine("Processes (${filtered.size} shown of ${processes.size}):")
            filtered.take(limit).forEach { p ->
                appendLine(
                    "- pid=${p.pid} uid=${p.uid} rss=${if (p.rssKb >= 0) "${p.rssKb}K" else "?"} " +
                        "state=${p.state.ifBlank { "?" }} ${p.name}" +
                        (p.appPackage?.let { " pkg=$it" } ?: ""),
                )
            }
            if (filtered.size > limit) appendLine("… ${filtered.size - limit} more (raise limit)")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════

class JiguanMemoryScanTool constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanMemoryScan"
    override val name = "Jiguan: Memory Scan"
    override val description = """
        Scan a process's memory for values (memory_scan): exact / fuzzy (±range) /
        AOB byte-pattern ("DE AD ?? EF"). Types: int8/int16/int32/int64, float32/float64,
        utf8/utf16 strings, bytes (AOB). Only readable regions are scanned; writable
        regions first. Results capped (default 200) for context economy.

        Example: {"pid": 12345, "value": "999999", "type": "int32"}
        Example AOB: {"pid": 12345, "pattern": "7F 45 4C 46", "type": "bytes"}
        Example fuzzy: {"pid": 12345, "value": "3.14", "type": "float32", "fuzzy": 0.01}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pid": {"type": "integer", "description": "Target process id"},
                "value": {"type": "string", "description": "Value literal (numeric or string)"},
                "type": {"type": "string", "enum": ["int8","int16","int32","int64","float32","float64","utf8","utf16","bytes"], "description": "Value type (default int32)"},
                "pattern": {"type": "string", "description": "AOB hex pattern with ?? wildcards (type=bytes)"},
                "fuzzy": {"type": "number", "description": "Tolerance for numeric scan (0 = exact)"},
                "region_filter": {"type": "string", "description": "Substring filter on region path (e.g. [heap])"},
                "max_results": {"type": "integer", "description": "Max hits (default 200)"}
            },
            "required": ["pid"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.MEDIUM)
        .tag("jiguan", "memory", "scan")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pid = JiguanArgs.int(args, "pid", -1)
        if (pid <= 0) return "Error: missing required parameter 'pid'"
        val type = when (JiguanArgs.string(args, "type", "int32")) {
            "int8" -> ScanValueType.INT8
            "int16" -> ScanValueType.INT16
            "int64" -> ScanValueType.INT64
            "float32" -> ScanValueType.FLOAT32
            "float64" -> ScanValueType.FLOAT64
            "utf8" -> ScanValueType.UTF8
            "utf16" -> ScanValueType.UTF16
            "bytes" -> ScanValueType.BYTE_PATTERN
            else -> ScanValueType.INT32
        }
        val pattern = JiguanArgs.string(args, "pattern")
        val value = JiguanArgs.string(args, "value")
        if (type == ScanValueType.BYTE_PATTERN && pattern.isBlank()) {
            return "Error: type=bytes requires 'pattern' (e.g. \"DE AD ?? EF\")"
        }
        if (type != ScanValueType.BYTE_PATTERN && value.isBlank()) {
            return "Error: missing required parameter 'value'"
        }
        val request = ScanRequest(
            pid = pid,
            value = value,
            type = type,
            fuzzyRange = JiguanArgs.double(args, "fuzzy", 0.0),
            bytePattern = pattern,
            regionFilter = JiguanArgs.string(args, "region_filter"),
            maxResults = JiguanArgs.int(args, "max_results", 200).coerceIn(1, 2000),
        )
        val hits = runtime.scan(request).fold(
            onSuccess = { it },
            onFailure = {
                return "Error: scan failed (${it.message ?: "read channel offline"}) — " +
                    "memory access requires root (dd over proc-mem) or the jiguan native core."
            },
        )
        if (hits.isEmpty()) return "No hits. Widen fuzzy range, check the value/type, or scan without region_filter."
        return buildString {
            appendLine("Memory scan hits (${hits.size}, type=${request.type}):")
            hits.take(60).forEach { hit ->
                appendLine("- 0x${hit.address.toString(16)} = ${hit.valueText}  [${hit.regionPath}]")
            }
            if (hits.size > 60) appendLine("… ${hits.size - 60} more (narrow with fuzzy/region_filter or re-scan after value changes)")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════

class JiguanMemoryHexDumpTool constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanMemoryHexDump"
    override val name = "Jiguan: Memory Hex Dump"
    override val description = """
        Read a memory region as a classic hex dump (memory_hex_dump): offset/hex/ascii
        columns plus little-endian numeric interpretations (int/float/utf8/utf16) so you
        can analyze data structures (JSON strings, protobuf, packed structs).

        Example: {"pid": 12345, "address": "0x7a8b9c0000", "size": 256}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pid": {"type": "integer", "description": "Target process id"},
                "address": {"type": "string", "description": "Start address (decimal or 0x-hex)"},
                "size": {"type": "integer", "description": "Bytes to read (1..4096, default 256)"}
            },
            "required": ["pid", "address"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.LOW)
        .tag("jiguan", "memory", "hex")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pid = JiguanArgs.int(args, "pid", -1)
        val address = JiguanArgs.address(args, "address")
        val size = JiguanArgs.int(args, "size", 256).coerceIn(1, 4096)
        if (pid <= 0) return "Error: missing required parameter 'pid'"
        if (address < 0) return "Error: missing required parameter 'address'"
        val buffer = runtime.memory.read(pid, address, size).fold(
            onSuccess = { it },
            onFailure = {
                return "Error: memory read failed (${it.message ?: "channel offline"}) — " +
                    "verify pid/address; needs root or jiguan native core."
            },
        )
        val view = com.apex.agent.core.jiguan.HexDumpFormatter.view(pid, buffer, address)
        return buildString {
            appendLine("Hex dump pid=$pid @ 0x${address.toString(16)} (${buffer.size}B):")
            appendLine(view.text)
            if (view.interpretations.isNotEmpty()) {
                appendLine("Interpretations:")
                view.interpretations.forEach { appendLine("- $it") }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════

class JiguanMemoryWriteTool constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanMemoryWrite"
    override val name = "Jiguan: Memory Write"
    override val description = """
        Write bytes into a process's memory (memory_write). HIGH RISK — the execution
        gate asks the user once per session. Writes are atomic replacements at the
        given address; always read the current bytes first (JiguanMemoryHexDump) and
        prefer the smallest possible write.

        Example: {"pid": 12345, "address": "0x7a8b9c1234", "hex": "E8 03 00 00"}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pid": {"type": "integer", "description": "Target process id"},
                "address": {"type": "string", "description": "Target address (decimal or 0x-hex)"},
                "hex": {"type": "string", "description": "Bytes to write as hex pairs, e.g. \"E8 03 00 00\""}
            },
            "required": ["pid", "address", "hex"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.HIGH)
        .tag("jiguan", "memory", "write", "dangerous")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pid = JiguanArgs.int(args, "pid", -1)
        val address = JiguanArgs.address(args, "address")
        val hex = JiguanArgs.string(args, "hex")
        if (pid <= 0) return "Error: missing required parameter 'pid'"
        if (address < 0) return "Error: missing required parameter 'address'"
        val data = parseHexBytes(hex)
            ?: return "Error: invalid 'hex' — expected pairs like \"E8 03 00 00\" (got: \"$hex\")"
        if (data.size > 256) return "Error: write payload too large (${data.size}B > 256B) — split into chunks"
        // 写前快照（审计回滚面）：读旧值失败不阻断 —— 但必须在结果里声明。
        val previous = runtime.memory.read(pid, address, data.size).getOrNull()
        return runtime.memory.write(pid, address, data).fold(
            onSuccess = {
                buildString {
                    appendLine("Wrote ${data.size}B @ 0x${address.toString(16)} (pid $pid).")
                    appendLine(
                        if (previous != null) {
                            "Previous bytes (snapshot for rollback): " +
                                previous.joinToString(" ") { String.format("%02X", it) }
                        } else {
                            "WARN: pre-write snapshot unavailable (read failed) — rollback info lost."
                        },
                    )
                }
            },
            onFailure = {
                "Error: memory write failed (${it.message ?: "channel offline"}) — " +
                    "needs root; verify the address is writable (rw-p) via JiguanMemoryScan context."
            },
        )
    }

    private fun parseHexBytes(hex: String): ByteArray? {
        val tokens = hex.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 256) return null
        val out = ByteArray(tokens.size)
        for ((index, token) in tokens.withIndex()) {
            if (token.length != 2) return null
            val v = token.toIntOrNull(16) ?: return null
            out[index] = v.toByte()
        }
        return out
    }
}
