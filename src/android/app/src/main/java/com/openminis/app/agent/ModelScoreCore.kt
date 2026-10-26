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
        // [38.9.15.1] 「问题我看它都不会切」: also the last 3 calls failed in a row, or the model keeps saying it is redoing things
        val lastThreeFailed = calls.size >= 3 && calls.takeLast(3).all { it.failed }
        val redo = redoLines(lastText)
        return when {
            sameTwice -> "同一步失败了两次"
            lastThreeFailed -> "连续 3 步失败"
            fails.size >= 3 -> "出错 ${fails.size} 次"
            redo >= 3 -> "反复重做同一件事"
            kind == Kind.PLAN && unsure && fails.isNotEmpty() -> "模型拿不准"
            else -> null
        }
    }

    /**
     * [38.9.15.1] lines where the model narrates a redo / work-around (「命令有点乱。重发干净版。」「heredoc 被工具搞坏了。写到文件再跑。」
     * 「没输出，先验证…」): 3 of them in one run = a retry loop even when every tool call technically "succeeded".
     */
    private val redoRe = Regex("重发|重跑|再跑|重新跑|再试|重试|改用|换个办法|换种|搞坏|损坏|截断|没输出|没有输出|又失败|还是失败|还是不行|不行，|报错了|重来|又报错|try again|retry|corrupt|truncat")
    fun redoLines(text: String): Int = text.lines().count { it.isNotBlank() && redoRe.containsMatchIn(it.lowercase()) }

    /** [38.9.15.1] model family by id (DeepSeek / MiMo / Qwen / GLM …): a stuck run switches FAMILY, not just account */
    fun family(modelId: String): String {
        val s = modelId.lowercase().substringAfterLast('/')
        listOf("deepseek", "mimo", "qwen", "qwq", "glm", "kimi", "moonshot", "gemini", "gemma", "claude", "gpt", "o1", "o3", "o4", "grok",
            "llama", "mistral", "minimax", "abab", "doubao", "hunyuan", "ernie", "yi-", "baichuan", "step", "internlm", "intern", "phi", "nova", "command")
            .firstOrNull { s.contains(it) }?.let { return if (it == "qwq") "qwen" else if (it == "moonshot") "kimi" else if (it == "abab") "minimax" else it.trimEnd('-') }
        return s.substringBefore('-').substringBefore(':').ifBlank { s }
    }

    data class Member(val member: String, val key: String, val modelId: String)
    data class Pick(val target: Member?, val why: String)

    /**
     * [38.9.15.1] where a stuck run goes (user 15:02 「模型就这些模型基本都是flash」):
     *  1. a really stronger model (tier above the current one) — only if one is configured;
     *  2. else another FAMILY (MiMo ↔ DeepSeek …), best score, not cooling / recently bad;
     *  3. else another model of the same family; 4. else the same model on another account.
     * The caller also raises 思考 one level ([boostLevel]) and hands over a compact summary instead of the noisy transcript.
     */
    fun pickEscalation(cands: List<Member>, cur: Member, stats: (String) -> Stats?, cooling: (Member) -> Boolean, now: Long,
                       webBusy: Boolean = false): Pick {
        val ok = cands.filter { it.member != cur.member && !cooling(it) && !recentBad(stats(it.key), now) && !(webBusy && webMember(it.modelId)) }
        val curTier = tier(cur.modelId)
        fun best(l: List<Member>) = l.maxByOrNull { score(it.modelId, stats(it.key), now) }
        best(ok.filter { tier(it.modelId) > curTier })?.let { return Pick(it, "换更强的模型") }
        val fam = family(cur.modelId)
        best(ok.filter { family(it.modelId) != fam })?.let { return Pick(it, "换另一家模型") }
        best(ok.filter { it.modelId != cur.modelId })?.let { return Pick(it, "换同家另一个模型") }
        ok.firstOrNull { it.modelId == cur.modelId }?.let { return Pick(it, "换账号重来") }
        return Pick(null, "只提高思考强度")
    }

    /** [38.9.15.1] 思考 one step up (高 → 极高), capped at what the model supports; null = no change possible */
    fun <L : Comparable<L>> boostLevel(cur: L, supported: List<L>): L? = supported.sorted().firstOrNull { it > cur }

    data class Cand(val key: String, val modelId: String)

    /** strongest candidate other than [currentKey] (web members allowed: escalation is a plan / stuck call) */
    fun strongest(cands: List<Cand>, currentKey: String, stats: (String) -> Stats?, now: Long, webBusy: Boolean = false): Cand? {
        val cur = cands.firstOrNull { it.key == currentKey }
        val curScore = cur?.let { score(it.modelId, stats(it.key), now) } ?: 0.0
        return cands.filter { it.key != currentKey && !recentBad(stats(it.key), now) && !(webBusy && webMember(it.modelId)) }
            .maxByOrNull { score(it.modelId, stats(it.key), now) }
            ?.takeIf { score(it.modelId, stats(it.key), now) > curScore + 0.25 }
    }

    /**
     * next fallback after a 429 / 5xx / stall: the same model on another account first, then the best working one (never a web
     * member). [38.9.15.1] 「同模型先轮换账号再降级」: the same-model step looks only at the ACCOUNT's own cool-down ([cooling], keyed
     * per group member). The score key is provider-type|model, shared by all 14 AMD accounts, so one account's time-out used to
     * mark every AMD account of that model bad and the turn dropped straight to a weaker model.
     */
    fun <T> nextFallback(list: List<T>, curModelId: String, modelOf: (T) -> String, keyOf: (T) -> String, stats: (String) -> Stats?, now: Long,
                         cooling: (T) -> Boolean = { false }): T? {
        val usable = list.filter { !webMember(modelOf(it)) }.ifEmpty { list }
        val fresh = usable.filter { !cooling(it) }
        fresh.firstOrNull { modelOf(it) == curModelId }?.let { return it }
        val pool = fresh.ifEmpty { usable }
        return pool.filter { !recentBad(stats(keyOf(it)), now) }.maxByOrNull { score(modelOf(it), stats(keyOf(it)), now) } ?: pool.firstOrNull()
    }

    /** [38.9.15.1] per-account (group member) cool-down after a stall / 429 / 5xx / time-out; cleared by the member's next answer */
    class AccountCool(private val ms: Long = COOL_MS) {
        private val until = HashMap<String, Long>()
        @Synchronized fun mark(member: String, now: Long) { if (member.isNotBlank()) until[member] = now + ms }
        @Synchronized fun clear(member: String) { until.remove(member) }
        @Synchronized fun cooling(member: String, now: Long): Boolean = (until[member] ?: 0L) > now
    }

    /** [38.9.15.1] a stall: retry the same member only when no other account of the same model is free (else rotate at once) */
    fun <T> stallRotateNow(cands: List<T>, curMember: String, curModelId: String, memberOf: (T) -> String, modelOf: (T) -> String,
                           cooling: (T) -> Boolean): Boolean =
        cands.any { memberOf(it) != curMember && modelOf(it) == curModelId && !cooling(it) }

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
