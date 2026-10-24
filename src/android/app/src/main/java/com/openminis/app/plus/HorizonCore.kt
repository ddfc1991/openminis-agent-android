package com.openminis.app.plus

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.11 c] 消息看板 → 每日简报, rebuilt after Horizon (github.com/Thysrael/Horizon, MIT; user 2026-10-19 11:36 /
 * 10-20 08:46). Horizon's pipeline, ported to run on the phone with no server:
 *   1. collect (time window) from the user's own source list: RSS / Atom, Hacker News, Reddit, Telegram public channels,
 *      GitHub (repo releases, user activity), X (the user's signed-in X session), and Mines' existing feed;
 *   2. cross-source merge by a conservative URL key (scheme/host/port/path/query minus utm_* & trackers);
 *   3. AI analysis per item: score 0–10 on Horizon's tech-news rubric + reason + one-line summary + tags
 *      (heuristic score from engagement + the user's follow words when no model answers);
 *   4. threshold, then AI topic de-duplication (same real-world event only; the first index is kept, comments merged);
 *   5. balanced digest: max items + per-category cap so one busy topic does not crowd out the rest;
 *   6. enrichment in Chinese: short title, 2–4 sentence summary, 背景 (background), 网友讨论 (community discussion from the
 *      fetched comments, omitted when there is none);
 *   7. render a Markdown briefing grouped by category (saved to /var/minis/shared/每日简报/).
 * Pure (org.json only), unit-tested.
 */
object HorizonCore {
    enum class Kind(val zh: String, val hint: String) {
        RSS("RSS", "订阅地址，例如 https://simonwillison.net/atom/everything/"),
        HN("Hacker News", "最低热度分，例如 100"),
        REDDIT("Reddit", "版块名，例如 LocalLLaMA"),
        TELEGRAM("Telegram", "公开频道名，例如 durov"),
        GITHUB("GitHub", "仓库 owner/repo（看发布）或 user:名字（看动态）"),
        X("X", "@用户名 或 关键词（用你已登录的 X 读取）"),
        MINES("Mines", "Mines 已有的消息（GitHub 通知、邮箱、账号巡查…）"),
    }

    data class Source(val id: String, val kind: Kind, val value: String, val name: String, val category: String, val on: Boolean = true)

    val CATEGORIES = listOf("AI", "开发", "安全", "加密货币", "科技", "其他")

    val DEFAULTS: List<Source> = listOf(
        Source("hn", Kind.HN, "150", "Hacker News", "科技"),
        Source("r-localllama", Kind.REDDIT, "LocalLLaMA", "r/LocalLLaMA", "AI"),
        Source("r-singularity", Kind.REDDIT, "singularity", "r/singularity", "AI", on = false),
        Source("simonw", Kind.RSS, "https://simonwillison.net/atom/everything/", "Simon Willison", "AI"),
        Source("hf-blog", Kind.RSS, "https://huggingface.co/blog/feed.xml", "Hugging Face Blog", "AI"),
        Source("gh-codex", Kind.GITHUB, "openai/codex", "openai/codex 发布", "开发"),
        Source("gh-ollama", Kind.GITHUB, "ollama/ollama", "ollama 发布", "开发"),
        Source("tg-durov", Kind.TELEGRAM, "durov", "Telegram · durov", "科技", on = false),
        Source("x-follow", Kind.X, "", "X（我关注的）", "科技"),
        Source("mines", Kind.MINES, "", "Mines 已有消息", "其他"),
    )

    // ---------------------------------------------------------------- source list (user-editable)
    fun encode(list: List<Source>): String = JSONArray().apply {
        list.forEach { s -> put(JSONObject().put("id", s.id).put("kind", s.kind.name).put("value", s.value).put("name", s.name).put("cat", s.category).put("on", s.on)) }
    }.toString()

    fun decode(json: String?): List<Source> {
        if (json.isNullOrBlank()) return DEFAULTS
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val k = runCatching { Kind.valueOf(o.optString("kind")) }.getOrNull() ?: return@mapNotNull null
                Source(o.optString("id").ifBlank { "s$i" }, k, o.optString("value"), o.optString("name").ifBlank { k.zh }, o.optString("cat", "其他"), o.optBoolean("on", true))
            }
        }.getOrDefault(DEFAULTS)
    }

    /** what the user typed in 「添加来源」 → a source (type detected from the text), or null when it can't be read */
    fun parseSourceLine(raw: String, category: String = "其他"): Source? {
        val s = raw.trim().removeSuffix("/")
        if (s.isEmpty()) return null
        fun id(p: String) = p + "-" + s.lowercase().filter { it.isLetterOrDigit() }.takeLast(24)
        Regex("""(?i)^(?:https?://)?(?:www\.|old\.)?reddit\.com/r/([A-Za-z0-9_]+)""").find(s)?.let { return Source(id("r"), Kind.REDDIT, it.groupValues[1], "r/" + it.groupValues[1], category) }
        Regex("""^r/([A-Za-z0-9_]+)$""").find(s)?.let { return Source(id("r"), Kind.REDDIT, it.groupValues[1], "r/" + it.groupValues[1], category) }
        Regex("""(?i)^(?:https?://)?t\.me/(?:s/)?([A-Za-z0-9_]{4,})""").find(s)?.let { return Source(id("tg"), Kind.TELEGRAM, it.groupValues[1], "Telegram · " + it.groupValues[1], category) }
        Regex("""(?i)^(?:https?://)?(?:x|twitter)\.com/([A-Za-z0-9_]{1,15})$""").find(s)?.let { return Source(id("x"), Kind.X, "@" + it.groupValues[1], "X · @" + it.groupValues[1], category) }
        Regex("""^@([A-Za-z0-9_]{1,15})$""").find(s)?.let { return Source(id("x"), Kind.X, "@" + it.groupValues[1], "X · @" + it.groupValues[1], category) }
        Regex("""(?i)^(?:https?://)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$""").find(s)?.let { val r = it.groupValues[1] + "/" + it.groupValues[2]; return Source(id("gh"), Kind.GITHUB, r, "$r 发布", category) }
        Regex("""(?i)^(?:https?://)?github\.com/([A-Za-z0-9_-]+)$""").find(s)?.let { return Source(id("ghu"), Kind.GITHUB, "user:" + it.groupValues[1], "GitHub · " + it.groupValues[1], category) }
        Regex("""^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$""").find(s)?.let { return Source(id("gh"), Kind.GITHUB, s, "$s 发布", category) }
        if (s.equals("hn", true) || s.contains("news.ycombinator.com", true)) return Source("hn", Kind.HN, "150", "Hacker News", category)
        if (Regex("""(?i)^https?://\S+$""").matches(s)) return Source(id("rss"), Kind.RSS, s, hostOf(s).ifBlank { "RSS" }, category)
        return null
    }

    // ---------------------------------------------------------------- collected items
    data class Raw(
        val id: String, val kind: Kind, val source: String, val title: String, val url: String, val text: String, val at: Long,
        val points: Int = 0, val comments: Int = 0, val category: String = "其他", val discussion: List<String> = emptyList(),
        val sources: List<String> = listOf(source),
    )

    fun hostOf(url: String): String = Regex("""(?i)^[a-z]+://([^/:?#]+)""").find(url)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.") ?: ""

    private val TRACKERS = setOf("ref", "ref_src", "fbclid", "gclid", "mc_cid", "mc_eid", "igshid", "si", "spm", "from", "source")
    /** Horizon's conservative URL identity: scheme + host (lower, default port dropped) + path (no trailing /) + query minus trackers */
    fun urlKey(url: String): String {
        val m = Regex("""(?i)^([a-z][a-z0-9+.-]*)://([^/?#]*)([^?#]*)(?:\?([^#]*))?""").find(url.trim()) ?: return url.trim()
        val scheme = m.groupValues[1].lowercase()
        var host = m.groupValues[2].substringAfter('@').lowercase()
        if ((scheme == "http" && host.endsWith(":80")) || (scheme == "https" && host.endsWith(":443"))) host = host.substringBeforeLast(':')
        val path = m.groupValues[3].trimEnd('/').ifEmpty { "/" }
        val q = m.groupValues[4].split('&').filter { it.isNotEmpty() }.filterNot { p ->
            val n = java.net.URLDecoder.decode(p.substringBefore('='), "UTF-8").lowercase(); n.startsWith("utm_") || n in TRACKERS
        }.joinToString("&")
        return "$scheme://$host$path" + (if (q.isNotEmpty()) "?$q" else "")
    }

    /** cross-source duplicates (same URL key) → one item: the most-discussed copy, comments + source names merged */
    fun mergeCrossSource(items: List<Raw>): List<Raw> = items.groupBy { if (it.url.isBlank()) "id:" + it.id else urlKey(it.url) }.values.map { g ->
        if (g.size == 1) g[0] else {
            val p = g.maxBy { it.points + 3 * it.comments }
            p.copy(discussion = g.flatMap { it.discussion }.distinct().take(8), sources = g.flatMap { it.sources }.distinct(),
                points = g.maxOf { it.points }, comments = g.sumOf { it.comments })
        }
    }

    // ---------------------------------------------------------------- parsers (tolerant, no DOM)
    fun decodeEntities(s: String): String = s
        .replace(Regex("""<!\[CDATA\[([\s\S]*?)]]>"""), "$1")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: "" }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
    fun plain(html: String, max: Int = 600): String = decodeEntities(decodeEntities(html).replace(Regex("(?i)<br\\s*/?>|</p>"), " ").replace(Regex("<[^>]+>"), " "))
        .replace(Regex("\\s+"), " ").trim().take(max)

    /** RSS 2.0 / Atom → (title, link, epoch ms or 0, text) */
    data class FeedEntry(val title: String, val link: String, val at: Long, val text: String)
    fun parseFeed(xml: String): List<FeedEntry> {
        val blocks = Regex("""(?is)<(item|entry)[\s>].*?</\1>""").findAll(xml).map { it.value }.toList()
        return blocks.mapNotNull { b ->
            fun tag(vararg names: String): String? = names.firstNotNullOfOrNull { n -> Regex("""(?is)<$n(?:\s[^>]*)?>(.*?)</$n>""").find(b)?.groupValues?.get(1) }
            val title = plain(tag("title") ?: return@mapNotNull null, 300)
            val link = Regex("""(?is)<link[^>]*rel=["']alternate["'][^>]*href=["']([^"']+)""").find(b)?.groupValues?.get(1)
                ?: Regex("""(?is)<link[^>]*href=["']([^"']+)["']""").find(b)?.groupValues?.get(1)
                ?: tag("link")?.let { decodeEntities(it).trim() } ?: tag("guid")?.trim() ?: ""
            val date = tag("pubDate", "published", "updated", "dc:date")?.trim().orEmpty()
            FeedEntry(title, decodeEntities(link).trim(), parseDate(date), plain(tag("description", "summary", "content", "content:encoded").orEmpty()))
        }
    }

    fun parseDate(s: String): Long {
        if (s.isBlank()) return 0L
        runCatching { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return java.time.Instant.parse(s).toEpochMilli() }
        runCatching { return java.time.ZonedDateTime.parse(s, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        runCatching { return java.time.ZonedDateTime.parse(s.replace(Regex("\\s+"), " "), java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", java.util.Locale.US)).toInstant().toEpochMilli() }
        return 0L
    }

    /** hn.algolia.com /api/v1/search?tags=front_page JSON */
    fun parseHn(json: String, minPoints: Int, category: String): List<Raw> = runCatching {
        val hits = JSONObject(json).optJSONArray("hits") ?: return emptyList()
        (0 until hits.length()).mapNotNull { i ->
            val h = hits.optJSONObject(i) ?: return@mapNotNull null
            val pts = h.optInt("points", 0); if (pts < minPoints) return@mapNotNull null
            val id = h.optString("objectID"); val title = h.optString("title").ifBlank { return@mapNotNull null }
            val disc = "https://news.ycombinator.com/item?id=$id"
            Raw("hn:$id", Kind.HN, "Hacker News", title, h.optString("url").ifBlank { disc }, plain(h.optString("story_text")), h.optLong("created_at_i", 0) * 1000,
                pts, h.optInt("num_comments", 0), category)
        }
    }.getOrDefault(emptyList())

    /** hn.algolia.com /api/v1/items/<id>: the top-level comments' text (first [n]) */
    fun parseHnComments(json: String, n: Int = 5): List<String> = runCatching {
        val ch = JSONObject(json).optJSONArray("children") ?: return emptyList()
        (0 until ch.length()).mapNotNull { i -> ch.optJSONObject(i)?.optString("text")?.let { plain(it, 300) }?.takeIf { it.length > 20 } }.take(n)
    }.getOrDefault(emptyList())

    /** reddit.com/r/<sub>/top.json?t=day */
    fun parseReddit(json: String, sub: String, category: String): List<Raw> = runCatching {
        val ch = JSONObject(json).optJSONObject("data")?.optJSONArray("children") ?: return emptyList()
        (0 until ch.length()).mapNotNull { i ->
            val d = ch.optJSONObject(i)?.optJSONObject("data") ?: return@mapNotNull null
            if (d.optBoolean("stickied") || d.optBoolean("over_18")) return@mapNotNull null
            val perma = "https://www.reddit.com" + d.optString("permalink")
            val url = d.optString("url_overridden_by_dest").ifBlank { perma }
            Raw("rd:" + d.optString("id"), Kind.REDDIT, "r/$sub", decodeEntities(d.optString("title")), url, plain(d.optString("selftext")), (d.optDouble("created_utc", 0.0) * 1000).toLong(),
                d.optInt("score", 0), d.optInt("num_comments", 0), category)
        }
    }.getOrDefault(emptyList())

    /** reddit.com/comments/<id>.json?sort=top: the top comments' bodies */
    fun parseRedditComments(json: String, n: Int = 5): List<String> = runCatching {
        val arr = JSONArray(json); val ch = arr.optJSONObject(1)?.optJSONObject("data")?.optJSONArray("children") ?: return emptyList()
        (0 until ch.length()).mapNotNull { i -> ch.optJSONObject(i)?.optJSONObject("data")?.optString("body")?.let { plain(it, 300) }?.takeIf { it.length > 20 && it != "[deleted]" && it != "[removed]" } }.take(n)
    }.getOrDefault(emptyList())

    /** t.me/s/<channel> public preview page (Horizon's telegram scraper reads the same markup) */
    fun parseTelegram(html: String, channel: String, category: String): List<Raw> {
        val parts = html.split("tgme_widget_message_wrap").drop(1)
        return parts.mapNotNull { p ->
            val post = Regex("""data-post="([^"]+)"""").find(p)?.groupValues?.get(1) ?: return@mapNotNull null
            val text = Regex("""(?s)tgme_widget_message_text[^>]*>(.*?)</div>""").find(p)?.groupValues?.get(1)?.let { plain(it, 800) }.orEmpty()
            if (text.isBlank()) return@mapNotNull null
            val at = Regex("""<time[^>]*datetime="([^"]+)"""").find(p)?.groupValues?.get(1)?.let { parseDate(it) } ?: 0L
            val views = Regex("""tgme_widget_message_views">([\d.,]+)([KM]?)<""").find(p)?.let { m ->
                val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: 0.0
                (v * when (m.groupValues[2]) { "K" -> 1_000.0; "M" -> 1_000_000.0; else -> 1.0 }).toInt()
            } ?: 0
            Raw("tg:$post", Kind.TELEGRAM, "Telegram · $channel", text.take(90), "https://t.me/$post", text, at, views / 1000, 0, category)
        }
    }

    /** api.github.com/users/<u>/events/public: releases, new repos, stars, pushes worth a line */
    fun parseGithubEvents(json: String, user: String, category: String): List<Raw> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { i ->
            val e = a.optJSONObject(i) ?: return@mapNotNull null
            val repo = e.optJSONObject("repo")?.optString("name").orEmpty(); val p = e.optJSONObject("payload") ?: JSONObject()
            val at = parseDate(e.optString("created_at"))
            val (title, url) = when (e.optString("type")) {
                "ReleaseEvent" -> "$repo 发布 ${p.optJSONObject("release")?.optString("tag_name").orEmpty()}" to (p.optJSONObject("release")?.optString("html_url") ?: "https://github.com/$repo")
                "CreateEvent" -> if (p.optString("ref_type") == "repository") "$user 新建仓库 $repo" to "https://github.com/$repo" else return@mapNotNull null
                "WatchEvent" -> "$user 收藏了 $repo" to "https://github.com/$repo"
                "PublicEvent" -> "$user 公开了 $repo" to "https://github.com/$repo"
                else -> return@mapNotNull null
            }
            Raw("ghe:" + e.optString("id"), Kind.GITHUB, "GitHub · $user", title, url, plain(p.optJSONObject("release")?.optString("body").orEmpty()), at, 0, 0, category)
        }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- scoring
    /** no model: engagement (log) + follow words + freshness, on the 0–10 scale */
    fun heuristicScore(r: Raw, follows: List<String>, now: Long): Double {
        val eng = when (r.kind) {
            Kind.HN -> 2.0 * kotlin.math.log10(1.0 + r.points) + 0.8 * kotlin.math.log10(1.0 + r.comments)
            Kind.REDDIT -> 1.6 * kotlin.math.log10(1.0 + r.points) + 0.8 * kotlin.math.log10(1.0 + r.comments)
            Kind.GITHUB -> 3.5
            else -> 2.5 + 0.5 * kotlin.math.log10(1.0 + r.points)
        }
        val text = (r.title + " " + r.text).lowercase()
        val hits = follows.count { it.isNotBlank() && text.contains(it.lowercase()) }.coerceAtMost(3)
        val ageH = if (r.at > 0) (now - r.at).coerceAtLeast(0) / 3_600_000.0 else 12.0
        val promo = Regex("(?i)sponsored|giveaway|promo code|广告|推广").containsMatchIn(text)
        return (eng + 1.2 * hits + 1.5 * kotlin.math.exp(-ageH / 18.0) - (if (promo) 3.0 else 0.0)).coerceIn(0.0, 10.0)
    }

    /** Horizon tech-news rubric, Chinese instructions; one JSON object per item */
    fun analysisPrompt(items: List<Raw>, follows: List<String>): String {
        val arr = JSONArray()
        items.forEachIndexed { i, r ->
            arr.put(JSONObject().put("i", i).put("src", r.source).put("title", r.title.take(200)).put("text", r.text.take(700))
                .put("engagement", "${r.points} 分 / ${r.comments} 评论").put("comments", JSONArray(r.discussion.take(3).map { it.take(200) })))
        }
        return """你是新闻编辑，给每条内容按重要性打分（0-10，可以有小数），读者关心：软件工程、AI / 大模型、安全、开源、加密货币、科技产业${if (follows.isNotEmpty()) "；特别关注：" + follows.take(12).joinToString("、") else ""}。
评分标准（Horizon tech-news）：
9-10 重大突破：范式变化、广泛使用的技术大版本、重要研究结果、改变行业的发布；
7-8 很有价值：值得马上关注的进展、技术深度文章、新方法、有洞见的分析、好用的工具或库；
5-6 有意思：增量改进、实用教程、中等社区关注、值得知道但不紧急；
3-4 低优先：例行更新、常识、浅尝辄止、以推广为主；
0-2 噪音：垃圾、离题、琐碎、纯广告。
看技术深度、新颖性、可能影响、来源质量、相关性和具体证据；有实质讨论可加分，但热度不等于重要；不要奖励标题党。
只输出 JSON 数组：[{"i":序号,"score":数字,"reason":"一句中文理由","summary":"一句中文概括","tags":["3-5 个标签"],"cat":"AI|开发|安全|加密货币|科技|其他"}]
$arr"""
    }

    data class Analysis(val score: Double, val reason: String, val summary: String, val tags: List<String>, val cat: String?)
    fun parseAnalysis(text: String?, n: Int): Map<Int, Analysis> {
        val a = jsonArrayIn(text) ?: return emptyMap()
        val out = HashMap<Int, Analysis>()
        for (k in 0 until a.length()) {
            val o = a.optJSONObject(k) ?: continue
            val i = o.optInt("i", -1); if (i !in 0 until n) continue
            val s = o.optDouble("score", Double.NaN); if (s.isNaN()) continue
            val tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) }.filter { it.isNotBlank() }.take(5) } ?: emptyList()
            out[i] = Analysis(s.coerceIn(0.0, 10.0), o.optString("reason").take(120), o.optString("summary").take(160), tags, o.optString("cat").takeIf { it in CATEGORIES })
        }
        return out
    }

    // ---------------------------------------------------------------- topic de-duplication
    fun dedupPrompt(items: List<Pair<String, String>>): String {
        val lines = items.mapIndexed { i, (t, s) -> "[$i] $t — ${s.take(140)}" }.joinToString("\n")
        return """你是新闻去重助手。找出报道「同一个真实事件」的条目（同一次发布、同一个事故、同一条公告）。
规则：只有同一事件才算重复；同一产品的不同事件不算（「Gemma 4 发布」和「Gemma 4 被越狱」不是重复）；拿不准就当不重复。
下面的条目已按重要性从高到低排序：
$lines
只输出 JSON：{"duplicates": [[保留的序号, 重复的序号, ...], ...]}，没有重复就输出 {"duplicates": []}"""
    }

    /** groups from the model's JSON; each group's first index is kept. Invalid / overlapping indices are dropped. */
    fun parseDedup(text: String?, n: Int): List<List<Int>> {
        val o = jsonObjectIn(text) ?: return emptyList()
        val a = o.optJSONArray("duplicates") ?: return emptyList()
        val seen = HashSet<Int>(); val out = ArrayList<List<Int>>()
        for (k in 0 until a.length()) {
            val g = a.optJSONArray(k) ?: continue
            val idx = (0 until g.length()).map { g.optInt(it, -1) }.filter { it in 0 until n && it !in seen }.distinct()
            if (idx.size >= 2) { out += idx; seen += idx }
        }
        return out
    }

    /** no model: titles sharing most of their words (Jaccard ≥ 0.6 over word / 2-char tokens) are the same story */
    fun heuristicDuplicates(titles: List<String>): List<List<Int>> {
        fun tok(s: String): Set<String> {
            val low = s.lowercase()
            val en = Regex("[a-z0-9][a-z0-9.+-]{1,}").findAll(low).map { it.value }.filter { it.length >= 3 }.toSet()
            val zh = Regex("[\\u4e00-\\u9fa5]{2,}").findAll(low).flatMap { m -> (0 until m.value.length - 1).asSequence().map { m.value.substring(it, it + 2) } }.toSet()
            return en + zh
        }
        val t = titles.map { tok(it) }
        val used = HashSet<Int>(); val out = ArrayList<List<Int>>()
        for (i in t.indices) {
            if (i in used || t[i].size < 2) continue
            val g = mutableListOf(i)
            for (j in i + 1 until t.size) {
                if (j in used || t[j].size < 2) continue
                val inter = t[i].intersect(t[j]).size.toDouble(); val uni = t[i].union(t[j]).size.toDouble()
                if (uni > 0 && inter / uni >= 0.6) g += j
            }
            if (g.size >= 2) { out += g; used += g }
        }
        return out
    }

    /** keep each group's first item, fold the rest's comments + sources into it */
    fun <T> applyGroups(items: List<T>, groups: List<List<Int>>, merge: (T, List<T>) -> T): List<T> {
        val drop = HashSet<Int>(); val repl = HashMap<Int, T>()
        for (g in groups) { repl[g[0]] = merge(items[g[0]], g.drop(1).map { items[it] }); drop += g.drop(1) }
        return items.indices.filter { it !in drop }.map { repl[it] ?: items[it] }
    }

    // ---------------------------------------------------------------- balanced digest
    /** items already sorted by score: take at most [maxItems], at most [perCategory] from one category */
    fun <T> balance(items: List<T>, maxItems: Int, perCategory: Int, cat: (T) -> String): List<T> {
        val count = HashMap<String, Int>(); val out = ArrayList<T>()
        for (it in items) {
            if (out.size >= maxItems) break
            val c = cat(it); val n = count[c] ?: 0
            if (n >= perCategory) continue
            count[c] = n + 1; out += it
        }
        return out
    }

    // ---------------------------------------------------------------- enrichment (Chinese)
    fun enrichPrompt(items: List<Raw>): String {
        val arr = JSONArray()
        items.forEachIndexed { i, r ->
            arr.put(JSONObject().put("i", i).put("src", r.sources.joinToString(" / ")).put("title", r.title.take(200)).put("text", r.text.take(1400))
                .put("comments", JSONArray(r.discussion.take(6).map { it.take(260) })))
        }
        return """你是科技编辑，帮读者准确、高效地看懂重要新闻。为每条写中文简报：
title：不超过 20 个字的准确标题，保留产品/项目名和关键变化，不要标题党；
summary：2-4 句完整的话，先说到底变了什么、对谁有影响，给出关键细节（名称、版本、数字、可用性、条件），分清「宣布的计划/厂商说法」和「已经上线/独立测得的结果」，不编造；
background：1-3 句，解释看懂这条新闻需要的前置概念或之前的相关进展，不重复 summary，不写公司简介；
discussion：1-2 句，概括 comments 里最有用的观点、分歧或亲身经验，说明是网友观点，不当成事实；comments 为空或没有实质内容就输出空字符串。
避免「里程碑」「将重塑行业」这类空话。只输出 JSON 数组：[{"i":序号,"title":"…","summary":"…","background":"…","discussion":"…"}]
$arr"""
    }

    data class Written(val title: String, val summary: String, val background: String, val discussion: String)
    fun parseEnrich(text: String?, n: Int): Map<Int, Written> {
        val a = jsonArrayIn(text) ?: return emptyMap()
        val out = HashMap<Int, Written>()
        for (k in 0 until a.length()) {
            val o = a.optJSONObject(k) ?: continue
            val i = o.optInt("i", -1); if (i !in 0 until n) continue
            val t = o.optString("title").trim(); val s = o.optString("summary").trim()
            if (t.isEmpty() || s.isEmpty()) continue
            out[i] = Written(t.take(40), s.take(400), o.optString("background").trim().take(300), o.optString("discussion").trim().take(240))
        }
        return out
    }

    // ---------------------------------------------------------------- the briefing
    data class BriefItem(
        val id: String, val title: String, val summary: String, val background: String, val discussion: String,
        val score: Double, val reason: String, val tags: List<String>, val category: String, val url: String, val sources: List<String>, val at: Long,
    )
    data class Brief(val day: String, val createdAt: Long, val items: List<BriefItem>, val collected: Int, val kept: Int, val model: String, val notes: List<String>)

    fun briefToJson(b: Brief): String = JSONObject().put("day", b.day).put("at", b.createdAt).put("collected", b.collected).put("kept", b.kept).put("model", b.model)
        .put("notes", JSONArray(b.notes)).put("items", JSONArray().apply {
            b.items.forEach { i -> put(JSONObject().put("id", i.id).put("title", i.title).put("summary", i.summary).put("background", i.background).put("discussion", i.discussion)
                .put("score", i.score).put("reason", i.reason).put("tags", JSONArray(i.tags)).put("cat", i.category).put("url", i.url).put("sources", JSONArray(i.sources)).put("at", i.at)) }
        }).toString()

    fun briefFromJson(s: String?): Brief? = runCatching {
        val o = JSONObject(s ?: return null)
        fun strs(a: JSONArray?) = a?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
        val a = o.getJSONArray("items")
        Brief(o.getString("day"), o.optLong("at"), (0 until a.length()).map { k ->
            val i = a.getJSONObject(k)
            BriefItem(i.optString("id"), i.optString("title"), i.optString("summary"), i.optString("background"), i.optString("discussion"), i.optDouble("score", 0.0),
                i.optString("reason"), strs(i.optJSONArray("tags")), i.optString("cat", "其他"), i.optString("url"), strs(i.optJSONArray("sources")), i.optLong("at"))
        }, o.optInt("collected"), o.optInt("kept"), o.optString("model"), strs(o.optJSONArray("notes")))
    }.getOrNull()

    /** Markdown briefing grouped by category (Horizon renders without a final AI call, so do we) */
    fun render(b: Brief): String = buildString {
        append("# 每日简报 · ").append(b.day).append("\n\n")
        append("> 从 ").append(b.collected).append(" 条里挑出 ").append(b.items.size).append(" 条值得读的").append(if (b.model.isNotBlank()) "（${b.model} 打分 / 去重 / 撰写）" else "（未连上模型：按热度和你关注的词挑选）").append("\n\n")
        val groups = b.items.groupBy { it.category }
        for (c in CATEGORIES + groups.keys.filter { it !in CATEGORIES }) {
            val list = groups[c] ?: continue
            append("## ").append(c).append("\n\n")
            for (i in list) {
                append("### ").append(i.title).append("  `").append("%.1f".format(i.score)).append("`\n\n")
                append(i.summary).append("\n\n")
                if (i.background.isNotBlank()) append("**背景**：").append(i.background).append("\n\n")
                if (i.discussion.isNotBlank()) append("**网友讨论**：").append(i.discussion).append("\n\n")
                append("来源：").append(i.sources.joinToString(" · ")).append(if (i.url.isNotBlank()) " · [原文](${i.url})" else "").append("\n\n")
            }
        }
        if (b.notes.isNotEmpty()) append("---\n").append(b.notes.joinToString("\n") { "- $it" }).append("\n")
    }

    /** the brief is due: none today, and it is past [hour] local */
    fun due(lastDay: String?, today: String, hourNow: Int, hour: Int = 7): Boolean = lastDay != today && hourNow >= hour

    // ---------------------------------------------------------------- model JSON helpers
    fun jsonArrayIn(text: String?): JSONArray? {
        val t = text ?: return null
        val a = t.indexOf('['); val b = t.lastIndexOf(']')
        return if (a < 0 || b <= a) null else runCatching { JSONArray(t.substring(a, b + 1)) }.getOrNull()
    }
    fun jsonObjectIn(text: String?): JSONObject? {
        val t = text ?: return null
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        return if (a < 0 || b <= a) null else runCatching { JSONObject(t.substring(a, b + 1)) }.getOrNull()
    }
}
