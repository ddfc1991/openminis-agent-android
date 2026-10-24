package com.openminis.app.plus

/**
 * [Mines-38.9.12 b] pure helpers for the 消息 验证码 tab / mailbox 摘要 (design ws2 ①) and the 注册台账 (ws2 ③).
 * No Android, unit-tested (Inbox3912CoreTest).
 */
object Inbox3912Core {
    const val CODE_WINDOW_MS = 30L * 60_000L     // codes older than 30 min are shown as 已过期
    const val SHOW_MS = 24L * 3600_000L          // the tab lists the last 24 h

    data class MailLite(val id: String, val from: String, val subject: String, val text: String, val at: Long, val box: String)
    data class Code(val mailId: String, val code: String, val site: String, val box: String, val at: Long, val fresh: Boolean)

    private val SITE_WORDS = Regex("(?i)\\b(GitHub|Google|Cloudflare|Railway|ModelScope|OpenAI|Vercel|Hugging ?Face|Microsoft|Apple|Oracle|Aliyun|阿里云|OKX|Binance|Discord|X|Twitter|Telegram|Notion|Supabase|Netlify|Render|Fly\\.io|Docker)\\b")

    /** who sent the code: a known brand in subject/from, else the sender name, else the sender domain */
    fun siteOf(from: String, subject: String): String {
        SITE_WORDS.find(subject)?.let { return it.value }
        SITE_WORDS.find(from)?.let { return it.value }
        val name = from.substringBefore('<').trim().trim('"')
        if (name.isNotBlank() && !name.contains('@')) return name.take(24)
        val dom = from.substringAfter('@', "").substringBefore('>').trim()
        return dom.split('.').let { p -> if (p.size >= 2) p[p.size - 2] else dom }.replaceFirstChar { it.uppercase() }.ifBlank { "未知来源" }
    }

    /** codes found in the last 24 h of mail, newest first, one per mail */
    fun codes(mails: List<MailLite>, now: Long, extract: (String) -> String?): List<Code> =
        mails.asSequence().filter { now - it.at in 0..SHOW_MS }.sortedByDescending { it.at }
            .mapNotNull { m -> extract(m.subject + "\n" + m.text)?.let { c -> Code(m.id, c, siteOf(m.from, m.subject), m.box, m.at, now - m.at <= CODE_WINDOW_MS) } }
            .distinctBy { it.mailId }.toList()

    /** 482917 → 482 917 for reading; letters kept as they are */
    fun spaced(code: String): String = if (code.length == 6 && code.all { it.isDigit() }) code.substring(0, 3) + " " + code.substring(3) else code

    private val ACT = Regex("(?i)(到期|过期|续费|安全|异常|风险|轮换|泄露|失败|欠费|suspend|expir|security|alert|breach|rotate|failed|overdue|action required)")

    data class Summary(val today: Int, val unread: Int, val codes: Int, val needAct: List<String>, val line: String)

    /** a short local summary of today's mail: how many, which need action (security / expiry / failures), the rest */
    fun summary(mails: List<MailLite>, unread: Int, codes: Int, startOfDay: Long): Summary {
        val today = mails.filter { it.at >= startOfDay }
        val act = today.filter { ACT.containsMatchIn(it.subject) }.map { it.subject.trim().take(28) }.distinct()
        val line = when {
            today.isEmpty() -> "今天还没有新邮件。"
            act.isEmpty() -> "今天 ${today.size} 封，没有需要处理的事项" + (if (codes > 0) "；$codes 个验证码在「验证码」里。" else "。")
            else -> "需要处理：" + act.take(2).joinToString("；") + (if (act.size > 2) " 等 ${act.size} 项" else "") +
                "。其余 ${today.size - act.size} 封为通知或订阅" + (if (codes > 0) "，$codes 个验证码。" else "。")
        }
        return Summary(today.size, unread, codes, act, line)
    }

    // ── 注册台账 ──
    enum class RegSt(val zh: String) { DONE("已验证"), PENDING("未完成"), ROTATE("待轮换"), SAVED("已保存") }

