package com.apex.agent.tools

import com.apex.agent.core.engine.AgentAnswer
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.DeviceGateDecision
import com.apex.agent.core.engine.DeviceIntentGate
import com.apex.agent.core.tools.device.CapabilityProbe
import com.apex.agent.core.tools.device.DeviceCapabilitySnapshot
import com.apex.agent.core.tools.device.DeviceOpsPort
import com.apex.agent.core.tools.device.DeviceOpResult
import com.apex.agent.core.tools.device.DevicePrivilege
import com.apex.agent.core.tools.device.IntentCatalog
import com.apex.agent.platform.privilege.shizuku.DeviceChannel
import com.apex.agent.platform.privilege.shizuku.DeviceExecRequest
import com.apex.agent.platform.privilege.shizuku.DeviceExecResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ═══ S05/S06/S07 执行面编排测试 ═══
 *
 * 手写 fake（通道 / 探针 / 门 / 审计；无 mock 框架）穷举核心流程：
 * DCP_DISABLED / 未知意图 / 档位不足 / dry_run 零副作用 + token /
 * 不可逆无 token 拒绝 / Stale 刷新 / 守卫硬拒 / 越权路径拒绝 / 执行 + verify。
 */
class DeviceOpsExecutorTest {

    private class RecordingChannel : DeviceChannel {
        val argv = mutableListOf<List<String>>()
        /** 状态标志：卸载 argv 执行后置位，后续 pm list 探针返回空（模拟已卸载）。 */
        var uninstalled = false
        var result: (List<String>) -> DeviceExecResult = { DeviceExecResult(ok = true, stdout = "Success", exitCode = 0) }

        override suspend fun execute(req: DeviceExecRequest, isCancelled: () -> Boolean): DeviceExecResult {
            argv += req.argv.toList()
            return result(req.argv.toList())
        }
    }

    private class FixedProbe(private val privilege: DevicePrivilege = DevicePrivilege.SHIZUKU) : CapabilityProbe {
        var snapshotCount = 0
        override fun snapshot(): DeviceCapabilitySnapshot {
            snapshotCount++
            return DeviceCapabilitySnapshot(
                privilege, 2000, true, true, 13, emptySet(), emptyMap(), 0L
            )
        }
    }

    private class RecordingAudit : DeviceAuditSink {
        val events = mutableListOf<ToolAuditLogger.Event>()
        override fun log(event: ToolAuditLogger.Event) {
            events += event
        }
    }

    private class GateScript(
        var rules: List<com.apex.agent.core.engine.DeviceGateRule> = emptyList(),
        var mode: com.apex.agent.core.engine.DeviceGateMode = com.apex.agent.core.engine.DeviceGateMode.BYPASS,
        var answer: String = "allow_once"
    ) {
        var asked = 0
        var lastQuestion: AgentQuestion? = null
        val gate = DeviceIntentGate(
            ask = { q ->
                asked++
                lastQuestion = q
                AgentAnswer(questionId = q.id, selectedOptionId = answer)
            },
            rules = { rules },
            mode = { mode }
        )
    }

    private fun catalog(): IntentCatalog {
        val file = listOf(
            File("app/src/main/assets/device/intents.json"),
            File("../app/src/main/assets/device/intents.json")
        ).firstOrNull { it.isFile } ?: error("intents.json 不存在")
        return IntentCatalog.load(file.readText())
    }

