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
    /** Mirrors ChrootMountScript: canonical root, sibling STATE_BASE, leaf name. */
    private fun state(root: File): File {
        val canonical = root.canonicalPath
        return File(File(canonical).parentFile, ".t99-chroot-state/${File(canonical).name}")
    }

    /**
     * Rootfs skeleton without any bind targets: the symlink test must replace
     * them and every other fixture starts from a clean root.
     */
    private fun guestRoot(name: String, vararg targets: String): File = tmp.newFolder(name).also { root ->
        for (target in targets) File(root, target).mkdirs()
    }
    private fun table(name: String) = File(tmp.root, name).apply { writeText("1 0 0:1 / / rw - rootfs fake rw\n") }
    /**
     * The fake guest inherits fd 9 exactly like the real chroot line (only fd 8
     * is closed), so it holds the shared users lock for its whole lifetime.
     */
    private fun script(root: File, table: File, name: String = "proc", guest: String = "chroot \"\$R\" /bin/false 8>&-"): String = listOf(
        "R=${shellQuote(root.absolutePath)}",
        ChrootMountScript.header(root.absolutePath, table.absolutePath),
        // The real backend mkdir's each bind target; mirror that in the fixture.
        "mkdir -p \"\$R/$name\" || exit 1",
        ChrootMountScript.mount(name, "mount", "--bind", "/source"),
        ChrootMountScript.releaseOperationsLock,
        guest
    ).joinToString("\n")
    private fun target(name: String, root: File): File = File(root, name).apply { mkdirs() }

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
        val output = process.inputStream.bufferedReader().readText()
        if (process.exitValue() !in listOf(1, 7)) {
            throw AssertionError("unexpected rc=${process.exitValue()}: $output")
        }
        return process.exitValue()
    }

    /** Guest rc=7 means every guard passed; any guard exit fails with its output. */
    private fun runGuest(script: String, binaries: File, table: File, ops: File): Int {
        val process = start(script, binaries, table, ops)
        assertTrue("script timed out", process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        return if (process.exitValue() == 7) 7 else {
            throw AssertionError("guard failed rc=${process.exitValue()}: $output")
        }
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
        val root = guestRoot("guest-state", "proc")
        val table = table("mi-state")
        val ops = File(tmp.root, "ops-state").apply { writeText("") }
        assertEquals("guest must start", 7, run(script(root, table), bin(), table, ops))
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
        target("proc", root)
        for (dir in listOf(File(root, ".t99-chroot-mounts"), state(root))) {
            File(dir, "proc").mkdirs() // redirect to a directory must fail
        }
        val table = table("mi-marker")
        val ops = File(tmp.root, "ops-marker").apply { writeText("") }
        assertEquals("marker redirect must fail closed", 1, run(script(root, table), bin(), table, ops))
        assertEquals(listOf("mount ${root.absolutePath}/proc", "umount ${root.absolutePath}/proc"), ops.readLines())
        assertFalse(table.readText().contains("${root.absolutePath}/proc"))
    }

    @Test fun `failed marker write cannot unmount a different overmount`() {
        linuxOnly()
        val root = guestRoot("guest-overmount")
        target("proc", root)
        for (dir in listOf(File(root, ".t99-chroot-mounts"), state(root))) File(dir, "proc").mkdirs()
        val table = table("mi-overmount")
        val ops = File(tmp.root, "ops-overmount").apply { writeText("") }
        val binaries = bin()
        // Race model: our bind (ID 2) lands, then a foreign overmount (ID 999)
        // appears on the same target before the marker write fails. mount_id()
        // returns the last ID, so a correct rollback must compare IDs and refuse.
        File(binaries, "mount").appendText(
            "printf '999 1 0:1 / %s rw - other fake rw\\n' \"\$actual\" >> \"\$TABLE\"\n"
        )
        assertEquals("overmount race must fail closed", 1, run(script(root, table), binaries, table, ops))
        assertFalse("never detach an unrelated overmount", ops.readLines().any { it.startsWith("umount ") })
        assertTrue(table.readText().contains("999 1 0:1 / ${root.absolutePath}/proc"))
    }

    /**
     * SIGKILL on the su shell must leave the long-lived guest holding the shared
     * users lock, so a later shell cannot detach mounts the guest still uses.
     */
    @Test fun `orphan guest keeps users lock when su shell is killed`() {
        linuxOnly()
        val root = guestRoot("guest-orphan", "proc")
        val table = table("mi-orphan")
        val ops = File(tmp.root, "ops-orphan").apply { writeText("") }
        val gate = File(tmp.root, "orphan-fifo")
        val mkfifo = ProcessBuilder("mkfifo", gate.absolutePath).start()
        assertTrue(mkfifo.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, mkfifo.exitValue())
        val binaries = bin()
        File(binaries, "chroot").writeText(
            // READY on stdout so the test's readLine() sees it; the ops log
            // records that the guest (and its inherited fd 9) is live.
            "#!/bin/sh\nprintf 'READY\\n'\nprintf 'GUEST_UP\\n' >> \"\$OPS\"\nread -r answer < \"\$GATE\"\nexit 0\n"
        )
        val original = start(script(root, table), binaries, table, ops, gate)
        try {
            assertEquals("orphan guest started", "READY", original.inputStream.bufferedReader().readLine())
            // SIGKILL the su shell; the orphan guest keeps its inherited fd 9.
            original.destroyForcibly()
            assertTrue(original.waitFor(5, TimeUnit.SECONDS))
            // A subsequent short guest must not detach mounts the orphan uses.
            File(binaries, "chroot").writeText("#!/bin/sh\nexit 7\n")
            assertEquals(7, run(script(root, table), binaries, table, ops))
            assertFalse(
                "orphan still holds the shared lock: nothing may be detached",
                ops.readLines().any { it.startsWith("umount ") }
            )
            // Signal the orphan to exit; the kernel closes its inherited fd 9.
            runCatching {
                gate.outputStream().bufferedWriter().use { it.write("exit\n") }
            }
            assertEquals(7, run(script(root, table), binaries, table, ops))
            assertEquals(
                "exactly one detach after the orphan is gone",
                1, ops.readLines().count { it == "umount ${root.absolutePath}/proc" }
            )
        } finally {
            // Always unblock a possibly-still-blocked fake guest so a failed
            // assertion cannot wedge the whole suite on a FIFO write.
            original.destroyForcibly()
            runCatching {
                ProcessBuilder("sh", "-c", "printf 'exit\\n' > '${gate.absolutePath}'")
                    .start().waitFor(3, TimeUnit.SECONDS)
            }
        }
    }
}
