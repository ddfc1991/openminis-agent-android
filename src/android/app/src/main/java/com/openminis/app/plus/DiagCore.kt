package com.openminis.app.plus

import com.openminis.app.crash.CrashRedactCore
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.11.3] 远程诊断 (off by default, user-approved 2026-10-21): pure rules, unit-tested (DiagCoreTest).
 *  - what may leave the phone: every text through [scrub] (CrashRedactCore + cookies / headers / query strings / chat-shaped
 *    fields); payloads are built only from the fixed builders below — never from chat, prompts, files or settings;
 *  - what may come back: ONLY the 7 commands in [WHITELIST], each HMAC-SHA256-signed with the session key, fresh (±5 min),
 *    never replayed, with exactly the allowed arguments in range. Everything else is rejected (and the rejection reported).
 *    There is no shell, no input injection, no file access, no settings change, no accessibility action — by construction:
 *    the phone has no code path that could perform them on request.
 */
object DiagCore {
    const val MAX_ON_MS = 24L * 3600_000L
    const val TS_WINDOW_MS = 5L * 60_000L
    val WHITELIST = setOf("ping", "capture_jank_trace", "dump_main_thread_now", "set_log_level", "get_recent_crash",
        "show_notice", "request_screenshot_of_mines_only", "set_config")
    val LOG_LEVELS = listOf("error", "warn", "info", "debug")

    // ------------------------------------------------------------------ config (诊断码)
    data class Endpoint(val url: String, val pinSha256: String?)   // pin = SHA-256 of the server cert (DER), hex, or null = normal TLS
    data class Config(val token: String, val endpoints: List<Endpoint>)

    /**
     * 诊断码 = "mdiag1:" + base64url(JSON {"t": token, "e": [[url, pin|""], …]}). The order is the failover order
     * (梦云 direct first, the Cloudflare hostname second). Only wss:// (or ws:// to 127.0.0.1 / 10.0.2.2 for tests).
     */
    fun parseCode(code: String): Config? = runCatching {
        val c = code.trim()
        if (!c.startsWith("mdiag1:")) return null
        val raw = String(java.util.Base64.getUrlDecoder().decode(c.removePrefix("mdiag1:").trim()))
        val o = JSONObject(raw)
        val tok = o.getString("t").trim()
        if (tok.length < 24) return null
        val e = o.getJSONArray("e")
        val eps = (0 until e.length()).mapNotNull { i ->
            val a = e.getJSONArray(i); val url = a.getString(0).trim(); val pin = a.optString(1, "").trim().lowercase().replace(":", "")
            if (!allowedUrl(url)) null else Endpoint(url, pin.takeIf { it.matches(Regex("[0-9a-f]{64}")) })
        }
        if (eps.isEmpty()) null else Config(tok, eps.take(4))
    }.getOrNull()

    fun encodeCode(cfg: Config): String {
        val e = JSONArray(); cfg.endpoints.forEach { e.put(JSONArray().put(it.url).put(it.pinSha256 ?: "")) }
        return "mdiag1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("t", cfg.token).put("e", e).toString().toByteArray())
    }

    fun allowedUrl(u: String): Boolean = u.startsWith("wss://") ||
        (u.startsWith("ws://127.0.0.1") || u.startsWith("ws://10.0.2.2") || u.startsWith("ws://localhost"))

    fun autoOffDue(onAt: Long, now: Long) = onAt <= 0L || now - onAt >= MAX_ON_MS

    // ------------------------------------------------------------------ status line
    fun status(on: Boolean, connected: Boolean, endpointIdx: Int, error: String?): String = when {
        !on -> "诊断已关闭"
        connected -> "诊断已连接（双向）" + if (endpointIdx > 0) " · 备用线路" else ""
        else -> "诊断已断开" + (error?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "，正在重连")
    }

