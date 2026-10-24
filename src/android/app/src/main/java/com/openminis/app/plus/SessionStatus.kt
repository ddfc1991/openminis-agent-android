package com.openminis.app.plus

/**
 * [Mines-38.9.1] user 2026-10-16 「登录过的账号怎么老是异常？老是掉」. What was wrong:
 *  - every web-chat use / check wrote 「signed out」 (weblogin_<id> = 0) on ONE probe that looked like a guest page (a
 *    heuristic: a consent line, a 登录 button near the top, or the session cookie not visible in that moment), and that
 *    flag then overrode the real cookies until the user signed in again → 「掉了」 though the session was fine;
 *  - the 4-hourly 账号巡查 marked any key test that was not 2xx / 401 / 402 / 429 (a 5xx, a proxy page, a VPN blip) as
 *    异常 (BAD) → red rows for working keys;
 *  - LinkAll counted a WEB account as 过期 when its cookie check came back empty once.
 * Now: only a CONFIRMED signed-out page (a login wall twice in a row, or a guest page while the session cookie is really
 * gone) counts as 登录失效; a network error, a time-out, a robot check or a slow page is 「未知，稍后再查」 and never
 * changes the saved state; a key is 异常 only on 401 / 403 (bad key). 异常登录 comes only from a real new-device mail / SMS
 * ([Keychain]). Pure part, unit-tested.
 */
object SessionStatus {
    enum class Probe { SIGNED_IN, GUEST, LOGIN, VERIFY, LOADING, NET_ERROR }
    enum class Verdict { OK, UNKNOWN, EXPIRED }

    /**
     * [prevLoginWalls] = how many probes in a row before this one saw a login wall.
     * [Mines-38.9.3] [everSignedIn] = this account was confirmed signed in on this phone at some point: a site that never
     * had a session is 「还没登录」, not 「失效」, so a login page there changes nothing (user 2026-10-17 「动不动就账号失效」).
     */
    fun web(p: Probe, hasSessionCookie: Boolean, prevLoginWalls: Int, everSignedIn: Boolean = true): Verdict = when (p) {
        Probe.SIGNED_IN -> Verdict.OK
        Probe.LOGIN -> if (everSignedIn && (prevLoginWalls >= 1 || !hasSessionCookie)) Verdict.EXPIRED else Verdict.UNKNOWN
        Probe.GUEST -> if (everSignedIn && !hasSessionCookie) Verdict.EXPIRED else Verdict.UNKNOWN
        Probe.VERIFY, Probe.LOADING, Probe.NET_ERROR -> Verdict.UNKNOWN
    }

    // ---------------- [Mines-38.9.3] one status vocabulary for the merged 「账号」 page ----------------

    /** The one pill every account row shows: 正常 (green) / 要注意 (amber) / 异常 (red) / 未检查 (grey). */
    enum class Pill(val label: String) { OK("正常"), WARN("要注意"), BAD("异常"), UNCHECKED("未检查") }

    /**
     * A service whose credential is an API key / token is judged ONLY by its key test (AccountPatrol level):
     * null = never tested → 未检查; a web session is never consulted (OpenRouter showed 登录失效 because the key
     * service was judged as a web login it never had).
     */
    fun keyPill(level: String?): Pill = when (level) { null, "" -> Pill.UNCHECKED; "OK" -> Pill.OK; "BAD" -> Pill.BAD; else -> Pill.WARN }

    /** [Mines-38.9.5 i] the key test was refused (401 / 403 / invalid key): a key problem, not a sign-in. */
    fun keyRefused(level: String?, reason: String?): Boolean = level == "BAD" &&
        Regex("(?i)\\b40[13]\\b|unauthori[sz]ed|forbidden|invalid[ _-]?(api[ _-]?)?key|incorrect api key|密钥无效|key 无效|无效的?密钥|密钥.*(错|失效)").containsMatchIn(reason.orEmpty())
    /** The 服务 pill text: a refused key says 「key 无效」 (38.9.4 smoke: OpenRouter 401 was listed as 登录过期了). */
    fun keyPillLabel(level: String?, reason: String?): String = if (keyRefused(level, reason)) "key 无效" else keyPill(level).label

    /**
     * A web login: 异常 only for a real new-device alert; 要注意 only for a CONFIRMED sign-out (login wall after a real probe,
     * or the auth cookie gone after the session was once seen); signed in → 正常; anything else (never checked, network
     * error, captcha, recorded but not verified) → 未检查.
     */
    fun loginPill(status: String, alert: Boolean, confirmedOut: Boolean): Pill = when {
        alert -> Pill.BAD
        status == IdentityCore.LINKED -> Pill.OK
        status == IdentityCore.EXPIRED && confirmedOut -> Pill.WARN
        else -> Pill.UNCHECKED
    }

