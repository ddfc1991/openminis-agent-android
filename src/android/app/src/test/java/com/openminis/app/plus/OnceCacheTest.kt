package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OnceCacheTest {
    /** 8 threads ask at once: one build, everyone gets the same instance. */
    @Test fun buildsOncePerKey() {
        val c = OnceCache<Any>(); val builds = AtomicInteger(); val go = CountDownLatch(1); val got = arrayOfNulls<Any>(8)
        val ts = (0 until 8).map { i -> Thread { go.await(); got[i] = c.get("oauth_prefs") { builds.incrementAndGet(); Thread.sleep(200); Any() } } }
        ts.forEach { it.start() }; go.countDown(); ts.forEach { it.join(5000) }
        assertEquals(1, builds.get()); got.forEach { assertSame(got[0], it) }
    }

    /**
     * The 2026-10-21 ANR shape: a background thread is stuck in a slow build (Keystore taking seconds) while the main
     * thread asks for a store. Old code re-built on every call and so waited on the same lock; now an open store is
     * returned at once, and a slow build of another key does not block it.
     */
    @Test fun hitNeverWaitsOnASlowBuild() {
        val c = OnceCache<String>()
        c.get("oauth_prefs") { "open" }
        val inBuild = CountDownLatch(1); val release = CountDownLatch(1)
        val slow = Thread { c.get("provider_secrets") { inBuild.countDown(); release.await(10, TimeUnit.SECONDS); "late" } }.apply { start() }
        assertTrue(inBuild.await(2, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        val v = c.get("oauth_prefs") { error("must not rebuild") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("open", v); assertTrue("hit took $ms ms", ms < 50)
        release.countDown(); slow.join(5000); assertEquals("late", c.peek("provider_secrets"))
    }

    /** A failed build is not remembered: the next call builds again. */
    @Test fun failureNotCached() {
        val c = OnceCache<String>(); var n = 0
        runCatching { c.get("k") { n++; throw IllegalStateException("keystore busy") } }
        assertEquals("ok", c.get("k") { n++; "ok" }); assertEquals(2, n)
    }
}
