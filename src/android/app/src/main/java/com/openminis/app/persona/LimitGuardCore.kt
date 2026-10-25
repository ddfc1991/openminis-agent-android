package com.openminis.app.persona

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.15 限制层] The user's own limits on the persona. User 2026-10-25: 「限制层听我号令」 — only the user can
 * change what is in here, and only through the 伪人核心 UI with an explicit tap (plus the device lock where the phone
 * has one). The agent has NO tool that writes these settings; every write goes through [Persona.userSet*], which
 * refuses unless the caller passes [USER_TAP] — a token the tool dispatcher never holds.
 *
 * This file is the pure decision layer: given the limits and what the agent is about to do, may it? Unit-tested in
 * LimitGuardCoreTest (including "an agent call can never loosen a limit").
 */
object LimitGuardCore {

    /** what the agent is about to do, as the choke point classifies it */
    enum class Act(val id: String, val zh: String) {
        READ("read", "看"),
        DRAFT("draft", "写草稿"),
        /** a tap / typing on one of the user's own account pages: counts against the cap, no confirm */
        TOUCH("touch", "操作"),
        SEND("send", "发出去"),
        POST("post", "发布"),
        DELETE("delete", "删除"),
        PAY("pay", "付款");

        /** the acts the user's confirm switch covers (user 2026-10-25: 发帖/发消息/删除/付款 先问我) */
        val needsConfirmByDefault: Boolean get() = this in setOf(SEND, POST, DELETE, PAY)
    }

    /** why an action was refused — each becomes a 等你处理 task state with this sentence */
    enum class Why(val zh: String) {
        OK(""),
        KILLED("总开关已关：你把伪人核心停了，先在 应用 → 伪人核心 打开再说"),
        FLAG_OFF("伪人核心没打开"),
        NOT_ALLOWED("这个账号你还没允许我用，到 应用 → 伪人核心 → 账号 里打开才行"),
        NEEDS_CONFIRM("这一步要你点头：发出去/发布/删除/付款 默认都等你确认"),
        CAP("今天这个账号的次数用完了"),
        QUIET_HOURS("现在不在你设的活动时间里，等到时间我再做"),
    }

    data class Limits(
        /** one tap kills everything the persona does (also from the notification) */
        val killed: Boolean = false,
        /** acts outside the user's active hours are held (reads are always allowed) */
        val quietHours: Boolean = true,
    )

    data class Decision(val allowed: Boolean, val why: Why, val account: String = "") {
        val zh: String get() = why.zh
    }

    /** today's count per account, kept by [Persona] */
    fun capKey(accountId: String, yyyymmdd: String) = "$accountId@$yyyymmdd"

    /**
     * The one rule.
     *  - On one of the user's accounts ([account] non-null): the kill switch stops everything; reading and drafting
     *    pass; anything else needs the account allow-listed (nothing starts allow-listed), inside active hours, under
     *    the daily cap, and — for send / post / delete / pay — the user's 同意 unless they switched confirm off.
     *  - Off the user's accounts (a registration page, a search): the persona's limits do not apply, except that the
     *    kill switch and [requireAccount] (a mail / post tool that always speaks as the user) still refuse, and paying
     *    is never automatic.
     */
    fun check(
        flagOn: Boolean,
        limits: Limits,
        account: PersonaCore.Account?,
        act: Act,
        usedToday: Int,
        withinHours: Boolean,
        /** the user tapped 同意 for this account + act in the 伪人核心 page (one-shot, short-lived) */
        approved: Boolean = false,
        /** the call speaks as the user by definition (send_email): no account = not allowed */
        requireAccount: Boolean = false,
    ): Decision {
        val id = account?.id.orEmpty()
        if (account == null) {
            if (requireAccount) return Decision(false, if (limits.killed) Why.KILLED else if (!flagOn) Why.FLAG_OFF else Why.NOT_ALLOWED, id)
            return when {
                limits.killed && act != Act.READ -> Decision(false, Why.KILLED, id)
                act == Act.PAY -> Decision(false, Why.NEEDS_CONFIRM, id)
                else -> Decision(true, Why.OK, id)
            }
        }
        if (limits.killed) return Decision(false, Why.KILLED, id)
        if (act == Act.READ || act == Act.DRAFT) return Decision(true, Why.OK, id)
        if (!flagOn) return Decision(false, Why.FLAG_OFF, id)
        if (!account.allowed) return Decision(false, Why.NOT_ALLOWED, id)
        if (limits.quietHours && !withinHours) return Decision(false, Why.QUIET_HOURS, id)
        if (usedToday >= account.dailyCap.coerceAtLeast(0)) return Decision(false, Why.CAP, id)
        if (account.confirmSend && act.needsConfirmByDefault && !approved) return Decision(false, Why.NEEDS_CONFIRM, id)
        return Decision(true, Why.OK, id)
    }