    private fun executor(
        channel: RecordingChannel = RecordingChannel(),
        probe: FixedProbe = FixedProbe(),
        gate: GateScript = GateScript(),
        audit: RecordingAudit = RecordingAudit(),
        enabled: Boolean = true,
        selfPackage: String = "com.apex.self"
    ): DeviceOpsExecutor {
        val cat = catalog()
        val pathPolicy = DevicePathPolicy(extraAllowedRoots = emptyList())
        val trash = DeviceTrash(channel)
        val preflight = DevicePreflight(channel, cat, clock = { 1_000L }, sign = { "sig:$it" }, selfPackage = selfPackage)
        val fileOps = FileOpsRunner(channel, trash, pathPolicy, clock = { 1_000L })
        return DeviceOpsExecutor(
            catalog = cat, channel = channel, preflight = preflight, capability = probe,
            gate = gate.gate, pathPolicy = pathPolicy, trash = trash, fileOps = fileOps,
            audit = audit, selfPackage = selfPackage, enabled = { enabled },
            auditIdGen = { "dcp-fixed01" }, now = { 1_000L }
        )
    }

    @Test
    fun `DCP 关闭时结构化返回 DCP_DISABLED 不静默降级`() = runTest {
        val ex = executor(enabled = false)
        val r = ex.execute("package.list", emptyMap(), dryRun = false, token = null)
        assertEquals(false, r.ok)
        assertEquals("DCP_DISABLED", r.error)
        assertTrue(r.hint!!.contains("未启用"))
    }

    @Test
    fun `未知意图结构化拒绝`() = runTest {
        val ex = executor()
        val r = ex.execute("no.such.intent", emptyMap(), dryRun = false, token = null)
        assertEquals("UNKNOWN_INTENT", r.error)
    }

    @Test
    fun `档位不足结构化拒绝并给开通步骤`() = runTest {
        val ex = executor(probe = FixedProbe(DevicePrivilege.NORMAL_SHELL))
        val r = ex.execute("package.list", emptyMap(), dryRun = false, token = null)
        assertEquals("DEVICE_PERMISSION_REQUIRED", r.error)
        assertTrue(r.hint!!.contains("Shizuku"))
    }

    @Test
    fun `dry_run 返回 diff 与 confirm_token 且通道只收到只读探针`() = runTest {
        val channel = RecordingChannel().apply {
            result = { argv ->
                when (argv.firstOrNull()) {
                    "pm" -> DeviceExecResult(ok = true, stdout = "package:com.target.app", exitCode = 0)
                    "dumpsys" -> DeviceExecResult(
                        ok = true,
                        stdout = "versionName=1.0\nuserId=10234\npkgFlags=[ SYSTEM ]\nfirstInstallTime=2026-01-01",
                        exitCode = 0
                    )
                    else -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
                }
            }
        }
        val audit = RecordingAudit()
        val ex = executor(channel = channel, audit = audit)
        val r = ex.execute(
            "package.uninstall", mapOf("package" to "com.target.app"), dryRun = true, token = null
        )
        assertEquals(true, r.ok)
        assertEquals("dry-run", r.channel)
        assertTrue(r.confirmToken!!.startsWith("v1."))
        assertEquals("true", r.diff?.before?.get("installed"))
        assertEquals("true", r.diff?.before?.get("systemApp"))
        // dry_run 零写侧效应：通道全部请求都是只读探针。
        channel.argv.forEach { argv ->
            assertTrue("dry_run 只允许只读探针：$argv", argv.first() in READONLY_PROBES)
        }
        // 审计：dry_run 记一条 preflight 事件（带 intentId / auditId）。
        assertEquals(1, audit.events.size)
        assertEquals("preflight", audit.events[0].decision)
        assertEquals("package.uninstall", audit.events[0].intentId)
        assertEquals("dcp-fixed01", audit.events[0].auditId)
        assertEquals(true, audit.events[0].dryRun)
    }

    @Test
    fun `不可逆意图无 token 拒绝且文案引导 dry_run`() = runTest {
        val channel = uninstallScripts()
        val ex = executor(channel = channel)
        val r = ex.execute("package.uninstall", mapOf("package" to "com.target.app"), dryRun = false, token = null)
        assertEquals("CONFIRM_TOKEN_REQUIRED", r.error)
        assertTrue(r.hint!!.contains("dry_run"))
    }

