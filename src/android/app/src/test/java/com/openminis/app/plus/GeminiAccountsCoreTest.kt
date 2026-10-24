package com.openminis.app.plus

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAccountsCoreTest {
    private val C = GeminiAccountsCore

    @Test fun authUrlCarriesPkceConsentAndHint() {
        val u = C.authUrl("st1", "chal", "a@gmail.com")
        assertTrue(u.startsWith(C.AUTH_URL + "?"))
        assertTrue(u.contains("code_challenge=chal")); assertTrue(u.contains("code_challenge_method=S256"))
        assertTrue(u.contains("access_type=offline")); assertTrue(u.contains("prompt=consent%20select_account"))
        assertTrue(u.contains("login_hint=a%40gmail.com")); assertTrue(u.contains("state=st1"))
        assertTrue(u.contains("redirect_uri=" + java.net.URLEncoder.encode(C.REDIRECT, "UTF-8")))
        assertFalse(C.authUrl("s", "c").contains("login_hint"))
    }

    @Test fun redirectAndPastedCode() {
        val r = C.parseRedirect(C.REDIRECT + "?state=x&code=4%2Fabc_DEF-123456789012345678&scope=a")
        assertNotNull(r); assertEquals("4/abc_DEF-123456789012345678", r!!.code); assertEquals("x", r.state)
        assertEquals("access_denied", C.parseRedirect(C.REDIRECT + "?error=access_denied")!!.error)
        assertNull(C.parseRedirect("https://example.com/?code=1"))
        assertNull(C.parseRedirect(C.REDIRECT + "?foo=1"))
        assertEquals("4/0AbCdEfGhIjKlMnOpQrStUv", C.pastedCode("  code: 4/0AbCdEfGhIjKlMnOpQrStUv \n"))
        assertNull(C.pastedCode("hello")); assertNull(C.pastedCode(null))
    }

    @Test fun tokensParseKeepRefreshAndRoundTrip() {
        val now = 1_000_000L
        val t = C.parseTokens("""{"access_token":"ya29.x","expires_in":3600,"refresh_token":"1//r"}""", now)!!
        assertEquals("1//r", t.refresh); assertEquals(now + 3540_000L, t.expiresAt)
        val t2 = C.parseTokens("""{"access_token":"ya29.y","expires_in":3600}""", now, "1//old")!!
        assertEquals("1//old", t2.refresh)
        assertNull(C.parseTokens("""{"error":"invalid_grant"}""", now))
        assertNull(C.parseTokens("not json", now))
        val back = C.tokenFromJson(C.tokenJson(t))!!
        assertEquals(t.access, back.access); assertEquals(t.refresh, back.refresh); assertEquals(t.expiresAt, back.expiresAt)
        assertTrue(C.needsRefresh(t, t.expiresAt - 60_000L)); assertFalse(C.needsRefresh(t, now))
    }

    @Test fun idTokenEmail() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"me@gmail.com"}""".toByteArray())
        assertEquals("me@gmail.com", C.emailFromIdToken("h.$payload.s"))
        assertNull(C.emailFromIdToken("garbage")); assertNull(C.emailFromIdToken(null))
    }

    @Test fun forms() {
        val f = C.tokenForm("4/c", "ver")
        assertEquals("authorization_code", f["grant_type"]); assertEquals("ver", f["code_verifier"]); assertEquals(C.REDIRECT, f["redirect_uri"])
        assertTrue(C.CLIENT_SECRET.startsWith("GOCSPX-"))
        assertEquals("refresh_token", C.refreshForm("1//r")["grant_type"])
    }

    @Test fun loadAndOnboard() {
        val s = C.parseLoad("""{"currentTier":{"id":"free-tier","name":"Gemini Code Assist for individuals"},"cloudaicompanionProject":"proj-1"}""")
        assertEquals("proj-1", s.project); assertEquals("free-tier", s.tierId); assertNull(s.blocked)
        assertEquals("免费版", C.tierLabel(s.tierId, s.tierName))
        val n = C.parseLoad("""{"allowedTiers":[{"id":"legacy-tier"},{"id":"free-tier","isDefault":true}]}""")
        assertNull(n.tierId); assertEquals("free-tier", n.onboardTier)
        val b = C.parseLoad("""{"ineligibleTiers":[{"reasonMessage":"Not eligible"}]}""")
        assertNotNull(b.blocked); assertTrue(b.blocked!!.contains("Not eligible"))
        assertEquals("free-tier", C.parseLoad("{bad").onboardTier)
        assertEquals(true to "p9", C.parseOnboard("""{"done":true,"response":{"cloudaicompanionProject":{"id":"p9"}}}"""))
        assertEquals(false to null, C.parseOnboard("""{"name":"op"}"""))
        assertFalse(C.onboardBody("free-tier", "p").has("cloudaicompanionProject"))
        assertTrue(C.onboardBody("standard-tier", "p").has("cloudaicompanionProject"))
        assertEquals("未开通", C.tierLabel(null, null))
    }

    @Test fun wrapUnwrap() {
        val req = JSONObject().put("contents", org.json.JSONArray())
        val w = C.wrap("gemini-2.5-pro", "p1", req, "id1")
        assertEquals("gemini-2.5-pro", w.getString("model")); assertEquals("p1", w.getString("project")); assertEquals("id1", w.getString("user_prompt_id"))
        assertFalse(C.wrap("m", null, req, "i").has("project"))
        val inner = JSONObject().put("candidates", org.json.JSONArray())
        assertTrue(C.unwrap(JSONObject().put("response", inner)).has("candidates"))
        assertTrue(C.unwrap(inner).has("candidates"))
    }

    @Test fun classifyErrors() {
        val q = C.classify(429, """{"error":{"code":429,"message":"Quota exceeded","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}""")
        assertEquals(GeminiAccountsCore.ErrKind.QUOTA, q.kind); assertEquals(37_000L, q.retryAfterMs); assertTrue(q.rotate)
        assertEquals(60_000L, C.classify(429, "").retryAfterMs)
        assertEquals(GeminiAccountsCore.ErrKind.AUTH, C.classify(401, """{"error":{"status":"UNAUTHENTICATED"}}""").kind)
        assertEquals(GeminiAccountsCore.ErrKind.LOCATION, C.classify(400, """{"error":{"message":"User location is not supported for the API use."}}""").kind)
        assertFalse(C.classify(400, """{"error":{"message":"User location is not supported"}}""").rotate)
        assertEquals(GeminiAccountsCore.ErrKind.PROJECT, C.classify(403, """{"error":{"message":"Permission denied on resource project x"}}""").kind)
        assertEquals(GeminiAccountsCore.ErrKind.MODEL, C.classify(404, "{}").kind)
        assertEquals(GeminiAccountsCore.ErrKind.TRANSIENT, C.classify(503, "oops").kind)
        val o = C.classify(400, """{"error":{"message":"bad field"}}""")
        assertEquals(GeminiAccountsCore.ErrKind.OTHER, o.kind); assertTrue(o.zh.contains("bad field"))
    }

    @Test fun quota() {
        val q = C.parseQuota("""{"buckets":[{"modelId":"gemini-2.5-pro","tokenType":"REQUESTS","remainingFraction":0.8},
            {"modelId":"gemini-2.5-flash","remainingFraction":1},{"modelId":"gemini-2.5-pro","tokenType":"TOKENS","remainingFraction":0.1}]}""")
        assertEquals(2, q.size)
        assertEquals("Pro 剩 80% · Flash 剩 100%", C.quotaLine(q))
        assertEquals("", C.quotaLine(emptyList())); assertTrue(C.parseQuota("x").isEmpty())
    }

    @Test fun poolOrderRoundRobinThenCooling() {
        val now = 10_000L
        fun s(id: String, cool: Long = 0, signed: Boolean = true, login: Boolean = false, blocked: Boolean = false, off: Boolean = false) =
            GeminiAccountsCore.Slot(id, "$id@gmail.com", signed, login, cool, blocked, off)
        val slots = listOf(s("a"), s("b", cool = now + 50), s("c"), s("d", signed = false), s("e", blocked = true), s("f", cool = now + 10), s("g", off = true))
        assertEquals(listOf("a", "c", "f", "b"), C.order(slots, now, 0).map { it.id })
        assertEquals(listOf("c", "a", "f", "b"), C.order(slots, now, 1).map { it.id })
        assertEquals(listOf("a", "c", "f", "b"), C.order(slots, now, -2).map { it.id })
        assertTrue(C.order(listOf(s("x", signed = false)), now, 0).isEmpty())
        assertEquals("2 个账号可用 · 1 个要登录 · 2 个额度用完", C.summary(slots, now))
        assertEquals("还没有谷歌账号", C.summary(emptyList(), now))
    }

    @Test fun maskEmail() {
        assertEquals("de***@gmail.com", C.mask("deningyang740@gmail.com"))
        assertEquals("nomail", C.mask("nomail"))
    }
}
