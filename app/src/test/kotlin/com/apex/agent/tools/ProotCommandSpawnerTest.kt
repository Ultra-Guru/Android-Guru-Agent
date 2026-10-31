package com.apex.agent.tools

import com.apex.agent.platform.TerminalExec.SpawnRequest
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

/**
 * T92（#255 权限链审计）—— TerminalExec 路由决策锁。
 *
 * ProotCommandSpawner 是 `TerminalExec` 的第一跳路由（PRoot Ubuntu 优先，
 * 未就绪/Android 专有命令回落宿主三级链）。此前路由逻辑（ANDROID_ONLY_COMMANDS
 * 判定 / host→guest cwd 映射 / rootfs 解析）零单测 —— 路由错一步模型就会
 * 在错误的执行环境里拿到 "not found"。这里把三条路由轴固化为回归锁：
 *
 * 1. [ProotCommandSpawner.isAndroidOnlyCommand] —— 首 token 判定（引号/路径/多行变体）；
 * 2. [ProotCommandSpawner.planGuestCwd] —— cwd 映射表（类 KDoc 契约逐条对齐）；
 * 3. [ProotCommandSpawner.resolveUbuntuRoute] —— rootfs 就绪门禁 + current 标记解析。
 *
 * 纯 JVM：PRootHostEnvironment 用临时目录构造，不 exec 任何 proot 二进制。
 */
class ProotCommandSpawnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newSpawner(
        rootfsReady: Boolean = true,
        defaultWorkspaceDir: File? = null,
        persistentHomeDir: File? = null,
        rootfsDir: File = tmp.newFolder("rootfs")
    ): ProotCommandSpawner = ProotCommandSpawner(
        hostEnvironment = PRootHostEnvironment(
            nativeLibraryDir = tmp.newFolder("nativelib").absolutePath,
            baseDir = tmp.newFolder("base"),
            cacheDir = tmp.newFolder("cache")
        ),
        rootfsDir = rootfsDir,
        isRootfsReady = { rootfsReady },
        defaultWorkspaceDir = defaultWorkspaceDir,
        persistentHomeDir = persistentHomeDir,
        fallback = PrivilegedCommandSpawner()
    )

    private fun request(command: String, cwd: String? = null) =
        SpawnRequest(command = command, cwd = cwd, env = emptyMap())

    // ── ① Android 专有命令判定 ───────────────────────────────────────

    @Test
    fun `am pm dumpsys input 等 Android 专有命令命中`() {
        val spawner = newSpawner()
        listOf(
            "am start -n com.example/.Main",
            "pm list packages -3",
            "dumpsys battery",
            "input tap 100 200",
            "settings get system screen_brightness",
            "getprop ro.product.model",
            "logcat -d -t 100",
            "screencap -p /sdcard/s.png"
        ).forEach { cmd ->
            assertTrue("应判定为 Android 专有: $cmd", spawner.isAndroidOnlyCommand(cmd))
        }
    }

    @Test
    fun `通用 Linux 命令不命中`() {
        val spawner = newSpawner()
        listOf(
            "python3 --version",
            "gcc main.c -o main",
            "apt list --upgradable",
            "git status",
            "npm install",
            "ls -la",
            "cat /etc/os-release"
        ).forEach { cmd ->
            assertFalse("不应判定为 Android 专有: $cmd", spawner.isAndroidOnlyCommand(cmd))
        }
    }

    @Test
    fun `绝对路径形式的首 token 取文件名判定`() {
        val spawner = newSpawner()
        assertTrue(spawner.isAndroidOnlyCommand("/system/bin/pm list packages"))
        assertFalse(spawner.isAndroidOnlyCommand("/usr/bin/python3 --version"))
    }

    @Test
    fun `引号包裹的首 token 剥引号后判定`() {
        val spawner = newSpawner()
        assertTrue(spawner.isAndroidOnlyCommand("\"am\" start -n com.example/.Main"))
        assertTrue(spawner.isAndroidOnlyCommand("'pm' list packages"))
    }

    @Test
    fun `多行命令只看首行首 token`() {
        val spawner = newSpawner()
        // am 开头（首行）→ 宿主；即使第二行是 Linux 命令
        assertTrue(spawner.isAndroidOnlyCommand("am start -n a/b\npython3 -c 'print(1)'"))
        // python3 开头 → Ubuntu 通道
        assertFalse(spawner.isAndroidOnlyCommand("python3 -c 'print(1)'\nam start -n a/b"))
    }

    @Test
    fun `sudo 前缀的 pm 不按 Android 专有判定 集合保守语义`() {
        val spawner = newSpawner()
        // sudo 不在集合内 —— 首 token 是 sudo；保守集合只看首 token（文档语义）
        assertFalse(spawner.isAndroidOnlyCommand("sudo pm list packages"))
    }

    // ── ② host → guest cwd 映射 ─────────────────────────────────────

    @Test
    fun `cwd 为 null 且默认工作区存在 映射到 guest workspace`() {
        val ws = tmp.newFolder("workspaces").resolve("default").apply { mkdirs() }
        val home = tmp.newFolder("home")
        val spawner = newSpawner(defaultWorkspaceDir = ws, persistentHomeDir = home)
        val plan = spawner.planGuestCwd(null)
        assertEquals("/workspace", plan.guestCwd)
        assertEquals("workspace:/", plan.workingDirectory.value)
        assertSame(ws, plan.workspaceHostDir)
    }

    @Test
    fun `cwd 为 null 且无默认工作区 映射到 guest root home`() {
        val spawner = newSpawner(defaultWorkspaceDir = null, persistentHomeDir = null)
        val plan = spawner.planGuestCwd(null)
        assertEquals("/root", plan.guestCwd)
        assertEquals("/root", plan.workingDirectory.value)
        assertNull(plan.workspaceHostDir)
    }

    @Test
    fun `持久化 home 前缀映射到 guest root 子路径`() {
        val home = tmp.newFolder("linux").resolve("home").apply { mkdirs() }
        val spawner = newSpawner(persistentHomeDir = home)
        val plan = spawner.planGuestCwd(File(home, "projects/app").apply { mkdirs() }.absolutePath)
        assertEquals("/root/projects/app", plan.guestCwd)
        assertEquals("/root/projects/app", plan.workingDirectory.value)
    }

    @Test
    fun `home 前缀的根 本体映射到 guest root`() {
        val home = tmp.newFolder("linux").resolve("home").apply { mkdirs() }
        val spawner = newSpawner(persistentHomeDir = home)
        val plan = spawner.planGuestCwd(home.absolutePath)
        assertEquals("/root", plan.guestCwd)
    }

    @Test
    fun `工作区父目录下的非默认工作区 bind 该工作区为 workspace`() {
        val wsRoot = tmp.newFolder("ws-root")
        val default = wsRoot.resolve("default").apply { mkdirs() }
        val project2 = wsRoot.resolve("project2").apply { mkdirs() }
        val spawner = newSpawner(defaultWorkspaceDir = default)
        val plan = spawner.planGuestCwd(project2.absolutePath)
        assertEquals("/workspace", plan.guestCwd)
        // 路径相等断言（实现经 File(cwd) 重建实例，引用不保证相同）
        assertEquals(
            "cwd 指向 project2 时应 bind project2 而非 default",
            project2.absolutePath,
            plan.workspaceHostDir?.absolutePath
        )
    }

    @Test
    fun `其它真实存在的 host 目录 同路径 bind 语义不失效`() {
        val spawner = newSpawner()
        val dir = tmp.newFolder("external")
        val plan = spawner.planGuestCwd(dir.absolutePath)
        assertEquals(dir.absolutePath, plan.guestCwd)
        assertEquals(1, plan.extraBinds.size)
        assertEquals(dir.absolutePath, plan.extraBinds[0].hostPath.value)
        assertEquals(dir.absolutePath, plan.extraBinds[0].guestPath)
    }

    @Test
    fun `不存在的 cwd 与 ProcessBuilder 语义一致抛 FileNotFoundException`() {
        val spawner = newSpawner()
        val ghost = File(tmp.root, "ghost-dir")
        try {
            spawner.planGuestCwd(ghost.absolutePath)
            throw AssertionError("应抛 FileNotFoundException")
        } catch (expected: FileNotFoundException) {
            assertTrue(expected.message!!.contains("cwd does not exist"))
        }
    }

    // ── ③ Ubuntu 路由门禁 ────────────────────────────────────────────

    @Test
    fun `rootfs 未就绪 回落宿主链`() {
        val spawner = newSpawner(rootfsReady = false)
        assertNull(spawner.resolveUbuntuRoute(request("python3 --version")))
    }

    @Test
    fun `Android 专有命令即使 rootfs 就绪也回落宿主链`() {
        val spawner = newSpawner(rootfsReady = true)
        assertNull(spawner.resolveUbuntuRoute(request("am start -n a/b")))
    }

    @Test
    fun `rootfs 就绪且通用命令 经 current 标记解析出版本目录`() {
        val rootfsDir = tmp.newFolder("ubuntu-rootfs")
        val versionDir = rootfsDir.resolve("versions/ubuntu-24.04-arm64").apply { mkdirs() }
        File(rootfsDir, "current").writeText("ubuntu-24.04-arm64")
        val spawner = newSpawner(rootfsReady = true, rootfsDir = rootfsDir)
        val route = spawner.resolveUbuntuRoute(request("python3 --version"))
        assertNotNull(route)
        assertEquals(versionDir.absolutePath, route!!.rootfs.absolutePath)
    }

    @Test
    fun `current 标记指向不存在的版本 诚实回落宿主链`() {
        val rootfsDir = tmp.newFolder("ubuntu-rootfs-bad")
        File(rootfsDir, "current").writeText("ghost-artifact")
        val spawner = newSpawner(rootfsReady = true, rootfsDir = rootfsDir)
        assertNull(spawner.resolveUbuntuRoute(request("python3 --version")))
    }

    @Test
    fun `裸布局 无 current 标记但有 bin 目录 仍可路由`() {
        val rootfsDir = tmp.newFolder("ubuntu-rootfs-bare")
        rootfsDir.resolve("bin").mkdirs()
        val spawner = newSpawner(rootfsReady = true, rootfsDir = rootfsDir)
        val route = spawner.resolveUbuntuRoute(request("gcc --version"))
        assertNotNull(route)
        assertEquals(rootfsDir.absolutePath, route!!.rootfs.absolutePath)
    }
}
