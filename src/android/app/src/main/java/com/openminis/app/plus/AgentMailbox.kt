package com.openminis.app.plus

import android.content.Context
import com.openminis.app.plus.group.GroupPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.9.5 i] 「验证码来源」: the user's own linked mailboxes (default, each with a switch) — see [CodeSourcesCore].
 * Codes are read only from recent mail of the site being signed into; only the code / link is taken, never stored with
 * the mail. The last few codes are kept in memory, masked, for the AI 服务 page.
 */
object CodeSources {
    data class Seen(val site: String, val masked: String, val source: String, val at: Long)
    private val _seen = MutableStateFlow<List<Seen>>(emptyList())
    val seen: StateFlow<List<Seen>> = _seen.asStateFlow()

    fun on(id: String): Boolean = GroupPrefs.get("codesrc_$id") != "0"
    fun setOn(id: String, v: Boolean) = GroupPrefs.put("codesrc_$id", if (v) null else "0")

    /** Linked mailboxes (Gmail web / Outlook / IMAP boxes) + ready AI 邮箱, with their switch. */
    fun all(c: Context): List<Pair<MailSources.Box, Boolean>> {
        GroupPrefs.init(c)
        val boxes = runCatching { MailSources.boxes(c) }.getOrDefault(emptyList()) + AgentMailbox.boxes()
        return boxes.map { it to on(it.id) }
    }

    fun enabledBoxes(c: Context): List<MailSources.Box> = all(c).filter { it.second }.map { it.first }

    /**
     * A code / link from [site] that arrived after [sinceMs], from switched-on sources only. [site] blank = the old
     * behaviour (any code), used by pages that did not say which site they are.
     */
    /** [Mines-38.9.5 i] label of the mailbox the last code was found in (for LoginMemory); "" when none / SMS. */
    @Volatile var lastVia: String = ""

    suspend fun find(c: Context, sinceMs: Long, site: String): CodeSourcesCore.Found? = withContext(Dispatchers.IO) {
        val off = all(c).filter { !it.second }.map { it.first.label }.toSet()
        MailSources.invalidate()
        val mails = runCatching { MailSources.recent(c, 0L) }.getOrDefault(emptyList()).filter { it.source !in off } +
            runCatching { AgentMailbox.recent(c) }.getOrDefault(emptyList()).filter { it.source !in off }
        val now = System.currentTimeMillis()
        val f = if (site.isBlank()) MailParse.codeFrom(mails, sinceMs)?.let { (code, m) -> CodeSourcesCore.Found(code, null, m.from, m.at) }
            else CodeSourcesCore.find(mails, site, sinceMs, now)
        if (f != null) lastVia = mails.firstOrNull { it.at == f.at && it.from == f.from }?.source.orEmpty()
        f?.code?.let { code -> _seen.value = (listOf(Seen(site.ifBlank { CodeSourcesCore.senderDomain(f.from) }, CodeSourcesCore.mask(code), f.from.substringBefore('<').trim().take(20), now)) + _seen.value).take(5) }
        f
    }
}

/**
 * [Mines-38.9.5 i] user 19:36 「AI 邮箱」: ready-made agent mailboxes the user can pick (AgentMail, AGmail, Mail.tm); a
 * self-hosted domain (Cloudflare) is 高级 · 可选 and shows 未开通 until set up — no fake data. Keys / passwords live in the
 * vault (secret extras of "agent_mail"), never shown.
 */
