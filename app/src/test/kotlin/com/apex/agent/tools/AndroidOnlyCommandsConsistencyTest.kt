package com.apex.agent.tools

import com.apex.agent.core.tools.device.IntentCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ═══ S07 Android 专有命令派生集合一致性测试 ═══
 *
 * 生效集合 = 历史底线（25 名）∪ manifest 派生（intents.json 的 argv[0]）。
 * 锁定契约：
 * 1. 生效集合 ⊇ 历史底线（**只增不减** —— 删历史名 = TerminalExec 回落
 *    语义回归，需人工审计）；
 * 2. manifest 每个 argv[0] 都在生效集合内（新增设备命令只改 JSON）；
 * 3. manifest 派生相对历史底线的差异**有明确记录**（新增/未覆盖集合打印
 *    进断言消息 —— 人工可审）。
 */
class AndroidOnlyCommandsConsistencyTest {

    private fun loadCatalog(): IntentCatalog {
        val file = listOf(
            File("app/src/main/assets/device/intents.json"),
            File("../app/src/main/assets/device/intents.json")
        ).firstOrNull { it.isFile } ?: error("intents.json 不存在")
        return IntentCatalog.load(file.readText())
    }

    @Test
    fun `生效集合是历史底线的超集（只增不减）`() {
        val spawner = ProotCommandSpawner(manifestAndroidCommands = { loadCatalog().androidOnlyExecutables() })
        val effective = spawner.androidOnlyCommands()
        val legacy = ProotCommandSpawner.ANDROID_ONLY_COMMANDS
        val missing = legacy - effective
        assertTrue(
            "生效集合不得删除历史底线成员，缺失：$missing（删除 = 回落语义回归，需人工审计）",
            missing.isEmpty()
        )
    }

    @Test
    fun `manifest 派生的每个可执行名都在生效集合内`() {
        val catalog = loadCatalog()
        val spawner = ProotCommandSpawner(manifestAndroidCommands = { catalog.androidOnlyExecutables() })
        val effective = spawner.androidOnlyCommands()
        catalog.androidOnlyExecutables().forEach { executable ->
            assertTrue(
                "manifest 新增的可执行名 $executable 必须进生效集合（否则该命令会误入 Ubuntu）",
                executable in effective
            )
        }
    }

    @Test
    fun `manifest 派生相对历史底线的差异有明确记录（S07 迁移证据）`() {
        val catalog = loadCatalog()
        val derived = catalog.androidOnlyExecutables()
        val legacy = ProotCommandSpawner.ANDROID_ONLY_COMMANDS
        val additions = derived - legacy
        val notCoveredByManifest = legacy - derived
        // manifest 相对历史底线的「新增」：当前为空集（manifest 首发与底线
        // 同源）；未来往 intents.json 加新命令（如 svc）时此断言会提醒你：
        // 新名字自动进生效集合，无需改这里。
        assertEquals(
            "manifest 派生的增量集合（记录给人工审计，当前应为空）：$additions",
            emptySet<String>(),
            additions
        )
        // manifest 未覆盖的历史名（input/wm/screencap/…）：保留底线成员，
        // 由历史集合兜底回落语义（非 manifest 能力面的命令不强行入表）。
        val expectedUncovered = setOf(
            "input", "wm", "screencap", "screenrecord", "service", "logcat",
            "content", "sm", "adb", "fastboot", "uiautomator", "resetprop",
            "magisk", "su", "toybox", "settings-provider", "appops", "atrace"
        )
        assertEquals(
            "manifest 未覆盖的历史名集合变化需人工审计（新增成员须说明为什么不能进 intents.json）：$notCoveredByManifest",
            expectedUncovered,
            notCoveredByManifest
        )
    }

    @Test
    fun `未注入派生源时仅用历史底线（零回归默认）`() {
        val spawner = ProotCommandSpawner()
        assertEquals(
            "默认（无 manifest）= 历史底线，行为与旧版一致",
            ProotCommandSpawner.ANDROID_ONLY_COMMANDS,
            spawner.androidOnlyCommands()
        )
        assertTrue(spawner.isAndroidOnlyCommand("pm list packages"))
        assertTrue(!spawner.isAndroidOnlyCommand("python3 --version"))
    }
}
