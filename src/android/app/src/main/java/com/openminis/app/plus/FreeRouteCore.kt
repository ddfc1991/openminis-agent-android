package com.openminis.app.plus

/**
 * [Mines-38.9.8] 「免登录 AI（Mines）」 in 模型组 (user 2026-10-19 「这些免登陆的，用不了啊」): the routes it lists (扣子 bots,
 * web-chat bridges 豆包 / 元宝 / Kimi / DeepSeek / 通义 / 智谱清言 / Gemini / Hark …) were listed as soon as a sign-in cookie
 * existed — never actually called. Now each route is called for real (one tiny chat, time-limited); a route that does not
 * answer is hidden from the gateway's model list until a later test passes (user rule: nothing that cannot run is shown),
 * with its reason kept for 自检 / the 免登录 AI card. Also: two routes with the same name (two bots both called
 * 「Mines-Test」) get the end of their id appended so they can be told apart. Pure, unit-tested in FreeRouteCoreTest.
 */
object FreeRouteCore {
    const val TEST_PROMPT = "只回复两个字母：OK"
    const val TEST_TIMEOUT_MS = 45_000L
    const val CHAT_TIMEOUT_MS = 90_000L
    const val RETEST_MS = 12 * 3_600_000L

    data class Live(val id: String, val ok: Boolean, val reason: String, val at: Long, val ms: Long = 0)

    /** A reply counts when it has any visible text (the routes are chat pages; they may not obey 「OK」 exactly). */
    fun answered(reply: String?): Boolean = !reply.isNullOrBlank() && reply.trim().length in 1..4000 && !Regex("^(error|错误|出错)", RegexOption.IGNORE_CASE).containsMatchIn(reply.trim())

    /** Shown in the gateway's /v1/models: never tested yet, or the last test passed. */
    fun visible(id: String, live: Map<String, Live>): Boolean = live[id]?.ok ?: true

    fun needsTest(id: String, live: Map<String, Live>, now: Long): Boolean = live[id]?.let { now - it.at > RETEST_MS } ?: true

    fun timeoutReason(ms: Long) = "${ms / 1000} 秒没有回复，已停止（网页没打开或要重新登录）"

    /** One 自检 line: 「能用 5 个：豆包、Kimi…；已隐藏 2 个：元宝（45 秒没有回复…）…」. */
    fun summary(names: Map<String, String>, live: Map<String, Live>): Pair<Boolean, String> {
        val tested = names.keys.filter { live.containsKey(it) }
        val ok = tested.filter { live[it]!!.ok }
        val dead = tested.filter { !live[it]!!.ok }
        val untested = names.keys.filter { !live.containsKey(it) }
        if (names.isEmpty()) return true to "没有免登录路线（没登录任何网页版 / 扣子）"
        val sb = StringBuilder()
        if (ok.isNotEmpty()) sb.append("能用 ${ok.size} 个：").append(ok.joinToString("、") { names[it] ?: it })
        if (dead.isNotEmpty()) { if (sb.isNotEmpty()) sb.append("；"); sb.append("已隐藏 ${dead.size} 个：").append(dead.joinToString("、") { "${names[it] ?: it}（${live[it]!!.reason.take(40)}）" }) }
        if (untested.isNotEmpty()) { if (sb.isNotEmpty()) sb.append("；"); sb.append("没测 ${untested.size} 个") }
        return (dead.isEmpty()) to sb.toString()
    }

    /**
     * Display names that can be told apart: a name used by more than one id gets " · " + the last 4 characters of the
     * id's own part (coze:7696936574432591908 → 「Mines-Test · 1908」); unique names stay as they are.
     */
    fun distinctNames(items: List<Pair<String, String>>): Map<String, String> {
        val dup = items.groupBy { it.second.trim() }.filter { it.value.map { p -> p.first }.distinct().size > 1 }.keys
        val out = LinkedHashMap<String, String>()
        for ((id, name) in items) {
            if (name.trim() !in dup) { out[id] = name; continue }
            val tail = id.substringAfterLast(':').substringAfterLast('/').substringAfterLast('#').filter { it.isLetterOrDigit() }.takeLast(4)
            out[id] = if (tail.isBlank()) name else "$name · $tail"
        }
        // still equal (same tail) → use 6 characters
        val again = out.entries.groupBy { it.value }.filter { it.value.size > 1 }
        for ((_, es) in again) es.forEach { e -> out[e.key] = items.first { it.first == e.key }.second + " · " + e.key.filter { it.isLetterOrDigit() }.takeLast(6) }
        return out
    }
}
