package com.openminis.app.plus

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.5] 消息来源 = the user's own source ACCOUNTS (user 2026-10-15 「消息最好好撑可以添加不同的账号连接」).
 * One row per signed-in account (several per platform allowed, the 账号连接 multi-account ids "x", "x#2" …),
 * with its status; 添加 opens that platform's sign-in in 账号连接, 移除 signs it out. Platforms with a reader feed
 * the 消息 page: X (DigestX), GitHub (token / web), 邮箱 (MailSources), and — new in 38.5 — 微博, B站, 知乎 read
 * through the signed-in cookie jar (default account), all best effort and silent on failure.
 */
object SourceAccounts {
    data class Platform(val kind: String, val name: String, val icon: String, val hint: String, val url: String? = null)

    val platforms = listOf(
        Platform("x", "X", "globe", "你关注的人和话题"),
        Platform("github", "GitHub", "code", "@你、审代码、安全提醒、Trending"),
        Platform("weibo", "微博", "globe", "关注的人的新微博"),
        Platform("bilibili", "B站", "globe", "关注的 UP 主动态"),
        Platform("site:www.zhihu.com", "知乎", "book-open", "推荐和关注的回答", "https://www.zhihu.com/signin"),
        Platform("xiaohongshu", "小红书", "image", "登录后可在对话里让 Mines 帮你看"),
        Platform("gmail", "邮箱", "mail", "验证码、账单、重要邮件"),
        Platform("wechat_mp", "公众号", "file-text", "在微信里把文章分享给 Mines 就会收进来"),
    )

    data class Row(val platform: Platform, val entryId: String?, val title: String, val account: String, val status: String, val ok: Boolean?)

    fun rows(context: Context): List<Row> {
        PlusVault.init(context)
        val problems = runCatching { AccountPatrol.problems().toMap() }.getOrDefault(emptyMap())
        val out = ArrayList<Row>()
        for (p in platforms) {
            when (p.kind) {
                "gmail" -> {
                    val boxes = runCatching { MailSources.boxes(context) }.getOrDefault(emptyList())
                    boxes.forEach { b -> out += Row(p, null, b.label, b.how, "正常", true) }
                }
                "wechat_mp" -> {}
                else -> {
                    val ids = PlusVault.accountsOf(p.kind)
                    ids.forEachIndexed { i, id ->
                        val e = PlusVault.entries.value[id]
                        val kind = PlusVault.kind(id)
                        val signed = kind?.let { runCatching { LoginWeb.signedIn(it) }.getOrDefault(false) } == true || PlusVault.secret(id) != null
                        val prob = problems[id]
                        val acct = e?.account?.takeIf { it.isNotBlank() }?.let { AccountLabel.maskText(it) } ?: "账号 ${i + 1}"
                        // [Mines-38.6] 微博 / B站 / 知乎: the last real read says what happened (never a silent blank)
                        val rr = lastResult[id] ?: lastResult[p.kind].takeIf { i == 0 }
                        out += Row(p, id, p.name, acct, when { prob != null -> "要处理：${prob.reason.take(30)}"; signed && rr != null -> rr.text; signed -> "正常"; else -> "登录过期，点一下重新登录" },
                            if (prob != null || !signed || (rr != null && !rr.ok)) false else true)
                    }
                    if (ids.isEmpty() && (p.kind == "x" && AutoSources.xSignedIn() || p.kind == "github" && (AutoSources.githubSession() || AutoSources.githubToken() != null)))
                        out += Row(p, null, p.name, "已登录", "正常", true)
                }
            }
        }
        return out
    }

