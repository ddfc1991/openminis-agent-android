package com.openminis.app.plus

/**
 * [Mines-38.9.5 i] Copyable values in a chat message (user 2026-10-17 22:23): URLs, API base URLs,
 * emails, account names, phone numbers, IP[:port], file paths, verification codes, order numbers.
 * Each becomes a small chip under the message: tap copies exactly [ValueChip.value], long-press
 * opens / shares it. Secrets (API keys, passwords, tokens, JWTs) are detected FIRST so no other
 * chip can expose them; they come back masked with copyable = false.
 *
 * Pure Kotlin (no Android) so it is unit-tested in ValueChipsTest.
 */
object ValueChips {
    enum class Kind(val label: String, val mono: Boolean) {
        SECRET("密钥", true), URL("链接", false), API("接口", true), EMAIL("邮箱", false),
        IP("IP", true), CODE("验证码", true), ORDER("单号", true), PHONE("电话", true),
        PATH("路径", true), ACCOUNT("账号", false),
    }

    data class ValueChip(
        val kind: Kind,
        /** What a tap copies. For SECRET this is "" — never copyable. */
        val value: String,
        /** What the chip shows (masked for SECRET, middle-ellipsized when long). */
        val display: String,
        val copyable: Boolean,
        val start: Int,
        val end: Int,
    )

    const val MAX_CHIPS = 12
    private const val MAX_DISPLAY = 28

    private data class Cand(val kind: Kind, val s: Int, val e: Int, val value: String, val prio: Int)

    // ── patterns ─────────────────────────────────────────────────────────────
    private val secretToken = Regex(
        "(?<![A-Za-z0-9_-])(" +
            "sk-(?:ws-|proj-|ant-|or-v1-)?[A-Za-z0-9_-]{16,}" +
            "|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}" +
            "|xox[abposr]-[A-Za-z0-9-]{10,}" +
            "|AKIA[0-9A-Z]{16}" +
            "|AIza[0-9A-Za-z_-]{30,}" +
            "|nm_[A-Za-z0-9]{16,}" +
            "|pat_[A-Za-z0-9]{20,}" +
            "|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{6,}" +
            ")",
    )
    private val secretLabelled = Regex(
        "(?i)(密码|口令|授权码|密钥|令牌|password|passwd|pwd|token|secret|api[ _-]?key|access[ _-]?key|apikey)" +
            "\\s*(?:是|为)?\\s*[:：=]\\s*[`\"'“]?([^\\s`\"'”，,。；;）)]{4,})",
    )
    private val url = Regex("(?i)\\bhttps?://[^\\s<>\"'`，。；、）)\\]】」』]+")
    private val email = Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(?![A-Za-z0-9-])")
    private val ip = Regex("(?<![0-9.])((?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})(?::(\\d{1,5}))?(?![0-9.]*\\d)")
    private val code = Regex("(?i)(验证码|校验码|动态码|确认码|code|otp)\\s*(?:是|为)?\\s*[:：]?\\s*([A-Za-z0-9]{4,8})(?![A-Za-z0-9])")
    private val order = Regex("(?i)(订单号|订单编号|单号|运单号|交易号|流水号|工单号|order\\s*(?:no\\.?|number|id|#))\\s*(?:是|为)?\\s*[:：#]?\\s*([A-Za-z0-9][A-Za-z0-9-]{5,31})(?![A-Za-z0-9-])")
    private val phone = Regex("(?<![0-9+])(\\+?86[ -]?)?(1[3-9]\\d[ -]?\\d{4}[ -]?\\d{4})(?!\\d)|(?<![0-9+A-Za-z])(\\+\\d{1,3}[ -]?\\d{2,4}[ -]?\\d{3,4}(?:[ -]?\\d{3,4})?)(?!\\d)")
    private val path = Regex("(?<![A-Za-z0-9_/:.~-])(~?/(?:[A-Za-z0-9_.@+-]+/)+[A-Za-z0-9_.@+-]*|[A-Za-z]:\\\\(?:[^\\\\\\s:*?\"<>|]+\\\\)*[^\\\\\\s:*?\"<>|]*)")
    private val account = Regex("(账号|帐号|用户名|用户 ID|用户ID|登录名|username|user|login)\\s*(?:是|为)?\\s*[:：]\\s*([A-Za-z0-9_.@+-]{3,64})", RegexOption.IGNORE_CASE)

