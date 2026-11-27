package com.apex.agent.tools

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
 * ═══ S04 Preflight / DryRun / 确认令牌测试 ═══
 *
 * 手写 fake [DeviceChannel]（脚本化探针输出，无 mock 框架）+ 假钟注入；
 * 覆盖：diff 解析（dumpsys / pm list / 设备管理器 / 桌面 / stat）、
 * 守卫 flag（IS_SELF / SYSTEM_APP / DEVICE_ADMIN / IS_LAUNCHER / parseIncomplete）、
 * 令牌绑定（Valid / Stale / Rejected / 过期 / 篡改）。
 */
class DevicePreflightTest {

    /** 脚本化通道：按 argv 首词匹配预设输出；未命中按只读探针失败处理。 */
    private class FakeChannel : DeviceChannel {
        val requests = mutableListOf<List<String>>()
        val scripts = mutableMapOf<String, (List<String>) -> DeviceExecResult>()

        override suspend fun execute(
            req: DeviceExecRequest,
            isCancelled: () -> Boolean
        ): DeviceExecResult {
            requests += req.argv.toList()
            val handler = scripts[req.argv.firstOrNull() ?: ""]
                ?: return DeviceExecResult(ok = true, stdout = "", exitCode = 0)
            return handler(req.argv.toList())
        }
    }

    private fun realCatalog(): IntentCatalog {
        val file = listOf(
            File("app/src/main/assets/device/intents.json"),
            File("../app/src/main/assets/device/intents.json")
        ).firstOrNull { it.isFile } ?: error("intents.json 不存在")
        return IntentCatalog.load(file.readText())
    }

    private fun uninstallProbeScripts(): FakeChannel =
        FakeChannel().apply {
            scripts["pm"] = { argv ->
                when {
                    argv.take(3) == listOf("pm", "list", "packages") ->
                        DeviceExecResult(ok = true, stdout = "package:com.target.app", exitCode = 0)
                    argv.take(2) == listOf("pm", "path") ->
                        DeviceExecResult(ok = true, stdout = "package:/data/app/com.target.app/base.apk", exitCode = 0)
                    else -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
                }
            }
            scripts["dumpsys"] = { argv ->
                when {
                    argv == listOf("dumpsys", "package", "com.target.app") ->
                        DeviceExecResult(
                            ok = true,
                            stdout = DUMPSYS_PACKAGE_OUTPUT,
                            exitCode = 0
                        )
                    argv == listOf("dumpsys", "device_policy") ->
                        DeviceExecResult(ok = true, stdout = DEVICE_POLICY_OUTPUT, exitCode = 0)
                    else -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
                }
            }
            scripts["cmd"] = { argv ->
                when {
                    argv == listOf("cmd", "shortcut", "get-default-launcher") ->
                        DeviceExecResult(ok = true, stdout = LAUNCHER_OUTPUT, exitCode = 0)
                    else -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
                }
            }
        }

    @Test
    fun `probe 解析 dumpsys 并产出 SYSTEM_APP flag 与 before 字段`() = runTest {
        val catalog = realCatalog()
        val channel = uninstallProbeScripts()
        val preflight = DevicePreflight(
            channel = channel,
            catalog = catalog,
            clock = { 1_000L },
            sign = { "sig($it)" },
            selfPackage = "com.apex.self"
        )
        val intent = catalog.byId("package.uninstall")!!
        val diff = preflight.probe(intent, mapOf("package" to "com.target.app")).getOrThrow()

        assertEquals("1.2.3", diff.before["versionName"])
        assertEquals("true", diff.before["installed"])
        assertEquals("true", diff.before["systemApp"])
        assertTrue(GuardFlag.SYSTEM_APP in diff.flags)
        assertTrue("指纹来自注入的 sign", diff.fingerprint.startsWith("sig("))
        // dry_run 零副作用：channel 只收到只读探针，没有任何写 argv。
        channel.requests.forEach { argv ->
            val probe = argv.firstOrNull() ?: ""
            assertTrue(
                "dry_run 通道只允许只读探针，实际收到：$argv",
                probe in setOf("pm", "dumpsys", "cmd", "stat", "ls", "cat", "settings", "getprop", "pidof")
            )
        }
    }