    /** Opens the sign-in for one more account of [p] (账号连接 → that service → 连接 / 添加账号). */
    fun add(p: Platform) {
        if (p.kind == "gmail") { com.openminis.app.ui.plus.PlusNav.openKind = "google"; com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.CONNECTIONS); return }
        if (p.url != null) PlusVault.setExtra(p.kind, "url", p.url)
        com.openminis.app.ui.plus.PlusNav.openKind = p.kind
        com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.plus.PlusRoutes.CONNECTIONS)
    }

    fun remove(context: Context, entryId: String) {
        val k = PlusVault.kind(entryId) ?: return
        runCatching { PlusVault.remove(context, k) }
    }

    // ---------------- readers (default account, cookie jar) ----------------

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    @Volatile private var lastRead = 0L
    @Volatile private var cache: List<Digest.Item> = emptyList()

    /**
     * [Mines-38.7] one account's own session for an HTTP read: its WebView profile (2nd+ account on phones with profiles),
     * the shared jar when it is the default (signed-in) account there, else the snapshot 账号连接 parked for it.
     */
    private suspend fun accountCookie(id: String, url: String): String = withContext(Dispatchers.Main) {
        val host = PlusConnect.hostOf(url)
        if (id.contains('#') && LoginWeb.profilesSupported()) runCatching { LoginWeb.cookies(id).getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        if (PlusVault.defaultAccount(PlusVault.baseId(id)) == id || PlusVault.accountsOf(PlusVault.baseId(id)).size <= 1)
            return@withContext runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
        val raw = PlusVault.secretExtra(id, "cookies") ?: return@withContext ""
        runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.filter { o -> val h = o.optString("host"); h == host || host.endsWith("." + h.removePrefix("www.")) || h.endsWith("." + host) }
                .joinToString("; ") { it.optString("cookie") }
        }.getOrDefault("")
    }

    private suspend fun cookie(url: String): String = withContext(Dispatchers.Main) { runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty() }

    /** [Mines-38.6] one HTTP read: (status, body); status 0 = no connection. */
    private fun fetch(url: String, cookie: String, referer: String): Pair<Int, String> = runCatching {
        val b = Request.Builder().url(url).header("Referer", referer)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            .header("Accept", "application/json, text/plain, */*")
        if (cookie.isNotBlank()) b.header("Cookie", cookie)
        http.newCall(b.build()).execute().use { r -> r.code to (r.body?.string().orEmpty().take(400_000)) }
    }.getOrElse { 0 to (it.message ?: "") }

    private fun json(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

    /**
     * [Mines-38.6] What the last read of 微博 / B站 / 知乎 gave, in one plain line (shown in 消息来源 instead of a silent
     * empty list): 「读到 N 条」, 「需要登录」, 「被风控拦了，稍后再试」 or 「连不上（网络）」.
     */
    data class ReadResult(val kind: String, val ok: Boolean, val count: Int, val text: String, val http: Int, val detail: String = "")
    val lastResult = java.util.concurrent.ConcurrentHashMap<String, ReadResult>()

    private fun verdict(kind: String, name: String, code: Int, body: String, count: Int, loginCodes: Boolean): ReadResult {
        val o = json(body)
        val low = body.take(2000).lowercase()
        return when {
            code == 0 -> ReadResult(kind, false, 0, "连不上$name（网络问题）", 0, body.take(80))
            count > 0 -> ReadResult(kind, true, count, "读到 $count 条", code)
            loginCodes || code == 401 || low.contains("passport") || low.contains("login") || low.startsWith("<") ->
                ReadResult(kind, false, 0, "需要登录$name：点一下登录", code, (o?.optString("message") ?: o?.optString("msg") ?: body.take(80)).take(120))
            code == 403 || code == 412 || code == 429 || low.contains("risk") || low.contains("风控") || low.contains("-352") || low.contains("-412") ->
                ReadResult(kind, false, 0, "$name 暂时拦了自动读取，稍后再试", code, body.take(120))
            else -> ReadResult(kind, true, 0, "$name 暂时没有新内容", code, body.take(120))
        }
    }

    private fun readBili(c: String, out: MutableList<Digest.Item>?): ReadResult {
        val (code, body) = fetch("https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all?type=all", c, "https://t.bilibili.com/")
        val o = json(body)
        var n = 0
        o?.optJSONObject("data")?.optJSONArray("items")?.let { a ->
            for (i in 0 until minOf(a.length(), 15)) {
                val it = a.optJSONObject(i) ?: continue
                val mod = it.optJSONObject("modules") ?: continue
                val author = mod.optJSONObject("module_author")
                val dyn = mod.optJSONObject("module_dynamic")
                val arc = dyn?.optJSONObject("major")?.optJSONObject("archive")
                val title = arc?.optString("title")?.takeIf { s -> s.isNotBlank() } ?: dyn?.optJSONObject("desc")?.optString("text")?.take(80) ?: continue
                val name = author?.optString("name").orEmpty()
                val ts = (author?.optLong("pub_ts") ?: 0L) * 1000
                n++
                out?.add(Digest.Item("bili:" + it.optString("id_str"), "B站", "bilibili:$name", name, title,
                    arc?.optString("desc")?.take(120).orEmpty(), if (ts > 0) ts else System.currentTimeMillis(),
                    url = arc?.optString("jump_url")?.let { u -> if (u.startsWith("//")) "https:$u" else u }))
            }
        }
        return verdict("bilibili", "B站", code, body, n, o?.optInt("code") == -101)
    }

    private fun readWeibo(c: String, out: MutableList<Digest.Item>?): ReadResult {
        val (code, body) = fetch("https://m.weibo.cn/feed/friends?", c, "https://m.weibo.cn/")
        val o = json(body)
        var n = 0
        o?.optJSONObject("data")?.optJSONArray("statuses")?.let { a ->
            for (i in 0 until minOf(a.length(), 15)) {
                val st = a.optJSONObject(i) ?: continue
                val name = st.optJSONObject("user")?.optString("screen_name").orEmpty()
                val text = st.optString("text").replace(Regex("<[^>]+>"), "").trim().take(140)
                if (text.isBlank()) continue
                n++
                out?.add(Digest.Item("wb:" + st.optString("id"), "微博", "weibo:$name", name, text.take(60), text, System.currentTimeMillis(),
                    url = "https://m.weibo.cn/status/" + st.optString("id")))
            }
        }
        return verdict("weibo", "微博", code, body, n, o != null && o.optInt("ok", 1) != 1 && (o.optString("url").contains("passport") || o.optInt("ok") == -100))
    }

    private fun readZhihu(c: String, out: MutableList<Digest.Item>?): ReadResult {
        val (code, body) = fetch("https://www.zhihu.com/api/v3/feed/topstory/recommend?desktop=true&limit=10", c, "https://www.zhihu.com/")
        val o = json(body)
        var n = 0
        o?.optJSONArray("data")?.let { a ->
            for (i in 0 until minOf(a.length(), 10)) {
                val t = a.optJSONObject(i)?.optJSONObject("target") ?: continue
                val q = t.optJSONObject("question")?.optString("title") ?: t.optString("title")
                if (q.isNullOrBlank()) continue
                val name = t.optJSONObject("author")?.optString("name").orEmpty()
                n++
                out?.add(Digest.Item("zh:" + t.optString("id"), "知乎", "zhihu:$name", name, q, t.optString("excerpt").take(120),
                    (t.optLong("updated_time").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)) * 1000,
                    url = t.optString("url").takeIf { it.startsWith("http") }?.replace("api.zhihu.com/answers", "www.zhihu.com/answer")))
            }
        }
        return verdict("site:www.zhihu.com", "知乎", code, body, n, code == 401 || o?.optJSONObject("error")?.optInt("code") == 100)
    }

    suspend fun items(context: Context, maxAgeMs: Long = 10 * 60_000L): List<Digest.Item> = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lastRead < maxAgeMs) return@withContext cache   // [Mines-38.9.2] a pull passes 0
        lastRead = System.currentTimeMillis()
        val out = ArrayList<Digest.Item>()
        // [Mines-38.7] every signed-in account of a platform is read with ITS OWN session (isolated store / saved set);
        // with several accounts the items say whose they are (「B站·账号B」)
        fun each(kind: String, name: String, url: String, mark: String, read: (String, MutableList<Digest.Item>) -> ReadResult) {
            val ids = PlusVault.accountsOf(kind).ifEmpty { listOf(kind) }
            ids.forEachIndexed { i, id ->
                runCatching {
                    val c = kotlinx.coroutines.runBlocking { accountCookie(id, url) }
                    if (!c.contains(mark)) return@runCatching
                    val n0 = out.size
                    val r = read(c, out)
                    lastResult[id] = r; if (i == 0) lastResult[kind] = r
                    if (ids.size > 1) for (j in n0 until out.size) out[j] = out[j].copy(source = name + "·账号" + ('A' + i), id = out[j].id + "@" + id)
                }
            }
        }
        each("bilibili", "B站", "https://api.bilibili.com", "SESSDATA") { c, o -> readBili(c, o) }
        each("weibo", "微博", "https://m.weibo.cn", "SUB=") { c, o -> readWeibo(c, o) }
        each("site:www.zhihu.com", "知乎", "https://www.zhihu.com", "z_c0") { c, o -> readZhihu(c, o) }
        cache = out
        out
    }

    /**
     * [Mines-38.6] Self-test / 消息来源 「检查」: one real request per platform with whatever session the app has (none in
     * the smoke test) plus one public endpoint each (B站 热门, 微博 热搜, 知乎 热榜), so a broken reader shows a reason
     * instead of an empty list.
     */
    suspend fun check(context: Context): List<Pair<ReadResult, ReadResult>> = withContext(Dispatchers.IO) {
        val bc = cookie("https://api.bilibili.com"); val wc = cookie("https://m.weibo.cn"); val zc = cookie("https://www.zhihu.com")
        val own = listOf(readBili(bc, null), readWeibo(wc, null), readZhihu(zc, null))
        own.forEach { lastResult[it.kind] = it }
        fun pub(kind: String, name: String, url: String, ref: String, count: (JSONObject?) -> Int): ReadResult {
            val (code, body) = fetch(url, "", ref)
            return verdict(kind, name, code, body, count(json(body)), false)
        }
        val public = listOf(
            pub("bilibili", "B站", "https://api.bilibili.com/x/web-interface/popular?ps=5&pn=1", "https://www.bilibili.com/") { it?.optJSONObject("data")?.optJSONArray("list")?.length() ?: 0 },
            pub("weibo", "微博", "https://weibo.com/ajax/side/hotSearch", "https://weibo.com/") { it?.optJSONObject("data")?.optJSONArray("realtime")?.length() ?: 0 },
            pub("site:www.zhihu.com", "知乎", "https://www.zhihu.com/api/v3/feed/topstory/hot-lists/total?limit=5", "https://www.zhihu.com/hot") { it?.optJSONArray("data")?.length() ?: 0 },
        )
        own.zip(public)
    }
}
