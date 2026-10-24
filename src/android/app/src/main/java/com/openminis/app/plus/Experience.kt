package com.openminis.app.plus

import android.content.Context
import com.openminis.app.agent.ExperienceCore
import com.openminis.app.agent.ExperienceCore.Entry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [Mines-38.9] 经验库 on the phone (files/mines_experience.json). See [ExperienceCore] for what an entry is.
 * Written by itself — after a task that went well (做法), when the loop guard stopped a run (教训), when a phone
 * screen path was saved for replay (做法 · 回放) — and by the user in 设置 › 经验 (view / edit / delete).
 * Never holds passwords, codes or keys (only tool names and short argument summaries, secrets blanked).
 */
object Experience {
    private val _list = MutableStateFlow<List<Entry>>(emptyList())
    val list: StateFlow<List<Entry>> = _list
    @Volatile private var loaded = false
    private fun file(c: Context) = File(c.applicationContext.filesDir, "mines_experience.json")

    fun init(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _list.value = runCatching { fromJson(JSONArray(file(c).readText())) }.getOrDefault(emptyList())
            loaded = true
        }
    }

    private fun persist(c: Context) = runCatching {
        val f = file(c); val tmp = File(f.path + ".tmp")
        tmp.writeText(toJson(_list.value).toString()); tmp.renameTo(f)
    }

    fun toJson(l: List<Entry>): JSONArray = JSONArray().apply {
        l.forEach { e -> put(JSONObject().put("id", e.id).put("kind", e.kind.name).put("pattern", e.pattern).put("steps", e.steps)
            .put("tools", JSONArray(e.tools)).put("pitfalls", e.pitfalls).put("version", e.version).put("uses", e.uses)
            .put("createdAt", e.createdAt).put("updatedAt", e.updatedAt).put("source", e.source).put("keywords", JSONArray(e.keywords))) }
    }

    fun fromJson(a: JSONArray): List<Entry> = (0 until a.length()).mapNotNull { i -> runCatching {
        val o = a.getJSONObject(i)
        fun arr(k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.optString(it) } } ?: emptyList()
        Entry(o.getString("id"), runCatching { ExperienceCore.Kind.valueOf(o.optString("kind")) }.getOrDefault(ExperienceCore.Kind.HOWTO),
            o.optString("pattern"), o.optString("steps"), arr("tools"), o.optString("pitfalls"), o.optInt("version", 1), o.optInt("uses"),
            o.optLong("createdAt"), o.optLong("updatedAt"), o.optString("source", "自动"), arr("keywords"))
    }.getOrNull() }

    private fun newId() = "x" + java.lang.Long.toString(System.currentTimeMillis(), 36) + (100..999).random()

    fun upsert(c: Context, e: Entry) { init(c); _list.value = ExperienceCore.upsert(_list.value, e); persist(c) }

    /** User edit in 设置 › 经验: a new version, marked 手动 (later automatic learning keeps the user's steps). */
    fun edit(c: Context, id: String, pattern: String, steps: String, pitfalls: String) {
        init(c)
        val now = System.currentTimeMillis()
        _list.value = _list.value.map { if (it.id == id) it.copy(pattern = pattern.trim().take(60), steps = steps.trim().take(1200), pitfalls = pitfalls.trim().take(400),
            version = it.version + 1, updatedAt = now, source = "手动", keywords = ExperienceCore.tokens(pattern + " " + steps).distinct().take(12)) else it }
        persist(c)
    }

    fun add(c: Context, kind: ExperienceCore.Kind, pattern: String, steps: String, pitfalls: String) {
        val now = System.currentTimeMillis()
        upsert(c, Entry(newId(), kind, pattern.trim().take(60), steps.trim().take(1200), emptyList(), pitfalls.trim().take(400),
            createdAt = now, updatedAt = now, source = "手动", keywords = ExperienceCore.tokens(pattern + " " + steps).distinct().take(12)))
    }

    fun delete(c: Context, id: String) { init(c); _list.value = _list.value.filterNot { it.id == id }; persist(c) }

    /**
     * [Mines-38.9.11 i32] user 2026-10-20: 经验 held single verification codes (「012746」「Qd5xs3d」) and raw envelopes. Drops
     * trivial / code entries, masks codes and keys in what remains, and merges near-duplicates of the same kind (uses summed,
     * newest kept, version bumped). Returns how many entries went away.
     */
    fun scrubAll(c: Context): Int {
        init(c)
        val before = _list.value.size
        val kept = _list.value.filterNot { it.source != "手动" && XpViewCore.trivial(it.pattern, it.steps) }.map { e ->
            e.copy(pattern = XpViewCore.scrub(XpViewCore.scheduled(e.pattern)?.let { "定时任务：" + it.label } ?: XpViewCore.clean(e.pattern)).take(60).ifBlank { e.pattern.take(60) },
                steps = e.steps.lines().joinToString("\n") { XpViewCore.scrub(it) }, pitfalls = e.pitfalls.lines().joinToString("\n") { XpViewCore.scrub(it) })
        }
        val merged = ExperienceCore.Kind.values().flatMap { k ->
            XpViewCore.groupSimilar(kept.filter { it.kind == k }) { it.pattern }.map { g ->
                if (g.size == 1) g[0] else {
                    val newest = g.maxByOrNull { it.updatedAt } ?: g[0]
                    newest.copy(uses = g.sumOf { it.uses }, version = g.maxOf { it.version } + 1, createdAt = g.minOf { it.createdAt },
                        source = if (g.any { it.source == "手动" }) "手动" else newest.source,
                        pitfalls = g.flatMap { it.pitfalls.lines() }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4).joinToString("\n"))
                }
            }
        }.sortedByDescending { it.updatedAt }
        _list.value = merged; persist(c)
        return before - merged.size
    }

    /** the task as it should be remembered: scheduled → 「定时任务：label」, envelopes / tags / ids removed */
    private fun cleanTask(task: String) = XpViewCore.scheduled(task)?.let { "定时任务：" + it.label } ?: XpViewCore.clean(task)

    // ---------------- agent hooks ----------------

    /** The block injected into the system prompt before a model call (empty when nothing is relevant). Bumps uses. */
    fun promptFragment(c: Context, query: String): String {
        if (query.isBlank()) return ""
        init(c)
        val hits = ExperienceCore.retrieve(query.take(600), _list.value).map { it.first }
        if (hits.isEmpty()) return ""
        lastHits = hits.map { it.id }.toSet()
        return ExperienceCore.promptBlock(hits)
    }
    @Volatile private var lastHits: Set<String> = emptySet()

    fun relevant(c: Context, query: String, k: Int = 2): List<Entry> { init(c); return ExperienceCore.retrieve(query.take(600), _list.value, k, min = 0.5).map { it.first } }

    private val secret = Regex("(?i)(password|passwd|token|secret|api[_-]?key|authorization|bearer|cookie)\\S*\\s*[:=]\\s*\\S+")
    /** One short, secret-free summary of a tool call's arguments. */
    fun brief(argsJson: String): String = runCatching {
        val o = JSONObject(argsJson)
        val k = listOf("tool_title", "command", "action", "url", "path", "query", "task", "text").firstOrNull { o.has(it) && o.optString(it).isNotBlank() }
        val v = if (k == null) argsJson else (if (k == "action" && o.has("url")) o.optString("action") + " " + o.optString("url") else o.optString(k))
        v.replace(secret, "$1=***").replace(Regex("\\s+"), " ").take(70)
    }.getOrDefault(argsJson.replace(secret, "$1=***").take(70))

    /** After a run ended (plan → execute → self-check → record): keeps a 做法 when it went well. No question to the user. */
    fun recordRun(c: Context, task: String, calls: List<ExperienceCore.Call>, finalText: String) = runCatching {
        init(c)
        val now = System.currentTimeMillis()
        // entries that were in the prompt and the run went well → used once more
        if (lastHits.isNotEmpty() && ExperienceCore.selfCheck(calls, finalText)) {
            _list.value = _list.value.map { if (it.id in lastHits) it.copy(uses = it.uses + 1) else it }; persist(c)
        }
        // [Mines-38.9.11 i32] never learn from an envelope or a code; never keep a code in the steps
        val t = cleanTask(task)
        if (XpViewCore.trivial(t, "")) return@runCatching
        ExperienceCore.learnHowto(t, calls.map { it.copy(brief = XpViewCore.scrub(it.brief), error = XpViewCore.scrub(it.error)) }, finalText, now, newId())?.let { upsert(c, it) }
    }

    /** The loop guard stopped a run → a 教训 (what failed, why, what to do instead). */
    fun recordLesson(c: Context, task: String, tool: String, signature: String, instead: String) = runCatching {
        upsert(c, ExperienceCore.learnLesson(cleanTask(task), tool, XpViewCore.scrub(signature), XpViewCore.scrub(instead), System.currentTimeMillis(), newId()))
    }

    /** A phone screen path that worked was saved for replay → a 做法 that points at the replay command. */
    fun recordReplay(c: Context, pkg: String, task: String, steps: List<String>) = runCatching {
        val now = System.currentTimeMillis()
        upsert(c, Entry(newId(), ExperienceCore.Kind.HOWTO, "手机操作：$task".take(40),
            (listOf("直接回放：android-a11y-cli do --task \"$task\"（$pkg）") + steps.take(6)).joinToString("\n"),
            listOf("android-a11y-cli"), "", createdAt = now, updatedAt = now, source = "回放",
            keywords = ExperienceCore.tokens("$task $pkg 手机 屏幕").distinct().take(12)))
    }
}
