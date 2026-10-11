package com.apex.agent.platform.terminal.profile

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T87：GuestShellProfile（Android mksh rc）内容不变式。
 */
class GuestShellProfileTest {

    @Test
    fun `prompt has user host cwd context`() {
        val rc = GuestShellProfile.generate()
        assertTrue(rc.contains("PS1="))
        assertTrue(rc.contains("${'$'}{USER}@${'$'}{HOSTNAME}"))
    }

    @Test
    fun `no bash-only ps1 escapes (mksh width safety)`() {
        val rc = GuestShellProfile.generate()
        // \[ \] 是 bash readline 专属 —— mksh 不识别会让行编辑光标错位
        assertFalse(rc.contains("\\["))
        assertFalse(rc.contains("\\]"))
        // 提示符不携带 ANSI 转义
        assertFalse(rc.contains("PS1=.*\\e".toRegex()))
    }

    @Test
    fun `command discovery helpers exist`() {
        val rc = GuestShellProfile.generate()
        assertTrue(rc.contains("cmds()"))
        assertTrue(rc.contains("cmdf()"))
        assertTrue(rc.contains("guru_help"))
    }

    @Test
    fun `aliases only use toybox-safe forms`() {
        val rc = GuestShellProfile.generate()
        assertTrue(rc.contains("alias ll='ls -alF'"))
        // 不给本地 shell 塞 GNU 专属开关
        assertFalse(rc.contains("ls --color"))
    }

    @Test
    fun `history is guarded by writable home probe`() {
        val rc = GuestShellProfile.generate()
        assertTrue(rc.contains("HISTFILE"))
        assertTrue(rc.contains("touch \"${'$'}HOME/.h\""))
    }

    @Test
    fun `rc ends with true (source never fails)`() {
        val rc = GuestShellProfile.generate()
        assertTrue(rc.trimEnd().endsWith("true"))
    }

    @Test
    fun `shellEnv carries home env term colorterm`() {
        val env = GuestShellProfile.shellEnv("/data/home", "/data/home/.gurc")
        assertEquals("/data/home", env["HOME"]!!)
        assertEquals("/data/home/.gurc", env["ENV"]!!)
        assertEquals("xterm-256color", env["TERM"]!!)
        assertEquals("truecolor", env["COLORTERM"]!!)
    }

    @Test
    fun `host tool guards redirect to on-device equivalents`() {
        // T95：真机日志里用户连跑 adb shell getprop / systemd-analyze /
        // lsb_release —— mksh 无 command_not_found 钩子，只能裸报 not found。
        // rc 必须为这类宿主工具给出口径 + 设备侧正确替代。
        val rc = GuestShellProfile.generate()
        assertTrue(rc.contains("adb()"))
        assertTrue(rc.contains("systemd-analyze()"))
        assertTrue(rc.contains("systemctl()"))
        assertTrue(rc.contains("lsb_release()"))
        // 引导必须给出设备侧正确替代（getprop 家族），而不是只说「不可用」
        assertTrue(rc.contains("getprop ro.build.version.release"))
        assertTrue(rc.contains("getprop <name>"))
    }

    private fun assertEquals(expected: String, actual: String) =
        org.junit.Assert.assertEquals(expected, actual)
}

/**
 * T87：UbuntuBashProfile（guest bashrc）内容与幂等注入不变式。
 */
class UbuntuBashProfileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `managed block has interactive guard`() {
        val block = UbuntuBashProfile.managedBlock()
        assertTrue(block.contains("case \$- in *i*)"))
    }

    @Test
    fun `ps1 colors are width-marked for readline`() {
        val content = UbuntuBashProfile.generate()
        // bash PS1 的 ANSI 序列必须包在 \[ \] 内（长行编辑光标不错位）
        assertTrue(content.contains("\\[\\e[1;32m\\]"))
        assertTrue(content.contains("\\[\\e[0m\\]"))
    }

    @Test
    fun `command not found handler suggests installable packages`() {
        val content = UbuntuBashProfile.generate()
        assertTrue(content.contains("command_not_found_handle"))
        assertTrue(content.contains("apt install python3"))
        assertTrue(content.contains("apt install build-essential"))
    }

    @Test
    fun `apt-fix repair helper present`() {
        val content = UbuntuBashProfile.generate()
        assertTrue(content.contains("apt-fix()"))
        assertTrue(content.contains("dpkg --configure -a"))
    }

    @Test
    fun `ensure seeds full profile into fresh home`() {
        val home = tmp.newFolder("home")
        val action = UbuntuBashProfile.ensure(home)
        val bashrc = File(home, ".bashrc")
        assertTrue(bashrc.isFile)
        assertTrue(action.startsWith("bashrc: seeded"))
        assertTrue(bashrc.readText().contains(UbuntuBashProfile.MANAGED_BEGIN))
    }

    @Test
    fun `ensure appends to existing user bashrc without destroying it`() {
        val home = tmp.newFolder("home2")
        File(home, ".bashrc").writeText("# my custom rc\nexport FOO=1\n")
        val action = UbuntuBashProfile.ensure(home)
        assertTrue(action.startsWith("bashrc: appended"))
        val content = File(home, ".bashrc").readText()
        assertTrue("用户内容必须原样保留在前", content.startsWith("# my custom rc"))
        assertTrue(content.contains(UbuntuBashProfile.MANAGED_BEGIN))
        assertTrue(content.contains(UbuntuBashProfile.MANAGED_END))
    }

    @Test
    fun `ensure is idempotent - second run does not duplicate block`() {
        val home = tmp.newFolder("home3")
        UbuntuBashProfile.ensure(home)
        val first = File(home, ".bashrc").readText()
        val action = UbuntuBashProfile.ensure(home)
        assertTrue(action.startsWith("bashrc: managed block already present"))
        assertEqualsK(first, File(home, ".bashrc").readText())
    }

    @Test
    fun `managed block survives guest seeding markers`() {
        val gen = UbuntuBashProfile.generate()
        assertNotNull(gen)
        assertTrue(gen.contains(UbuntuBashProfile.MANAGED_BEGIN))
        assertTrue(gen.contains(UbuntuBashProfile.MANAGED_END))
    }

    private fun assertEqualsK(a: String, b: String) = org.junit.Assert.assertEquals(a, b)
}
