package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.exp

/**
 * [Mines-digest-31] 消息汇总: messages from the user's sources, ranked by an
 * algorithm (not by time alone), all on the phone.
 *
 * Sources: phone notifications (WeChat, Gmail app, …) via the notification
 * listener, GitHub notifications (connected GitHub token), GitHub Trending
 * (daily, filtered by the follow list), 账号巡查 problems, and X posts read
 * through the user's own logged-in X session (DigestX, WebView).
 *
 * score = 3·关注匹配 + 来源权重(学自点开/置顶/静音) + 2·紧急词 + 2.5·要处理
 *         + 2·新鲜度(e^(−age/12h)) − 3·噪音(群发/通讯/赏金刷屏)
 * Category: 要处理 / 可薅 / 可学 / 飙升 / 其他.
 */
object Digest {
    enum class Cat(val label: String) { ACT("要处理"), DEAL("值得关注"), LEARN("可学"), HOT("飙升"), OTHER("其他") }
    /** [Mines-feed-35.1] 消息 page tabs (user 2026-10-14): each item lives in exactly one, by source first. */
    enum class Tab(val label: String) { ACCOUNT("账号异常"), FOLLOW("我关注的"), X("X"), GITHUB("GitHub"), DEAL("值得关注"), OTHER("其他") }

    data class Item(
        val id: String,
        val source: String,        // "微信", "GitHub", "Gmail", "X", "Trending", "账号巡查", app label
        val sourceKey: String,     // weight key: package / "github" / "x:@user" …
        val sender: String,
        val title: String,
        val summary: String,       // one line: what it needs from the user
        val at: Long,
        val url: String? = null,
        val pkg: String? = null,
        val stars: String? = null,
        var score: Double = 0.0,
        var cat: Cat = Cat.OTHER,
        var tab: Tab = Tab.OTHER,
        var pinned: Boolean = false,
        // [Mines-feed-32] takeaway headline + plain summary (cheap free model in batches, rule fallback), why it ranked
        var headline: String? = null,
        var brief: String? = null,
        var why: String = "",
        /** what 「行动起来」 does: "kind:<id>" opens that service on 应用, "url:<u>" opens it, null = ask Mines in chat */
        var act: String? = null,
        /** [Mines-feed-32] 「包含的内容」: what Mines will do on 开始吧 (title to subtitle) */
        var steps: List<Pair<String, String>> = emptyList(),
        /** link-preview picture (og:image), cached */
        var image: String? = null,
        /** how many raw items were merged into this card */
        var merged: Int = 1,
    )

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    @Volatile var lastError: String? = null; private set
    /** [Mines-38.9.2] when the last refresh finished (0 = never this process); the page refreshes on open when older than 30 s */
    @Volatile var lastRefreshAt = 0L; private set
    /** [Mines-38.9.2] cards that arrived with the last open/pull: shown first (after 置顶) until the next one */
    private val _fresh = MutableStateFlow<Set<String>>(emptySet())
    val fresh: StateFlow<Set<String>> = _fresh.asStateFlow()
    private val gate = FeedRefresh.Gate()
    /** reads started from the page run here, so leaving the page never cancels a read half-way */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var extrasRunning = false
    /** adb self-test only (msgfeed): a fake source; called once per refresh with the read number (1, 2, …) */
    @Volatile var testSource: ((Int) -> List<Item>)? = null
    @Volatile private var testReads = 0
    fun resetTestReads() { testReads = 0 }

