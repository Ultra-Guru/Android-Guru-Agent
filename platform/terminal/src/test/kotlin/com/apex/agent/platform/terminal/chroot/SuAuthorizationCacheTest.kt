package com.apex.agent.platform.terminal.chroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test fun `availability read does not wait for another create authorization prompt`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val su = su("waiting-su", "exit 0")
        val cache = SuAuthorizationCache(probe = {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            SuAuthorizationCache.State.GRANTED
        })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<SuAuthorizationCache.State> { cache.authorize(su) }
            assertTrue("first prompt did not start", entered.await(2, TimeUnit.SECONDS))
            val availability = pool.submit<SuAuthorizationCache.State> { cache.state(su) }
            assertEquals("availability must remain cheap and UNKNOWN while prompt is pending",
                SuAuthorizationCache.State.UNKNOWN, availability.get(1, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(SuAuthorizationCache.State.GRANTED, first.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test(timeout = 7_000)
    fun `large manager output cannot block bounded authorization probe`() {
        val fake = su("chatty-su", "echo 'uid=0(root)'; i=0; while [ \"\$i\" -lt 4096 ]; do printf 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'; i=\$((i+1)); done; exit 0")
        assertEquals(SuAuthorizationCache.State.GRANTED, SuAuthorizationCache().authorize(fake))
    }

    @Test(timeout = 4_000)
    fun `inherited stdout from a child cannot extend a completed authorization probe`() {
        val fake = su("child-su", "sleep 8 & echo 'uid=0(root)'; exit 0")
        assertEquals(SuAuthorizationCache.State.GRANTED, SuAuthorizationCache().authorize(fake))
    }
}
