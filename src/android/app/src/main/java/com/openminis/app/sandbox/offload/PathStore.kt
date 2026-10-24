package com.openminis.app.sandbox.offload

import android.content.Context
import org.json.JSONObject

/**
 * [Mines-38.8 v5d] Tier 1 storage: step paths that worked, per app (package at the start) + task name.
 * Small JSON values in one SharedPreferences file; at most [MAX] paths (least used / oldest dropped).
 */
internal object PathStore {
    private const val PREFS = "mines_ui_paths"
    private const val MAX = 200

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(c: Context, pkg: String, task: String): ScreenTiers.Path? =
        prefs(c).getString(ScreenTiers.key(pkg, task), null)?.let { runCatching { ScreenTiers.fromJson(JSONObject(it)) }.getOrNull() }

    fun put(c: Context, p: ScreenTiers.Path) {
        val sp = prefs(c)
        val e = sp.edit().putString(ScreenTiers.key(p.pkg, p.task), ScreenTiers.toJson(p).toString())
        val all = sp.all
        if (all.size >= MAX) {
            all.entries.mapNotNull { (k, v) -> (v as? String)?.let { s -> runCatching { ScreenTiers.fromJson(JSONObject(s)) }.getOrNull()?.let { k to it } } }
                .sortedWith(compareBy<Pair<String, ScreenTiers.Path>> { it.second.uses }.thenBy { it.second.savedAt })
                .take(all.size - MAX + 1).forEach { e.remove(it.first) }
        }
        e.apply()
    }

    fun describe(c: Context): String {
        val list = prefs(c).all.values.mapNotNull { (it as? String)?.let { s -> runCatching { ScreenTiers.fromJson(JSONObject(s)) }.getOrNull() } }
        if (list.isEmpty()) return "还没有保存的路径（do --task \"任务名\" \"步骤…\" 成功后会记下）\n"
        return list.sortedByDescending { it.savedAt }.joinToString("\n", postfix = "\n") { "· ${it.task} @ ${it.pkg} · ${it.steps.size} 步 · 回放 ${it.uses} 次" }
    }
}