    data class RegRow(val host: String, val account: String, val note: String, val st: RegSt)

    /** status from the 台账 备注 written by register_pick / register_done (RegisterCore.note) */
    fun regStatus(note: String): RegSt = when {
        note.startsWith("注册于") -> RegSt.DONE
        note.startsWith("注册中") -> RegSt.PENDING
        note.contains("轮换") -> RegSt.ROTATE
        else -> RegSt.SAVED
    }

    /** group 台账 rows by the mailbox they were registered with (accounts that are e-mails); the rest under 其他 */
    fun byMailbox(rows: List<RegRow>): List<Pair<String, List<RegRow>>> =
        rows.groupBy { if (it.account.contains('@')) it.account.lowercase() else "其他账号" }
            .entries.sortedWith(compareBy<Map.Entry<String, List<RegRow>>> { it.key == "其他账号" }.thenByDescending { it.value.size })
            .map { it.key to it.value.sortedBy { r -> r.host } }

    /** k7q2abc@mail.tm → k7q2••@mail.tm */
    fun maskMail(a: String): String {
        val at = a.indexOf('@'); if (at <= 0) return a
        val u = a.substring(0, at); return u.take(minOf(4, (u.length + 1) / 2)) + "••" + a.substring(at)
    }

    /** the one-click registration task handed to a new Mines conversation (agent uses accounts register_pick / register_done) */
    fun registerTask(platform: String, url: String, tempBox: String?, emailOnly: Boolean = false): String = buildString {
        append("帮我注册 $platform（$url）。\n")
        append("1. 用 accounts(action=register_pick, site=$url, n=1) 从台账取一个还没在该平台注册过的邮箱，并生成强密码（自动存进台账，备注 注册中）")
        if (!tempBox.isNullOrBlank()) append("；没有可用邮箱时用临时邮箱 $tempBox")
        append("。\n2. 打开注册页填写，验证码从邮箱自动读取。\n3. 完成后调用 accounts(action=register_done, ok=true)，失败则 ok=false。\n")
        append("遵守平台规则：一人一号、不批量注册；遇到人机验证、手机号验证或付款时停下来叫我，不要绕过。")
        if (emailOnly) append(EMAIL_ONLY_LINE)
    }

    /** [Mines-38.9.12.1] register with a fresh address on the user's own domain (codes are read from that inbox) */
    fun registerTaskSelf(platform: String, url: String, address: String, emailOnly: Boolean = false): String = buildString {
        append("帮我注册 $platform（$url）。\n")
        append("1. 用这个新邮箱注册：$address（我自己的域名，收件已接通）。用 accounts(action=register_pick, site=$url, n=1) 生成强密码并存进台账（备注 注册中），账号填这个邮箱。\n")
        append("2. 打开注册页填写，验证码会从这个邮箱自动读取。\n3. 完成后调用 accounts(action=register_done, ok=true)，失败则 ok=false。\n")
        append("遵守平台规则：一人一号、不批量注册；遇到人机验证、手机号验证或付款时停下来叫我，不要绕过。")
        if (emailOnly) append(EMAIL_ONLY_LINE)
    }

    /** [Mines-38.9.13 handoff_v2.oauth] Google / Apple refuse the in-app browser; that session would stay in Chrome */
    const val EMAIL_ONLY_LINE = "\n用邮箱注册，不要点 Google / Apple / 微软 等第三方登录按钮；页面只提供第三方登录时停下来告诉我。"

    val PLATFORMS = listOf(
        "Cloudflare" to "https://dash.cloudflare.com/sign-up", "GitHub" to "https://github.com/signup",
        "Railway" to "https://railway.com/login", "Vercel" to "https://vercel.com/signup",
        "Hugging Face" to "https://huggingface.co/join", "ModelScope" to "https://www.modelscope.cn/register",
        "Supabase" to "https://supabase.com/dashboard/sign-up", "Netlify" to "https://app.netlify.com/signup",
    )
}
