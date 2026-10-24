package com.openminis.app.agent

/**
 * [Mines-38.9] 经验库 — Mines' own, model-agnostic decision memory (user-approved 2026-10-16):
 *  - 做法 (HOWTO): a task pattern that worked, its steps / path, the tools, the pitfalls met on the way;
 *  - 教训 (LESSON): a Reflexion-style post-mortem — what failed, why, what to do instead.
 * Entries are versioned (every edit or re-learn bumps [Entry.version]), shown in 设置 › 经验 where the user can read,
 * change or delete them. Before every model call the few entries most relevant to the user's request are rendered
 * compactly into the system prompt, so whichever cloud model runs next starts from what Mines already learned.
 *
 * This file is the pure part (retrieval, rendering, learning from a finished run, the loop-guard consult) and is
 * unit-tested on the JVM; [com.openminis.app.plus.Experience] stores it on the phone.
 */
object ExperienceCore {
    enum class Kind(val label: String) { HOWTO("做法"), LESSON("教训") }

    data class Entry(
        val id: String,
        val kind: Kind,
        /** what kind of task this is about, in the user's words (「注册网站账号」「在 B 站发评论」) */
        val pattern: String,
        /** HOWTO: steps that worked (one per line). LESSON: what to do instead. */
        val steps: String,
        val tools: List<String> = emptyList(),
        /** HOWTO: pitfalls met. LESSON: what failed + why. */
        val pitfalls: String = "",
        val version: Int = 1,
        val uses: Int = 0,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
        /** "自动" (learned from a run), "手动" (user), "回放" (a saved phone path) */
        val source: String = "自动",
        val keywords: List<String> = emptyList(),
    )

    // ---------------- retrieval (cheap, no network) ----------------

    private val stop = setOf("的", "了", "我", "你", "帮", "一下", "请", "吗", "呢", "把", "给", "在", "是", "和", "the", "a", "an", "to", "of", "and", "for", "in", "on", "please", "me")

