package com.openminis.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-loop-30] Per-run guard against an agent that repeats itself.
 *
 * build-29 user report: on 商汤 / deepseek-flash the assistant wrote the same
 * line 「继续逆向 nanoMuse APK…」 and issued the same tool call over and over
 * until 224/224 tool runs. [ToolLoopDetector] only warns at 10 identical calls
 * and blocks at 20–30 identical *results*, and the chat cap is 200 turns, so
 * nothing stopped it.
 *
 * Rules (one instance per runAgentLoop call):
 *  - the same tool name + normalized args ≥ [REPEAT] times in this run, or the
 *    same assistant text in ≥ [REPEAT] consecutive turns → [Verdict.WARN]
 *    (the caller tells the model it is repeating);
 *  - after a warning, the same call / text again → [Verdict.PAUSE_REPEAT];
 *  - more than [maxToolCalls] tool calls in this run → [Verdict.PAUSE_CAP].
 * Pure logic, no Android state, so it is cheap to reason about.
 */
class RunLoopGuard(val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS) {

    enum class Verdict { OK, WARN, PAUSE_REPEAT, PAUSE_CAP }

    private val callCounts = HashMap<String, Int>()
    private var lastText: String? = null
    private var textStreak = 0
    private val warnedSigs = HashSet<String>()
    private var warnedText: String? = null
    var warned = false
        private set
    var toolCalls = 0
        private set
    /** Human-readable description of what repeated, for the note / log. */
    var lastRepeat: String = ""
        private set

    /** Call once per assistant turn that requested tools: [calls] = (name, argsJson). */
    fun onTurn(text: String, calls: List<Pair<String, String>>): Verdict {
        toolCalls += calls.size
        val t = normText(text)
        if (t.isNotEmpty() && t == lastText) textStreak++ else textStreak = if (t.isEmpty()) 0 else 1
        lastText = t.ifEmpty { null }

        val sigs = calls.map { (n, a) -> n + "|" + canonical(a) }
        for (s in sigs) callCounts[s] = (callCounts[s] ?: 0) + 1

        if (warned) {
            val again = sigs.firstOrNull { it in warnedSigs }
                ?: sigs.firstOrNull { (callCounts[it] ?: 0) >= REPEAT }
            val textAgain = t.isNotEmpty() && (t == warnedText || textStreak >= REPEAT)
            if (again != null || textAgain) {
                lastRepeat = again?.let { "工具 ${it.substringBefore('|')}（同样的参数）" } ?: "同一段话"
                return Verdict.PAUSE_REPEAT
            }
        }
        val repeated = sigs.filter { (callCounts[it] ?: 0) >= REPEAT }
        val textRepeated = textStreak >= REPEAT
        if (!warned && (repeated.isNotEmpty() || textRepeated)) {
            warned = true
            warnedSigs += repeated
            if (textRepeated) warnedText = t
            lastRepeat = repeated.firstOrNull()?.let { s ->
                "工具 ${s.substringBefore('|')}（同样的参数已调用 ${callCounts[s]} 次）"
            } ?: "同一段话（连续 $textStreak 轮）"
            return Verdict.WARN
        }
        if (toolCalls >= maxToolCalls) return Verdict.PAUSE_CAP
        return Verdict.OK
    }

    /** The note appended to the last tool result after [Verdict.WARN]. */
    fun warningNote(): String =
        "<system-reminder>You are repeating yourself: $lastRepeat. The results above did not change, " +
            "so doing the same thing again will not help. Read the tool results you already have, then " +
            "either change your approach (different command, different file, different arguments) or stop " +
            "calling tools and answer the user with what you found. If you repeat the same call again the " +
            "run will be paused.</system-reminder>"

    companion object {
        const val REPEAT = 3
        const val DEFAULT_MAX_TOOL_CALLS = 60
        private const val PREFS = "mines_agent"
        const val KEY_MAX_TOOL_CALLS = "max_tool_calls_per_run"

        fun maxToolCalls(context: Context): Int =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MAX_TOOL_CALLS, DEFAULT_MAX_TOOL_CALLS).coerceIn(10, 500)

        fun setMaxToolCalls(context: Context, n: Int) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MAX_TOOL_CALLS, n.coerceIn(10, 500)).apply()
        }

        // [Mines-degen-34] Per-turn thinking length cap (characters). Default 24 000 ≈ 8–16K tokens.
        const val KEY_THINK_CAP = "thinking_cap_chars"
        const val DEFAULT_THINK_CAP = 24_000
        fun thinkingCap(context: Context): Int =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_THINK_CAP, DEFAULT_THINK_CAP).coerceIn(4_000, 200_000)

        fun setThinkingCap(context: Context, n: Int) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_THINK_CAP, n.coerceIn(4_000, 200_000)).apply()
        }

        fun normText(s: String): String = s.trim().replace(Regex("\\s+"), " ")

        /** Args JSON with keys sorted and string whitespace collapsed, so cosmetic differences still match. */
        fun canonical(json: String): String = runCatching { canon(JSONObject(json)) }
            .getOrElse { normText(json) }

        private fun canon(v: Any?): String = when (v) {
            is JSONObject -> v.keys().asSequence().toList().sorted()
                .joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canon(v.opt(k)) }
            is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
            is String -> JSONObject.quote(normText(v))
            null, JSONObject.NULL -> "null"
            else -> v.toString()
        }
    }
}