    @Test
    fun `IS_SELF 守卫命中自应用包名`() = runTest {
        val catalog = realCatalog()
        val channel = uninstallProbeScripts()
        val preflight = DevicePreflight(
            channel, catalog, clock = { 1L }, sign = { "s" },
            selfPackage = "com.target.app"
        )
        val diff = preflight.probe(
            catalog.byId("package.uninstall")!!, mapOf("package" to "com.target.app")
        ).getOrThrow()
        assertTrue(GuardFlag.IS_SELF in diff.flags)
    }

    @Test
    fun `DEVICE_ADMIN 与 IS_LAUNCHER 从专用探针解析`() = runTest {
        val catalog = realCatalog()
        val channel = FakeChannel().apply {
            scripts["pm"] = { DeviceExecResult(ok = true, stdout = "package:com.target.app", exitCode = 0) }
            scripts["dumpsys"] = { argv ->
                when {
                    argv == listOf("dumpsys", "package", "com.target.app") ->
                        DeviceExecResult(ok = true, stdout = DUMPSYS_PACKAGE_OUTPUT, exitCode = 0)
                    argv == listOf("dumpsys", "device_policy") ->
                        // com.target.app 是设备管理器。
                        DeviceExecResult(
                            ok = true,
                            stdout = "Admins:\n  ComponentInfo{com.target.app/com.target.app.Admin}", exitCode = 0
                        )
                    else -> DeviceExecResult(ok = true, stdout = "", exitCode = 0)
                }
            }
            scripts["cmd"] = { argv ->
                if (argv == listOf("cmd", "shortcut", "get-default-launcher")) {
                    DeviceExecResult(
                        ok = true,
                        stdout = "Launcher: ComponentInfo{com.target.app/com.target.app.Home}", exitCode = 0
                    )
                } else DeviceExecResult(ok = true, stdout = "", exitCode = 0)
            }
        }
        val preflight = DevicePreflight(channel, catalog, clock = { 1L }, sign = { "s" }, selfPackage = "com.apex.self")
        val diff = preflight.probe(
            catalog.byId("package.uninstall")!!, mapOf("package" to "com.target.app")
        ).getOrThrow()
        assertTrue(GuardFlag.DEVICE_ADMIN in diff.flags)
        assertTrue(GuardFlag.IS_LAUNCHER in diff.flags)
        assertEquals("com.target.app", diff.before["defaultLauncher"])
    }

    @Test
    fun `字段缺失时 parseIncomplete 如实标注不编造`() = runTest {
        val catalog = realCatalog()
        val channel = FakeChannel().apply {
            // dumpsys 输出为空 → 全部字段 null + parseIncomplete。
            scripts["dumpsys"] = { DeviceExecResult(ok = true, stdout = "", exitCode = 0) }
            scripts["pm"] = { DeviceExecResult(ok = true, stdout = "package:com.target.app", exitCode = 0) }
            scripts["cmd"] = { DeviceExecResult(ok = true, stdout = "", exitCode = 0) }
        }
        val preflight = DevicePreflight(channel, catalog, clock = { 1L }, sign = { "s" }, selfPackage = "x")
        val diff = preflight.probe(
            catalog.byId("package.uninstall")!!, mapOf("package" to "com.target.app")
        ).getOrThrow()
        assertEquals("true", diff.before["parseIncomplete"])
        assertEquals(null, diff.before["versionName"])
        // 「没解析出来」不等于「不是系统应用」：SYSTEM_APP flag 不置位。
        assertTrue(GuardFlag.SYSTEM_APP !in diff.flags)
    }

    @Test
    fun `confirmToken 绑定 diff 且 Valid 通过`() = runTest {
        val catalog = realCatalog()
        val preflight = DevicePreflight(
            uninstallProbeScripts(), catalog,
            clock = { 1_000L }, sign = { "sig:$it" }, selfPackage = "com.apex.self"
        )
        val intent = catalog.byId("package.uninstall")!!
        val diff = preflight.probe(intent, mapOf("package" to "com.target.app")).getOrThrow()
        val token = preflight.confirmToken(diff)
        assertTrue(token.startsWith("v1.1000."))
        // 同一 diff 重放 token：窗口内 → Valid。
        assertTrue(preflight.confirm(token, diff) is ConfirmResult.Valid)
    }