    @Test
    fun `带 token 的完整执行链 走 argv 直调并 verify 复探`() = runTest {
        val channel = RecordingChannel().apply {
            result = { argv ->
                when {
                    argv.firstOrNull() == "pm" && argv.contains("uninstall") -> {
                        uninstalled = true
                        DeviceExecResult(ok = true, stdout = "Success", exitCode = 0)
                    }
                    argv.firstOrNull() == "pm" && argv.take(3) == listOf("pm", "list", "packages") ->
                        DeviceExecResult(
                            ok = true,
                            stdout = if (uninstalled) "" else "package:com.target.app",
                            exitCode = 0
                        )
                    argv.firstOrNull() == "pm" ->
                        DeviceExecResult(ok = true, stdout = "", exitCode = 1)
                    argv.firstOrNull() == "dumpsys" ->
                        DeviceExecResult(
                            ok = true,
                            stdout = "versionName=1.0\nuserId=10234\npkgFlags=[ SYSTEM ]",
                            exitCode = 0
                        )
                    argv.firstOrNull() == "stat" -> DeviceExecResult(ok = false, stdout = "", exitCode = 1)
                    else -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
                }
            }
        }
        val gate = GateScript()
        val audit = RecordingAudit()
        val ex = executor(channel = channel, gate = gate, audit = audit)
        // 1) dry_run 拿 token。
        val dry = ex.execute("package.uninstall", mapOf("package" to "com.target.app"), dryRun = true, token = null)
        val token = dry.confirmToken!!
        // 2) 带 token 执行。
        val r = ex.execute("package.uninstall", mapOf("package" to "com.target.app"), dryRun = false, token = token)
        assertEquals(true, r.ok)
        assertEquals("true", r.verify["installed"])
        // 通道看到写 argv：pm uninstall com.target.app（不经 sh -c）。
        val execArgv = channel.argv.last()
        assertEquals(listOf("pm", "uninstall", "com.target.app"), execArgv)
        // 审计链：preflight（dry_run 时）→ approved → executed 三条同 auditId。
        assertEquals(3, audit.events.size)
        assertEquals("preflight", audit.events[0].decision)
        assertEquals("approved", audit.events[1].decision)
        assertEquals("executed", audit.events[2].decision)
        assertTrue(audit.events.all { it.auditId == "dcp-fixed01" })
    }

    @Test
    fun `Stale token 返回刷新 diff 与新 token 不执行写 argv`() = runTest {
        val channel = uninstallScripts()
        val ex = executor(channel = channel)
        val dry = ex.execute("package.uninstall", mapOf("package" to "com.target.app"), dryRun = true, token = null)
        val token = dry.confirmToken!!
        // 状态变化：目标包已不存在 → fresh diff 与签发时不一致。
        channel.result = { argv ->
            when (argv.firstOrNull()) {
                "pm" -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
                "stat" -> DeviceExecResult(ok = false, stdout = "", exitCode = 1)
                else -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
            }
        }
        val writesBefore = channel.argv.count { it.firstOrNull() == "pm" && it.contains("uninstall") && !it.contains("list") }
        val r = ex.execute("package.uninstall", mapOf("package" to "com.target.app"), dryRun = false, token = token)
        assertEquals("STALE_CONFIRM_TOKEN", r.error)
        assertEquals("true", r.dryRun)
        assertTrue(r.confirmToken != null && r.confirmToken != token)
        val writesAfter = channel.argv.count { it.firstOrNull() == "pm" && it.contains("uninstall") && !it.contains("list") }
        assertEquals("Stale 时绝不执行写 argv", writesBefore, writesAfter)
    }

    @Test
    fun `守卫硬拒 SYSTEM_APP 自身不可 BYPASS`() = runTest {
        val channel = uninstallScripts()
        val ex = executor(channel = channel)
        val r = ex.execute("package.uninstall", mapOf("package" to "com.apex.self"), dryRun = true, token = null)
        assertEquals("GUARD_REJECTED", r.error)
        assertTrue(r.hint!!.contains("IS_SELF"))
    }

