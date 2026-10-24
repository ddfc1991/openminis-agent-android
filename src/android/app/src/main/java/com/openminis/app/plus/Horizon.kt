package com.openminis.app.plus

import android.content.Context
import com.openminis.app.plus.HorizonCore.Kind
import com.openminis.app.plus.HorizonCore.Raw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asContextElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.9.11 c] 每日简报 — the Horizon pipeline ([HorizonCore]) on the phone: reads the user's source list, scores /
 * de-duplicates / balances / writes the Chinese briefing with the user's own model (cheapest enabled cloud entry, then Memoh;
 * no model → engagement + follow words, titles only), keeps it in filesDir/horizon/ and /var/minis/shared/每日简报/<day>.md.
 * Runs when the 每日简报 tab is opened and today's is missing, from the background feed refresh after 07:00, or on pull.
 */
object Horizon {
    private const val PREFS = "mines_horizon"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    /** runs started from the page live here, so leaving the page / a pull timeout never cancels a briefing half-way */
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    fun runAsync(c: Context): kotlinx.coroutines.Deferred<HorizonCore.Brief?> { val app = c.applicationContext; return bg.async { run(app) } }

    private val _brief = MutableStateFlow<HorizonCore.Brief?>(null)
    val brief: StateFlow<HorizonCore.Brief?> = _brief.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    /** one line for the page while it runs: 「正在读 12 个来源」/「正在打分 40 条」… */
    private val _stage = MutableStateFlow("")
    val stage: StateFlow<String> = _stage.asStateFlow()
    @Volatile var lastError: String = ""; private set
    /** [38.9.11.3] the raw (English) details behind the Chinese lines — only for 「复制详情」 */
    val details: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
    private fun detail(s: String) { details += s.take(300); while (details.size > 30) details.removeAt(0) }
    /** [38.9.11.3] models that answered 「cannot be used with chat」: never picked again for the briefing */
    private fun nonChat(c: Context): Set<String> = c.getSharedPreferences(PREFS, 0).getStringSet("non_chat", emptySet()) ?: emptySet()
    private fun markNonChat(c: Context, id: String) { val p = c.getSharedPreferences(PREFS, 0); p.edit().putStringSet("non_chat", nonChat(c) + id).apply() }

    fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    // ---------------------------------------------------------------- settings
    fun sources(c: Context): List<HorizonCore.Source> = HorizonCore.decode(c.getSharedPreferences(PREFS, 0).getString("sources", null))
    fun setSources(c: Context, list: List<HorizonCore.Source>) { c.getSharedPreferences(PREFS, 0).edit().putString("sources", HorizonCore.encode(list)).apply() }
    fun threshold(c: Context): Float = c.getSharedPreferences(PREFS, 0).getFloat("threshold", 6.0f)
    fun setThreshold(c: Context, v: Float) { c.getSharedPreferences(PREFS, 0).edit().putFloat("threshold", v.coerceIn(0f, 10f)).apply() }
    fun maxItems(c: Context): Int = c.getSharedPreferences(PREFS, 0).getInt("max", 15)
    fun setMaxItems(c: Context, v: Int) { c.getSharedPreferences(PREFS, 0).edit().putInt("max", v.coerceIn(5, 40)).apply() }
    fun autoOn(c: Context): Boolean = c.getSharedPreferences(PREFS, 0).getBoolean("auto", true)
    fun setAuto(c: Context, on: Boolean) { c.getSharedPreferences(PREFS, 0).edit().putBoolean("auto", on).apply() }

    private fun dir(c: Context) = File(c.applicationContext.filesDir, "horizon").apply { mkdirs() }
    /** the saved briefing of [day] (today when null) */
    fun load(c: Context, day: String? = null): HorizonCore.Brief? {
        val d = day ?: c.getSharedPreferences(PREFS, 0).getString("last_day", null) ?: return null
        return HorizonCore.briefFromJson(runCatching { File(dir(c), "brief-$d.json").readText() }.getOrNull())?.also { if (day == null) _brief.value = it }
    }
    fun days(c: Context): List<String> = dir(c).list()?.mapNotNull { Regex("""brief-(\d{4}-\d{2}-\d{2})\.json""").find(it)?.groupValues?.get(1) }?.sortedDescending() ?: emptyList()

