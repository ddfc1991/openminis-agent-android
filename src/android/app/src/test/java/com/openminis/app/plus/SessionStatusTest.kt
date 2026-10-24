package com.openminis.app.plus

import com.openminis.app.plus.SessionStatus.Probe
import com.openminis.app.plus.SessionStatus.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.1] status classification (no 失效 / 异常 on a blip) + key tombstones (a deleted key is not re-adopted). */
class SessionStatusTest {
    @Test fun webBlipsAreUnknown() {
        assertEquals(Verdict.UNKNOWN, SessionStatus.web(Probe.NET_ERROR, true, 0))
        assertEquals(Verdict.UNKNOWN, SessionStatus.web(Probe.LOADING, true, 3))
        assertEquals(Verdict.UNKNOWN, SessionStatus.web(Probe.VERIFY, false, 0))
        // one guest-looking page with the session cookie still there is not a sign-out
        assertEquals(Verdict.UNKNOWN, SessionStatus.web(Probe.GUEST, true, 0))
        assertEquals(Verdict.UNKNOWN, SessionStatus.web(Probe.LOGIN, true, 0))
    }
    @Test fun confirmedSignOut() {
        assertEquals(Verdict.EXPIRED, SessionStatus.web(Probe.LOGIN, true, 1))
        assertEquals(Verdict.EXPIRED, SessionStatus.web(Probe.LOGIN, false, 0))
        assertEquals(Verdict.EXPIRED, SessionStatus.web(Probe.GUEST, false, 0))
        assertEquals(Verdict.OK, SessionStatus.web(Probe.SIGNED_IN, false, 5))
    }
    @Test fun keys() {
        assertEquals(SessionStatus.Key.BAD, SessionStatus.key(401))
        assertEquals(SessionStatus.Key.BAD, SessionStatus.key(403))
        assertEquals(SessionStatus.Key.WARN, SessionStatus.key(0))
        assertEquals(SessionStatus.Key.WARN, SessionStatus.key(502))
        assertEquals(SessionStatus.Key.WARN, SessionStatus.key(429))
        assertEquals(SessionStatus.Key.OK, SessionStatus.key(200))
        assertTrue(SessionStatus.nextCheckMs(1) < SessionStatus.nextCheckMs(3))
        assertTrue(SessionStatus.MIN_PROBE_MS >= 6 * 3_600_000L)
    }
    @Test fun deletedKeyIsNotReadopted() {
        val bad = "xai-BADKEY-0000000000000000006vzw"; val good = "xai-GOODKEY-00000000000000000dkr8"
        var tombs = ""
        // 模型组 holds both; 账号连接 holds only the good one after the user deleted the bad one
        tombs = KeyTombstone.add(tombs, bad)
        assertEquals(emptyList<String>(), KeyTombstone.toAdopt(listOf(bad, good), listOf(good), KeyTombstone.parse(tombs)))
        // without the tombstone it came back (the 38.9 bug)
        assertEquals(listOf(bad), KeyTombstone.toAdopt(listOf(bad, good), listOf(good), emptySet()))
        // typing it again on purpose clears the mark
        tombs = KeyTombstone.clear(tombs, bad)
        assertFalse(KeyTombstone.mark(bad) in KeyTombstone.parse(tombs))
        assertEquals(24, KeyTombstone.mark(good).length)
        assertFalse(KeyTombstone.add("", bad).contains("BADKEY"))   // the key itself is never stored
    }

