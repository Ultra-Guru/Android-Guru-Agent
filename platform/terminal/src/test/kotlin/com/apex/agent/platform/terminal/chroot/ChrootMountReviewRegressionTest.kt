package com.apex.agent.platform.terminal.chroot

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** POSIX shell execution fixtures: no real mount, su or chroot is invoked. */
class ChrootMountReviewRegressionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun linuxOnly() = assumeTrue("POSIX shell integration", System.getProperty("os.name").contains("Linux"))
    private fun state(root: File) = File(root.parentFile, ".t99-chroot-state/${root.name}")
    /** Rootfs skeleton: every bind target the real backend's script can mount. */
    private fun guestRoot(name: String): File = tmp.newFolder(name).also { root ->
        for (target in listOf("proc", "sys", "dev", "root", "workspace")) File(root, target).mkdirs()
    }
    private fun table(name: String) = File(tmp.root, name).apply { writeText("1 0 0:1 / / rw - rootfs fake rw\n") }
    private fun script(root: File, table: File, name: String = "proc", guest: String = "chroot \"\$R\" /bin/false 8>&- 9>&-"): String = listOf(
        "R=${shellQuote(root.absolutePath)}",
        ChrootMountScript.header(root.absolutePath, table.absolutePath),
        ChrootMountScript.mount(name, "mount", "--bind", "/source"),
        ChrootMountScript.releaseOperationsLock,
        guest
    ).joinToString("\n")

    private fun bin(): File = File(tmp.root, "bin").apply {
        mkdirs()
        File(this, "mount").apply {
            writeText("""#!/bin/sh
for arg in "§@"; do target="§arg"; done
# Model the kernel's mountinfo target, which resolves rootfs aliases.
actual="§(cd -P "§(dirname "§target")" && pwd -P)/§(basename "§target")"
id="§(wc -l < "§TABLE")"
printf '%s 1 0:1 / %s rw - bind fake rw\n' "§((id + 1))" "§actual" >> "§TABLE"
printf 'mount %s\n' "§actual" >> "§OPS"
""".trimIndent().replace("§", "$") + "\n")
            setExecutable(true)
        }
        File(this, "umount").apply {
            writeText("""#!/bin/sh
awk -v target="§1" '§5 != target' "§TABLE" > "§TABLE.next" && mv "§TABLE.next" "§TABLE"
printf 'umount %s\n' "§1" >> "§OPS"
""".trimIndent().replace("§", "$") + "\n")
            setExecutable(true)
        }
        File(this, "chroot").apply { writeText("#!/bin/sh\nexit 7\n"); setExecutable(true) }
    }

    private fun start(script: String, binaries: File, table: File, ops: File, gate: File? = null): Process =
        ProcessBuilder("sh", "-c", script).redirectErrorStream(true).apply {
            environment()["PATH"] = "${binaries.absolutePath}:${System.getenv("PATH")}"
            environment()["TABLE"] = table.absolutePath
            environment()["OPS"] = ops.absolutePath
            gate?.let { environment()["GATE"] = it.absolutePath }
        }.start()

    private fun run(script: String, binaries: File, table: File, ops: File): Int {
        val process = start(script, binaries, table, ops)
        assertTrue("script timed out", process.waitFor(10, TimeUnit.SECONDS))
        return process.exitValue()
    }

    @Test fun `rootfs alias matches canonical mountinfo without stacking`() {
        linuxOnly()
        val root = guestRoot("real-rootfs")
        val alias = File(tmp.root, "user-zero-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        val table = table("mi-alias").apply { appendText("2 1 0:1 / ${root.absolutePath}/proc rw - bind fake rw\n") }
        val ops = File(tmp.root, "ops-alias").apply { writeText("") }
        assertEquals(7, run(script(alias, table), bin(), table, ops))
        assertFalse("must reuse canonical existing bind", ops.readText().contains("mount "))
        assertTrue("unowned bind must survive", table.readText().contains("${root.absolutePath}/proc"))
    }

    @Test fun `every guest mount target symlink is rejected before mounting it`() {
        linuxOnly()
        val binaries = bin()
        for (name in listOf("proc", "sys", "dev", "root", "workspace", "sdcard")) {
            val root = guestRoot("guest-$name")
            val outside = tmp.newFolder("outside-$name")
            File(outside, "sentinel").writeText("keep")
            Files.createSymbolicLink(File(root, name).toPath(), outside.toPath())
            val table = table("mi-$name")
            val ops = File(tmp.root, "ops-$name").apply { writeText("") }
            assertEquals("$name must fail closed", 1, run(script(root, table, name), binaries, table, ops))
            assertFalse("$name must not be mounted", ops.readText().contains("mount "))
            assertEquals("keep", File(outside, "sentinel").readText())
        }
    }

    @Test fun `mount state and locks live outside guest rootfs`() {
        linuxOnly()
        val root = guestRoot("guest-state")
        val table = table("mi-state")
        val ops = File(tmp.root, "ops-state").apply { writeText("") }
        assertEquals(7, run(script(root, table), bin(), table, ops))
        assertTrue("host-only state directory", state(root).isDirectory)
        assertTrue(File(state(root), "users.lock").isFile)
        assertTrue(File(state(root), "ops.lock").isFile)
        assertFalse(File(root, ".t99-chroot-mounts").exists())
        assertFalse(File(root, ".t99-chroot-users.lock").exists())
        assertFalse(File(root, ".t99-chroot-ops.lock").exists())
    }

    @Test fun `failed marker write rolls back exactly the new mount`() {
        linuxOnly()
        val root = guestRoot("guest-marker")
        for (dir in listOf(File(root, ".t99-chroot-mounts"), state(root))) {
            File(dir, "proc").mkdirs() // redirect to a directory must fail
        }
        val table = table("mi-marker")
        val ops = File(tmp.root, "ops-marker").apply { writeText("") }
        assertEquals(1, run(script(root, table), bin(), table, ops))
        assertEquals(listOf("mount ${root.absolutePath}/proc", "umount ${root.absolutePath}/proc"), ops.readLines())
        assertFalse(table.readText().contains("${root.absolutePath}/proc"))
    }

    @Test fun `failed marker write cannot unmount a different overmount`() {
        linuxOnly()
        val root = guestRoot("guest-overmount")
        for (dir in listOf(File(root, ".t99-chroot-mounts"), state(root))) File(dir, "proc").mkdirs()
        val table = table("mi-overmount")
        val ops = File(tmp.root, "ops-overmount").apply { writeText("") }
        val binaries = bin()
        File(binaries, "mount").appendText("printf '999 1 0:1 / %s rw - other fake rw\\n' \"\$actual\" >> \"\$TABLE\"\n")
        assertEquals(1, run(script(root, table), binaries, table, ops))
        assertFalse("never detach an unrelated overmount", ops.readLines().any { it.startsWith("umount ") })
        assertTrue(table.readText().contains("999 1 0:1 / ${root.absolutePath}/proc"))
    }

    /**
     * SIGKILL on the su shell must leave the long-lived guest holding the shared
     * users lock, so a later shell cannot detach mounts the guest still uses.
     */
    @Test fun `orphan guest keeps users lock when su shell is killed`() {
        linuxOnly()
        val root = guestRoot("guest-orphan")
        val table = table("mi-orphan")
        val ops = File(tmp.root, "ops-orphan").apply { writeText("") }
        val gate = File(tmp.root, "orphan-fifo")
        val mkfifo = ProcessBuilder("mkfifo", gate.absolutePath).start()
        assertTrue(mkfifo.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, mkfifo.exitValue())
        val binaries = bin()
        File(binaries, "chroot").writeText(
            "#!/bin/sh\nprintf 'READY\\n' >> \"\$OPS\"\nread -r answer < \"\$GATE\"\nexit 0\n"
        )
        val original = start(script(root, table), binaries, table, ops, gate)
        try {
            assertEquals("orphan guest started", "READY", original.inputStream.bufferedReader().readLine())
            original.destroyForcibly()
            assertTrue(original.waitFor(5, TimeUnit.SECONDS))
            // A subsequent short guest must not detach mounts the orphan uses.
            File(binaries, "chroot").writeText("#!/bin/sh\nexit 7\n")
            assertEquals(7, run(script(root, table), binaries, table, ops))
            assertFalse("orphan holds shared lock", ops.readLines().any { it.startsWith("umount ") })
            gate.outputStream().bufferedWriter().use { it.write("exit\n") }
            assertEquals(7, run(script(root, table), binaries, table, ops))
            assertEquals(1, ops.readLines().count { it == "umount ${root.absolutePath}/proc" })
        } finally {
            original.destroyForcibly()
            // Release a still-blocked fake guest if an assertion failed.
            if (!ops.readLines().any { it.startsWith("umount ") }) {
                runCatching { ProcessBuilder("sh", "-c", "printf 'exit\\n' > '${gate.absolutePath}'").start().waitFor(2, TimeUnit.SECONDS) }
            }
        }
    }
}