    /** Before a 「要重新登录」 badge is shown the session is re-checked if the last check is older than this (10 min). */
    const val RECHECK_BEFORE_BADGE_MS = 10 * 60_000L
    fun needsRecheck(lastCheckAt: Long, now: Long): Boolean = now - lastCheckAt > RECHECK_BEFORE_BADGE_MS

    // ---------------- [Mines-38.9.3] keeping web sessions across restarts / updates ----------------

    private val noiseCookie = Regex("(?i)^(__cf_bm|cf_clearance|__cflb|_ga.*|_gid|_gat.*|_gcl.*|_fbp|_fbc|ajs_.*|intercom.*|mp_.*|amplitude.*|optimizely.*|__stripe.*|_hj.*|Hm_.*|_clck|_clsk|_uetsid|_uetvid)$")
    /** Cookies worth keeping across an app restart: everything except analytics / bot-check noise. */
    fun keepCookie(name: String): Boolean = name.isNotBlank() && !noiseCookie.matches(name)

    /** "a=1; b=2" → ordered map (name → value). */
    fun cookiePairs(s: String?): Map<String, String> = s.orEmpty().split(';').mapNotNull { kv ->
        val i = kv.indexOf('='); if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
    }.toMap()

    /**
     * What to put back after a restart: cookies the snapshot had that the live store lost (Chromium keeps session
     * cookies — no Expires / Max-Age — in memory only, so they vanish when Android kills Mines or it is updated).
     * A snapshot older than [maxAgeMs] is not used (a server-side expiry would only be resurrected).
     */
    fun missingCookies(snapshot: Map<String, String>, live: Map<String, String>, snapshotAt: Long, now: Long, maxAgeMs: Long = KEEP_MS): Map<String, String> =
        if (now - snapshotAt > maxAgeMs) emptyMap() else snapshot.filter { (n, v) -> keepCookie(n) && v.isNotEmpty() && n !in live }

    /** Set-Cookie value that makes a restored cookie PERSISTENT (30 days) on the site's registrable domain. */
    // [Mines-38.9.6 i13] __Host- cookies host-only + Secure (a Domain= made Chromium drop the hosted Memoh session)
    fun persistentCookie(name: String, value: String, root: String, secure: Boolean, maxAgeS: Long = KEEP_MS / 1000): String =
        LoginValueCore.cookieLine(name, value, root, secure, maxAgeS)

    const val KEEP_MS = 30L * 24 * 3_600_000L

    // ---------------- [Mines-38.9.3] SSH small pure parts ----------------

    /** Host key fingerprint seen during key exchange differs from the one pinned on first contact → 服务器重装过. */
    fun hostKeyChanged(pinned: String?, seen: String?): Boolean = !pinned.isNullOrBlank() && !seen.isNullOrBlank() && pinned != seen

    /** 「这个端口不是 SSH（这个端口不是 SSH）」 → the reason once. */
    fun reasonLine(kind: String, why: String): String = when {
        why.isBlank() -> kind
        kind.isBlank() || kind == why || why.startsWith(kind) -> why
        else -> "$kind（$why）"
    }

    /**
     * A 1–4 ms "direct" time to a far-away box while a VPN / TUN app is the default route is the local tunnel answering
     * (Clash fake-ip), not the server → 「经代理」.
     */
    fun viaLocalTunnel(ms: Int, vpnActive: Boolean): Boolean = vpnActive && ms in 0..4

    /** "1 ms" that never breaks between the number and the unit. */
    fun msText(ms: Int): String = "$ms\u00A0ms"

    enum class Key { OK, WARN, BAD }

    /** A key test: [code] = HTTP status (0 = no answer). Only a refused key is BAD. */
    fun key(code: Int, body: String = ""): Key = when {
        code in 200..299 -> Key.OK
        code == 401 || code == 403 -> Key.BAD
        code == 0 -> Key.WARN                       // no answer: network / VPN / DNS
        code == 429 || code >= 500 -> Key.WARN      // busy / server trouble
        code == 402 || body.contains("insufficient", true) || body.contains("余额") -> Key.WARN
        code == 404 -> Key.WARN                     // endpoint moved, not the key's fault
        else -> Key.WARN
    }

    /** Back-off for re-checking after an UNKNOWN result: 10 min, 30 min, 2 h, then 6 h. */
    fun nextCheckMs(unknownInRow: Int): Long = when (unknownInRow) { 0 -> 6 * 3_600_000L; 1 -> 10 * 60_000L; 2 -> 30 * 60_000L; 3 -> 2 * 3_600_000L; else -> 6 * 3_600_000L }

    /** Probing a site more often than this risks its risk-control (≥ 6 h, except right after the user opens it). */
    const val MIN_PROBE_MS = 6 * 3_600_000L
}