    private const val PREFS = "mines_digest"
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    // ---- follow list + learned weights ----
    fun follows(c: Context): List<String> = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("follows", null)
        ?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: listOf("羊毛", "免费", "白嫖", "空投", "airdrop", "free credits", "giveaway", "教程", "Gemini", "Claude", "DeepSeek", "OKX", "BTC")
    fun setFollows(c: Context, list: List<String>) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("follows", list.joinToString("\n")).apply()
    /** [Mines-feed-32] 「我关注的」 important senders (people, accounts, repos). */
    fun senders(c: Context): List<String> = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("senders", "")!!
        .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    fun setSenders(c: Context, list: List<String>) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("senders", list.joinToString("\n")).apply()
    /** ❤ more like this */
    fun like(c: Context, it: Item) { bump(c, it.sourceKey, 0.8); bump(c, "sender:" + it.sender, 1.0); learn(c, textOf(it), 1.2); rerank(c) }
    fun languages(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("langs", "") ?: ""
    fun setLanguages(c: Context, v: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("langs", v).apply()

    private fun weights(c: Context): JSONObject = runCatching { JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("w", "{}")!!) }.getOrDefault(JSONObject())
    private fun saveWeights(c: Context, w: JSONObject) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("w", w.toString()).apply()
    private fun toggleSet(c: Context, name: String, value: String?) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = (p.getStringSet(name, emptySet()) ?: emptySet()).toMutableSet()
        if (value == null) return; if (!s.add(value)) s.remove(value)
        p.edit().putStringSet(name, s).apply()
    }
    private fun prefSet(c: Context, name: String): Set<String> = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(name, emptySet()) ?: emptySet()

    /** Feedback ---------------------------------------------------------- */
    fun opened(c: Context, it: Item) { bump(c, it.sourceKey, 0.4); bump(c, "sender:" + it.sender, 0.6); learn(c, textOf(it), 0.5); rerank(c) }

