package com.apex.agent.core.tools.builtin

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * #354 ⑦ 回归：下载目录总量配额。
 *
 * 旧实现只有**单文件** 500MB 上限。`allow_session` 之后一轮任务可以调
 * 20–50 次 `AppDownloadInstall` —— 每次调用都合法、每次都能写 500MB，
 * 存储被慢慢填满且没有任何提示。这些用例锁定目录级配额：
 *
 * - 超配额时按「最久未用」清旧 APK，给本次下载腾空间；
 * - 清理成功就继续下载（不能因为目录曾经满过就永久拒绝）；
 * - 清不动就明确拒绝，**不写盘**（宿主文件被占用是真实场景）；
 * - 非 `.apk` 文件一律不碰（保守，避免误删宿主其他下载）。
 */
class AppDownloadInstallQuotaTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("apex-quota-test").toFile()
        httpClient = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    /** 稀疏文件：声明大体积但不实际占盘，测试跑得快。 */
    private fun sparseApk(name: String, size: Long, lastModified: Long): File {
        val f = File(dir, name)
        RandomAccessFile(f, "rw").use { it.setLength(size) }
        f.setLastModified(lastModified)
        return f
    }

    private class Installer : ApkInstaller {
        var calls = 0
            private set
        override suspend fun install(apkPath: String, sourceUrl: String?): ApkInstallResult {
            calls++
            return ApkInstallResult(success = true, message = "install triggered")
        }
    }

    private fun enqueueApk(bytes: Int = 8 * 1024) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.android.package-archive")
                .setBody(Buffer().write(ByteArray(bytes) { (it and 0xFF).toByte() }))
        )
    }

    @Test
    fun `超配额时按最久未用清理旧 APK 后继续下载`() = runTest {
        val quota = AppDownloadInstallTool.MAX_DOWNLOAD_DIR_BYTES
        // 两个旧 APK 合计超过配额（各 60%）。
        val old1 = sparseApk("old1.apk", (quota * 6) / 10, 1_000L)
        val old2 = sparseApk("old2.apk", (quota * 6) / 10, 2_000L)
        enqueueApk()
        val installer = Installer()
        val tool = AppDownloadInstallTool(httpClient, dir, installer, ssrfProtectionEnabled = false)

        val result = tool.execute("""{"url": "${server.url("/new.apk")}"}""")

        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertEquals(1, installer.calls)
        // 最久未用的先被清掉；最新的那个留下来。
        assertFalse("old1.apk 应被清理", old1.exists())
        assertTrue("old2.apk 应保留（仍在配额内）", old2.exists())
    }

    @Test
    fun `清理后仍在配额内时不删除任何文件`() = runTest {
        val small = sparseApk("keep.apk", 1024L * 1024, 1_000L)
        enqueueApk()
        val tool = AppDownloadInstallTool(httpClient, dir, Installer(), ssrfProtectionEnabled = false)

        val result = tool.execute("""{"url": "${server.url("/new.apk")}"}""")

        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertTrue("配额内不得清理", small.exists())
    }

    @Test
    fun `清理不动时拒绝下载且不写盘`() = runTest {
        // 目录不可写 → 旧 APK 删不掉（真实场景：文件被系统占用 / 存储只读）。
        val huge = sparseApk("huge.apk", AppDownloadInstallTool.MAX_DOWNLOAD_DIR_BYTES + 1, 1_000L)
        enqueueApk()
        val installer = Installer()
        val tool = AppDownloadInstallTool(httpClient, dir, installer, ssrfProtectionEnabled = false)

        assertTrue("前置：目录需可置为不可写", dir.setWritable(false, false))
        try {
            val result = tool.execute("""{"url": "${server.url("/new.apk")}"}""")

            assertTrue("须拒绝: $result", result.startsWith("Error"))
            assertTrue("拒绝时须说明配额: $result", result.contains("配额"))
            assertTrue("拒绝时须给出下一步: $result", result.contains("手动删除"))
            assertEquals("拒绝时不得调用安装", 0, installer.calls)
        } finally {
            dir.setWritable(true, false)
        }
        // 拒绝路径不得留下本次下载文件；旧文件仍在（没被误删）。
        assertTrue("拒绝时不得写盘", dir.listFiles()!!.none { it.name == "new.apk" })
        assertTrue("删不掉的旧文件应保留", huge.exists())
    }

    @Test
    fun `非 apk 文件不参与清理`() = runTest {
        val quota = AppDownloadInstallTool.MAX_DOWNLOAD_DIR_BYTES
        val notes = File(dir, "notes.txt")
        notes.writeText("user data")
        // 让 apk 总量超配额，但 txt 不应被删。
        sparseApk("a.apk", (quota * 6) / 10, 1_000L)
        sparseApk("b.apk", (quota * 6) / 10, 2_000L)
        enqueueApk()
        val tool = AppDownloadInstallTool(httpClient, dir, Installer(), ssrfProtectionEnabled = false)

        val result = tool.execute("""{"url": "${server.url("/new.apk")}"}""")

        assertTrue("should succeed: $result", result.startsWith("OK"))
        assertTrue("非 apk 文件不得被清理", notes.exists())
    }
}