    // ------------------------------------------------------------------ outgoing: scrub + builders
    private val cookieHdr = Regex("""(?im)\b(cookie|set-cookie|authorization|proxy-authorization|x-api-key|x-goog-api-key|x-diag-token|api-key)\s*[:=]\s*[^\n]*""")
    private val query = Regex("""(https?://[^\s?#"']+)\?[^\s"'#)]*""")
    private val chatField = Regex("""(?i)("?(content|text|prompt|message|messages|reply|input|query|body|password|passwd|secret|token|key|cookie)"?\s*[:=]\s*)("([^"\\]|\\.)*"|\[[^\]]*\]|\{[^}]*\}|[^\s,;\}\]]+)""")
    private val longCjk = Regex("""[\u4e00-\u9fff\u3000-\u303f\uff00-\uffef][^\n]{40,}""")
    private val hex = Regex("""\b[0-9a-fA-F]{32,}\b""")
    private val b64 = Regex("""\b[A-Za-z0-9+/_\-]{40,}={0,2}""")

    /** one text that leaves the phone (logcat line, stack frame text, error message) */
    fun scrub(s: String?, max: Int = 600): String {
        if (s.isNullOrEmpty()) return ""
        var r = CrashRedactCore.redact(s)
        r = cookieHdr.replace(r) { it.groupValues[1] + ": ***" }
        r = query.replace(r) { it.groupValues[1] + "?***" }
        r = chatField.replace(r) { it.groupValues[1] + "***" }
        r = hex.replace(r, "***")
        r = b64.replace(r) { m -> if (m.value.any { it.isDigit() } && m.value.any { it.isLetter() }) "***" else m.value }   // long identifiers stay
        r = longCjk.replace(r) { it.value.take(12) + "…（已省略）" }
        return if (r.length <= max) r else r.take(max) + "…"
    }

    /** a stack trace: frames are code locations (kept), the message line is scrubbed */
    fun scrubStack(st: String?, maxLines: Int = 60): String = (st ?: "").lineSequence().take(maxLines).joinToString("\n") { l ->
        val t = l.trimStart()
        if (t.startsWith("at ") || t.startsWith("#") || t.startsWith("... ")) l.take(300) else scrub(l, 400)
    }