    // ---- [Mines-38.5] 为你推荐: a local interest model (user 「人家都有算法排好你搜索和你最近观看的」) ----
    // Each card's words feed a keyword → weight map: search terms +1.5, open +0.5, read ≥ 8 s +0.8, like +1.2,
    // 不感兴趣 −1.2, mute −0.6. Weights fade with a 14-day half-life, so recent interests count most. A card's score
    // gets 1.6 × the sum of its words' weights (capped ±6) on top of source weight, freshness and the rules below.
    private fun textOf(it: Item) = it.title + " " + (it.headline ?: "") + " " + it.summary
    fun tokens(t: String): List<String> {
        val low = t.lowercase()
        val en = Regex("[a-z][a-z0-9+#.-]{2,}").findAll(low).map { it.value.trimEnd('.', '-') }.filter { it.length >= 3 && it !in stop }.toList()
        val zh = Regex("[\\u4e00-\\u9fa5]{2,}").findAll(low).flatMap { m -> val w = m.value; (0 until w.length - 1).asSequence().map { w.substring(it, it + 2) } }
            .filter { it !in zhStop }.toList()
        return (en + zh).distinct().take(40)
    }
    private val stop = setOf("the", "and", "for", "with", "you", "this", "that", "are", "from", "your", "have", "has", "was", "will", "not", "but", "can", "all", "new", "http", "https", "com", "www")
    private val zhStop = setOf("一个", "我们", "你的", "可以", "这个", "什么", "没有", "就是", "已经", "因为", "所以", "如果", "他们", "自己", "大家", "今天")
    private fun interest(c: Context): JSONObject = runCatching { JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("iw", "{}")!!) }.getOrDefault(JSONObject())
    fun learn(c: Context, text: String, d: Double) {
        val now = System.currentTimeMillis()
        val w = interest(c)
        for (t in tokens(text)) {
            val o = w.optJSONObject(t)
            val old = o?.let { decay(it.optDouble("w", 0.0), it.optLong("t", now), now) } ?: 0.0
            w.put(t, JSONObject().put("w", (old + d).coerceIn(-5.0, 5.0)).put("t", now))
        }
        // keep the 400 strongest
        if (w.length() > 400) {
            val keep = w.keys().asSequence().toList().sortedByDescending { kotlin.math.abs(w.optJSONObject(it)?.optDouble("w", 0.0) ?: 0.0) }.take(400).toSet()
            w.keys().asSequence().toList().filter { it !in keep }.forEach { w.remove(it) }
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("iw", w.toString()).apply()
    }
    private fun decay(w: Double, t: Long, now: Long) = w * Math.pow(0.5, (now - t).coerceAtLeast(0) / (14 * 86_400_000.0))
    /** Search terms typed anywhere in Mines (conversation search, 知识库 search) teach the feed what the user cares about. */
    fun recordSearch(c: Context, q: String) { if (q.isNotBlank()) { learn(c, q, 1.5); rerank(c) } }
    /** Detail sheet stayed open ≥ 8 s: the user actually read it. */
    fun dwelled(c: Context, it: Item, ms: Long) { if (ms >= 8000) { learn(c, textOf(it), 0.8); rerank(c) } }
    private fun interestScore(iw: JSONObject, it: Item, now: Long): Double =
        tokens(textOf(it)).sumOf { t -> iw.optJSONObject(t)?.let { o -> decay(o.optDouble("w", 0.0), o.optLong("t", now), now) } ?: 0.0 }.coerceIn(-6.0, 6.0)
    fun pin(c: Context, it: Item) { toggleSet(c, "pinned", it.id); bump(c, "sender:" + it.sender, 1.0); rerank(c) }
    fun mute(c: Context, it: Item) { toggleSet(c, "muted", it.sourceKey); learn(c, textOf(it), -0.6); rerank(c) }
    fun notInterested(c: Context, it: Item) {
        toggleSet(c, "hidden", it.id); bump(c, it.sourceKey, -0.8); bump(c, "sender:" + it.sender, -1.0); learn(c, textOf(it), -1.2); rerank(c)
    }
    /** [Mines-feed-35.1] 「忽略」 on the card: hide this one (persisted), undo puts it back. */
    fun ignore(c: Context, it: Item) { editSet(c, "hidden", it.id, add = true); rerank(c) }
    fun unignore(c: Context, it: Item) { editSet(c, "hidden", it.id, add = false); rerank(c) }
    private fun editSet(c: Context, name: String, value: String, add: Boolean) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = (p.getStringSet(name, emptySet()) ?: emptySet()).toMutableSet()
        if (add) s.add(value) else s.remove(value)
        p.edit().putStringSet(name, s).apply()
    }
    private fun bump(c: Context, k: String, d: Double) {
        val w = weights(c); w.put(k, (w.optDouble(k, 0.0) + d).coerceIn(-5.0, 5.0)); saveWeights(c, w)
    }

    // ---- scoring ----
    private val urgent = Regex("(?i)urgent|asap|deadline|today|expire|expiring|due|紧急|马上|尽快|截止|到期|今天|逾期|最后")
    private val action = Regex("(?i)approve|review requested|mention|assigned|security|sign-in|password|verify|payment|invoice|failed|2fa|login|请回复|回复我|审批|批准|验证码|安全|登录|异地|密码|付款|账单|扣款|失败|待处理|@你")
    private val deal = Regex("(?i)free|giveaway|airdrop|coupon|discount|promo|credits|白嫖|羊毛|免费|空投|优惠|折扣|领取|赠送|福利|薅")
    private val learn = Regex("(?i)tutorial|guide|how to|course|paper|learn|thread|docs|教程|指南|学习|课程|论文|入门|干货|笔记")
    // [Mines-patrol-32] security e-mails / notifications (new sign-in, password change) always go to 要处理
    private val security = Regex("(?i)new sign-in|new login|security alert|password (was )?changed|unusual sign|suspicious|新设备登录|异地登录|登录提醒|安全提醒|安全警告|密码已(修改|更改|重置)|账号异常")
    private val accountWords = Regex("(?i)余额|balance|欠费|充值|密钥|api ?key|token|quota|额度(用完|不足)|429|rate.?limit|账号|account|登录|sign-?in|login|password|密码|验证|2fa|billing|invoice|payment|扣款|账单|过期|expired|suspend|封禁|停用|权限")
    private val failWords = Regex("(?i)fail|失败|error|cancel")
    private val noise = Regex("(?i)newsletter|unsubscribe|digest|no-reply|noreply|marketing|bounty|claude-builders-bounty|weekly|推广|广告|订阅|退订|营销")

    fun scoreAll(c: Context, raw: List<Item>): List<Item> {
        val w = weights(c)
        val fol = follows(c).map { it.lowercase() }
        val snd = senders(c).map { it.lowercase() }
        val pinned = prefSet(c, "pinned"); val muted = prefSet(c, "muted"); val hidden = prefSet(c, "hidden")
        val now = System.currentTimeMillis()
        val iw = interest(c)
        // flood detection: one sender with many items in a day is noise (e.g. bounty bots)
        val perSender = raw.groupingBy { it.sourceKey + "|" + it.sender }.eachCount()
        return raw.filter { it.id !in hidden && it.sourceKey !in muted }.map { it ->
            val text = (it.title + " " + it.summary + " " + it.sender).lowercase()
            val f = fol.count { k -> text.contains(k) }.coerceAtMost(3)
            val ageH = (now - it.at).coerceAtLeast(0) / 3_600_000.0
            val flood = (perSender[it.sourceKey + "|" + it.sender] ?: 1) >= 8
            val sec = security.containsMatchIn(text)
            val sm = snd.any { k -> it.sender.lowercase().contains(k) || it.sourceKey.lowercase().contains(k) }
            val isAct = action.containsMatchIn(text) || it.source == "账号巡查" || sec
            val nz = (if (noise.containsMatchIn(text)) 1.0 else 0.0) + (if (flood) 1.0 else 0.0)
            it.score = 3.0 * f + w.optDouble(it.sourceKey, 0.0) + w.optDouble("sender:" + it.sender, 0.0) +
                (if (urgent.containsMatchIn(text)) 2.0 else 0.0) + (if (isAct) 2.5 else 0.0) +
                2.0 * exp(-ageH / 12.0) - 3.0 * nz + (if (it.source == "Trending") 0.5 else 0.0) +
                (if (sec) 3.0 else 0.0) + (if (sm) 4.0 else 0.0) + (if (it.source == "免费额度") 1.5 else 0.0) + 1.6 * interestScore(iw, it, now)
            it.why = listOfNotNull(
                if (f > 0) "命中你关注的词 $f 个" else null, if (sm) "来自你关注的人" else null,
                if (sec) "安全提醒" else null, if (isAct) "需要你处理" else null,
                if (urgent.containsMatchIn(text)) "有时间要求" else null,
                if (ageH < 6) "很新（${ageH.toInt()} 小时内）" else null,
                interestScore(iw, it, now).takeIf { v -> kotlin.math.abs(v) >= 0.5 }?.let { v -> if (v > 0) "和你最近搜索、常看的内容相关" else "和你不感兴趣的内容相似，已降权" },
                if (nz > 0) "像广告或刷屏，已降权" else null,
                w.optDouble(it.sourceKey, 0.0).takeIf { v -> v != 0.0 }?.let { v -> if (v > 0) "你常看这个来源" else "你常忽略这个来源" },
            ).joinToString("；").ifBlank { "按时间排序" } + "\n来源：${it.source} · ${it.sender}"
            it.pinned = it.id in pinned
            if (it.brief == null) DigestWriter.cachedFull(c, it.id)?.let { w -> if (it.headline == null) it.headline = w.h; it.brief = w.s; if (it.steps.isEmpty()) it.steps = w.steps }
            if (it.image == null) it.url?.let { u -> it.image = DigestWriter.preview(c, u) }
            if (it.pinned) it.score += 100.0
            it.cat = when {
                isAct && nz < 1 -> Cat.ACT
                it.source == "Trending" || it.stars != null -> Cat.HOT
                it.source == "免费额度" || deal.containsMatchIn(text) -> Cat.DEAL
                learn.containsMatchIn(text) -> Cat.LEARN
                else -> Cat.OTHER
            }
            // [Mines-feed-35.1] tab: 账号异常 only for real account problems; then 我关注的 senders; then by source
            val isX = it.source == "X"
            val isGh = it.source == "GitHub" || it.source == "Trending"
            val all = text + " " + (it.headline ?: "").lowercase()
            val ciFail = isGh && it.summary.startsWith("CI 结果") && failWords.containsMatchIn(all)
            val ghSec = isGh && it.summary.startsWith("安全警告")
            val account = it.source == "账号巡查" || ciFail || ghSec || (sec && !isX) ||
                (it.cat == Cat.ACT && !isX && !isGh && accountWords.containsMatchIn(text))
            it.tab = when {
                account -> Tab.ACCOUNT
                // [Mines-warm-36] X = 「我关注的」 (people the user follows + X's picks): X posts stay in X even when
                // they mention a GitHub repo or come from a followed sender; GitHub stays GitHub.
                isX -> Tab.X
                isGh -> Tab.GITHUB
                sm -> Tab.FOLLOW
                it.source == "免费额度" || it.cat == Cat.DEAL -> Tab.DEAL
                else -> Tab.OTHER
            }
            it
        }.sortedByDescending { it.score }
    }

    private var lastRaw: List<Item> = emptyList()
    fun rerank(c: Context) { publish(scoreAll(c, lastRaw)) }
    /** [Mines-38.9.2] new cards (this open / pull) go to the top, right after 置顶 */
    private fun publish(scored: List<Item>) { _items.value = FeedRefresh.freshFirst(scored, _fresh.value, { it.id }, { it.pinned }) }

    /** [Mines-feed-35.3] adb self-test only: extra cards that survive refresh (smoke taps their buttons). */
    @Volatile private var injected: List<Item> = emptyList()
    fun inject(c: Context, list: List<Item>) {
        injected = list
        lastRaw = lastRaw.filterNot { r -> list.any { it.id == r.id } } + list
        rerank(c)
    }

    /** [Mines-patrol-35.1] an account was fixed / is being re-checked: its 账号巡查 cards leave the feed now. */
    fun dropAccount(c: Context, kindId: String) {
        lastRaw = lastRaw.filterNot { it.id.startsWith("pt:$kindId:") }
        rerank(c)
    }

    fun top(n: Int = 3): List<Item> = _items.value.take(n)

    // ---- sources ----
    private fun notifications(c: Context): List<Item> = runCatching {
        val arr = com.openminis.app.offload.MinisNotificationListenerService.getActiveNotifications() ?: return emptyList()
        val pm = c.packageManager
        arr.filter { it.packageName != c.packageName && !it.isOngoing }.mapNotNull { sbn ->
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return@mapNotNull null
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            val src = when { sbn.packageName.contains("tencent.mm") -> "微信"; sbn.packageName.contains("google.android.gm") -> "Gmail"; else -> app }
            Item("n:" + sbn.key, src, sbn.packageName, title, title, oneLine(text), sbn.postTime, pkg = sbn.packageName)
        }
    }.getOrDefault(emptyList())

    private fun ghToken() = (PlusVault.defaultAccount("github")?.let { PlusVault.secret(it) } ?: PlusVault.secret("github"))?.takeIf { it.isNotBlank() }

    private fun githubNotifications(): List<Item> {
        val t = ghToken() ?: return emptyList()
        val body = http.newCall(Request.Builder().url("https://api.github.com/notifications?per_page=40")
            .header("Authorization", "Bearer $t").header("Accept", "application/vnd.github+json").build()).execute().use {
                if (it.code == 401) throw FeedRefresh.SourceFail("GitHub 令牌失效")   // [Mines-38.9.2] was a silent empty list
                if (it.isSuccessful) it.body?.string() else throw FeedRefresh.SourceFail("GitHub 返回 ${it.code}")
            } ?: return emptyList()
        val a = JSONArray(body)
        return (0 until a.length()).map { i ->
            val n = a.getJSONObject(i)
            val repo = n.optJSONObject("repository")?.optString("full_name").orEmpty()
            val sub = n.optJSONObject("subject") ?: JSONObject()
            val reason = n.optString("reason")
            val need = when (reason) {
                "review_requested" -> "请你审代码"; "mention", "team_mention" -> "有人 @你"; "assign" -> "分配给你"
                "security_alert" -> "安全警告"; "ci_activity" -> "CI 结果"; "author" -> "你发起的有更新"; else -> "有更新"
            }
            val apiUrl = sub.optString("url")
            val web = apiUrl.replace("https://api.github.com/repos/", "https://github.com/").replace("/pulls/", "/pull/")
            Item("gh:" + n.optString("id"), "GitHub", "github:$repo", repo, sub.optString("title"), "$need · ${sub.optString("type")} · ${sub.optString("title")}".take(120),
                java.time.Instant.parse(n.optString("updated_at", "1970-01-01T00:00:00Z")).toEpochMilli(), url = web.ifBlank { "https://github.com/notifications" })
        }
    }

    /** github.com/trending (daily), filtered by the user's languages; each item shows stars gained. */
    private fun trending(c: Context): List<Item> {
        val langs = languages(c).split(',', ' ', '，').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val urls = if (langs.isEmpty()) listOf("https://github.com/trending?since=daily") else langs.take(3).map { "https://github.com/trending/${java.net.URLEncoder.encode(it, "UTF-8")}?since=daily" }
        val out = ArrayList<Item>()
        for (u in urls) {
            val html = runCatching { http.newCall(Request.Builder().url(u).header("User-Agent", MINES_MOBILE_UA).build()).execute().use { it.body?.string() } }.getOrNull() ?: continue
            html.split("<article").drop(1).take(15).forEach { art ->
                val repo = Regex("""<h2[^>]*>\s*<a[^>]*href="/([^"]+)"""").find(art)?.groupValues?.get(1)?.trim() ?: return@forEach
                val desc = Regex("""<p[^>]*>([\s\S]*?)</p>""").find(art)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim().orEmpty()
                val stars = Regex("""([\d,]+)\s+stars\s+today""").find(art)?.groupValues?.get(1)
                out += Item("tr:$repo", "Trending", "trending", repo.substringBefore('/'), repo, oneLine((stars?.let { "今日 +$it ★ · " } ?: "") + desc),
                    System.currentTimeMillis() - 3_600_000, url = "https://github.com/$repo", stars = stars)
            }
        }
        return out.distinctBy { it.id }
    }

    private fun patrol(): List<Item> = AccountPatrol.problems().map { (k, s) ->
        val name = if (k.startsWith("vps:")) "服务器" else PlusVault.kind(k)?.name ?: k
        Item("pt:$k:${s.reason.hashCode()}", "账号巡查", "patrol", name, "$name：${s.level.label}", s.reason, s.at,
            act = if (k.startsWith("vps:")) "route:" + com.openminis.app.ui.plus.PlusRoutes.WORK
                else if (s.fix == "regen") "fix:$k" else "kind:" + PlusVault.baseId(k))
    }

    /** [Mines-offers-32] 可薅: curated free-credit offers for services not connected yet. */
    private fun offers(c: Context): List<Item> = FreeOffers.relevant(c).map { o ->
        Item("of:" + o.id, "免费额度", "offers", PlusVault.kind(o.kind)?.name ?: o.kind, o.title, oneLine(o.detail), System.currentTimeMillis() - 2 * 3_600_000,
            url = o.source, act = "kind:" + o.kind).also { it.headline = o.title; it.brief = o.detail + (FreeOffers.needsText(o).let { n -> if (n.isBlank()) "" else "\n$n" }) }
    }

    /**
     * [Mines-feed-32] merge repetitive items into one card: GitHub CI runs of a repo →
     * "Mines 构建 3 次成功", many notifications from one chat app sender → one card.
     */
    private fun mergeDupes(raw: List<Item>): List<Item> {
        val out = ArrayList<Item>()
        val groups = raw.groupBy { r ->
            when {
                r.source == "GitHub" && r.summary.startsWith("CI 结果") -> "ci|" + r.sourceKey
                r.id.startsWith("n:") -> "n|" + r.sourceKey + "|" + r.sender
                else -> "id|" + r.id
            }
        }
        for ((k, list) in groups) {
            if (list.size == 1 || k.startsWith("id|")) { out += list; continue }
            val first = list.maxBy { it.at }
            val m = first.copy(id = "m:" + k.hashCode(), merged = list.size,
                summary = if (k.startsWith("ci|")) "CI 结果 ${list.size} 次 · " + list.take(3).joinToString("；") { it.title }.take(120)
                    else "${list.size} 条：" + list.sortedByDescending { it.at }.take(3).joinToString("；") { it.summary }.take(130))
            if (k.startsWith("ci|")) {
                val fail = list.count { it.title.contains("fail", true) || it.summary.contains("失败") }
                val repo = first.sender.substringAfter('/')
                m.headline = if (fail == 0) "$repo 构建 ${list.size} 次，全部成功" else "$repo 构建 ${list.size} 次，有 $fail 次失败"
            }
            out += m
        }
        return out
    }

    /** [Mines-warm-36] never 「GitHub：null」: an empty/null reason becomes a plain sentence. */
    private fun errLine(what: String, t: Throwable): String =
        t.message?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { "$what：$it" } ?: "$what 暂时连不上"

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim().take(140)

    /**
     * [Mines-38.9.2] Re-read the sources. BACKGROUND (patrol, home card) skips when a refresh is running and may use
     * each source's cache; OPEN (page opened / resumed) and PULL wait for a running one and then read again —
     * PULL with no cache at all. Returns what was new and which sources failed (null = skipped).
     */
    suspend fun refresh(c: Context, mode: FeedRefresh.Mode = FeedRefresh.Mode.BACKGROUND): FeedRefresh.Outcome? = withContext(Dispatchers.IO) {
        val out = gate.run(waitIfBusy = mode != FeedRefresh.Mode.BACKGROUND) { readAll(c, mode) }
        if (out != null) slowExtras(c, mode)
        out
    }

    /** The page's pull / open: runs in Digest's own scope (survives leaving the page); the caller awaits the outcome. */
    fun refreshAsync(c: Context, mode: FeedRefresh.Mode): Deferred<FeedRefresh.Outcome?> {
        val app = c.applicationContext
        return bg.async { refresh(app, mode) }
    }

    private suspend fun readAll(c: Context, mode: FeedRefresh.Mode): FeedRefresh.Outcome {
        _busy.value = true
        try {
            PlusVault.init(c)
            val before = _items.value.map { it.id }.toSet()
            val errs = java.util.Collections.synchronizedList(ArrayList<String>())   // sources run in parallel
            val live = mode != FeedRefresh.Mode.BACKGROUND
            val parts = coroutineScope {
                listOf(
                    async { notifications(c) },
                    async { runCatching { githubNotifications() }.getOrElse { errs += FeedRefresh.netFail("GitHub", it); emptyList() } },
                    async { runCatching { trending(c) }.getOrElse { errs += FeedRefresh.netFail("GitHub 热门", it); emptyList() } },
                    async { patrol() },
                    async { runCatching { Keychain.digestItems(c) }.getOrDefault(emptyList()) },   // [Mines-38.9] new-device sign-ins
                    // [Mines-38.9.2] X is read with the other sources on open / pull (was: only after, at most every 10 min)
                    async {
                        if (live) runCatching {
                            when (AutoSources.readX(c, FeedRefresh.maxAgeMs(mode, 10 * 60_000L), deadlineMs = System.currentTimeMillis() + 25_000L)) {
                                AutoSources.XRead.TIMEOUT -> { errs.add("X 读取超时") }
                                AutoSources.XRead.FAILED -> { errs.add("X 没读到（登录可能过期）") }
                                else -> Unit
                            }
                        }
                        DigestX.cached()
                    },
                    async { runCatching { FreeOffers.load(c); offers(c) }.getOrDefault(emptyList()) },
                    // [Mines-sources-33] every signed-in source feeds the digest with no setup
                    async { runCatching { MailSources.digestItems(c, FeedRefresh.maxAgeMs(mode, 5 * 60_000L), errs) }.getOrElse { errs += FeedRefresh.netFail("邮箱", it); emptyList() } },
                    async { runCatching { AutoSources.githubWebItems() }.getOrDefault(emptyList()) },
                    async { runCatching { SourceAccounts.items(c, FeedRefresh.maxAgeMs(mode, 10 * 60_000L)) }.getOrDefault(emptyList()) },  // [Mines-38.5] 微博 / B站 / 知乎
                    async { injected.map { it.copy() } },
                    async { testSource?.let { f -> runCatching { f(++testReads) }.getOrDefault(emptyList()) } ?: emptyList() },
                ).awaitAll()
            }
            lastRaw = mergeDupes(parts.flatten())
            val fails = errs.toList().distinct()
            lastError = fails.joinToString("；").ifBlank { null }
            val scored = scoreAll(c, lastRaw)
            val fresh = FeedRefresh.freshIds(before, scored.map { it.id }, countAll = mode == FeedRefresh.Mode.PULL || lastRefreshAt > 0)
            if (live) _fresh.value = fresh.toSet()
            publish(scored)
            lastRefreshAt = System.currentTimeMillis()
            return FeedRefresh.Outcome(fresh, fails)
        } finally { _busy.value = false }
    }

    /** Link previews + model headlines (slow, network / model) — after the cards are on screen, never under the spinner. */
    private fun slowExtras(c: Context, mode: FeedRefresh.Mode) {
        if (extrasRunning) return
        extrasRunning = true
        bg.launch {
            try {
                runCatching { DigestWriter.previews(c, lastRaw.filter { it.url != null }.take(20)); publish(scoreAll(c, lastRaw)) }
                // [Mines-feed-32] headlines for the top cards: free model, batched, cached, daily cap
                runCatching { if (DigestWriter.writeAll(c, _items.value.take(36))) publish(scoreAll(c, lastRaw)) }
                // [Mines-38.9.11 c] 每日简报: build today's after 07:00 when auto is on (once a day, failed tries wait an hour)
                if (mode == FeedRefresh.Mode.BACKGROUND) runCatching { Horizon.autoIfDue(c) }
                // [Mines-sources-33] background refreshes read X off-screen after the fast sources (throttled 10 min)
                if (mode == FeedRefresh.Mode.BACKGROUND) runCatching {
                    if (AutoSources.readX(c) == AutoSources.XRead.OK) {
                        lastRaw = mergeDupes(lastRaw.filterNot { it.source == "X" } + DigestX.cached())
                        publish(scoreAll(c, lastRaw))
                    }
                }
            } finally { extrasRunning = false }
        }
    }
}

