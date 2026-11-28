package com.apex.agent.tools

import com.apex.agent.core.tools.device.IntentCatalog
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
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
 * 3. manifest 派生相对历史底线的差异**有明确记录**（新增/未覆盖集合
 *    作为断言值锁定 —— 集合一变即翻红，人工审计后才允许更新期望值）。
 */
class AndroidOnlyCommandsConsistencyTest {

    private fun loadCatalog(): IntentCatalog {
        // 候选覆盖三种工作目录：仓库根（IDE）、模块根（Gradle :app 测试）、
        // 模块根上一级（仓库根兜底）。app 模块测试工作目录 = app/。
        val file = listOf(
            File("app/src/main/assets/device/intents.json"),
            File("../app/src/main/assets/device/intents.json")
        ).firstOrNull { it.isFile } ?: error("intents.json 不存在")
        return IntentCatalog.load(file.readText())
    }

    /**
     * 只测 androidOnlyCommands / isAndroidOnlyCommand 集合语义：宿主环境
     * 与 rootfs 目录用占位值（这两个 API 不触碰它们），对齐
     * ProotCommandSpawnerTest 的夹具形状。
     */
    private fun spawner(manifest: (() -> Set<String>)? = null): ProotCommandSpawner =
        ProotCommandSpawner(
            hostEnvironment = PRootHostEnvironment(
                nativeLibraryDir = "unused",
                baseDir = File("unused"),
                cacheDir = File("unused")
            ),
            rootfsDir = File("unused"),
            isRootfsReady = { false },
            defaultWorkspaceDir = null,
            persistentHomeDir = null,
            manifestAndroidCommands = manifest
        )

    @Test
    fun `生效集合是历史底线的超集（只增不减）`() {
        val spawner = spawner(manifest = { loadCatalog().androidOnlyExecutables() })
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
        val spawner = spawner(manifest = { catalog.androidOnlyExecutables() })
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
        // manifest 相对历史底线的「新增」：file.* / probe.* 族引入的只读与
        // 文件操作可执行名（Android 侧 busybox 语义；预检/软删/回收区依赖）。
        // 集合一变即翻红 —— 人工审计每个新名字（确认它不应跑进 Ubuntu）后
        // 才允许更新本期望值。
        assertEquals(
            "manifest 派生的增量集合变化需人工审计：$additions",
            setOf(
                "cat", "df", "getenforce", "id", "ls", "md5sum",
                "mkdir", "mv", "rm", "stat", "tee"
            ),
            additions
        )
        // manifest 未覆盖的历史名（input/wm/screencap/…）：保留底线成员，
        // 由历史集合兜底回落语义（非 manifest 能力面的命令不强行入表）。
        assertEquals(
            "manifest 未覆盖的历史名集合变化需人工审计（新增成员须说明为什么不能进 intents.json）：$notCoveredByManifest",
            setOf(
                "input", "wm", "screencap", "screenrecord", "service", "logcat",
                "content", "sm", "adb", "fastboot", "uiautomator", "resetprop",
                "magisk", "su", "toybox", "settings-provider", "appops", "atrace"
            ),
            notCoveredByManifest
        )
    }

    @Test
    fun `未注入派生源时仅用历史底线（零回归默认）`() {
        val spawner = spawner()
        assertEquals(
            "默认（无 manifest）= 历史底线，行为与旧版一致",
            ProotCommandSpawner.ANDROID_ONLY_COMMANDS,
            spawner.androidOnlyCommands()
        )
        assertTrue(spawner.isAndroidOnlyCommand("pm list packages"))
        assertTrue(!spawner.isAndroidOnlyCommand("python3 --version"))
    }
}