    /** Tokens: lower-case latin / digit words (≥ 2) + Chinese character bigrams (single chars for 1-char runs). */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val t = text.lowercase()
        Regex("[a-z0-9][a-z0-9_.\\-]+").findAll(t).forEach { m -> m.value.trim('.', '-', '_').takeIf { it.length >= 2 && it !in stop }?.let(out::add) }
        Regex("[\\u4e00-\\u9fff]+").findAll(t).forEach { m ->
            val s = m.value
            if (s.length == 1) { if (s !in stop) out += s } else for (i in 0 until s.length - 1) { val b = s.substring(i, i + 2); if (b !in stop) out += b }
        }
        return out
    }

    private fun docText(e: Entry) = e.pattern + " " + e.pattern + " " + e.keywords.joinToString(" ") + " " + e.tools.joinToString(" ") + " " + e.steps.take(400) + " " + e.pitfalls.take(200)

    /**
     * BM25-style score of every entry against [query]; pattern words count double (it is in the doc twice). Entries
     * used often get a small boost; lessons get a small boost too (they prevent repeats). Returns at most [k] with score > [min].
     */
    fun retrieve(query: String, entries: List<Entry>, k: Int = 3, min: Double = 0.9): List<Pair<Entry, Double>> {
        val q = tokens(query).distinct()
        if (q.isEmpty() || entries.isEmpty()) return emptyList()
        val docs = entries.map { it to tokens(docText(it)) }
        val n = docs.size.toDouble()
        val avg = docs.sumOf { it.second.size }.toDouble() / n
        val df = HashMap<String, Int>()
        docs.forEach { (_, d) -> d.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val k1 = 1.2; val b = 0.75
        return docs.map { (e, d) ->
            val tf = d.groupingBy { it }.eachCount()
            var s = 0.0
            for (w in q) {
                val f = tf[w] ?: continue
                val idf = kotlin.math.ln(1 + (n - (df[w] ?: 0) + 0.5) / ((df[w] ?: 0) + 0.5))
                s += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * d.size / avg.coerceAtLeast(1.0)))
            }
            if (s > 0) s *= 1.0 + 0.05 * kotlin.math.min(e.uses, 10) + (if (e.kind == Kind.LESSON) 0.1 else 0.0)
            e to s
        }.filter { it.second > min }.sortedByDescending { it.second }.take(k)
    }

    /** The compact system-prompt block (≤ [maxChars]); empty when nothing is relevant. */
    fun promptBlock(hits: List<Entry>, maxChars: Int = 900): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder("Mines experience library (learned on this phone; prefer these over guessing, they apply to every model):\n")
        for (e in hits) {
            val line = buildString {
                append("- [").append(e.kind.label).append(" v").append(e.version).append("] ").append(e.pattern.take(40)).append(": ")
                append(e.steps.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" → ").take(220))
                if (e.pitfalls.isNotBlank()) append(" | 注意：").append(e.pitfalls.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(100))
            }
            if (sb.length + line.length > maxChars) break
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd()
    }

    // ---------------- learning from a run ----------------

    data class Call(val tool: String, val brief: String, val failed: Boolean = false, val error: String = "")

    private val failWords = Regex("(没成功|失败|做不到|无法完成|没能|不能完成|failed|unable to|couldn't|could not)", RegexOption.IGNORE_CASE)

    /** Self-check of a finished run: did it end well? (final text does not report failure, and the last calls did not fail) */
    fun selfCheck(calls: List<Call>, finalText: String): Boolean {
        if (calls.isEmpty()) return false
        val tail = calls.takeLast(3)
        if (tail.isNotEmpty() && tail.all { it.failed }) return false
        return !failWords.containsMatchIn(finalText.take(600))
    }

    /** Short task pattern from the user's request (first sentence, ≤ 40 chars). */
    fun patternOf(task: String): String = task.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        .split(Regex("[。！？!?\\n]")).first().trim().take(40)

    /**
     * Turn a finished run into a 做法 (when [selfCheck] passed and it took ≥ 3 tool calls) — steps are the successful
     * calls in order, de-duplicated, ≤ 8; pitfalls are the distinct errors met on the way. Null when nothing worth keeping.
     */
    fun learnHowto(task: String, calls: List<Call>, finalText: String, now: Long, id: String): Entry? {
        if (calls.size < 3 || !selfCheck(calls, finalText)) return null
        val pat = patternOf(task); if (pat.length < 4) return null
        val steps = calls.filter { !it.failed }.map { "${it.tool}: ${it.brief}".take(90) }.distinct().take(8)
        if (steps.isEmpty()) return null
        val pit = calls.filter { it.failed && it.error.isNotBlank() }.map { "${it.tool}: ${it.error.take(70)}" }.distinct().take(3)
        return Entry(id, Kind.HOWTO, pat, steps.joinToString("\n"), calls.map { it.tool }.distinct().take(6), pit.joinToString("\n"),
            createdAt = now, updatedAt = now, keywords = tokens(task).distinct().take(12))
    }

    /** A 教训 after the loop guard stopped a run: what failed, why, what to do instead. */
    fun learnLesson(task: String, tool: String, signature: String, instead: String, now: Long, id: String): Entry =
        Entry(id, Kind.LESSON, patternOf(task).ifBlank { tool }, instead.take(200), listOf(tool),
            "反复失败：$tool → ${signature.take(90)}", createdAt = now, updatedAt = now, keywords = (tokens(task) + tokens(signature)).distinct().take(12))

    /**
     * Merge [n] into the library: same kind + same pattern (normalised) → the old entry is replaced by a new version
     * (version + 1, uses kept); else added. Returns the new list (newest first, ≤ [cap]).
     */
    fun upsert(list: List<Entry>, n: Entry, cap: Int = 300): List<Entry> {
        fun norm(s: String) = s.lowercase().replace(Regex("[\\s，,。.!！?？]"), "")
        val old = list.firstOrNull { it.kind == n.kind && norm(it.pattern) == norm(n.pattern) }
        val merged = if (old == null) n else n.copy(id = old.id, version = old.version + 1, uses = old.uses, createdAt = old.createdAt,
            source = if (old.source == "手动") "手动" else n.source,
            // a user-written entry keeps the user's steps; the run only adds its pitfalls
            steps = if (old.source == "手动") old.steps else n.steps,
            pitfalls = (old.pitfalls.lines() + n.pitfalls.lines()).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4).joinToString("\n"))
        return (listOf(merged) + list.filterNot { it.id == merged.id }).take(cap)
    }

    // ---------------- loop guard: consult at the 2nd repeat ----------------

    /**
     * Note appended to the tool result when the same failure is seen the 2nd time: do not retry it a 3rd time; here is
     * what the library knows; change approach or ask ONE short question. Asking is only required before spending money,
     * sending messages for the user, or deleting data.
     */
    fun consultNote(tool: String, signature: String, hits: List<Entry>): String = buildString {
        append("[Mines] 同样的失败已经出现 2 次（").append(tool).append("：").append(signature.take(80)).append("）。不要用同样的方法试第 3 次。")
        if (hits.isNotEmpty()) {
            append("\n经验库里相关的：\n"); append(promptBlock(hits, 600))
        }
        append("\n换一种做法继续；只有确实缺信息时，才问用户一个很短的问题。花钱、替用户发消息、删除数据之前仍要先问。")
    }
}
