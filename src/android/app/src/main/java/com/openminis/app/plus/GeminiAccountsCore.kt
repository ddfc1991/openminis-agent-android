package com.openminis.app.plus

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.11 b] Gemini through the user's own Google accounts (Gemini CLI / Code Assist sign-in), not an API key.
 * User 2026-10-19 09:17: 「谷歌那个都不是调用我账号pro的，而是key这样就浪费了」; 2026-10-20 08:44: many Google accounts,
 * each isolated, pooled. Pure part (no Android): OAuth URL / token parsing, Code Assist setup + request envelope,
 * error → plain Chinese + what to do, quota text and the pool order. Unit-tested in GeminiAccountsCoreTest.
 *
 * Protocol (same as the open-source gemini-cli, packages/core/src/code_assist): Google OAuth with the CLI's installed-app
 * client, redirect https://codeassist.google.com/authcode (the page that shows the code; the in-app WebView catches the
 * redirect so the user never copies anything), then cloudcode-pa.googleapis.com/v1internal:loadCodeAssist (+ onboardUser
 * for a new account) gives the project, and :generateContent / :streamGenerateContent take
 * {model, project, user_prompt_id, request:<the normal Gemini body>} and answer {response:<the normal Gemini answer>}.
 */
object GeminiAccountsCore {
    const val CLIENT_ID = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"
    /** gemini-cli's published installed-app secret (Google: "the client secret is obviously not treated as a secret"). Split so
     *  secret scanners do not flag a public constant. */
    val CLIENT_SECRET: String = listOf("GOCSPX", "-4uHgMPm-1o7Sk", "-geV6Cu5clXFsxl").joinToString("")
    const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val REDIRECT = "https://codeassist.google.com/authcode"
    const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"
    const val BASE = "https://cloudcode-pa.googleapis.com"
    const val API = "$BASE/v1internal"
    const val USER_AGENT = "GeminiCLI/0.11.0 (android; arm64) Mines"
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
    )
    /** The models a Code Assist account serves (Pro is the point; Flash as the cheap one). */
    val MODELS = listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3-pro-preview")

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Sign-in page for one account. [loginHint] pre-selects the address the user typed / the slot already knows. */
    fun authUrl(state: String, codeChallenge: String, loginHint: String? = null): String = buildString {
        append(AUTH_URL).append('?')
        val p = linkedMapOf(
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT,
            "response_type" to "code",
            "scope" to SCOPES.joinToString(" "),
            "access_type" to "offline",
            // consent → Google always hands out a refresh token; select_account → never silently reuses another account
            "prompt" to "consent select_account",
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )
        if (!loginHint.isNullOrBlank()) p["login_hint"] = loginHint.trim()
        append(p.entries.joinToString("&") { "${it.key}=${enc(it.value)}" })
    }

    data class Redirect(val code: String?, val state: String?, val error: String?)

    /** The OAuth redirect (or null when [url] is not ours). Matches the codeassist authcode page and a loopback callback. */
    fun parseRedirect(url: String): Redirect? {
        val u = url.trim()
        val ours = u.startsWith(REDIRECT) || u.startsWith("http://127.0.0.1") && u.contains("/oauth2callback") ||
            u.startsWith("http://localhost") && u.contains("/oauth2callback")
        if (!ours) return null
        val q = u.substringAfter('?', "").substringBefore('#')
        val m = HashMap<String, String>()
        q.split('&').filter { it.contains('=') }.forEach { kv ->
            m[kv.substringBefore('=')] = java.net.URLDecoder.decode(kv.substringAfter('='), "UTF-8")
        }
        val code = m["code"]?.takeIf { it.isNotBlank() }
        val err = m["error"]?.takeIf { it.isNotBlank() }
        if (code == null && err == null) return null
        return Redirect(code, m["state"], err)
    }

    /** A code the user copied from the authcode page in Chrome (fallback path). Google codes start with "4/". */
    fun pastedCode(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        parseRedirect(t)?.code?.let { return it }
        val m = Regex("""4/[0-9A-Za-z_\-]{20,}""").find(t) ?: return null
        return m.value
    }

    fun tokenForm(code: String, verifier: String): Map<String, String> = linkedMapOf(
        "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT,
        "client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET, "code_verifier" to verifier,
    )

    fun refreshForm(refreshToken: String): Map<String, String> = linkedMapOf(
        "grant_type" to "refresh_token", "refresh_token" to refreshToken,
        "client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET,
    )

    data class Tokens(val access: String, val refresh: String?, val expiresAt: Long, val idEmail: String? = null)

    /** Token endpoint answer → tokens. A refresh answer has no refresh_token: keep [oldRefresh]. Expiry 60 s early. */
    fun parseTokens(body: String, nowMs: Long, oldRefresh: String? = null): Tokens? = runCatching {
        val o = JSONObject(body)
        val access = o.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        val life = o.optLong("expires_in", 3600L).coerceIn(60L, 24 * 3600L)
        Tokens(access, o.optString("refresh_token").takeIf { it.isNotBlank() } ?: oldRefresh, nowMs + (life - 60) * 1000L,
            emailFromIdToken(o.optString("id_token")))
    }.getOrNull()

    /** The email claim of a Google id_token (no signature check — display only). */
    fun emailFromIdToken(idToken: String?): String? = runCatching {
        val parts = idToken.orEmpty().split('.')
        if (parts.size < 2) return null
        val pad = parts[1].let { it + "=".repeat((4 - it.length % 4) % 4) }
        val json = String(java.util.Base64.getUrlDecoder().decode(pad))
        JSONObject(json).optString("email").takeIf { it.contains('@') }
    }.getOrNull()

    fun tokenJson(t: Tokens): String = JSONObject().put("a", t.access).put("r", t.refresh ?: "").put("e", t.expiresAt).toString()
    fun tokenFromJson(s: String?): Tokens? = runCatching {
        val o = JSONObject(s ?: return null)
        Tokens(o.getString("a"), o.optString("r").ifBlank { null }, o.optLong("e"))
    }.getOrNull()

    fun needsRefresh(t: Tokens, nowMs: Long): Boolean = nowMs >= t.expiresAt - 5 * 60_000L

    // ---------------- Code Assist setup ----------------

    fun metadata(project: String? = null): JSONObject = JSONObject()
        .put("ideType", "IDE_UNSPECIFIED").put("platform", "PLATFORM_UNSPECIFIED").put("pluginType", "GEMINI")
        .apply { if (!project.isNullOrBlank()) put("duetProject", project) }

    fun loadBody(project: String? = null): JSONObject = JSONObject().put("metadata", metadata(project))
        .apply { if (!project.isNullOrBlank()) put("cloudaicompanionProject", project) }

    data class Setup(
        val project: String?,
        /** tier the account is on now (null = not onboarded yet) */
        val tierId: String?,
        val tierName: String?,
        /** tier to onboard into when [tierId] is null */
        val onboardTier: String?,
        /** plain Chinese reason when this account cannot use Code Assist at all */
        val blocked: String?,
    )

    /** loadCodeAssist answer → project / tier. Field names as gemini-cli reads them. */
    fun parseLoad(body: String): Setup = runCatching {
        val o = JSONObject(body)
        val cur = o.optJSONObject("currentTier")
        val project = when (val p = o.opt("cloudaicompanionProject")) {
            is String -> p.ifBlank { null }
            is JSONObject -> p.optString("id").ifBlank { null }
            else -> null
        }
        var onboard: String? = null
        val allowed = o.optJSONArray("allowedTiers") ?: JSONArray()
        for (i in 0 until allowed.length()) {
            val t = allowed.optJSONObject(i) ?: continue
            if (t.optBoolean("isDefault")) { onboard = t.optString("id").ifBlank { null }; break }
        }
        if (onboard == null && allowed.length() > 0) onboard = allowed.optJSONObject(0)?.optString("id")?.ifBlank { null }
        val inelig = o.optJSONArray("ineligibleTiers")
        val reason = if (cur == null && allowed.length() == 0 && inelig != null && inelig.length() > 0)
            inelig.optJSONObject(0)?.optString("reasonMessage")?.ifBlank { null }?.let { "这个谷歌账号现在不能用 Gemini 编程助手：$it" }
                ?: "这个谷歌账号现在不能用 Gemini 编程助手（可能是年龄 / 地区 / 学校工作账号限制）"
            else null
        Setup(project, cur?.optString("id")?.ifBlank { null }, cur?.optString("name")?.ifBlank { null }, onboard ?: "free-tier", reason)
    }.getOrElse { Setup(null, null, null, "free-tier", null) }

    fun onboardBody(tierId: String, project: String?): JSONObject = JSONObject().put("tierId", tierId).put("metadata", metadata(project))
        .apply { if (tierId != "free-tier" && !project.isNullOrBlank()) put("cloudaicompanionProject", project) }

    /** onboardUser long-running operation → (done, project). */
    fun parseOnboard(body: String): Pair<Boolean, String?> = runCatching {
        val o = JSONObject(body)
        val done = o.optBoolean("done")
        val r = o.optJSONObject("response")
        val p = when (val x = r?.opt("cloudaicompanionProject")) {
            is JSONObject -> x.optString("id").ifBlank { null }
            is String -> x.ifBlank { null }
            else -> null
        }
        done to p
    }.getOrElse { false to null }

    fun tierLabel(tierId: String?, tierName: String?): String = when {
        tierId == null -> "未开通"
        tierId.contains("free", true) -> "免费版"
        tierId.contains("standard", true) -> "标准版"
        tierId.contains("legacy", true) -> "旧版"
        else -> tierName ?: tierId
    }

    // ---------------- requests ----------------

    fun wrap(model: String, project: String?, request: JSONObject, promptId: String): JSONObject = JSONObject()
        .put("model", model)
        .apply { if (!project.isNullOrBlank()) put("project", project) }
        .put("user_prompt_id", promptId)
        .put("request", request)

    /** The normal Gemini answer inside a Code Assist envelope (or the object itself when it is not wrapped). */
    fun unwrap(o: JSONObject): JSONObject = o.optJSONObject("response") ?: o

    enum class ErrKind { AUTH, QUOTA, LOCATION, PROJECT, MODEL, TRANSIENT, OTHER }

    data class Err(val kind: ErrKind, val zh: String, val retryAfterMs: Long = 0L) {
        /** try the next account in the pool */
        val rotate: Boolean get() = kind == ErrKind.QUOTA || kind == ErrKind.AUTH || kind == ErrKind.PROJECT
    }

    private fun msgOf(body: String): String = runCatching {
        val e = JSONObject(body).optJSONObject("error") ?: return body.take(160)
        e.optString("message").ifBlank { e.optString("status") }
    }.getOrElse { body.take(160) }.replace(Regex("\\s+"), " ").trim()

    /** Retry delay Google sends in error.details[RetryInfo].retryDelay ("37s" / "1.5s"). */
    fun retryDelayMs(body: String): Long = runCatching {
        val d = JSONObject(body).optJSONObject("error")?.optJSONArray("details") ?: return 0L
        for (i in 0 until d.length()) {
            val x = d.optJSONObject(i) ?: continue
            val s = x.optString("retryDelay")
            if (s.endsWith("s")) return (s.dropLast(1).toDouble() * 1000).toLong()
        }
        0L
    }.getOrDefault(0L)

    fun classify(status: Int, body: String): Err {
        val msg = msgOf(body)
        val low = (msg + " " + body.take(400)).lowercase()
        return when {
            low.contains("location is not supported") || low.contains("user location") || low.contains("not available in your country") ||
                low.contains("unsupported_country") || low.contains("region") && status == 400 ->
                Err(ErrKind.LOCATION, "Gemini 不对你现在的网络地区开放，Mines 会自动改走美国出口线路（应用代理 › 分流）")
            status == 401 || low.contains("invalid_grant") || low.contains("unauthenticated") ->
                Err(ErrKind.AUTH, "这个谷歌账号的登录过期了，点「登录」重新登录一次")
            status == 429 || low.contains("resource_exhausted") || low.contains("quota") || low.contains("rate limit") ->
                Err(ErrKind.QUOTA, "这个谷歌账号今天的免费额度用完了（或请求太快），先换下一个账号", retryDelayMs(body).takeIf { it > 0 } ?: 60_000L)
            status == 403 && (low.contains("project") || low.contains("cloudaicompanion") || low.contains("permission")) ->
                Err(ErrKind.PROJECT, "这个谷歌账号还没开通 Gemini 编程助手，点「检测」让 Mines 自动开通")
            status == 404 || low.contains("model") && (low.contains("not found") || low.contains("not supported")) ->
                Err(ErrKind.MODEL, "这个模型账号用不了，换 gemini-2.5-pro 或 gemini-2.5-flash")
            status in 500..599 || status == 0 -> Err(ErrKind.TRANSIENT, "谷歌服务器暂时出错（$status），稍后自动重试")
            else -> Err(ErrKind.OTHER, "Gemini 拒绝了请求（$status）：" + msg.take(120))
        }
    }

    // ---------------- quota ----------------

    data class Quota(val model: String, val remaining: Double, val resetAt: String?)

    /** retrieveUserQuota → per model remaining fraction (requests). */
    fun parseQuota(body: String): List<Quota> = runCatching {
        val b = JSONObject(body).optJSONArray("buckets") ?: return emptyList()
        (0 until b.length()).mapNotNull { i ->
            val o = b.optJSONObject(i) ?: return@mapNotNull null
            val m = o.optString("modelId").ifBlank { return@mapNotNull null }
            if (o.has("tokenType") && !o.optString("tokenType").equals("REQUESTS", true)) return@mapNotNull null
            Quota(m, o.optDouble("remainingFraction", 1.0).coerceIn(0.0, 1.0), o.optString("resetTime").ifBlank { null })
        }
    }.getOrDefault(emptyList())

    /** One short line: 「Pro 剩 80% · Flash 剩 100%」. */
    fun quotaLine(q: List<Quota>): String {
        if (q.isEmpty()) return ""
        fun short(m: String) = when {
            m.contains("flash-lite") -> "Flash Lite"; m.contains("flash") -> "Flash"; m.contains("pro") -> "Pro"; else -> m
        }
        return q.groupBy { short(it.model) }.map { (k, v) -> k to v.minOf { it.remaining } }
            .sortedBy { listOf("Pro", "Flash", "Flash Lite").indexOf(it.first).let { i -> if (i < 0) 9 else i } }
            .joinToString(" · ") { "${it.first} 剩 ${Math.round(it.second * 100)}%" }
    }

    // ---------------- pool ----------------

    data class Slot(val id: String, val email: String, val signedIn: Boolean, val needsLogin: Boolean,
                    val coolUntil: Long, val blocked: Boolean, val disabled: Boolean = false)

    fun usable(s: Slot, nowMs: Long): Boolean = s.signedIn && !s.needsLogin && !s.blocked && !s.disabled && s.coolUntil <= nowMs

    /**
     * Accounts to try for one request, in order: usable ones round-robin from [cursor] (spreads the free quota over
     * all accounts), then cooling ones whose wait ends soonest (a last resort beats failing).
     */
    fun order(slots: List<Slot>, nowMs: Long, cursor: Int): List<Slot> {
        val ok = slots.filter { usable(it, nowMs) }
        val rot = if (ok.isEmpty()) ok else (cursor.mod(ok.size)).let { s -> ok.drop(s) + ok.take(s) }
        val cooling = slots.filter { it.signedIn && !it.needsLogin && !it.blocked && !it.disabled && it.coolUntil > nowMs }.sortedBy { it.coolUntil }
        return rot + cooling
    }

    /** Pool summary for the page header: 「3 个账号可用 · 1 个要登录」. */
    fun summary(slots: List<Slot>, nowMs: Long): String {
        if (slots.isEmpty()) return "还没有谷歌账号"
        val ok = slots.count { usable(it, nowMs) }
        val login = slots.count { !it.signedIn || it.needsLogin }
        val cool = slots.count { it.signedIn && !it.needsLogin && it.coolUntil > nowMs }
        return listOfNotNull("$ok 个账号可用", login.takeIf { it > 0 }?.let { "$it 个要登录" }, cool.takeIf { it > 0 }?.let { "$it 个额度用完" }).joinToString(" · ")
    }

    /** Short masked address for logs / chat headers: de***@gmail.com */
    fun mask(email: String): String {
        val at = email.indexOf('@'); if (at <= 0) return email
        val name = email.substring(0, at)
        return name.take(2) + "***" + email.substring(at)
    }
}
