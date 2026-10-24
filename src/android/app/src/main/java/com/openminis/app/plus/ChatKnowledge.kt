package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [Mines-kb-33 → 38.7] 对话整理: every chat window becomes knowledge with no setup (default on).
 *
 * Sources (user 2026-10-15 「每个聊天窗口…自动整理进知识库」): normal chats (Room sessions), AI 群聊 and the 1:1 chats with an
 * AI 伙伴 (GroupStore, 「单聊」 = a group with one member and no host). Each conversation is distilled into
 * {topic, summary, points, decisions(结论), todos(待办), links, files} by the best key the user has (DeepSeek → 智谱 →
 * 硅基流动) or, without a key, by local rules ([Rules]) — never blank. Each one is one document in the fixed knowledge base
 * 「对话整理」 (id "chats") so `search_knowledge` finds it, and its entry carries where it came from (kind, source label incl.
 * the member account 「豆包·账号A」, route back to the chat).
 *
 * When: a chat idle for [IDLE_MS] that changed since its last pass; at once (3 s) when the user leaves a chat window
 * ([touch]); when the app goes to the background; and 「立即整理」 ([runNow]) which ignores the idle wait.
 */
object ChatKnowledge {
    const val KB_ID = "chats"
    const val KB_NAME = "对话整理"
    private const val PREFS = "mines_chat_knowledge"
    private const val IDLE_MS = 2 * 60_000L
    private const val PER_PASS = 12
    private const val DAILY_CAP = 80
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(70, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()

    /** kind: "chat" (Mines chat) / "group" (AI 群聊) / "direct" (单聊 with one AI 伙伴). */
    data class Entry(val sessionId: String, val title: String, val topic: String, val summary: String,
                     val facts: List<String>, val decisions: List<String>, val links: List<String>, val files: List<String>, val at: Long, val byModel: Boolean,
                     val todos: List<String> = emptyList(), val kind: String = "chat", val source: String = "", val route: String = "chat/$sessionId",
                     val chatAt: Long = 0L) {
        /** Plain where-from line: 「来自：群聊「交易复盘群」」. */
        val from: String get() = when (kind) {
            "group" -> "来自：群聊「$source」"
            "direct" -> "来自：和 $source 的单聊"
            else -> "来自：对话「${title.take(24)}」"
        }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _last = MutableStateFlow(0L)
    /** When the last pass finished (0 = never). */
    val lastRun: StateFlow<Long> = _last.asStateFlow()
    /** One line about the last 「立即整理」: 「整理了 3 段对话」 / 「都是最新的」. */
    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("on", on).apply()

    /** smoke / user override of the summarising endpoint: "base|key|model" (OpenAI-compatible). */
    fun setEndpointOverride(c: Context, v: String?) = prefs(c).edit().apply { if (v.isNullOrBlank()) remove("ep") else putString("ep", v) }.apply()

    fun load(c: Context): List<Entry> {
        val p = prefs(c)
        val list = p.all.keys.filter { it.startsWith("e_") }.mapNotNull { k -> runCatching { parse(JSONObject(p.getString(k, "{}")!!)) }.getOrNull() }
            .sortedByDescending { it.at }
        _entries.value = list
        _last.value = p.getLong("last_run", 0L)
        return list
    }

    private fun arr(o: JSONObject, k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
    private fun parse(o: JSONObject): Entry {
        val sid = o.getString("sid")
        val kind = o.optString("kind", "chat").ifBlank { "chat" }
        return Entry(sid, o.optString("title"), o.optString("topic"), o.optString("summary"),
            arr(o, "facts"), arr(o, "decisions"), arr(o, "links"), arr(o, "files"), o.optLong("at"), o.optBoolean("model"),
            arr(o, "todos"), kind, o.optString("source"), o.optString("route").ifBlank { "chat/$sid" }, o.optLong("chatAt"))
    }
    private fun toJson(e: Entry) = JSONObject().put("sid", e.sessionId).put("title", e.title).put("topic", e.topic).put("summary", e.summary)
        .put("facts", JSONArray(e.facts)).put("decisions", JSONArray(e.decisions)).put("links", JSONArray(e.links)).put("files", JSONArray(e.files))
        .put("at", e.at).put("model", e.byModel).put("todos", JSONArray(e.todos)).put("kind", e.kind).put("source", e.source).put("route", e.route)
        .put("chatAt", e.chatAt)

    fun search(q: String): List<Entry> {
        val t = q.trim().lowercase(); if (t.isEmpty()) return _entries.value
        return _entries.value.filter { e -> (e.title + e.topic + e.summary + e.facts + e.decisions + e.todos + e.links + e.files + e.source).lowercase().contains(t) }
    }

    // ---------------- triggers ----------------

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** [Mines-38.7] the user left a chat window (or a group / 单聊): organise that conversation 3 s later, no idle wait. */
    fun touch(c: Context, key: String) {
        if (!enabled(c)) return
        val app = c.applicationContext
        pending.remove(key)?.cancel()
        pending[key] = scope.launch {
            delay(3000)
            runCatching { gate.withLock { pass(app, force = setOf(key), all = false) } }
            pending.remove(key)
        }
    }

    /** Background pass (app start / app to background): idle, changed chats only. */
    suspend fun run(c: Context) { if (enabled(c)) runCatching { gate.withLock { pass(c.applicationContext, emptySet(), all = false) } } }

    /** 「立即整理」: every changed conversation now, no idle wait. Returns how many were (re)organised. */
    suspend fun runNow(c: Context): Int = gate.withLock {
        val n = runCatching { pass(c.applicationContext, emptySet(), all = true) }.getOrDefault(0)
        _note.value = if (n > 0) "整理了 $n 段对话" else "都是最新的"
        n
    }

    /** [Mines-38.7] the app went to the background: every changed conversation counts as finished. */
    fun onAppStop(c: Context) {
        if (!enabled(c)) return
        val app = c.applicationContext
        scope.launch { delay(1500); runCatching { gate.withLock { pass(app, emptySet(), all = true) } } }
    }

    fun runInBackground(c: Context) { val app = c.applicationContext; scope.launch { run(app) } }

    // ---------------- one pass ----------------

    private class Conv(val key: String, val id: String, val kind: String, val title: String, val source: String, val route: String,
                       val updatedAt: Long, val lines: List<String>, val files: List<String>,
                       /** chat sessions read their messages only when they are actually organised */
                       val load: (suspend () -> Pair<List<String>, List<String>>)? = null)

    private suspend fun conversations(c: Context): List<Conv> = withContext(Dispatchers.IO) {
        val out = ArrayList<Conv>()
        val app = c as? com.openminis.app.MinisApp ?: c.applicationContext as? com.openminis.app.MinisApp
        if (app != null) runCatching {
            val dao = app.database.chatDao()
            val minesSid = runCatching { c.getSharedPreferences("mines_group_prefs", Context.MODE_PRIVATE).getString("mines_sid", null) }.getOrNull()
            for (s in dao.listSessions()) {
                if (s.parentSessionId != null || s.id == minesSid) continue
                val loader: suspend () -> Pair<List<String>, List<String>> = {
                    val msgs = runCatching { dao.loadMessages(s.id) }.getOrDefault(emptyList())
                    val lines = ArrayList<String>(); val files = LinkedHashSet<String>()
                    for (m in msgs) {
                        val (text, f) = partsText(m.partsJson)
                        files += f
                        if (text.isBlank() || (m.role != "user" && m.role != "assistant")) continue
                        lines += (if (m.role == "user") "用户：" else "助手：") + text.replace(Regex("\\s+"), " ").take(1500)
                    }
                    lines to files.toList()
                }
                out += Conv("chat:" + s.id, s.id, "chat", s.title ?: "", "", "chat/" + s.id, s.updatedAt, emptyList(), emptyList(), loader)
            }
        }
        runCatching {
            val gs = com.openminis.app.plus.group.GroupStore
            gs.init(c)
            for (g in gs.groups.value) {
                val msgs = gs.messages(g.id).value.filter { it.status == "ok" && it.text.isNotBlank() }
                if (msgs.isEmpty()) continue
                val direct = g.members.size == 1 && !g.host
                val lines = msgs.map { m ->
                    (if (m.who == "user") "用户：" else "${com.openminis.app.plus.group.GroupRunner.shortName(m.name).ifBlank { "AI" }}：") +
                        m.text.replace(Regex("\\s+"), " ").take(1200)
                }
                val src = if (direct) memberLabel(g.members.first(), g.name) else g.name
                out += Conv("group:" + g.id, "g:" + g.id, if (direct) "direct" else "group", g.name, src,
                    "plus_group_chat/" + g.id, msgs.maxOf { it.at }, lines, emptyList())
            }
        }
        out
    }

    /** 「豆包·账号A」 for a 单聊 member (the group name already carries it, the live partner list is the fallback). */
    private fun memberLabel(id: String, fallback: String): String =
        com.openminis.app.plus.group.Partners.find(id)?.spec?.name ?: fallback

    /** Always off the main thread: 立即整理 is started from the UI and the summarising call is blocking network IO. */
    private suspend fun pass(c: Context, force: Set<String>, all: Boolean): Int = withContext(Dispatchers.IO) { passIo(c, force, all) }

    private suspend fun passIo(c: Context, force: Set<String>, all: Boolean): Int {
        if (_busy.value) return 0
        _busy.value = true
        var done = 0
        try {
            load(c)
            val p = prefs(c)
            val now = System.currentTimeMillis()
            val convs = conversations(c)
            // "chat:recent" = a chat window that was opened as a new chat (its real id was made inside the screen)
            val recent = if ("chat:recent" in force) convs.filter { it.kind == "chat" }.maxByOrNull { it.updatedAt }?.key else null
            val todo = convs.filter { cv ->
                val changed = cv.updatedAt > p.getLong("done_" + cv.id, 0L)
                changed && (all || cv.key in force || cv.key == recent || now - cv.updatedAt > IDLE_MS)
            }.sortedByDescending { it.updatedAt }.take(if (all || force.isNotEmpty()) 40 else PER_PASS)
            if (todo.isEmpty()) return 0
            KnowledgeBase.ensure(c, KB_ID, KB_NAME)
            for (cv0 in todo) {
                val cv = cv0.load?.let { r -> val (l, f) = r(); Conv(cv0.key, cv0.id, cv0.kind, cv0.title, cv0.source, cv0.route, cv0.updatedAt, l, f) } ?: cv0
                val lines = cv.lines
                if (lines.size < 2) { p.edit().putLong("done_" + cv.id, cv.updatedAt).apply(); continue }
                val transcript = lines.joinToString("\n").let { if (it.length > 14_000) it.take(4_000) + "\n…\n" + it.takeLast(10_000) else it }
                val links = Regex("https?://[^\\s)\"'<>，。]+").findAll(transcript).map { it.value.trimEnd('.', ',') }.distinct().take(12).toList()
                val title = cv.title.takeIf { it.isNotBlank() } ?: lines.first().substringAfter('：').take(30)
                val base = summarize(c, cv.id, title, transcript, links, cv.files) ?: Rules.digest(cv.id, title, lines, links, cv.files)
                val e = base.copy(kind = cv.kind, source = cv.source, route = cv.route, chatAt = cv.updatedAt)
                val doc = buildString {
                    append("对话：").append(e.title).append("\n").append(e.from).append("\n主题：").append(e.topic).append("\n摘要：").append(e.summary)
                    if (e.facts.isNotEmpty()) append("\n要点：\n").append(e.facts.joinToString("\n") { "- $it" })
                    if (e.decisions.isNotEmpty()) append("\n结论：\n").append(e.decisions.joinToString("\n") { "- $it" })
                    if (e.todos.isNotEmpty()) append("\n待办：\n").append(e.todos.joinToString("\n") { "- $it" })
                    if (e.links.isNotEmpty()) append("\n链接：\n").append(e.links.joinToString("\n"))
                    if (e.files.isNotEmpty()) append("\n文件：").append(e.files.joinToString("、"))
                }
                runCatching { KnowledgeBase.replaceSource(c, KB_ID, "对话：" + e.title, cv.key, doc) }
                p.edit().putString("e_" + cv.id, toJson(e).toString()).putLong("done_" + cv.id, cv.updatedAt).apply()
                done++
            }
            load(c)
        } finally {
            prefs(c).edit().putLong("last_run", System.currentTimeMillis()).apply()
            _last.value = System.currentTimeMillis()
            _busy.value = false
        }
        return done
    }

    /** Plain text + file names of one message's parts JSON. */
    private fun partsText(json: String): Pair<String, List<String>> = runCatching {
        val a = JSONArray(json)
        val sb = StringBuilder(); val files = ArrayList<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            when (o.optString("type")) {
                "text" -> sb.append(o.optString("value")).append('\n')
                "mediaRef" -> o.optJSONObject("value")?.let { v -> listOf("name", "fileName", "filename", "path").firstNotNullOfOrNull { k -> v.optString(k).takeIf { it.isNotBlank() } }?.let { files += it.substringAfterLast('/') } }
            }
        }
        sb.toString().trim() to files
    }.getOrDefault("" to emptyList())

    private fun endpoint(c: Context): Triple<String, String, String>? {
        prefs(c).getString("ep", null)?.split('|')?.takeIf { it.size >= 3 }?.let { return Triple(it[0].trimEnd('/') + "/chat/completions", it[1], it[2]) }
        PlusVault.defaultAccount("deepseek")?.let { PlusVault.secret(it) }?.let { return Triple("https://api.deepseek.com/chat/completions", it, "deepseek-chat") }
        PlusVault.defaultAccount("zhipu")?.let { PlusVault.secret(it) }?.let { return Triple("https://open.bigmodel.cn/api/paas/v4/chat/completions", it, "glm-4-flash") }
        PlusVault.defaultAccount("siliconflow")?.let { PlusVault.secret(it) }?.let { return Triple("https://api.siliconflow.cn/v1/chat/completions", it, "Qwen/Qwen2.5-7B-Instruct") }
        return null
    }

    private fun summarize(c: Context, sid: String, title: String, transcript: String, links: List<String>, files: List<String>): Entry? {
        val p = prefs(c)
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val used = if (p.getString("day", "") == day) p.getInt("count", 0) else 0
        if (used >= DAILY_CAP) return null
        val (url, key, model) = endpoint(c) ?: return null
        p.edit().putString("day", day).putInt("count", used + 1).apply()
        val prompt = "把下面这段对话整理成用户以后能查的知识。只输出 JSON：" +
            "{\"topic\":\"2-6 字主题\",\"summary\":\"2-3 句大白话总结\",\"facts\":[\"要点：关键事实/配置/数字，最多 8 条\"],\"decisions\":[\"结论或做出的决定，最多 5 条\"],\"todos\":[\"还要做的事（待办），最多 5 条\"],\"links\":[\"重要链接\"],\"files\":[\"提到的文件\"]}。" +
            "不要写密码、密钥、验证码。\n标题：$title\n已发现链接：${links.joinToString(" ")}\n已发现文件：${files.joinToString(" ")}\n对话：\n$transcript"
        val body = JSONObject().put("model", model).put("temperature", 0.2).put("max_tokens", 1200)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val text = runCatching {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                JSONObject(r.body?.string().orEmpty()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        }.getOrNull() ?: return null
        val o = runCatching { JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)) }.getOrNull() ?: return null
        val local = Rules.digest(sid, title, transcript.lines(), links, files)
        return Entry(sid, title, o.optString("topic").ifBlank { local.topic }, o.optString("summary").take(400).ifBlank { local.summary },
            arr(o, "facts").take(8).ifEmpty { local.facts }, arr(o, "decisions").take(5), (arr(o, "links") + links).distinct().take(12),
            (arr(o, "files") + files).distinct().take(12), System.currentTimeMillis(), true, todos = arr(o, "todos").take(5))
    }

    /**
     * [Mines-38.7] Local rules when no model key is set (never blank): topic by keywords, 要点 = the user's questions and the
     * assistant's sentences with numbers / names, 结论 = sentences with 结论/决定/建议/推荐/选…, 待办 = sentences with
     * 待办/记得/明天/周末/下周/要去/提醒/下单/预约….
     */
    object Rules {
        private val decisionRx = Regex("结论|决定|定了|就用|最终|建议|推荐|选择|选了|方案是|答案是|总之|所以")
        private val todoRx = Regex("待办|记得|别忘|明天|后天|周末|下周|下个月|今晚|要去|需要去|提醒|下单|预约|稍后|回头|之后要|TODO|todo|计划")
        private fun sentences(t: String) = t.split(Regex("(?<=[。！？!?；;\\n])")).map { it.trim().trim('。', '；', ';') }.filter { it.length in 4..120 }

        fun digest(sid: String, title: String, lines: List<String>, links: List<String>, files: List<String>): Entry {
            val user = lines.filter { it.startsWith("用户：") }.map { it.removePrefix("用户：") }
            val others = lines.filterNot { it.startsWith("用户：") }.map { it.substringAfter('：') }
            val all = (user + others).flatMap { sentences(it) }
            val decisions = all.filter { decisionRx.containsMatchIn(it) }.distinct().take(5)
            val todos = all.filter { todoRx.containsMatchIn(it) && it !in decisions }.distinct().take(5)
            val asks = user.flatMap { sentences(it) }.take(3)
            val facts = (asks + others.flatMap { sentences(it) }.filter { Regex("\\d|[A-Za-z]{3,}|：").containsMatchIn(it) && it !in decisions && it !in todos })
                .distinct().take(6).ifEmpty { all.take(3) }
            val topic = ChatOrganizerTopic.of(title + " " + lines.joinToString(" "))
            val summary = buildString {
                append("聊了：").append(asks.firstOrNull() ?: title)
                decisions.firstOrNull()?.let { append("。结论：").append(it) }
                todos.firstOrNull()?.let { append("。待办：").append(it) }
            }.take(220)
            return Entry(sid, title, topic, summary, facts, decisions, links, files, System.currentTimeMillis(), false, todos = todos)
        }
    }
}

/** Topic by the same keywords 对话整理 chips use (no model). */
internal object ChatOrganizerTopic {
    fun of(text: String): String {
        val t = text.lowercase()
        return ChatOrganizer.topics.maxByOrNull { tp -> tp.keywords.count { t.contains(it) } }
            ?.takeIf { tp -> tp.keywords.any { t.contains(it) } }?.name ?: "其他"
    }
}
