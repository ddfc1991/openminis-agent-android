package com.openminis.app.agent

import org.json.JSONObject

/**
 * [Mines-38.9.9] 自动调度 (user 2026-10-19 09:10–09:18): nothing for the user to pick. Each model the app calls is scored from
 * what actually happened (answers, task completions, 5xx / 429 / time-outs, latency) plus a small built-in tier table by
 * model-id pattern; a route that rotates models behind one name (商汤 …) is judged by its results, not its name.
 *  - turn kinds: chat / tool / plan (plan+debug) from the request itself;
 *  - start cheap, escalate on signals: the same step failed twice, many tool errors, a long multi-step request;
 *  - a model with a 5xx / time-out in the last 10 min is skipped while another works;
 *  - on a 429 / 5xx the same model on another account (AMD × 14) is tried before a different model;
 *  - signed-in web members (web:…) are strong but only for plan / stuck / review calls, one at a time.
 * Pure (unit-tested); the Android side is [ModelScore].
 */
object ModelScoreCore {
    enum class Kind(val zh: String) { CHAT("聊天"), TOOL("执行"), PLAN("规划/排障") }

    data class Stats(
        val ok: Int = 0, val fail: Int = 0, val done: Int = 0,
        val last5xxAt: Long = 0, val lastTimeoutAt: Long = 0, val last429At: Long = 0,
        val latencyMs: Double = 0.0, val lastOkAt: Long = 0,
    )

    const val COOL_MS = 10 * 60_000L

    /** built-in prior by id pattern: 3 strong reasoning, 2 general, 1 light. Observed results dominate after ~5 calls. */
    fun tier(modelId: String): Int {
        val s = modelId.lowercase()
        if (s.startsWith("web:")) return 3
        if (Regex("opus|o[1-9](-|$)|o[1-9]-pro|gpt-?5(?!.*(mini|nano))|gpt-?4\\.1(?!-mini|-nano)|claude-(sonnet|opus)|sonnet|gemini-[2-9](\\.\\d)?-pro|gemini.*pro|deepseek-(r1|reasoner|v[3-9](\\.\\d)?(?!.*flash))|qwq|qwen3?-(max|235b|coder-plus)|glm-[4-9](\\.\\d)?(?!.*(flash|air))|kimi-k2|grok-[3-9](?!.*mini)|reason|thinking").containsMatchIn(s)
            && !Regex("flash|lite|mini|nano|tiny|small|haiku|air\\b|turbo|8b|7b|3b|1\\.5b").containsMatchIn(s)) return 3
        if (Regex("flash|lite|mini|nano|tiny|small|haiku|air\\b|turbo|\\b[1-9]b\\b|1[0-4]b").containsMatchIn(s)) return 1
        return 2
    }

    fun webMember(modelId: String) = modelId.lowercase().startsWith("web:")

    /** higher = stronger / more trustworthy now */
    fun score(modelId: String, st: Stats?, now: Long): Double {
        val prior = tier(modelId).toDouble()
        if (st == null) return prior
        val n = st.ok + st.fail
        val rate = if (n == 0) 0.6 else (st.ok + 0.6 * 2) / (n + 2.0)          // smoothed success rate
        val w = (n / 5.0).coerceAtMost(1.0)                                         // trust observations after ~5 calls
        val observed = 1.0 + 2.0 * rate + (st.done.coerceAtMost(10) / 10.0) * 0.5   // 1..3.5
        var s = (1 - w) * prior + w * observed
        if (st.latencyMs > 30_000) s -= 0.3
        if (recentBad(st, now)) s -= 2.0
        return s
    }

    fun recentBad(st: Stats?, now: Long): Boolean = st != null &&
        (now - st.last5xxAt < COOL_MS || now - st.lastTimeoutAt < COOL_MS) && st.lastOkAt < maxOf(st.last5xxAt, st.lastTimeoutAt)

    fun onOk(st: Stats?, ms: Long, now: Long, done: Boolean = false): Stats {
        val s = st ?: Stats()
        val lat = if (s.latencyMs <= 0) ms.toDouble() else s.latencyMs * 0.7 + ms * 0.3
        return s.copy(ok = s.ok + 1, done = s.done + if (done) 1 else 0, latencyMs = lat, lastOkAt = now)
    }

    fun onFail(st: Stats?, code: Int, timeout: Boolean, now: Long): Stats {
        val s = st ?: Stats()
        return s.copy(fail = s.fail + 1,
            last5xxAt = if (code in 500..599) now else s.last5xxAt,
            lastTimeoutAt = if (timeout) now else s.lastTimeoutAt,
            last429At = if (code == 429) now else s.last429At)
    }

