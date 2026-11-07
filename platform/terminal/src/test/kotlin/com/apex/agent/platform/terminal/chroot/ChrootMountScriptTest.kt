package com.apex.agent.platform.terminal.chroot

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Runs the actual generated POSIX shell protocol with fake mount/chroot binaries. */
class ChrootMountScriptTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `preexisting unowned mount is neither stacked nor detached and guest files remain`() {
        val root = guestRoot()
        val fixture = tmp.newFile("mountinfo")
        fixture.writeText("1 0 0:1 / / rw - rootfs fake rw\n2 1 0:1 / ${root.absolutePath}/proc rw - proc proc rw\n")
        val guestFile = File(root, "proc/guest-file").apply { parentFile.mkdirs(); writeText("keep") }
        val log = tmp.newFile("ops")
        val bin = fakeCommands()
        val rc = runScript(script(root, fixture), bin, fixture, log)
        assertEquals(7, rc) // fake guest fails; EXIT trap must preserve its status
        assertFalse(log.readText().contains("${root.absolutePath}/proc\n"))
        assertTrue(fixture.readText().contains("${root.absolutePath}/proc"))
        assertEquals("keep", guestFile.readText())
        val state = File(root.parentFile, ".t99-chroot-state/${root.name}")
        assertTrue("other owned mounts were cleaned", File(state, "sys").exists().not())
    }

    @Test fun `reused owned mount is cleaned after guest exit and repeat does not stack`() {
        val root = guestRoot()
        val fixture = tmp.newFile("mountinfo")
        fixture.writeText("1 0 0:1 / / rw - rootfs fake rw\n4 1 0:1 / ${root.absolutePath}/proc rw - proc proc rw\n")
        val state = File(root.parentFile, ".t99-chroot-state/${root.name}")
        File(state, "proc").apply { parentFile.mkdirs(); writeText("4\n") }
        val log = tmp.newFile("ops")
        val bin = fakeCommands()
        assertEquals(7, runScript(script(root, fixture), bin, fixture, log))
        assertEquals("1 0 0:1 / / rw - rootfs fake rw\n", fixture.readText())
        assertFalse("first run must reuse proc", log.readLines().any { it == "mount ${root.absolutePath}/proc" })
        assertFalse(File(state, "proc").exists())
        assertEquals(7, runScript(script(root, fixture), bin, fixture, log))
        assertEquals("one new proc mount", 1, log.readLines().count { it == "mount ${root.absolutePath}/proc" })
        assertEquals("one new proc detach", 2, log.readLines().count { it == "umount ${root.absolutePath}/proc" })
    }

    @Test fun `concurrent guests share mounts and only the last exit detaches them`() {
        org.junit.Assume.assumeTrue("POSIX FIFO integration", System.getProperty("os.name").contains("Linux"))
        val root = guestRoot()
        val fixture = tmp.newFile("mountinfo").apply { writeText("1 0 0:1 / / rw - rootfs fake rw\n") }
        val log = tmp.newFile("ops")
        val bin = fakeCommands()
        File(bin, "chroot").writeText("#!/bin/sh\nprintf 'READY\\n'\nread -r answer < \"\$GUEST_FIFO\"\nexit 0\n")
        val gateA = fifo("guest-a")
        val gateB = fifo("guest-b")
        fun start(gate: File): Process = ProcessBuilder("sh", "-c", script(root, fixture))
            .redirectErrorStream(true).apply {
                environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}"
                environment()["MI_FIXTURE"] = fixture.absolutePath
                environment()["OP_LOG"] = log.absolutePath
                environment()["GUEST_FIFO"] = gate.absolutePath
            }.start()
        val a = start(gateA)
        var b: Process? = null
        try {
            assertEquals("READY", a.inputStream.bufferedReader().readLine())
            val second = start(gateB)
            b = second
            assertEquals("READY", second.inputStream.bufferedReader().readLine())
            assertEquals(3, log.readLines().count { it.startsWith("mount ") })
            gateA.outputStream().bufferedWriter().use { it.write("go\n") }
            assertTrue(a.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, a.exitValue())
            assertFalse("first exit must not detach shared mounts", log.readLines().any { it.startsWith("umount ") })
            gateB.outputStream().bufferedWriter().use { it.write("go\n") }
            assertTrue(second.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, second.exitValue())
            assertEquals(3, log.readLines().count { it.startsWith("umount ") })
            assertEquals("1 0 0:1 / / rw - rootfs fake rw\n", fixture.readText())
        } finally {
            a.destroyForcibly()
            b?.destroyForcibly()
        }
    }

    /** Fake rootfs with every target the real script binds. */
    private fun guestRoot(): File = tmp.newFolder("guest").also { root ->
        for (target in listOf("proc", "sys", "dev", "root", "workspace")) File(root, target).mkdirs()
    }

    private fun fifo(name: String): File = File(tmp.root, name).also { file ->
        val p = ProcessBuilder("mkfifo", file.absolutePath).start()
        assertTrue(p.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue())
    }

    @Test fun `mountinfo failure fails closed without attempting a bind`() {
        val root = guestRoot()
        val fixture = tmp.newFile("mountinfo")
        fixture.writeText("corrupt mount table\n")
        val log = tmp.newFile("ops")
        val rc = runScript(script(root, fixture), fakeCommands(), fixture, log)
        assertEquals(1, rc)
        assertFalse(log.readText().contains("mount "))
    }

    @Test fun `mountinfo path escapes whitespace and backslashes`() {
        val root = tmp.newFolder("root with space\\name")
        val header = ChrootMountScript.header(root.absolutePath)
        val expected = root.canonicalPath
            .replace("\\", "\\134").replace(" ", "\\040")
        assertTrue("canonical escaped target in header: $header", header.contains("MI_R='$expected'"))
    }

    private fun script(root: File, fixture: File): String = listOf(
        "R=${shellQuote(root.absolutePath)}",
        ChrootMountScript.header(root.absolutePath, fixture.absolutePath),
        ChrootMountScript.mount("proc", "mount", "-t", "proc", "proc"),
        ChrootMountScript.mount("sys", "mount", "-t", "sysfs", "sys"),
        ChrootMountScript.mount("dev", "mount", "--bind", "/dev"),
        ChrootMountScript.releaseOperationsLock,
        "chroot \"\$R\" /bin/false 8>&- 9>&-"
    ).joinToString("\n")

    private fun fakeCommands(): File = tmp.newFolder("bin").also { bin ->
        File(bin, "mount").apply {
            writeText(listOf(
                "#!/bin/sh",
                "for arg in \"\$@\"; do target=\"\$arg\"; done",
                "id=\"\$(wc -l < \"\$MI_FIXTURE\")\"",
                "printf '%s 1 0:1 / %s rw - bind fake rw\\n' \"\$((id+1))\" \"\$target\" >> \"\$MI_FIXTURE\"",
                "printf 'mount %s\\n' \"\$target\" >> \"\$OP_LOG\""
            ).joinToString("\n", postfix = "\n"))
            setExecutable(true)
        }
        File(bin, "umount").apply {
            writeText(listOf(
                "#!/bin/sh",
                "awk -v target=\"\$1\" '\$5 != target' \"\$MI_FIXTURE\" > \"\$MI_FIXTURE.next\" && mv \"\$MI_FIXTURE.next\" \"\$MI_FIXTURE\"",
                "printf 'umount %s\\n' \"\$1\" >> \"\$OP_LOG\""
            ).joinToString("\n", postfix = "\n"))
            setExecutable(true)
        }
        File(bin, "chroot").apply { writeText("#!/bin/sh\nexit 7\n"); setExecutable(true) }
    }

    private fun runScript(script: String, bin: File, fixture: File, log: File): Int {
        val builder = ProcessBuilder("sh", "-c", script).redirectErrorStream(true)
        builder.environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}"
        builder.environment()["MI_FIXTURE"] = fixture.absolutePath
        builder.environment()["OP_LOG"] = log.absolutePath
        val process = builder.start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("mount script timed out: ${process.inputStream.bufferedReader().readText()}")
        }
        val output = process.inputStream.bufferedReader().readText()
        if (process.exitValue() !in listOf(1, 7)) throw AssertionError("unexpected rc: $output")
        return process.exitValue()
    }
}
