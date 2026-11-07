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
        val root = tmp.newFolder("guest")
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
        assertTrue("other owned mounts were cleaned", File(root, ".t99-chroot-mounts/sys").exists().not())
    }

    @Test fun `reused owned mount is cleaned after guest exit and repeat does not stack`() {
        val root = tmp.newFolder("guest")
        val fixture = tmp.newFile("mountinfo")
        fixture.writeText("1 0 0:1 / / rw - rootfs fake rw\n4 1 0:1 / ${root.absolutePath}/proc rw - proc proc rw\n")
        File(root, ".t99-chroot-mounts/proc").apply { parentFile.mkdirs(); writeText("4\n") }
        val log = tmp.newFile("ops")
        val bin = fakeCommands()
        assertEquals(7, runScript(script(root, fixture), bin, fixture, log))
        assertEquals("1 0 0:1 / / rw - rootfs fake rw\n", fixture.readText())
        assertFalse("first run must reuse proc", log.readText().contains("mount ${root.absolutePath}/proc\n"))
        assertFalse(File(root, ".t99-chroot-mounts/proc").exists())
        assertEquals(7, runScript(script(root, fixture), bin, fixture, log))
        assertEquals("one new proc mount", 1, log.readLines().count { it == "mount ${root.absolutePath}/proc" })
        assertEquals("one new proc detach", 2, log.readLines().count { it == "umount ${root.absolutePath}/proc" })
    }

    @Test fun `mountinfo failure fails closed without attempting a bind`() {
        val root = tmp.newFolder("guest")
        val fixture = tmp.newFile("mountinfo")
        fixture.writeText("corrupt mount table\n")
        val log = tmp.newFile("ops")
        val rc = runScript(script(root, fixture), fakeCommands(), fixture, log)
        assertEquals(1, rc)
        assertFalse(log.readText().contains("mount "))
    }

    @Test fun `mountinfo path escapes whitespace and backslashes`() {
        val header = ChrootMountScript.header("/data/guest root\\test")
        assertTrue(header.contains("MI_R='/data/guest\\040root\\134test'"))
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