    /** what the request is: planning / debugging, tool execution, or plain chat */
    fun classify(text: String): Kind {
        val t = text.lowercase()
        val planWords = Regex("排查|排障|调试|debug|为什么|报错|错误|失败|不工作|修复|fix|bug|根因|分析|方案|设计|规划|计划|plan|架构|重构|refactor|优化|定位|502|500|超时|timeout|crash|崩溃")
        val toolWords = Regex("打开|下载|安装|运行|执行|部署|上传|搜索|查一下|整理|生成|创建|删除|改成|写入|发送|run|install|deploy|open|fetch|ssh|curl|git ")
        val steps = Regex("(^|\\n)\\s*(\\d+[.、)]|[-•])").findAll(text).count()
        return when {
            planWords.containsMatchIn(t) || steps >= 3 || text.length > 600 -> Kind.PLAN
            toolWords.containsMatchIn(t) -> Kind.TOOL
            else -> Kind.CHAT
        }
    }

    /** one tool call as the loop saw it */
    data class Call(val tool: String, val failed: Boolean, val sig: String)

    /**
     * Escalation signal for this run, null = stay. Plain-Chinese reason for the progress card.
     * Uncertainty: the model's own text saying it is unsure counts as half a failure.
     */
    fun escalation(calls: List<Call>, kind: Kind, lastText: String = ""): String? {
        val fails = calls.filter { it.failed }
        val sameTwice = fails.groupBy { it.tool + "|" + it.sig }.values.any { it.size >= 2 }
        val unsure = Regex("不确定|不太清楚|可能是|也许|i'm not sure|not sure|unclear").containsMatchIn(lastText.lowercase())
        return when {
            sameTwice -> "这步卡了两次，换了更强的模型"
            fails.size >= 3 -> "连续出错 ${fails.size} 次，换了更强的模型"
            kind == Kind.PLAN && unsure && fails.isNotEmpty() -> "模型拿不准，换了更强的模型来判断"
            else -> null
        }
    }

    data class Cand(val key: String, val modelId: String)

    /** strongest candidate other than [currentKey] (web members allowed: escalation is a plan / stuck call) */
    fun strongest(cands: List<Cand>, currentKey: String, stats: (String) -> Stats?, now: Long, webBusy: Boolean = false): Cand? {
        val cur = cands.firstOrNull { it.key == currentKey }
        val curScore = cur?.let { score(it.modelId, stats(it.key), now) } ?: 0.0
        return cands.filter { it.key != currentKey && !recentBad(stats(it.key), now) && !(webBusy && webMember(it.modelId)) }
            .maxByOrNull { score(it.modelId, stats(it.key), now) }
            ?.takeIf { score(it.modelId, stats(it.key), now) > curScore + 0.25 }
    }

    /** next fallback after a 429 / 5xx: the same model on another account first, then the best working one (never a web member) */
    fun <T> nextFallback(list: List<T>, curModelId: String, modelOf: (T) -> String, keyOf: (T) -> String, stats: (String) -> Stats?, now: Long): T? {
        val usable = list.filter { !webMember(modelOf(it)) }.ifEmpty { list }
        usable.firstOrNull { modelOf(it) == curModelId && !recentBad(stats(keyOf(it)), now) }?.let { return it }
        return usable.filter { !recentBad(stats(keyOf(it)), now) }.maxByOrNull { score(modelOf(it), stats(keyOf(it)), now) } ?: list.firstOrNull()
    }

    /** compact hand-over for the stronger model: the task, what was tried, what failed */
    fun summary(task: String, calls: List<Call>, briefs: List<String>): String {
        val tried = calls.zip(briefs).takeLast(8).joinToString("\n") { (c, b) -> (if (c.failed) "✗ " else "✓ ") + c.tool + " " + b.take(80) + (if (c.failed && c.sig.isNotBlank()) " → " + c.sig.take(80) else "") }
        return "[自动调度] 前一个模型在这一步卡住了，现在由你接手。任务：" + task.lineSequence().firstOrNull().orEmpty().take(200) +
            "\n已尝试：\n" + tried + "\n请先看清本质（哪一层出的错），换一种做法，不要重复已失败的调用。"
    }

    fun toJson(m: Map<String, Stats>): JSONObject = JSONObject().apply {
        m.forEach { (k, s) -> put(k, JSONObject().put("ok", s.ok).put("fail", s.fail).put("done", s.done).put("x5", s.last5xxAt)
            .put("to", s.lastTimeoutAt).put("rl", s.last429At).put("lat", s.latencyMs).put("lok", s.lastOkAt)) }
    }

    fun fromJson(o: JSONObject): Map<String, Stats> = o.keys().asSequence().associateWith { k ->
        val j = o.optJSONObject(k) ?: JSONObject()
        Stats(j.optInt("ok"), j.optInt("fail"), j.optInt("done"), j.optLong("x5"), j.optLong("to"), j.optLong("rl"), j.optDouble("lat", 0.0), j.optLong("lok"))
    }
}