    private val trailing = ".,;:!?。，；：！？)]}>'\"`*_"

    private fun trimUrl(s: String): String {
        var t = s
        while (t.isNotEmpty() && t.last() in trailing) {
            // keep a closing paren that has its opener inside the URL (wikipedia-style)
            if (t.last() == ')' && t.count { it == '(' } >= t.count { it == ')' }) break
            t = t.dropLast(1)
        }
        return t
    }

    /** True for API base URLs (OpenAI-compatible /v1, /compatible-mode/…, /api, api.* hosts). */
    fun isApiBase(u: String): Boolean {
        val noQ = u.substringBefore('?').substringBefore('#').trimEnd('/')
        val afterScheme = noQ.substringAfter("://")
        val host = afterScheme.substringBefore('/').lowercase()
        val p = afterScheme.substringAfter('/', "").lowercase()
        if (Regex("(^|/)v\\d+(beta\\d*)?$").containsMatchIn(p)) return true
        if (p.contains("compatible-mode") || p.endsWith("/openai") || p == "api" || p.endsWith("/api")) return true
        return host.startsWith("api.") && p.isEmpty()
    }

    /** Masked view of a secret: a short recognisable head + dots. Never the tail, never the middle. */
    fun mask(secret: String): String {
        val head = when {
            secret.startsWith("sk-ws-") -> "sk-ws-"
            secret.startsWith("sk-") -> "sk-"
            secret.startsWith("github_pat_") -> "github_pat_"
            Regex("^gh[pousr]_").containsMatchIn(secret) -> secret.take(4)
            secret.startsWith("eyJ") -> "JWT "
            secret.startsWith("AKIA") -> "AKIA"
            secret.startsWith("AIza") -> "AIza"
            secret.startsWith("nm_") -> "nm_"
            secret.startsWith("pat_") -> "pat_"
            secret.startsWith("xox") -> secret.take(5)
            else -> ""
        }
        return head + "••••••"
    }

    fun ellipsize(s: String, max: Int = MAX_DISPLAY): String =
        if (s.length <= max) s else s.take(max * 2 / 3 - 1) + "…" + s.takeLast(max / 3)

