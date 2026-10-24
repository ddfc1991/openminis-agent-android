package com.openminis.app.plus

import android.content.Context
import com.openminis.app.agent.ExperienceCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * [Mines-38.9.11 i32] Card titles for 工作流记录 / 经验 (see [XpViewCore]). Every card shows a title at once from
 * [XpViewCore.heuristicTitle]; in the background the user's configured model writes a short Chinese title + one line
 * (cached in files/mines_xp_titles.json, ≤ 12 per page open, ≤ 80 a day, never the 免登录 gateway). Stored records are
 * migrated on first open: secrets / codes scrubbed, trivial experiences (「012746」) removed, near-duplicates merged.
 */
object XpTitles {
    data class Card(val title: String, val summary: String, val byModel: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _cache = MutableStateFlow<Map<String, Card>>(emptyMap())
    val cache: StateFlow<Map<String, Card>> = _cache.asStateFlow()
    @Volatile private var loaded = false
    @Volatile private var busy = false
    @Volatile var lastModel: String = ""
    @Volatile var lastError: String = ""

    private fun file(c: Context) = File(c.applicationContext.filesDir, "mines_xp_titles.json")
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("mines_xp", Context.MODE_PRIVATE)

    fun init(c: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _cache.value = runCatching {
                val o = JSONObject(file(c).readText())
                o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Card(it.optString("t"), it.optString("s"), it.optBoolean("m")) } }
            }.getOrDefault(emptyMap())
            loaded = true
        }
    }

    private fun persist(c: Context) = runCatching {
        val o = JSONObject(); _cache.value.forEach { (k, v) -> o.put(k, JSONObject().put("t", v.title).put("s", v.summary).put("m", v.byModel)) }
        val f = file(c); val tmp = File(f.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
    }

    fun keyOf(r: WorkflowLogCore.Run) = "w:" + r.id
    fun keyOf(e: ExperienceCore.Entry) = "x:" + e.id + ":" + e.version

    fun stepsOf(r: WorkflowLogCore.Run) = r.steps.map { it.tool to it.brief }
    fun stepsOf(e: ExperienceCore.Entry) = e.steps.lines().filter { it.isNotBlank() }.map { l ->
        val t = l.substringBefore(':', "").trim().takeIf { it.matches(Regex("[a-z_\\-]{3,40}")) } ?: (e.tools.firstOrNull() ?: "")
        t to l
    }

    /** the card for a run: model title when cached, else the heuristic */
    fun card(r: WorkflowLogCore.Run): Card = _cache.value[keyOf(r)]
        ?: Card(XpViewCore.heuristicTitle(r.task, stepsOf(r)), XpViewCore.heuristicSummary(stepsOf(r), r.ok), false)

    fun card(e: ExperienceCore.Entry): Card = _cache.value[keyOf(e)] ?: run {
        val st = stepsOf(e)
        val title = XpViewCore.heuristicTitle(e.pattern, st)
        val sum = if (e.kind == ExperienceCore.Kind.LESSON) XpViewCore.scrub(XpViewCore.clean(e.steps.lines().firstOrNull { it.isNotBlank() }.orEmpty())).take(40).ifBlank { "下次换个做法" }
            else XpViewCore.heuristicSummary(st, true)
        Card(title, sum, false)
    }

    /** one-time (per version) cleanup of what is already stored */
    fun migrate(c: Context) {
        val p = prefs(c)
        if (p.getInt("migrated", 0) >= 1) return
        runCatching { Experience.scrubAll(c) }
        runCatching { WorkflowLog.scrubAll(c) }
        p.edit().putInt("migrated", 1).apply()
    }

    /** background: model titles for the newest records that have none yet */
    fun summarizeSome(c: Context, runs: List<WorkflowLogCore.Run>, xps: List<ExperienceCore.Entry>, max: Int = 12) {
        init(c)
        if (busy) return
        val p = prefs(c)
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        val used = if (p.getString("day", "") == day) p.getInt("n", 0) else 0
        if (used >= 80) return
        val todo = ArrayList<Triple<String, String, () -> String>>()
        runs.filter { !_cache.value.containsKey(keyOf(it)) || _cache.value[keyOf(it)]?.byModel == false }.take(max).forEach { r ->
            todo += Triple(keyOf(r), "w", { XpViewCore.summaryPrompt(r.task, stepsOf(r), r.result, r.ok) })
        }
        xps.filter { !_cache.value.containsKey(keyOf(it)) }.take((max - todo.size).coerceAtLeast(0)).forEach { e ->
            todo += Triple(keyOf(e), "x", { XpViewCore.summaryPrompt(e.pattern, stepsOf(e), e.pitfalls, e.kind == ExperienceCore.Kind.HOWTO) })
        }
        if (todo.isEmpty()) return
        busy = true
        scope.launch {
            try {
                var n = used
                for ((k, _, prompt) in todo) {
                    if (n >= 80) break
                    val ans = ask(c, prompt()) ?: break      // no usable model → stop (heuristic titles stay)
                    n++
                    XpViewCore.parseModelSummary(ans)?.let { (t, s) -> _cache.value = _cache.value + (k to Card(t, s, true)); persist(c) }
                }
                p.edit().putString("day", day).putInt("n", n).apply()
            } finally { busy = false }
        }
    }

    /** the user's own model: cheapest enabled cloud entry with a key whose last health check was not a failure */
    private suspend fun ask(c: Context, prompt: String): String? {
        val a = c.applicationContext as? com.openminis.app.MinisApp ?: return null
        val repo = a.providerRepository
        ProviderHealth.init(c)
        val bad = ProviderHealth.checks.value.filterValues { !it.ok }.keys
        val cheapRe = Regex("(?i)(flash|mini|lite|haiku|turbo|nano|small|air|8b|7b)")
        val entries = repo.allVisibleEntries().filter { e -> if (!ErrZhCore.chatCapable(e.baseModel.id)) return@filter false   // [38.9.11.3] no TTS / embedding / image models
            val inst = repo.instance(e.providerInstanceId)
            inst != null && inst.isEnabled && e.providerInstanceId !in bad && !(inst.customBaseURL ?: "").contains("127.0.0.1")
        }.sortedByDescending { cheapRe.containsMatchIn(it.baseModel.id) }.take(3)
        for (e in entries) {
            val inst = repo.instance(e.providerInstanceId) ?: continue
            val key = repo.usableApiKey(inst) ?: (if (repo.hasAnyCredential(inst)) "" else null) ?: continue
            val r = runCatching {
                kotlinx.coroutines.withTimeout(30_000L) {
                    com.openminis.app.provider.ProviderFactory.create(inst, key, e.baseModel, c, overrides = e.overrides)
                        .sendMessage(messages = listOf(com.openminis.app.data.model.LLMMessage(role = com.openminis.app.data.model.LLMMessage.Role.USER, content = prompt)),
                            systemPrompt = "你只输出 JSON。", maxTokens = 300).text
                }
            }.onFailure { lastError = "${e.baseModel.id}：${it.message ?: it.javaClass.simpleName}".take(200) }.getOrNull()
            if (!r.isNullOrBlank()) { lastModel = e.baseModel.id; lastError = ""; return r }
        }
        return null
    }
}