    @Test
    fun `文件路径越权拒绝`() = runTest {
        val ex = executor()
        val r = ex.execute(
            "file.write",
            mapOf("path" to "/data/data/com.other/x", "content" to "hi"),
            dryRun = true, token = null
        )
        assertEquals("PATH_DENIED", r.error)
        assertTrue(r.hint!!.contains("data"))
    }

    @Test
    fun `软删除与撤销经回收区且返回 undo_token`() = runTest {
        val channel = RecordingChannel().apply {
            result = { argv ->
                when (argv.firstOrNull()) {
                    // 软删后 stat 探针失败 = exists=false（verify 语义）。
                    "stat" -> DeviceExecResult(ok = false, stdout = "", exitCode = 1)
                    "mv" -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
                    "tee" -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
                    "cat" -> DeviceExecResult(ok = true, stdout = "{}", exitCode = 0)
                    "ls" -> DeviceExecResult(ok = true, stdout = "a.txt", exitCode = 0)
                    else -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
                }
            }
        }
        val ex = executor(channel = channel)
        val r = ex.execute(
            "file.delete", mapOf("path" to "/sdcard/a.txt"), dryRun = false, token = null
        )
        assertEquals(true, r.ok)
        assertEquals("dcp-fixed01", r.undoToken)
        // 软删 argv：mv 到回收区（不经 sh -c）。
        assertTrue(channel.argv.any { it.firstOrNull() == "mv" && it.contains("/data/local/tmp/.dcp-trash/dcp-fixed01/a.txt") })
    }

    @Test
    fun `gate 拒绝时 DENIED_BY_USER 且不执行写 argv`() = runTest {
        val channel = uninstallScripts()
        val gate = GateScript(
            rules = listOf(com.apex.agent.core.engine.DeviceGateRule("file.move", com.apex.agent.core.engine.DeviceGateEffect.DENY))
        )
        val audit = RecordingAudit()
        val ex = executor(channel = channel, gate = gate, audit = audit)
        val r = ex.execute(
            "package.uninstall", mapOf("package" to "com.target.app"), dryRun = true, token = null
        )
        assertEquals(true, r.ok)
        // 可逆意图（file.move）走 gate 拒绝分支（DENY 规则优先，BYPASS 不豁免）。
        val r2 = ex.execute(
            "file.move", mapOf("path" to "/sdcard/a", "dest" to "/sdcard/b"), dryRun = false, token = null
        )
        assertEquals("DENIED_BY_USER", r2.error)
        assertEquals(1, audit.events.count { it.decision == "denied_by_user" })
        // 拒绝路径不执行写 argv。
        assertTrue(channel.argv.none { it.firstOrNull() == "mv" })
    }

    private fun uninstallScripts(): RecordingChannel = RecordingChannel().apply {
        result = { argv ->
            when {
                argv.firstOrNull() == "pm" && argv.take(3) == listOf("pm", "list", "packages") ->
                    DeviceExecResult(ok = true, stdout = "package:com.target.app", exitCode = 0)
                argv.firstOrNull() == "dumpsys" ->
                    DeviceExecResult(ok = true, stdout = "versionName=1.0\nuserId=10234\npkgFlags=[ SYSTEM ]", exitCode = 0)
                argv.firstOrNull() == "pm" -> DeviceExecResult(ok = true, stdout = "Success", exitCode = 0)
                argv.firstOrNull() == "stat" -> DeviceExecResult(ok = false, stdout = "", exitCode = 1)
                else -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
            }
        }
    }

    private companion object {
        private val READONLY_PROBES = setOf(
            "pm", "dumpsys", "cmd", "stat", "ls", "cat", "settings", "getprop", "pidof", "id", "df", "getenforce", "md5sum"
        )
    }
}
