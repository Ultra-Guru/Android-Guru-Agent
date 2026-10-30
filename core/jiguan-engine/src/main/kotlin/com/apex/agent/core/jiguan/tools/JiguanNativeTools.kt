package com.apex.agent.core.jiguan.tools

import com.apex.agent.core.jiguan.FridaScriptLibrary
import com.apex.agent.core.jiguan.HookKind
import com.apex.agent.core.jiguan.HookSpec
import com.apex.agent.core.jiguan.JiguanRuntime
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # Native 域工具（NDK & Assembler）
 *
 * - [JiguanModulesTool]：list_modules —— /proc maps 聚合的 .so 清单（零依赖通道）；
 * - [JiguanHookNativeFuncTool]：hook_native_func —— Interceptor.attach 导出函数 Hook；
 * - [JiguanDisassembleTool]：disassemble —— objdump 裸二进制反汇编（PRoot binutils）；
 * - [JiguanHardwareBreakpointTool]：set_hardware_breakpoint —— 硬件断点（Frida 通道）。
 */
// ═══════════════════════════════════════════════════════════════════

@Singleton
class JiguanModulesTool @Inject constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanModules"
    override val name = "Jiguan: Native Modules"
    override val description = """
        List native modules (.so libraries) loaded by a process (list_modules), with
        base address, size and rwx flags — aggregated from /proc maps. Use the base
        address plus a symbol offset to hook native functions.

        Example: {"pid": 12345, "filter": "crypto"}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pid": {"type": "integer", "description": "Target process id"},
                "filter": {"type": "string", "description": "Substring filter on module name (optional)"}
            },
            "required": ["pid"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.LOW)
        .tag("jiguan", "native", "so")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pid = JiguanArgs.int(args, "pid", -1)
        if (pid <= 0) return "Error: missing required parameter 'pid'"
        val filter = JiguanArgs.string(args, "filter")
        val modules = runtime.native.listModules(pid).fold(
            onSuccess = { it },
            onFailure = {
                return "Error: module enumeration failed (${it.message ?: "channel offline"}) — " +
                    "maps read requires the target to be same-uid or root."
            },
        )
        val filtered = if (filter.isBlank()) {
            modules
        } else {
            modules.filter { it.name.contains(filter, true) || it.path.contains(filter, true) }
        }
        if (filtered.isEmpty()) return "No modules matched \"$filter\" (loaded=${modules.size})."
        return buildString {
            appendLine("Native modules (${filtered.size} of ${modules.size}):")
            filtered.take(80).forEach { m ->
                appendLine(
                    "- ${m.name} base=0x${m.baseAddress.toString(16)} size=${m.sizeBytes / 1024}K " +
                        "${if (m.executable) "x" else "-"}${if (m.writable) "w" else "-"} ${m.path}",
                )
            }
            if (filtered.size > 80) appendLine("… ${filtered.size - 80} more")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════

@Singleton
class JiguanHookNativeFuncTool @Inject constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanHookNativeFunc"
    override val name = "Jiguan: Hook Native Func"
    override val description = """
        Hook an exported native function in a target process (hook_native_func) via
        Frida Interceptor.attach — logs args on enter and return value on leave.
        Find symbol names with JiguanModules + your reverse-engineering context.

        Example: {"pid": 12345, "module": "libnative.so", "symbol": "Java_com_x_nativeSign", "wait_seconds": 20}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pid": {"type": "integer", "description": "Target process id"},
                "module": {"type": "string", "description": "Module name, e.g. libnative.so"},
                "symbol": {"type": "string", "description": "Exported symbol name"},
                "wait_seconds": {"type": "integer", "description": "Capture window (default 20)"}
            },
            "required": ["pid", "module", "symbol"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.HIGH)
        .tag("jiguan", "native", "hook", "frida")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pid = JiguanArgs.int(args, "pid", -1)
        val module = JiguanArgs.string(args, "module")
        val symbol = JiguanArgs.string(args, "symbol")
        if (pid <= 0 || module.isBlank() || symbol.isBlank()) {
            return "Error: required parameters pid/module/symbol"
        }
        val spec = HookSpec(
            kind = HookKind.NATIVE_FUNC,
            pid = pid,
            className = module,
            symbolName = symbol,
        )
        val waitSec = JiguanArgs.int(args, "wait_seconds", 20).coerceIn(3, 60)
        val handle = runtime.hooks.install(spec, timeoutMs = waitSec * 1000L)
        return renderHookResult(handle.id, handle.channel.name, handle.events)
    }
}