object AgentMailbox {
    private const val V = "agent_mail"
    // [38.9.11.1] user 「AI 邮箱没一个可以打开」: requests went through the app ProxySelector to a dead 127.0.0.1:7899. Now:
    // the selector only hands out a local proxy that passed (LocalProxy), and a failed call is retried direct and through
    // the proxy that passed, with short timeouts; failures come back as one plain Chinese line (AiMailCore).
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    private fun via(p: java.net.Proxy): OkHttpClient = http.newBuilder().proxy(p).build()
    /** default route → direct → the local proxy that passed; the first that answers (any HTTP status) wins */
    private fun exec(req: Request): Pair<Int, String> {
        var last: Throwable? = null
        val routes = ArrayList<OkHttpClient>()
        routes += http
        routes += via(java.net.Proxy.NO_PROXY)
        LocalProxy.best?.let { routes += via(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", it))) }
        for ((i, c) in routes.withIndex()) {
            try { return c.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) } }
            catch (e: java.io.IOException) { last = e; if (i == 0) LocalProxy.reportFailure() }
        }
        throw last ?: java.io.IOException("no route")
    }
    private val JSON = "application/json".toMediaType()

    fun key(id: String): String? = PlusVault.secretExtra(V, "key_$id")?.takeIf { it.isNotBlank() }
    fun setKey(id: String, v: String?) = PlusVault.setSecretExtra(V, "key_$id", v?.trim())
    fun address(id: String): String? = GroupPrefs.get("agentmail_addr_$id")
    private fun setAddress(id: String, v: String?) = GroupPrefs.put("agentmail_addr_$id", v)

    /** 已开通 = has what it needs to read mail (a key, or the Mail.tm account). */
    fun ready(id: String): Boolean = when (id) {
        "mailtm" -> PlusVault.secretExtra(V, "mailtm_pass") != null && address("mailtm") != null
        "selfhost" -> selfConfig() != null && address("selfhost") != null   // [38.9.12.1] base + token (vault) + a domain from /api/health
        "agmail" -> false   // key can be saved; its read API is not wired / verified yet — never claimed as a code source
        else -> key(id) != null
    }

    fun boxes(): List<MailSources.Box> = CodeSourcesCore.PROVIDERS.filter { ready(it.id) }.map { MailSources.Box("ai:" + it.id, it.name, address(it.id) ?: "AI 邮箱") }

    /** Looks like an API key the user copied (AgentMail keys start with am_; anything long without spaces passes). */
    fun looksLikeKey(s: String?): Boolean = s != null && s.trim().length in 20..200 && s.trim().none { it.isWhitespace() }

    private fun get(url: String, bearer: String?): Pair<Int, String> {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        bearer?.let { b.header("Authorization", "Bearer $it") }
        return exec(b.build())
    }

    private fun post(url: String, body: JSONObject, bearer: String? = null): Pair<Int, String> {
        val b = Request.Builder().url(url).header("Accept", "application/json").post(body.toString().toRequestBody(JSON))
        bearer?.let { b.header("Authorization", "Bearer $it") }
        return exec(b.build())
    }