    /** background (feed patrol): build today's when it is due and auto is on; never twice at once */
    suspend fun autoIfDue(c: Context) {
        if (!autoOn(c) || _busy.value) return
        val last = c.getSharedPreferences(PREFS, 0).getString("last_day", null)
        if (!HorizonCore.due(last, today(), java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))) return
        // a failed try waits an hour
        if (System.currentTimeMillis() - c.getSharedPreferences(PREFS, 0).getLong("last_try", 0L) < 3_600_000L) return
        run(c)
    }

    /** the whole pipeline; returns the new briefing (null when nothing could be read) */
    suspend fun run(c: Context): HorizonCore.Brief? = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) { lock.withLock { }; return@withContext _brief.value }
        _busy.value = true
        val app = c.applicationContext
        try {
            app.getSharedPreferences(PREFS, 0).edit().putLong("last_try", System.currentTimeMillis()).apply()
            val notes = java.util.Collections.synchronizedList(ArrayList<String>())
            val src = sources(app).filter { it.on }
            _stage.value = "正在读 ${src.size} 个来源"
            val since = System.currentTimeMillis() - 30 * 3_600_000L
            val raw = coroutineScope {
                src.map { s -> async {
                    // [38.9.11.3] one short Chinese line per failed source; 403 / cut connection → one retry on the other route
                    val first = runCatching { fetch(app, s, since) }
                    first.getOrNull() ?: run {
                        val raw1 = first.exceptionOrNull().let { it?.message ?: it?.javaClass?.simpleName ?: "" }
                        detail("${s.name}: $raw1")
                        if (!ErrZhCore.retryable(raw1)) { notes += ErrZhCore.source(s.name, raw1); return@run emptyList<Raw>() }
                        val second = runCatching { withContext(altRoute.asContextElement(true)) { fetch(app, s, since) } }
                        second.getOrNull()?.also { notes += ErrZhCore.source(s.name, raw1, recovered = true) } ?: run {
                            val raw2 = second.exceptionOrNull().let { it?.message ?: it?.javaClass?.simpleName ?: "" }
                            detail("${s.name} (retry): $raw2"); notes += ErrZhCore.source(s.name, raw2.ifBlank { raw1 }, retried = true); emptyList()
                        }
                    }
                } }.awaitAll()
            }.flatten()
            val merged = HorizonCore.mergeCrossSource(raw.filter { it.at == 0L || it.at >= since })
            if (merged.isEmpty()) { lastError = "一条都没读到" + (notes.firstOrNull()?.let { "（$it）" } ?: ""); return@withContext null }
            val follows = runCatching { Digest.follows(app) }.getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            // keep the analysis affordable: the 60 most promising by engagement go to the model
            val pool = merged.sortedByDescending { HorizonCore.heuristicScore(it, follows, now) }.take(60)
            _stage.value = "正在打分 ${pool.size} 条"
            var model = ""
            val analysis = HashMap<Int, HorizonCore.Analysis>()
            for ((bi, chunk) in pool.chunked(20).withIndex()) {
                val (reply, m) = ask(app, HorizonCore.analysisPrompt(chunk, follows), "你是新闻编辑。只输出 JSON。") ?: break
                model = m
                HorizonCore.parseAnalysis(reply, chunk.size).forEach { (i, a) -> analysis[bi * 20 + i] = a }
            }
            if (analysis.isEmpty()) notes += "没连上模型：按热度和你关注的词挑选" + (lastError.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
            data class Scored(val r: Raw, val score: Double, val a: HorizonCore.Analysis?)
            val thr = if (analysis.isEmpty()) 4.5 else threshold(app).toDouble()
            var scored = pool.mapIndexed { i, r -> Scored(r.copy(category = analysis[i]?.cat ?: r.category), analysis[i]?.score ?: HorizonCore.heuristicScore(r, follows, now), analysis[i]) }
                .filter { it.score >= thr }.sortedByDescending { it.score }
            // topic de-duplication within the kept set
            if (scored.size >= 2) {
                _stage.value = "正在去重 ${scored.size} 条"
                val groups = (if (model.isNotBlank()) ask(app, HorizonCore.dedupPrompt(scored.map { it.r.title to (it.a?.summary ?: it.r.text) }), "你是新闻去重助手。只输出 JSON。")
                    ?.let { HorizonCore.parseDedup(it.first, scored.size) } else null) ?: HorizonCore.heuristicDuplicates(scored.map { it.r.title })
                scored = HorizonCore.applyGroups(scored, groups) { p, dups ->
                    p.copy(r = p.r.copy(discussion = (p.r.discussion + dups.flatMap { it.r.discussion }).distinct().take(8), sources = (p.r.sources + dups.flatMap { it.r.sources }).distinct()))
                }
            }
            val picked = HorizonCore.balance(scored, maxItems(app), perCategory = maxOf(3, maxItems(app) / 3)) { it.r.category }
            // comments for the picked HN / Reddit stories (網友讨论)
            _stage.value = "正在读网友讨论"
            val withComments = coroutineScope { picked.map { s -> async { s.copy(r = s.r.copy(discussion = (s.r.discussion + comments(s.r)).distinct().take(8))) } }.awaitAll() }
            _stage.value = "正在写简报 ${withComments.size} 条"
            val written = HashMap<Int, HorizonCore.Written>()
            if (model.isNotBlank()) for ((bi, chunk) in withComments.chunked(8).withIndex()) {
                val (reply, _) = ask(app, HorizonCore.enrichPrompt(chunk.map { it.r }), "你是科技编辑。只输出 JSON。", maxTokens = 4000) ?: break
                HorizonCore.parseEnrich(reply, chunk.size).forEach { (i, w) -> written[bi * 8 + i] = w }
            }
            val items = withComments.mapIndexed { i, s ->
                val w = written[i]
                HorizonCore.BriefItem(s.r.id, w?.title ?: s.r.title.take(60), w?.summary ?: (s.a?.summary?.takeIf { it.isNotBlank() } ?: s.r.text.take(220).ifBlank { s.r.title }),
                    w?.background.orEmpty(), w?.discussion ?: s.r.discussion.firstOrNull()?.let { "网友：" + it.take(140) }.orEmpty(),
                    s.score, s.a?.reason.orEmpty(), s.a?.tags.orEmpty(), s.r.category, s.r.url, s.r.sources, s.r.at)
            }
            val b = HorizonCore.Brief(today(), System.currentTimeMillis(), items, merged.size, items.size, model, notes.toList().take(6))
            save(app, b)
            _brief.value = b; lastError = ""
            b
        } catch (t: Throwable) {
            detail("run: ${t.message ?: t.javaClass.simpleName}"); lastError = ErrZhCore.source("简报", t.message ?: t.javaClass.simpleName).replace("，已跳过", ""); null
        } finally { _busy.value = false; _stage.value = ""; lock.unlock() }
    }

    private fun save(c: Context, b: HorizonCore.Brief) {
        runCatching { File(dir(c), "brief-${b.day}.json").writeText(HorizonCore.briefToJson(b)) }
        // the readable copy lives with the user's other files (shared/, visible in the system file picker)
        runCatching { File(SharedFilesCore.sharedDir(c.filesDir), "每日简报").apply { mkdirs() }.let { File(it, "${b.day}.md").writeText(HorizonCore.render(b)) } }
        c.getSharedPreferences(PREFS, 0).edit().putString("last_day", b.day).apply()
        // keep 30 days
        days(c).drop(30).forEach { File(dir(c), "brief-$it.json").delete() }
    }

    // ---------------------------------------------------------------- sources
    /** [38.9.11.3] set for the retry of a failed source: the other route (direct ↔ the user's proxy) */
    private val altRoute = ThreadLocal<Boolean>()

    private fun clientFor(url: String): OkHttpClient {
        if (altRoute.get() != true) return http
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return http
        val order = ProviderRoutes.order(host)
        val alt = order.getOrNull(1) ?: order.firstOrNull() ?: return http
        return http.newBuilder().proxy(ProviderRoutes.proxyFor(alt)).build()
    }

    private fun get(url: String, ua: String = MINES_MOBILE_UA): String {
        clientFor(url).newCall(Request.Builder().url(url).header("User-Agent", ua).header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    private suspend fun fetch(c: Context, s: HorizonCore.Source, since: Long): List<Raw> = when (s.kind) {
        Kind.RSS -> HorizonCore.parseFeed(get(s.value)).take(30).map { e ->
            Raw("rss:" + HorizonCore.urlKey(e.link.ifBlank { s.value + "#" + e.title }).hashCode(), Kind.RSS, s.name, e.title, e.link, e.text, e.at, 0, 0, s.category)
        }
        Kind.HN -> HorizonCore.parseHn(get("https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=40"), s.value.toIntOrNull() ?: 100, s.category)
        Kind.REDDIT -> HorizonCore.parseReddit(get("https://www.reddit.com/r/${s.value}/top.json?t=day&limit=25", "android:com.openminis.mines:1.0 (Mines news brief)"), s.value, s.category)
        Kind.TELEGRAM -> HorizonCore.parseTelegram(get("https://t.me/s/${s.value.removePrefix("@")}"), s.value.removePrefix("@"), s.category)
        Kind.GITHUB -> if (s.value.startsWith("user:")) {
            val u = s.value.removePrefix("user:")
            HorizonCore.parseGithubEvents(get("https://api.github.com/users/$u/events/public?per_page=30"), u, s.category)
        } else HorizonCore.parseFeed(get("https://github.com/${s.value}/releases.atom")).take(5).map { e ->
            Raw("ghr:" + e.link.hashCode(), Kind.GITHUB, s.name, "${s.value} ${e.title}", e.link, e.text, e.at, 0, 0, s.category)
        }
        // the user's signed-in X session (Digest reads it off-screen); a handle / keyword narrows it
        Kind.X -> DigestX.cached().filter { x ->
            val q = s.value.trim(); q.isEmpty() || x.sourceKey.equals("x:$q", true) || (x.title + " " + x.summary).contains(q.removePrefix("@"), true)
        }.map { x -> Raw("x:" + x.id, Kind.X, "X · " + x.sender, x.title, x.url.orEmpty(), x.summary, x.at, 0, 0, s.category) }
        // Mines' own feed (GitHub notifications, mail, trending …) minus account problems
        Kind.MINES -> Digest.items.value.filter { it.tab != Digest.Tab.ACCOUNT && it.source != "账号巡查" }.take(30).map { m ->
            Raw("m:" + m.id, Kind.MINES, m.source, m.headline ?: m.title, m.url.orEmpty(), m.brief ?: m.summary, m.at, m.stars?.replace(",", "")?.toIntOrNull() ?: 0, 0, s.category)
        }
    }

    private fun comments(r: Raw): List<String> = runCatching {
        when (r.kind) {
            Kind.HN -> HorizonCore.parseHnComments(get("https://hn.algolia.com/api/v1/items/" + r.id.removePrefix("hn:")))
            Kind.REDDIT -> HorizonCore.parseRedditComments(get("https://www.reddit.com/comments/" + r.id.removePrefix("rd:") + ".json?sort=top&limit=8", "android:com.openminis.mines:1.0 (Mines news brief)"))
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** the user's own model: cheapest enabled cloud entry with a key (not the local 免登录 gateway), then Memoh. (reply, model id) */
    private suspend fun ask(c: Context, prompt: String, system: String, maxTokens: Int = 3000): Pair<String, String>? {
        val a = c.applicationContext as? com.openminis.app.MinisApp
        if (a != null) {
            val repo = a.providerRepository
            val cheapRe = Regex("(?i)(flash|mini|lite|haiku|turbo|nano|small|air|v3|chat)")
            val bad = nonChat(c)
            val entries = runCatching { repo.allVisibleEntries() }.getOrDefault(emptyList()).filter { e ->
                val inst = repo.instance(e.providerInstanceId)
                inst != null && inst.isEnabled && !(inst.customBaseURL ?: "").contains("127.0.0.1") &&
                    ErrZhCore.chatCapable(e.baseModel.id) && e.baseModel.id !in bad   // [38.9.11.3] never TTS / embedding / image models
            }.sortedByDescending { cheapRe.containsMatchIn(it.baseModel.id) }.take(3)
            for (e in entries) {
                val inst = repo.instance(e.providerInstanceId) ?: continue
                val key = repo.usableApiKey(inst) ?: continue
                val r = runCatching {
                    kotlinx.coroutines.withTimeout(90_000L) {
                        com.openminis.app.provider.ProviderFactory.create(inst, key, e.baseModel, c, overrides = e.overrides)
                            .sendMessage(messages = listOf(com.openminis.app.data.model.LLMMessage(role = com.openminis.app.data.model.LLMMessage.Role.USER, content = prompt)),
                                systemPrompt = system, maxTokens = maxTokens).text
                    }
                }.onFailure {
                    val raw = it.message ?: it.javaClass.simpleName
                    detail("${e.baseModel.id}: $raw")
                    if (ErrZhCore.notChat(raw)) markNonChat(c, e.baseModel.id)
                    lastError = ErrZhCore.model(e.baseModel.id, raw)
                }.getOrNull()
                if (!r.isNullOrBlank()) return r to e.baseModel.id
            }
        }
        if (MemohKey.configured()) {
            val r = runCatching { MemohKey.reply(system, prompt) }.onFailure { detail("Memoh: ${it.message}"); lastError = ErrZhCore.model("Memoh", it.message, switched = false) }.getOrNull()
            if (!r.isNullOrBlank()) return r to "Memoh"
        }
        return null
    }
}
