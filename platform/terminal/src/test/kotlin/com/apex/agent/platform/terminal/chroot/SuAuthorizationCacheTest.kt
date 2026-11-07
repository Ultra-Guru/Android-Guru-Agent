package com.apex.agent.platform.terminal.chroot

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SuAuthorizationCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun su(name: String, script: String): File = tmp.newFile(name).apply {
        writeText("#!/bin/sh\n$script\n")
        setExecutable(true)
    }

    @Test fun `real probe requires uid zero rather than just exit success`() {
        val fake = su("fake-su", "echo 'uid=10000(app)'; exit 0")
        assertEquals(SuAuthorizationCache.State.DENIED, SuAuthorizationCache().authorize(fake))
    }

    @Test fun `real probe accepts root id with zero exit`() {
        val fake = su("root-su", "echo 'uid=0(root) gid=0(root)'; exit 0")
        assertEquals(SuAuthorizationCache.State.GRANTED, SuAuthorizationCache().authorize(fake))
    }

    @Test fun `successful su banner before id does not become denied`() {
        val fake = su("banner-su", "echo 'manager banner'; echo 'uid=0(root) gid=0(root)'; exit 0")
        assertEquals(SuAuthorizationCache.State.GRANTED, SuAuthorizationCache().authorize(fake))
    }

    @Test fun `real probe leaves spawn failure unknown rather than caching denial`() {
        assertEquals(SuAuthorizationCache.State.UNKNOWN,
            SuAuthorizationCache().authorize(File(tmp.root, "gone")))
    }

    @Test fun `cached result belongs to exact su path`() {
        val a = su("a-su", "echo 'uid=10000(app)'; exit 1")
        val b = su("b-su", "echo 'uid=0(root)'; exit 0")
        val cache = SuAuthorizationCache()
        assertEquals(SuAuthorizationCache.State.DENIED, cache.authorize(a))
        assertEquals(SuAuthorizationCache.State.UNKNOWN, cache.state(b))
        assertEquals(SuAuthorizationCache.State.GRANTED, cache.authorize(b))
    }
}
