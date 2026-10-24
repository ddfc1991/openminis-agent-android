package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.4] issue #2 Bug 2 (cloudflare listed twice: via=key + via=chrome, one token). */
class ConnDedupeTest {
    private fun E(id: String, via: String, acct: String = "", secret: String? = null) = ConnDedupe.E(id, via, acct, secret)

    @Test fun cloudflareKeyPlusChromeFolds() {
        val p = ConnDedupe.plan(listOf(E("cloudflare", "key", "", "cf-token-1"), E("cloudflare#2", "chrome", "Chrome 已登录", null))) { "cloudflare#2" }
        assertEquals(1, p.size)
        assertEquals("cloudflare#2", p[0].keep); assertEquals("cloudflare", p[0].drop); assertTrue(p[0].moveSecret)
    }

    @Test fun sameTokenTwiceFolds() {
        val p = ConnDedupe.plan(listOf(E("cloudflare", "key", "", "t"), E("cloudflare#2", "key", "", "t"))) { null }
        assertEquals(listOf("cloudflare#2"), p.map { it.drop })
        assertEquals(false, p[0].moveSecret)
    }

    @Test fun differentKeysStay() {
        assertTrue(ConnDedupe.plan(listOf(E("openai", "key", "", "a"), E("openai#2", "key", "", "b"))) { null }.isEmpty())
    }

    @Test fun differentIdentitiesStay() {
        assertTrue(ConnDedupe.plan(listOf(E("github", "key", "alice", "a"), E("github#2", "web", "bob", null))) { null }.isEmpty())
    }

    @Test fun twoWebSessionsStay() {
        assertTrue(ConnDedupe.plan(listOf(E("gmail", "web", "网页登录（自动发现）"), E("gmail#2", "web", "网页登录（自动发现）"))) { null }.isEmpty())
    }

    @Test fun singleEntriesUntouched() {
        assertTrue(ConnDedupe.plan(listOf(E("notion", "web"), E("x", "chrome"), E("okx", "web"))) { null }.isEmpty())
    }

    @Test fun namedIdentityCarriedOver() {
        val p = ConnDedupe.plan(listOf(E("cloudflare", "chrome", "Chrome 已登录"), E("cloudflare#2", "key", "me@example.com", "t"))) { "cloudflare" }
        assertEquals("me@example.com", p[0].account)
    }
}