    @Test
    fun `diff 变化时 confirm 返回 Stale 并携带 freshDiff`() = runTest {
        val catalog = realCatalog()
        val channel = uninstallProbeScripts()
        val preflight = DevicePreflight(
            channel, catalog, clock = { 1_000L }, sign = { "sig:$it" }, selfPackage = "com.apex.self"
        )
        val intent = catalog.byId("package.uninstall")!!
        val original = preflight.probe(intent, mapOf("package" to "com.target.app")).getOrThrow()
        val token = preflight.confirmToken(original)
        // 状态变化：目标已卸载（pm list 不再列出）。
        channel.scripts["pm"] = { DeviceExecResult(ok = true, stdout = "", exitCode = 0) }
        val fresh = preflight.probe(intent, mapOf("package" to "com.target.app")).getOrThrow()
        val result = preflight.confirm(token, fresh)
        assertTrue("diff 变化应 Stale，实际：$result", result is ConfirmResult.Stale)
        assertEquals(fresh, (result as ConfirmResult.Stale).freshDiff)
    }

    @Test
    fun `令牌过期按窗口拒绝（假钟驱动过期分支）`() = runTest {
        val catalog = realCatalog()
        var now = 1_000L
        val preflight = DevicePreflight(
            uninstallProbeScripts(), catalog,
            clock = { now }, sign = { "sig:$it" }, selfPackage = "com.apex.self"
        )
        val intent = catalog.byId("package.uninstall")!!
        val diff = preflight.probe(intent, mapOf("package" to "com.target.app")).getOrThrow()
        val token = preflight.confirmToken(diff)
        // 窗口 5 分钟：now 推到 300_001ms 之后 → 过期拒绝。
        now += catalog.snapshot.defaults.confirmWindowMs + 1
        val result = preflight.confirm(token, diff)
        assertTrue(result is ConfirmResult.Rejected)
        assertTrue((result as ConfirmResult.Rejected).reason.contains("过期"))
    }

    @Test
    fun `篡改令牌被拒绝`() = runTest {
        val catalog = realCatalog()
        val preflight = DevicePreflight(
            uninstallProbeScripts(), catalog,
            clock = { 1_000L }, sign = { "sig:$it" }, selfPackage = "com.apex.self"
        )
        val intent = catalog.byId("package.uninstall")!!
        val diff = preflight.probe(intent, mapOf("package" to "com.target.app")).getOrThrow()
        // 用另一个 sign 伪造 token。
        val forged = "v1.1000." + DevicePreflight(
            uninstallProbeScripts(), catalog,
            clock = { 1_000L }, sign = { "evil:$it" }, selfPackage = "com.apex.self"
        ).confirmToken(diff).split('.', limit = 3)[2]
        val result = preflight.confirm("v1.1000.$forged", diff)
        assertTrue(result is ConfirmResult.Stale || result is ConfirmResult.Rejected)
        // 格式坏 / 时间戳坏 → Rejected。
        assertTrue(preflight.confirm("garbage", diff) is ConfirmResult.Rejected)
        assertTrue(preflight.confirm("v2.1000.x", diff) is ConfirmResult.Rejected)
        assertTrue(preflight.confirm("v1.abc.x", diff) is ConfirmResult.Rejected)
    }

    @Test
    fun `verify 复探产出 after 字段`() = runTest {
        val catalog = realCatalog()
        val channel = uninstallProbeScripts()
        val preflight = DevicePreflight(
            channel, catalog, clock = { 1_000L }, sign = { "s" }, selfPackage = "com.apex.self"
        )
        val intent = catalog.byId("package.uninstall")!!
        val after = preflight.verify(intent, mapOf("package" to "com.target.app"))
        assertEquals("true", after["installed"])
        assertEquals("com.target.app", after["defaultLauncher"])
    }

    private companion object {
        private val DUMPSYS_PACKAGE_OUTPUT = """
            Package [com.target.app] (abc):
              userId=10234
              pkg=Package{abc com.target.app}
              versionName=1.2.3
              versionCode=45 minSdk=26 targetSdk=34
              firstInstallTime=2026-01-01 10:00:00
              lastUpdateTime=2026-02-01 10:00:00
              pkgFlags=[ SYSTEM HAS_CODE ]
              privateFlags=[ PRIVATE_FLAG_ACTIVITIES ]
        """.trimIndent()

        private val DEVICE_POLICY_OUTPUT = """
            Device Policy Manager State:
              Admins:
                ComponentInfo{com.other.admin/com.other.admin.DeviceAdmin}
        """.trimIndent()

        private const val LAUNCHER_OUTPUT =
            "Launcher: ComponentInfo{com.android.launcher3/com.android.launcher3.Launcher}"
    }
}