    /** the act a browser call or a mail/post tool amounts to; unknown verbs are treated as the strictest match */
    fun actOf(toolName: String, action: String?, text: String?): Act {
        val a = (action ?: "").trim().lowercase()
        val t = (text ?: "").trim().lowercase()
        if (toolName == "send_email" || a == "send_email") return Act.SEND
        if (a in setOf("delete", "delete_forever", "empty_trash")) return Act.DELETE
        if (a in setOf("pay", "checkout", "purchase")) return Act.PAY
        if (a in setOf("post", "publish", "tweet", "comment", "share")) return Act.POST
        if (a in setOf("send", "message", "dm", "reply")) return Act.SEND
        // a click / type whose target reads like a send or post button
        val code = CODE_WORDS.any { t.contains(it) }   // 「发送验证码」 is a sign-in step, not a message
        if (a == "click" && !code && SEND_WORDS.any { t.contains(it) }) return Act.SEND
        if (a == "click" && POST_WORDS.any { t.contains(it) }) return Act.POST
        if (a == "click" && DELETE_WORDS.any { t.contains(it) }) return Act.DELETE
        if (a == "click" || a == "type" || a == "fill") return Act.TOUCH
        return Act.READ
    }

    private val SEND_WORDS = setOf("send", "发送", "发出", "回复", "私信")
    private val POST_WORDS = setOf("post", "publish", "发布", "发表", "发帖", "评论", "转发")
    private val CODE_WORDS = setOf("验证码", "code", "otp")
    private val DELETE_WORDS = setOf("delete", "删除", "永久删除", "清空")

    /** the refusal the tool returns — the model sees it as a wall it cannot argue with */
    fun refusal(d: Decision): String =
        "【限制层】这一步被你的设置拦住了：${d.zh}。我不能自己改这个设置，也不要再试别的路子；" +
            "已经记成「等你处理」，等你在 应用 → 伪人核心 里松开或点同意。"

    // ── audit log ─────────────────────────────────────────────────────────────

    data class Entry(val at: Long, val account: String, val act: String, val allowed: Boolean, val why: String, val note: String)

    fun entryJson(e: Entry): JSONObject = JSONObject().put("at", e.at).put("acct", e.account).put("act", e.act)
        .put("ok", e.allowed).put("why", e.why).put("note", e.note.take(120))

    fun logToJson(l: List<Entry>, max: Int = 200): String =
        JSONArray().apply { l.takeLast(max).forEach { put(entryJson(it)) } }.toString()

    fun logFromJson(s: String?): List<Entry> = runCatching {
        if (s.isNullOrBlank()) return emptyList()
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { Entry(it.optLong("at"), it.optString("acct"), it.optString("act"), it.optBoolean("ok"), it.optString("why"), it.optString("note")) }
        }
    }.getOrDefault(emptyList())

    fun limitsToJson(l: Limits): String = JSONObject().put("killed", l.killed).put("quiet", l.quietHours).toString()

    fun limitsFromJson(s: String?): Limits = runCatching {
        if (s.isNullOrBlank()) return Limits()
        val o = JSONObject(s); Limits(o.optBoolean("killed", false), o.optBoolean("quiet", true))
    }.getOrDefault(Limits())

    /** [USER_TAP] is the proof that a change came from the user's own tap in the 伪人核心 UI. */
    const val USER_TAP = "user-tap"
}