    /** AgentMail: with the user's key, use the first inbox (or create one). Returns null = ok, else a plain reason. */
    suspend fun connectAgentMail(key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val (code, body) = get("https://api.agentmail.to/v0/inboxes?limit=5", key)
            if (code == 401 || code == 403) return@runCatching "API Key 不对（$code）"
            if (code !in 200..299) return@runCatching "AgentMail 没有正常回复（HTTP $code）"
            val a = JSONObject(body).optJSONArray("inboxes")
            var inbox = a?.optJSONObject(0)?.let { it.optString("inbox_id").ifBlank { it.optString("email") } }
            if (inbox.isNullOrBlank()) {
                val (c2, b2) = post("https://api.agentmail.to/v0/inboxes", JSONObject(), key)
                if (c2 !in 200..299) return@runCatching "建邮箱没成功（HTTP $c2）"
                inbox = JSONObject(b2).let { it.optString("inbox_id").ifBlank { it.optString("email") } }
            }
            setKey("agentmail", key); setAddress("agentmail", inbox)
            null
        }.getOrElse { AiMailCore.netReason("AgentMail", it.javaClass.simpleName, it.message) }
    }

    /** Mail.tm: a free temporary box, no key (api.mail.tm, ≤ 8 requests / s): GET /domains → POST /accounts → POST /token. */
    @Volatile var lastMailTmStep: String = ""
    suspend fun openMailTm(): String? = withContext(Dispatchers.IO) {
        runCatching {
            lastMailTmStep = "domains"
            val (c0, d) = get("https://api.mail.tm/domains", null)
            AiMailCore.mailTmHttp(AiMailCore.Step.DOMAINS, c0, d)?.let { return@runCatching it }
            val dom = AiMailCore.mailTmDomain(d) ?: return@runCatching "Mail.tm 现在没有可用域名"
            val pass = java.util.UUID.randomUUID().toString()
            var addr = ""; var why: String? = "建邮箱没成功"
            for (attempt in 0..1) {   // a taken name (422) → one more try with a new name
                lastMailTmStep = "accounts"
                addr = AiMailCore.userName(System.currentTimeMillis(), (0..899).random()) + "@" + dom
                val (c1, b1) = post("https://api.mail.tm/accounts", JSONObject().put("address", addr).put("password", pass))
                why = AiMailCore.mailTmHttp(AiMailCore.Step.ACCOUNT, c1, b1)
                if (why == null || c1 != 422) break
            }
            if (why != null) return@runCatching why
            // the box is only usable when a token can be had
            lastMailTmStep = "token"
            val (c2, b2) = post("https://api.mail.tm/token", JSONObject().put("address", addr).put("password", pass))
            AiMailCore.mailTmHttp(AiMailCore.Step.TOKEN, c2, b2)?.let { return@runCatching it }
            if (JSONObject(b2).optString("token").isBlank()) return@runCatching "Mail.tm 没给登录令牌"
            PlusVault.setSecretExtra(V, "mailtm_pass", pass); setAddress("mailtm", addr)
            lastMailTmStep = "ok"
            null
        }.getOrElse { AiMailCore.netReason("Mail.tm", it.javaClass.simpleName, it.message) }
    }

    private fun mailTmToken(): String? {
        val addr = address("mailtm") ?: return null
        val pass = PlusVault.secretExtra(V, "mailtm_pass") ?: return null
        val (c, b) = post("https://api.mail.tm/token", JSONObject().put("address", addr).put("password", pass))
        return if (c in 200..299) JSONObject(b).optString("token").takeIf { it.isNotBlank() } else null
    }

    // ---------------- [Mines-38.9.12.1] 自建域名 / Cloudflare (the user's own inbox API, SelfMailCore) ----------------

    /** base URL + bearer token from the encrypted vault (never in code / prefs) */
    fun selfConfig(): SelfMailCore.Config? {
        val b = PlusVault.secretExtra(V, "selfhost_url")?.takeIf { it.isNotBlank() } ?: return null
        val t = PlusVault.secretExtra(V, "selfhost_token")?.takeIf { it.isNotBlank() } ?: return null
        return SelfMailCore.Config(b, t)
    }
    /** the domain /api/health reported (shown on the row) */
    fun selfDomain(): String? = address("selfhost")

    private val _selfMails = MutableStateFlow<List<MailParse.Mail>>(emptyList())
    /** last read of the self-hosted inbox (验证码 tab) and the address each message went to */
    val selfMails: StateFlow<List<MailParse.Mail>> = _selfMails.asStateFlow()
    @Volatile var selfTo: Map<String, String> = emptyMap(); private set

    private fun del(url: String, bearer: String): Pair<Int, String> =
        exec(Request.Builder().url(url).header("Accept", "application/json").header("Authorization", "Bearer $bearer").delete().build())

    /** 开通: GET /api/health with the token; saved only when it answers ok with a domain. null = ok, else a plain reason. */
    suspend fun connectSelfHost(base: String, token: String): String? = withContext(Dispatchers.IO) {
        val b = SelfMailCore.normBase(base) ?: return@withContext "地址要以 https:// 开头"
        if (!SelfMailCore.tokenOk(token.trim())) return@withContext "令牌格式不对"
        runCatching {
            val (code, body) = get("$b/api/health", token.trim())
            if (code == 401 || code == 403) return@runCatching "令牌不对（$code）"
            if (code !in 200..299) return@runCatching "收件服务没有正常回复（HTTP $code）"
            val dom = SelfMailCore.parseHealth(body) ?: return@runCatching "收件服务没有报告域名"
            PlusVault.setSecretExtra(V, "selfhost_url", b); PlusVault.setSecretExtra(V, "selfhost_token", token.trim())
            setAddress("selfhost", dom)
            null
        }.getOrElse { AiMailCore.netReason("自建域名", it.javaClass.simpleName, it.message) }
    }

    /** a new address on the user's domain (unlimited); null when not connected / it failed */
    suspend fun selfNewAddress(prefix: String?): String? = withContext(Dispatchers.IO) {
        val c = selfConfig() ?: return@withContext null
        runCatching {
            val (code, body) = post("${c.base}/api/mailboxes", JSONObject().apply { if (!prefix.isNullOrBlank()) put("prefix", prefix) }, c.token)
            if (code in 200..299) SelfMailCore.parseAddress(body) else null
        }.getOrNull()
    }

    suspend fun selfBoxes(): List<SelfMailCore.Box> = withContext(Dispatchers.IO) {
        val c = selfConfig() ?: return@withContext emptyList()
        runCatching { val (code, body) = get("${c.base}/api/mailboxes", c.token); if (code in 200..299) SelfMailCore.parseMailboxes(body) else emptyList() }.getOrDefault(emptyList())
    }

    suspend fun selfDelete(id: String): Boolean = withContext(Dispatchers.IO) {
        val c = selfConfig() ?: return@withContext false
        runCatching { del("${c.base}/api/messages/" + java.net.URLEncoder.encode(id.removePrefix("sh:"), "UTF-8"), c.token).first in 200..299 }.getOrDefault(false)
    }

    /** last 24 h of mail to the newest 8 addresses (the API reads per address) */
    suspend fun selfRecent(): List<MailParse.Mail> = withContext(Dispatchers.IO) {
        val c = selfConfig() ?: return@withContext emptyList()
        val since = System.currentTimeMillis() - 24 * 3_600_000L
        val out = ArrayList<MailParse.Mail>(); val to = HashMap<String, String>()
        for (b in selfBoxes().sortedByDescending { it.createdAt }.take(8)) runCatching {
            val (code, body) = get("${c.base}/api/messages?to=" + java.net.URLEncoder.encode(b.address, "UTF-8") + "&since=$since&limit=50", c.token)
            if (code in 200..299) { out += SelfMailCore.parseMessages(body); to += SelfMailCore.toOf(body) }
        }
        val list = out.distinctBy { it.id }.sortedByDescending { it.at }
        _selfMails.value = list; selfTo = to
        list
    }

    /** Recent mail of every ready AI 邮箱 (subject + preview only). */
    suspend fun recent(c: Context): List<MailParse.Mail> = withContext(Dispatchers.IO) {
        val out = ArrayList<MailParse.Mail>()
        if (ready("agentmail")) runCatching {
            val id = address("agentmail")!!
            val (code, body) = get("https://api.agentmail.to/v0/inboxes/" + java.net.URLEncoder.encode(id, "UTF-8") + "/messages?limit=10", key("agentmail"))
            if (code in 200..299) out += CodeSourcesCore.agentMailMessages(body)
        }
        if (ready("selfhost")) runCatching { out += selfRecent() }
        if (ready("mailtm")) runCatching {
            mailTmToken()?.let { t -> val (code, body) = get("https://api.mail.tm/messages?page=1", t); if (code in 200..299) out += CodeSourcesCore.mailTmMessages(body) }
        }
        out
    }

    fun disconnect(id: String) {
        if (id == "selfhost") { PlusVault.setSecretExtra(V, "selfhost_url", null); PlusVault.setSecretExtra(V, "selfhost_token", null); _selfMails.value = emptyList() }
        setKey(id, null); setAddress(id, null)
        if (id == "mailtm") PlusVault.setSecretExtra(V, "mailtm_pass", null)
    }
}
