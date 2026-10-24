package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-degen-34] Models that produced garbage twice in a turn are marked 「不稳定」
 * (shown in 模型路由 with 清除). Also the weak/flash heuristic behind the
 * "switch to a stronger model" hint.
 */
object ModelHealth {
    private const val PREFS = "mines_model_health"
    data class Mark(val id: String, val name: String, val why: String, val at: Long)

    private val _marks = MutableStateFlow<List<Mark>>(emptyList())
    val marks: StateFlow<List<Mark>> = _marks.asStateFlow()
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val raw = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("marks", "[]") ?: "[]"
        _marks.value = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { val o = a.getJSONObject(it); Mark(o.optString("id"), o.optString("name"), o.optString("why"), o.optLong("at")) }
        }.getOrDefault(emptyList())
    }

    private fun save(c: Context) {
        val a = JSONArray()
        _marks.value.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("why", it.why).put("at", it.at)) }
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("marks", a.toString()).apply()
    }

    fun markUnstable(c: Context, id: String, name: String, why: String) {
        load(c)
        _marks.value = (_marks.value.filterNot { it.id == id } + Mark(id, name, why, System.currentTimeMillis())).takeLast(30)
        save(c)
    }

    fun clear(c: Context, id: String? = null) {
        load(c)
        _marks.value = if (id == null) emptyList() else _marks.value.filterNot { it.id == id }
        save(c)
    }

    fun isUnstable(c: Context, id: String): Boolean { load(c); return _marks.value.any { it.id == id } }

    /** Heuristic: flash / lite / mini / nano / small open models (≤ 35B) are "light" for long agent runs. */
    fun isWeak(id: String, name: String): Boolean {
        val s = (id + " " + name).lowercase()
        if (Regex("flash|lite|mini|nano|tiny|small|haiku|air\\b|turbo").containsMatchIn(s)) return true
        val b = Regex("(\\d+(?:\\.\\d+)?)\\s*b\\b").find(s)?.groupValues?.get(1)?.toDoubleOrNull()
        return b != null && b <= 35.0
    }
}