    // ---------------- [Mines-38.9.3] ----------------
    @Test fun neverSignedInIsNotExpired() {
        assertEquals(Verdict.UNKNOWN, SessionStatus.web(Probe.LOGIN, false, 3, everSignedIn = false))
        assertEquals(Verdict.UNKNOWN, SessionStatus.web(Probe.GUEST, false, 0, everSignedIn = false))
        assertEquals(Verdict.EXPIRED, SessionStatus.web(Probe.LOGIN, false, 0, everSignedIn = true))
    }
    @Test fun keyServiceIsJudgedByItsKeyOnly() {
        // OpenRouter: a key with a 429 / low balance note is 要注意, never 登录失效; untested is grey
        assertEquals(SessionStatus.Pill.UNCHECKED, SessionStatus.keyPill(null))
        assertEquals(SessionStatus.Pill.OK, SessionStatus.keyPill("OK"))
        assertEquals(SessionStatus.Pill.WARN, SessionStatus.keyPill("WARN"))
        assertEquals(SessionStatus.Pill.BAD, SessionStatus.keyPill("BAD"))
        assertEquals("未检查", SessionStatus.Pill.UNCHECKED.label)
    }
    @Test fun loginPillNeedsAConfirmedSignOut() {
        assertEquals(SessionStatus.Pill.OK, SessionStatus.loginPill(IdentityCore.LINKED, false, false))
        assertEquals(SessionStatus.Pill.UNCHECKED, SessionStatus.loginPill(IdentityCore.EXPIRED, false, false))   // stale 过期了
        assertEquals(SessionStatus.Pill.WARN, SessionStatus.loginPill(IdentityCore.EXPIRED, false, true))
        assertEquals(SessionStatus.Pill.UNCHECKED, SessionStatus.loginPill(IdentityCore.USER, false, false))      // recorded, never verified
        assertEquals(SessionStatus.Pill.UNCHECKED, SessionStatus.loginPill(IdentityCore.UNCHECKED, false, true))
        assertEquals(SessionStatus.Pill.BAD, SessionStatus.loginPill(IdentityCore.LINKED, true, false))           // new-device alert
        assertTrue(SessionStatus.needsRecheck(0L, 11 * 60_000L))
        assertFalse(SessionStatus.needsRecheck(5 * 60_000L, 11 * 60_000L))
    }
    @Test fun lostSessionCookiesComeBackPersistent() {
        val snap = SessionStatus.cookiePairs("sessionid=abc; _ga=GA1; login_aliyunid_ticket=t1; csrf=x")
        val live = SessionStatus.cookiePairs("_ga=GA1; csrf=x")
        val now = 1_000_000_000_000L
        assertEquals(mapOf("sessionid" to "abc", "login_aliyunid_ticket" to "t1"), SessionStatus.missingCookies(snap, live, now - 3_600_000L, now))
        // analytics / bot-check noise is never kept
        assertFalse(SessionStatus.keepCookie("_ga")); assertFalse(SessionStatus.keepCookie("cf_clearance")); assertTrue(SessionStatus.keepCookie("sessionid"))
        // a snapshot older than 30 days is not used
        assertEquals(emptyMap<String, String>(), SessionStatus.missingCookies(snap, live, now - 31L * 86_400_000L, now))
        // nothing lost → nothing written
        assertEquals(emptyMap<String, String>(), SessionStatus.missingCookies(snap, snap, now, now))
        val h = SessionStatus.persistentCookie("sessionid", "abc", "doubao.com", true)
        assertTrue(h.startsWith("sessionid=abc; Path=/; Max-Age=2592000"))
        assertTrue(h.contains("Domain=.doubao.com")); assertTrue(h.endsWith("Secure"))
        assertFalse(SessionStatus.persistentCookie("a", "b", "10.0.0.1", false).contains("Domain"))
    }
    @Test fun sshSmallParts() {
        assertTrue(SessionStatus.hostKeyChanged("SHA256:old", "SHA256:new"))
        assertFalse(SessionStatus.hostKeyChanged(null, "SHA256:new"))          // first contact pins, not a change
        assertFalse(SessionStatus.hostKeyChanged("SHA256:same", "SHA256:same"))
        assertFalse(SessionStatus.hostKeyChanged("SHA256:old", null))          // nothing seen (no answer) is not a change
        assertEquals("这个端口不是 SSH", SessionStatus.reasonLine("这个端口不是 SSH", "这个端口不是 SSH"))
        assertEquals("超时（连接超时（端口可能被网络拦截））".length > 0, true)
        assertEquals("端口拒绝（sshd 没开）", SessionStatus.reasonLine("端口拒绝", "sshd 没开"))
        assertTrue(SessionStatus.viaLocalTunnel(3, true)); assertFalse(SessionStatus.viaLocalTunnel(3, false)); assertFalse(SessionStatus.viaLocalTunnel(180, true))
        assertEquals("1\u00A0ms", SessionStatus.msText(1))
    }
    @Test fun mailSenderIsNotASiteName() {
        assertTrue(IdentityCore.looksLikeSenderName("Matt from WarpStati."))
        assertTrue(IdentityCore.looksLikeSenderName("The Notion Team"))
        assertFalse(IdentityCore.looksLikeSenderName("Hugging Face"))
        assertFalse(IdentityCore.looksLikeSenderName("Microsoft · Outlook"))
        assertFalse(IdentityCore.looksLikeSenderName("网易邮箱"))
    }

    @Test fun refusedKeyIsKeyInvalidNotLoginExpired() {
        assertEquals("key 无效", SessionStatus.keyPillLabel("BAD", "HTTP 401 Unauthorized"))
        assertEquals("key 无效", SessionStatus.keyPillLabel("BAD", "密钥无效（403）"))
        assertEquals("异常", SessionStatus.keyPillLabel("BAD", "连不上（网络）"))
        assertEquals("正常", SessionStatus.keyPillLabel("OK", "401"))
        assertEquals("未检查", SessionStatus.keyPillLabel(null, null))
    }
}