    /** own-process logcat tail: W/E/F lines only (I/D lines are where apps print content), each scrubbed */
    fun scrubLogcat(lines: List<String>, max: Int = 120): List<String> = lines.filter { l ->
        Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+([WEF])\s""").containsMatchIn(l) || Regex("""^[WEF]/""").containsMatchIn(l)
    }.takeLast(max).map { scrub(it, 300) }

    /** [k] = this session's random command key (hex, 64): the relay signs commands with it. The relay's connect token is
     *  shipped in the APK (public), so it is NOT what commands are signed with; [k] travels only inside the pinned TLS. */
    fun hello(sid: String, version: String, versionCode: Long, device: String, android: Int, k: String = ""): JSONObject = JSONObject()
        .put("t", "hello").put("sid", sid).put("app", "Mines").put("version", "$version ($versionCode)")
        .put("device", scrub(device, 80)).put("android", android).put("caps", JSONArray(WHITELIST.sorted())).apply { if (k.isNotEmpty()) put("k", k) }

    /** level filter for the own-process logcat tail: error = E/F, warn = W/E/F (default), info/debug = + I/D lines of Mines'
     *  own diagnostic tags only (other I/D lines are where content gets printed). Every line still goes through [scrub]. */
    val DIAG_TAGS = listOf("T-HANG-DIAG", "MinesPerf", "SkyPerf", "WeatherSky", "LocalProxy", "ProviderRoutes", "MinesDiag", "Choreographer", "OpenGLRenderer", "ActivityManager")
    fun logLevelOf(l: String) = when (l) { "error" -> 'E'; "info" -> 'I'; "debug" -> 'D'; else -> 'W' }
    fun filterLogcat(lines: List<String>, level: String, max: Int = 120): List<String> {
        val min = logLevelOf(level)
        val rank = "VDIWEF"
        return lines.mapNotNull { l ->
            val m = Regex("""^\S+\s+\S+\s+\d+\s+\d+\s+([VDIWEF])\s+([^:]*):""").find(l) ?: Regex("""^([VDIWEF])/([^(:]*)""").find(l) ?: return@mapNotNull null
            val lv = m.groupValues[1][0]; val tag = m.groupValues[2].trim()
            val ok = rank.indexOf(lv) >= rank.indexOf(min) && (lv in "WEF" || DIAG_TAGS.any { tag.startsWith(it) })
            if (ok) l else null
        }.takeLast(max).map { scrub(it, 300) }
    }

    /** one page's frames → (frames, janky, slowest ms, p90 ms); janky = longer than 1.5 × the frame budget */
    data class FrameSum(val frames: Int, val janky: Int, val slowestMs: Double, val p90Ms: Double)
    fun frameSum(durMs: List<Double>, budgetMs: Double): FrameSum {
        if (durMs.isEmpty()) return FrameSum(0, 0, 0.0, 0.0)
        val s = durMs.sorted()
        return FrameSum(s.size, s.count { it > budgetMs * 1.5 }, s.last(), s[((s.size - 1) * 0.9).toInt()])
    }

    /** relay side mirror: the session key is accepted only as 64 hex chars */
    fun validKey(k: String?) = k != null && k.matches(Regex("[0-9a-f]{64}"))

    fun jankEvent(page: String, frames: Int, janky: Int, slowestMs: Double, p90Ms: Double): JSONObject = JSONObject()
        .put("t", "jank").put("page", pageName(page)).put("frames", frames).put("janky", janky)
        .put("janky_pct", if (frames > 0) Math.round(janky * 1000.0 / frames) / 10.0 else 0.0)
        .put("slowest_ms", Math.round(slowestMs * 10) / 10.0).put("p90_ms", Math.round(p90Ms * 10) / 10.0)

    fun stallEvent(page: String, ms: Long, stack: String): JSONObject = JSONObject()
        .put("t", "stall").put("page", pageName(page)).put("ms", ms).put("stack", scrubStack(stack, 40))

    fun memEvent(pssKb: Long, javaHeapUsedKb: Long, javaHeapMaxKb: Long, nativeHeapKb: Long, lowMemory: Boolean): JSONObject = JSONObject()
        .put("t", "mem").put("pss_kb", pssKb).put("java_used_kb", javaHeapUsedKb).put("java_max_kb", javaHeapMaxKb)
        .put("native_kb", nativeHeapKb).put("low_memory", lowMemory)

    fun crashEvent(kind: String, title: String, report: String): JSONObject = JSONObject()
        .put("t", "crash").put("kind", kind).put("title", title).put("report", scrubStack(report, 160).take(60_000))

    /** routes carry ids (chat/<sessionId>, provider/<id>): keep the route shape only */
    fun pageName(route: String?): String = (route ?: "?").substringBefore('?').split('/')
        .joinToString("/") { seg -> if (seg.startsWith("{") || seg.length > 24 || seg.any { it.isDigit() } && seg.length > 6) "*" else seg }.take(60)

    // ------------------------------------------------------------------ incoming: signed whitelist
    fun hmac(key: ByteArray, msg: String): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")); doFinal(msg.toByteArray(Charsets.UTF_8))
    }
    fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun sessionKey(token: String, sid: String): ByteArray = hmac(token.toByteArray(), "mines-diag|$sid")

    /** sorted k=v joined by '&' — identical to relay.py canon_args */
    fun canonArgs(a: JSONObject?): String {
        if (a == null) return ""
        return a.keys().asSequence().toList().sorted().joinToString("&") { k -> "$k=" + when (val v = a.get(k)) { is Boolean -> if (v) "true" else "false"; else -> v.toString() } }
    }
    fun sign(token: String, sid: String, id: String, cmd: String, args: JSONObject?, ts: Long): String =
        hexOf(hmac(sessionKey(token, sid), "$id|$cmd|${canonArgs(args)}|$ts|$sid"))

    sealed class Verdict {
        data class Ok(val id: String, val cmd: String, val args: Map<String, Any>) : Verdict()
        data class Rejected(val id: String, val reason: String) : Verdict()
    }

    /** the only door in. [seen] = ids already executed in this session (replay guard; caller adds Ok ids) */
    fun verify(raw: String, token: String, sid: String, now: Long, seen: Set<String>): Verdict {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return Verdict.Rejected("", "不是 JSON")
        if (o.optString("t") != "cmd") return Verdict.Rejected("", "不是命令")
        val id = o.optString("id"); val cmd = o.optString("cmd"); val ts = o.optLong("ts", 0L); val sig = o.optString("sig")
        val args = o.optJSONObject("args") ?: JSONObject()
        if (!id.matches(Regex("[A-Za-z0-9_\\-]{6,40}"))) return Verdict.Rejected(id.take(40), "命令编号不合法")
        if (cmd !in WHITELIST) return Verdict.Rejected(id, "不在白名单：" + cmd.take(40))
        val want = sign(token, sid, id, cmd, args, ts)
        if (sig.length != want.length || !java.security.MessageDigest.isEqual(sig.toByteArray(), want.toByteArray())) return Verdict.Rejected(id, "签名不对")
        if (kotlin.math.abs(now - ts) > TS_WINDOW_MS) return Verdict.Rejected(id, "命令过期")
        if (id in seen) return Verdict.Rejected(id, "重复的命令")
        val a = checkArgs(cmd, args) ?: return Verdict.Rejected(id, "参数不合法")
        return Verdict.Ok(id, cmd, a)
    }

    /** exact argument schema per command; unknown keys → null (rejected) */
    fun checkArgs(cmd: String, a: JSONObject): Map<String, Any>? {
        val keys = a.keys().asSequence().toSet()
        fun only(vararg k: String) = keys.all { it in k }
        return when (cmd) {
            "ping", "dump_main_thread_now", "get_recent_crash", "request_screenshot_of_mines_only" -> if (keys.isEmpty()) emptyMap() else null
            "capture_jank_trace" -> {
                if (!only("seconds")) return null
                val s = if (a.has("seconds")) (a.opt("seconds") as? Number)?.toInt() ?: a.optString("seconds").toIntOrNull() ?: return null else 10
                if (s !in 1..30) null else mapOf("seconds" to s)
            }
            "set_log_level" -> {
                if (!only("level")) return null
                val l = a.optString("level"); if (l !in LOG_LEVELS) null else mapOf("level" to l)
            }
            "set_config" -> {
                if (keys != setOf("key", "value")) return null
                val k = a.optString("key"); val v = configValue(k, a.opt("value")?.toString()) ?: return null
                mapOf("key" to k, "value" to v)
            }
            "show_notice" -> {
                if (!only("text")) return null
                val t = notice(a.optString("text")) ?: return null
                mapOf("text" to t)
            }
            else -> null
        }
    }

    /** [38.9.11.3 g2] remote config: Mines-internal switches only, in-memory (process death / 诊断关闭 / 24 h → back to the
     *  user's own values), bounded values; "default" = the user's own value. Labels are what the in-app card shows. */
    val CONFIG_LABELS = linkedMapOf("sky_mode" to "天空动画", "sky_fps_cap" to "天空动画帧率上限", "nav_anim" to "翻页动画", "log_level" to "诊断日志级别")
    fun configValue(key: String, raw: String?): String? {
        val v = (raw ?: "").trim()
        if (v == "default" && key in CONFIG_LABELS) return v
        return when (key) {
            "sky_mode" -> v.takeIf { it in setOf("smooth", "rich", "off") }
            "sky_fps_cap" -> v.toIntOrNull()?.takeIf { it in 5..30 }?.toString()
            "nav_anim" -> v.takeIf { it in setOf("on", "off") }
            "log_level" -> v.takeIf { it in LOG_LEVELS }
            else -> null
        }
    }
    fun configShown(key: String, value: String): String = when (value) {
        "default" -> "你的设置"
        "smooth" -> "流畅"; "rich" -> "精美"; "off" -> "关闭"; "on" -> "打开"
        else -> if (key == "sky_fps_cap") "$value 帧" else value
    }

    /** a notice the phone will show: plain Chinese text, ≤ 80 chars, no links, no control characters; must contain Chinese */
    fun notice(raw: String?): String? {
        val t = (raw ?: "").replace(Regex("[\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.length > 80) return null
        if (t.none { it in '\u4e00'..'\u9fff' }) return null
        if (Regex("(?i)(https?://|www\\.|://)").containsMatchIn(t)) return null
        return t
    }

    fun rejected(id: String, reason: String) = JSONObject().put("t", "rejected").put("id", id).put("reason", reason)
    fun result(id: String, cmd: String, body: JSONObject) = JSONObject().put("t", "result").put("id", id).put("cmd", cmd).put("body", body)
}
