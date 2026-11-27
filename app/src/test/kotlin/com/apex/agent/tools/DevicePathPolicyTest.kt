package com.apex.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══ S06 路径白名单策略测试 ═══
 *
 * 白名单：/sdcard、/storage/emulated/0、/data/local/tmp（+ 注入的 app 私有目录）；
 * 禁区：/system、/vendor、/proc、/sys、其它包 /data/data、回收区本体。
 */
class DevicePathPolicyTest {

    private val policy = DevicePathPolicy(extraAllowedRoots = listOf("/data/user/0/com.apex.agent/files"))

    private fun denied(path: String, write: Boolean = true): String? {
        val d = policy.check(path, write)
        return (d as? DevicePathPolicy.Decision.Deny)?.reason
    }

    @Test
    fun `白名单根与子路径允许`() {
        assertTrue(policy.check("/sdcard", false) is DevicePathPolicy.Decision.Allow)
        assertTrue(policy.check("/sdcard/Download/a.txt", true) is DevicePathPolicy.Decision.Allow)
        assertTrue(policy.check("/storage/emulated/0/DCIM", true) is DevicePathPolicy.Decision.Allow)
        assertTrue(policy.check("/data/local/tmp/x", true) is DevicePathPolicy.Decision.Allow)
        assertTrue(
            policy.check("/data/user/0/com.apex.agent/files/cfg.json", true) is DevicePathPolicy.Decision.Allow
        )
    }

    @Test
    fun `路径穿越被拒绝`() {
        assertTrue(denied("/sdcard/a/../../data/data/com.other/x") != null)
        assertTrue(denied("/sdcard/..") != null)
    }

    @Test
    fun `冗余段被拒绝（防混淆）`() {
        assertTrue(denied("/sdcard//a") != null)
        assertTrue(denied("/sdcard/./a") != null)
    }

    @Test
    fun `系统保护区写入拒绝且文案指出拿不到`() {
        val reason = denied("/system/build.prop")
        assertTrue(reason != null)
        assertTrue(reason!!.contains("系统保护区"))
        assertTrue(denied("/vendor/app/x.apk") != null)
        assertTrue(denied("/proc/self/mem") != null)
        assertTrue(denied("/sys/class/x") != null)
        assertTrue(denied("/dev/null") != null)
    }

    @Test
    fun `其它包的 data 目录拒绝`() {
        val reason = denied("/data/data/com.other/x")
        assertTrue(reason != null)
        assertTrue(reason!!.contains("data"))
    }

    @Test
    fun `白名单外路径拒绝且文案列出允许范围`() {
        val reason = denied("/etc/passwd")
        assertTrue(reason != null)
        assertTrue(reason!!.contains("白名单"))
    }

    @Test
    fun `回收区本体不可作为操作目标`() {
        val reason = denied("/data/local/tmp/.dcp-trash/dcp-abc/a.txt")
        assertTrue(reason != null)
        assertTrue(reason!!.contains("回收区"))
        assertTrue(denied("/data/local/tmp/.dcp-trash") != null)
    }

    @Test
    fun `basename 取末段且空段兜底`() {
        assertEquals("a.txt", policy.basename("/sdcard/Download/a.txt"))
        assertEquals("file", policy.basename("/sdcard/Download/"))
        assertEquals("file", policy.basename("/"))
    }

    @Test
    fun `读与写同一白名单（无读写双标）`() {
        assertTrue(policy.check("/data/data/com.other/x", false) is DevicePathPolicy.Decision.Deny)
        assertTrue(policy.check("/sdcard/x", false) is DevicePathPolicy.Decision.Allow)
    }
}