    fun detect(text: String): List<ValueChip> {
        if (text.isBlank()) return emptyList()
        val c = ArrayList<Cand>()
        secretToken.findAll(text).forEach { m -> c += Cand(Kind.SECRET, m.range.first, m.range.last + 1, m.value, 0) }
        secretLabelled.findAll(text).forEach { m ->
            val g = m.groups[2]!!
            c += Cand(Kind.SECRET, g.range.first, g.range.last + 1, g.value, 0)
        }
        url.findAll(text).forEach { m ->
            val v = trimUrl(m.value)
            if (v.length > 10) c += Cand(if (isApiBase(v)) Kind.API else Kind.URL, m.range.first, m.range.first + v.length, v, 1)
        }
        email.findAll(text).forEach { m -> c += Cand(Kind.EMAIL, m.range.first, m.range.last + 1, m.value, 2) }
        ip.findAll(text).forEach { m ->
            val port = m.groups[2]?.value?.toIntOrNull()
            if (port != null && port !in 1..65535) return@forEach
            val v = m.value
            // version numbers like 1.2.3.4 without a port are only taken when they look routable
            if (port == null && v.startsWith("0.")) return@forEach
            c += Cand(Kind.IP, m.range.first, m.range.last + 1, v, 3)
        }
        code.findAll(text).forEach { m ->
            val g = m.groups[2]!!
            if (g.value.any { it.isDigit() }) c += Cand(Kind.CODE, g.range.first, g.range.last + 1, g.value, 4)
        }
        order.findAll(text).forEach { m ->
            val g = m.groups[2]!!
            if (g.value.any { it.isDigit() }) c += Cand(Kind.ORDER, g.range.first, g.range.last + 1, g.value, 4)
        }
        phone.findAll(text).forEach { m ->
            val v = m.value.trim()
            val digits = v.filter { it.isDigit() }
            if (digits.length in 8..15) c += Cand(Kind.PHONE, m.range.first, m.range.last + 1, v.replace(" ", "").replace("-", ""), 5)
        }
        path.findAll(text).forEach { m ->
            val v = m.value.trimEnd('.', ',', '。', '，', ')', '）')
            val segs = v.split('/', '\\').count { it.isNotEmpty() }
            if (segs >= 2) c += Cand(Kind.PATH, m.range.first, m.range.first + v.length, v, 6)
        }
        account.findAll(text).forEach { m ->
            val g = m.groups[2]!!
            c += Cand(Kind.ACCOUNT, g.range.first, g.range.last + 1, g.value, 7)
        }

        // Accept by priority (secrets first), then earliest / longest; never overlap an accepted span.
        val taken = ArrayList<Cand>()
        for (cand in c.sortedWith(compareBy<Cand>({ it.prio }, { it.s }, { -(it.e - it.s) }))) {
            if (taken.any { cand.s < it.e && it.s < cand.e }) continue
            taken += cand
        }
        val out = ArrayList<ValueChip>()
        val seen = HashSet<String>()
        for (t in taken.sortedBy { it.s }) {
            if (t.kind == Kind.SECRET) {
                out += ValueChip(Kind.SECRET, "", mask(t.value), false, t.s, t.e)
                continue
            }
            val key = t.kind.name + "|" + t.value
            if (!seen.add(key)) continue
            out += ValueChip(t.kind, t.value, ellipsize(t.value), true, t.s, t.e)
        }
        // Secrets stay (so the user sees one was there, masked) but count towards the cap last.
        return out.take(MAX_CHIPS)
    }
}

/**
 * [Mines-38.9.5 i] 「引用回复」: the quoted text sits as a small collapsible block above the input
 * and goes out as a Markdown quote ahead of the next message.
 */
object QuoteReply {
    const val MAX_QUOTE = 2000

    /** [38.9.15.1] markdown marks out of a one-line preview / title (「**阿里云 QwenPaw**」 → 「阿里云 QwenPaw」) */
    fun plain(q: String): String = q.replace(Regex("\\*\\*|__|`+"), "").replace(Regex("(?m)^\\s*(#{1,6}|>)\\s*"), "")

    /** One-line preview for the collapsed block. */
    fun preview(q: String, max: Int = 60): String {
        val one = plain(q).trim().replace(Regex("\\s+"), " ")
        return if (one.length <= max) one else one.take(max - 1) + "…"
    }

    /** Markdown sent to the model: every quoted line prefixed with "> ", then a blank line, then the message. */
    fun compose(quote: String?, message: String): String {
        val q = quote?.trim().orEmpty()
        if (q.isEmpty()) return message
        val capped = if (q.length > MAX_QUOTE) q.take(MAX_QUOTE - 1) + "…" else q
        val quoted = capped.lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" }
        return if (message.isBlank()) quoted else "$quoted\n\n$message"
    }

    /** [Mines-38.9.14.1] 「回复」: a short reference to the replied message (not the full quote), then the message. */
    fun composeReply(who: String, target: String?, message: String): String {
        val t = target?.trim().orEmpty()
        if (t.isEmpty()) return message
        val head = "（回复 ${who.ifBlank { "这条消息" }}：「${preview(t, 80)}」）"
        return if (message.isBlank()) head else "$head\n$message"
    }
}