/**
 * [Mines-digest-31] X / Twitter through the user's own logged-in X session
 * (the shared WebView cookie jar — log in once under 账号连接 → X). Reads the
 * search page for followed keywords (live) and bookmarks; no paid API.
 */
object DigestX {
    @Volatile private var cache: List<Digest.Item> = emptyList()
    fun cached() = cache
    fun store(items: List<Digest.Item>) { cache = items }

    /** JS run on x.com pages: posts as JSON [{u,t,url,ts}]. */
    const val EXTRACT_JS = """(function(){var out=[];document.querySelectorAll('article[data-testid=tweet]').forEach(function(a){
      try{var u=(a.querySelector('[data-testid=User-Name]')||{}).innerText||'';var t=(a.querySelector('[data-testid=tweetText]')||{}).innerText||'';
      var l=a.querySelector('a[href*="/status/"]');var tm=a.querySelector('time');
      out.push({u:u.split('\n')[0],h:(u.match(/@\w+/)||[''])[0],t:t,url:l?l.href:'',ts:tm?tm.getAttribute('datetime'):''});}catch(e){}});return JSON.stringify(out.slice(0,25));})()"""

    fun parse(json: String, query: String): List<Digest.Item> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val t = o.optString("t"); if (t.isBlank()) return@mapNotNull null
            val ts = runCatching { java.time.Instant.parse(o.optString("ts")).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
            Digest.Item("x:" + o.optString("url").ifBlank { t.hashCode().toString() }, "X", "x:" + o.optString("h"), o.optString("u"),
                t.take(80), (if (query.isNotBlank()) "「$query」 · " else "") + t.replace(Regex("\\s+"), " ").take(130), ts, url = o.optString("url").ifBlank { null })
        }
    }.getOrDefault(emptyList())
}
