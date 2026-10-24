package com.openminis.app.plus

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [Mines-38.9.12.1] Safe start after an out-of-memory death (user 2026-10-23: 1.23.11.3.1 OOM'd with 5 agent sessions
 * running and then would not open). The crash handler calls [noteFatal] synchronously before the process dies; it keeps
 * the crash times and, after an OOM (or a second crash inside 10 min), writes files/mines-safe-start.json with the
 * sessions that were running. The next launch ([checkAtLaunch], MinisApp.onCreate) then:
 *  - lands on home — never the auto-reopened last chat or a saved-state chat ([forceHome]);
 *  - resumes nothing by itself (Mines never auto-resumes; this only makes sure the launch does not reopen the chat);
 *  - shows one card 「上次内存不足退出，已暂停 N 个任务」 with 继续 per task ([card]).
 * A 38.9.11.x OOM (no marker) is recognised from the unseen crash record that names OutOfMemoryError.
 */
object SafeStart {
    private const val TAG = "MinesSafeStart"
    private const val MARKER = "mines-safe-start.json"
    private const val CRASHES = "mines-crash-times.json"

    data class Card(val title: String, val body: String, val sessions: List<String>, val oom: Boolean)

    /** the home card; null = nothing to show */
    val card = MutableStateFlow<Card?>(null)
    /** true for the first launch after the marker: land on home */
    @Volatile var forceHome: Boolean = false
        private set
    @Volatile private var checked = false
    /** for the smoke readout */
    @Volatile var lastReason: String = ""
        private set

    private fun marker(c: Context) = File(c.filesDir, MARKER)

    /** Crash handler (any thread, process about to die): cheap, synchronous, never throws. */
    fun noteFatal(c: Context, oom: Boolean) {
        runCatching {
            val now = System.currentTimeMillis()
            val f = File(c.filesDir, CRASHES)
            val prev = runCatching { JSONArray(f.readText()).let { a -> List(a.length()) { a.getLong(it) } } }.getOrDefault(emptyList())
            val times = SafeStartCore.noteCrash(prev, now)
            f.writeText(JSONArray(times).toString())
            if (!SafeStartCore.safeStart(oom, times.size)) return
            val m = com.openminis.app.service.SessionConcurrencyManager
            val sessions = (m.runningSessions.value + m.suspendedSessions.value).filter { it.isNotBlank() && !it.startsWith("__new__") }.distinct()
            marker(c).writeText(JSONObject().put("at", now).put("oom", oom).put("crashes", times.size)
                .put("sessions", JSONArray(sessions)).put("launched", false).toString())
            Log.e(TAG, "safe start armed: oom=$oom crashes=${times.size} sessions=${sessions.size}")
        }
    }

    /** MinisApp.onCreate, once per process (small file reads only). */
    fun checkAtLaunch(c: Context) {
        if (checked) return
        checked = true
        runCatching {
            val now = System.currentTimeMillis()
            val f = marker(c)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val at = o.optLong("at")
                if (!SafeStartCore.markerLive(at, now)) { f.delete(); return }
                val a = o.optJSONArray("sessions") ?: JSONArray()
                val sessions = List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
                val oom = o.optBoolean("oom", true)
                if (!o.optBoolean("launched", false)) {
                    forceHome = true
                    f.writeText(o.put("launched", true).toString())
                }
                card.value = Card(SafeStartCore.cardTitle(sessions.size, oom), SafeStartCore.cardBody(sessions.size), sessions, oom)
                lastReason = "marker"
                Log.w(TAG, "safe start: forceHome=$forceHome paused=${sessions.size} oom=$oom")
                return
            }
            // a 38.9.11.x OOM death before this build wrote markers: its unseen crash record names the OOM
            val crash = MinesCrash.unseen(c) ?: return
            val cf = MinesCrash.file(c)
            if (crash.contains("OutOfMemoryError") && SafeStartCore.markerLive(cf.lastModified(), now)) {
                forceHome = true
                card.value = Card(SafeStartCore.cardTitle(0, true), SafeStartCore.cardBody(0), emptyList(), true)
                f.writeText(JSONObject().put("at", cf.lastModified()).put("oom", true).put("sessions", JSONArray()).put("launched", true).toString())
                lastReason = "crashlog"
                Log.w(TAG, "safe start from an OOM crash record")
            }
        }
    }

    /** 继续: this task leaves the card (the chat opens; the user resumes it there). */
    fun continued(c: Context, sessionId: String) {
        val cur = card.value ?: return
        val left = cur.sessions - sessionId
        if (left.isEmpty()) { dismiss(c); return }
        card.value = cur.copy(title = SafeStartCore.cardTitle(left.size, cur.oom), body = SafeStartCore.cardBody(left.size), sessions = left)   // [38.9.12.1 e] title follows the count
        runCatching {
            val f = marker(c)
            if (f.exists()) f.writeText(JSONObject(f.readText()).put("sessions", JSONArray(left)).toString())
        }
    }

    /** 知道了 */
    fun dismiss(c: Context) {
        card.value = null
        runCatching { marker(c).delete() }
        runCatching { MinesCrash.markSeen(c) }
    }

    fun stateJson(): String = JSONObject().put("forceHome", forceHome).put("reason", lastReason)
        .put("card", card.value?.let { JSONObject().put("title", it.title).put("paused", it.sessions.size).put("oom", it.oom) } ?: JSONObject.NULL).toString()
}