// ═══════════════════════════════════════════════════════════════════

@Singleton
class JiguanDisassembleTool @Inject constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanDisassemble"
    override val name = "Jiguan: Disassemble"
    override val description = """
        Disassemble a memory range of a process (disassemble) — dumps the bytes then
        runs objdump in binary mode (aarch64 little-endian) via the PRoot toolchain.
        Returns addr/bytes/mnemonic lines ready for analysis.

        Example: {"pid": 12345, "address": "0x7a8b9c1000", "size": 128}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pid": {"type": "integer", "description": "Target process id"},
                "address": {"type": "string", "description": "Start address (decimal or 0x-hex)"},
                "size": {"type": "integer", "description": "Bytes to disassemble (16..2048, default 128)"}
            },
            "required": ["pid", "address"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.MEDIUM)
        .tag("jiguan", "native", "disasm", "arm64")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pid = JiguanArgs.int(args, "pid", -1)
        val address = JiguanArgs.address(args, "address")
        val size = JiguanArgs.int(args, "size", 128).coerceIn(16, 2048)
        if (pid <= 0) return "Error: missing required parameter 'pid'"
        if (address < 0) return "Error: missing required parameter 'address'"
        val lines = runtime.native.disassemble(pid, address, size).fold(
            onSuccess = { it },
            onFailure = {
                return "Error: disassembly failed (${it.message ?: "channel offline"}) — " +
                    "needs PRoot Ubuntu with binutils (apt install binutils) plus memory read channel."
            },
        )
        if (lines.isEmpty()) return "Disassembler returned nothing (region unreadable or objdump missing)."
        return buildString {
            appendLine("Disassembly pid=$pid @ 0x${address.toString(16)} ($size B):")
            lines.take(200).forEach { line ->
                appendLine("0x${line.address.toString(16)}  ${line.bytes.padEnd(12)} ${line.text}")
            }
            if (lines.size > 200) appendLine("… ${lines.size - 200} more instructions")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════

@Singleton
class JiguanHardwareBreakpointTool @Inject constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanHardwareBreakpoint"
    override val name = "Jiguan: Hardware Breakpoint"
    override val description = """
        Set a hardware breakpoint at an address (set_hardware_breakpoint) via the
        Frida thread breakpoint API — installs on all threads and captures execution
        context hits (pc, fault type) as events. Higher fidelity than inline hooks
        for locating execution paths.

        Example: {"pid": 12345, "address": "0x7a8b9c1234", "wait_seconds": 30}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pid": {"type": "integer", "description": "Target process id"},
                "address": {"type": "string", "description": "Breakpoint address (decimal or 0x-hex)"},
                "wait_seconds": {"type": "integer", "description": "Capture window (default 30)"}
            },
            "required": ["pid", "address"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.HIGH)
        .tag("jiguan", "native", "breakpoint", "frida")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pid = JiguanArgs.int(args, "pid", -1)
        val address = JiguanArgs.address(args, "address")
        if (pid <= 0) return "Error: missing required parameter 'pid'"
        if (address < 0) return "Error: missing required parameter 'address'"
        val script = FridaScriptLibrary.hardwareBreakpoint(address)
        val waitSec = JiguanArgs.int(args, "wait_seconds", 30).coerceIn(5, 120)
        val output = runtime.native.runScript(pid, script, waitSec * 1000L).fold(
            onSuccess = { it },
            onFailure = {
                return "Error: frida bridge unavailable (${it.message ?: "channel offline"}) — " +
                    "hardware breakpoints need frida-server; see JiguanCapabilityReport."
            },
        )
        val hits = output.lineSequence().filter { it.startsWith("[hwbp-hit]") }.toList()
        return buildString {
            appendLine("Hardware breakpoint @ 0x${address.toString(16)} (pid $pid):")
            if (hits.isEmpty()) {
                appendLine("Installed but no hits in ${waitSec}s. Output:")
                output.lineSequence().take(20).forEach { appendLine(it) }
            } else {
                appendLine("Hits (${hits.size}):")
                hits.take(60).forEach { appendLine(it) }
            }
        }
    }
}
