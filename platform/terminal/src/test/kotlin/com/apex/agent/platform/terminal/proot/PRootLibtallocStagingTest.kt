package com.apex.agent.platform.terminal.proot

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * PRootLibtallocStaging 回归测试 —— libtalloc.so.2 SONAME staging。
 *
 * 2026-10-24 真机事故（OPPO PLE110 / Android 16）：旧兜底循环只在含
 * libtalloc.so 的目录（nativeLibraryDir）建同目录软链，staging 目录
 * （LD_LIBRARY_PATH 首项、可写性有保证）永远拿不到链接；而
 * nativeLibraryDir 建链在部分真机静默失败（runCatching 吞异常）——
 * git/MCP/marketplace 三条 proot 路径全线 `CANNOT LINK EXECUTABLE
 * libproot.so: library libtalloc.so.2 not found`。用户在 filesDir 手工
 * 建链后 proot 立即恢复（实证根因）。本测试锁定修复后的行为：
 *
 * - LD_LIBRARY_PATH 各目录（含 staging）都有指向真实 libtalloc.so 的链接；
 * - 幂等（二次调用 ALREADY_OK，不重复建链）；
 * - 悬空链接（app 升级后 staging 指向已删除的旧 nativeLibraryDir）删除重建；
 * - 失败可观测（FAILED + 异常详情），不再静默吞掉；
 * - PRootHostEnvironment.prepare 对悬空 staging 链接不再抛
 *   FileAlreadyExistsException（旧实现 File.exists 对悬空链接返回 false
 *   → 跳过 delete → createSymbolicLink 抛异常 → 终端 proot 全断）。
 */
class PRootLibtallocStagingTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── 夹具 ─────────────────────────────────────────────────────────

    /** 构造 nativeLibraryDir（libproot.so 可执行假文件 + libtalloc.so）。 */
    private fun nativeLibDir(tag: String): File =
        File(tmp.root, "native-$tag").apply {
            mkdirs()
            File(this, "libproot.so").apply {
                writeText("x")
                setExecutable(true)
            }
            File(this, "libtalloc.so").writeText("x")
        }

    /** 标准宿主 env（staging 首项 + nativeLibraryDir，与生产 hostEnv 同构）。 */
    private fun hostEnvOf(staging: File, nativeLib: File) =
        mapOf("LD_LIBRARY_PATH" to "${staging.absolutePath}:${nativeLib.absolutePath}")

    private fun libprootPath(nativeLib: File) = nativeLib.resolve("libproot.so").absolutePath

    // ── 测试 ─────────────────────────────────────────────────────────

    @Test
    fun `ensure creates libtalloc so2 symlink in every LD_LIBRARY_PATH dir including staging`() {
        val nativeLib = nativeLibDir("a")
        val staging = File(tmp.root, "files/linux/bin") // staging 目录尚不存在

        val results = PRootLibtallocStaging.ensure(hostEnvOf(staging, nativeLib), libprootPath(nativeLib))

        assertEquals(2, results.size)
        for (r in results) {
            assertNotEquals(
                "dir=${r.dir} must not FAIL", PRootLibtallocStaging.Outcome.FAILED,
                r.outcome, "err=${r.error}",
            )
        }
        // staging 目录被创建且链接有效（旧实现这里永远拿不到链接——根因）
        assertTrue("staging dir must be created", staging.isDirectory)
        val stagingLink = File(staging, "libtalloc.so.2")
        assertTrue("staging link must exist", stagingLink.exists())
        assertTrue("staging link must be a symlink", Files.isSymbolicLink(stagingLink.toPath()))
        val target = Files.readSymbolicLink(stagingLink.toPath()).toString()
        assertTrue("staging link must point to real libtalloc.so: $target", target.endsWith("libtalloc.so"))
        // nativeLibraryDir 同目录链接
        val nativeLink = File(nativeLib, "libtalloc.so.2")
        assertTrue("nativeLibraryDir link must exist", nativeLink.exists())
    }

    @Test
    fun `ensure is idempotent - second call reports ALREADY_OK`() {
        val nativeLib = nativeLibDir("b")
        val staging = File(tmp.root, "files/linux/bin")
        val env = hostEnvOf(staging, nativeLib)

        PRootLibtallocStaging.ensure(env, libprootPath(nativeLib))
        val second = PRootLibtallocStaging.ensure(env, libprootPath(nativeLib))

        assertEquals(2, second.size)
        for (r in second) {
            assertEquals("dir=${r.dir} must be ALREADY_OK", PRootLibtallocStaging.Outcome.ALREADY_OK, r.outcome)
        }
    }

    @Test
    fun `ensure recreates dangling staging link left by app upgrade`() {
        val nativeLib = nativeLibDir("c")
        val staging = File(tmp.root, "files/linux/bin").apply { mkdirs() }
        // 悬空链接：指向已删除的旧 nativeLibraryDir（app 升级场景）
        val stagingLink = File(staging, "libtalloc.so.2")
        Files.createSymbolicLink(
            stagingLink.toPath(),
            File(tmp.root, "old-native/libtalloc.so").toPath(),
        )
        assertFalse("precondition: link must be dangling", stagingLink.exists())
        assertTrue("precondition: link must be a symlink", Files.isSymbolicLink(stagingLink.toPath()))

        val results = PRootLibtallocStaging.ensure(hostEnvOf(staging, nativeLib), libprootPath(nativeLib))

        val stagingResult = results.first { it.dir == staging.absolutePath }
        assertEquals(
            "dangling link must be recreated", PRootLibtallocStaging.Outcome.RECREATED,
            stagingResult.outcome,
        )
        assertTrue("staging link must be valid after recreate", stagingLink.exists())
        val target = Files.readSymbolicLink(stagingLink.toPath()).toString()
        assertTrue("recreated link must point to real libtalloc.so: $target", target.endsWith("libtalloc.so"))
    }

    @Test
    fun `ensure reports FAILED when nativeLibraryDir lacks libtalloc so`() {
        val nativeLib = File(tmp.root, "native-d").apply {
            mkdirs()
            File(this, "libproot.so").apply {
                writeText("x")
                setExecutable(true)
            }
        }
        val staging = File(tmp.root, "files/linux/bin")

        val results = PRootLibtallocStaging.ensure(hostEnvOf(staging, nativeLib), libprootPath(nativeLib))

        assertEquals(2, results.size)
        for (r in results) {
            assertEquals("dir=${r.dir} must FAIL", PRootLibtallocStaging.Outcome.FAILED, r.outcome)
            assertNotNull("FAILED must carry exception detail", r.error)
        }
    }

    @Test
    fun `ensure is no-op when LD_LIBRARY_PATH absent`() {
        val nativeLib = nativeLibDir("e")
        val results = PRootLibtallocStaging.ensure(emptyMap(), libprootPath(nativeLib))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `prepare succeeds with dangling staging link - upgrade scenario`() {
        val nativeLib = nativeLibDir("f")
        val baseDir = File(tmp.root, "files")
        val cacheDir = File(tmp.root, "cache")
        val env = PRootHostEnvironment(nativeLib.absolutePath, baseDir, cacheDir)
        // 模拟 app 升级后的悬空 staging 链接（旧实现 prepare 在此抛
        // FileAlreadyExistsException → 终端 proot 通道全断）
        val staging = File(baseDir, "linux/bin").apply { mkdirs() }
        Files.createSymbolicLink(
            File(staging, "libtalloc.so.2").toPath(),
            File(tmp.root, "old-native/libtalloc.so").toPath(),
        )

        val prepared = env.prepare()
        assertTrue(
            "prepare must succeed with dangling staging link: ${prepared.exceptionOrNull()}",
            prepared.isSuccess,
        )
        val stagingLink = File(staging, "libtalloc.so.2")
        assertTrue("staging link must be valid after prepare", stagingLink.exists())
        assertTrue("prootTmpDir must be created", env.prootTmpDir.isDirectory)
    }
}
