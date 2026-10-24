package com.openminis.app.plus

/**
 * [Mines-38.9.13 handoff_v2] Pure rules for handing a browser step to the user and back (HandoffCoreTest).
 *
 * User 2026-10-24: 「过了弹窗不会立马接管…有时候会停在那里…我已经帮他登录，但它并没有去到登录的页面」. Root causes
 * (specs/autoreg-handoff-fix.md R1–R8): the wait for the user sat inside one 75 s browser action (the card vanished and the
 * healthy tab was rebuilt), resume was polling + the model remembering to retry, 「去验证」 could open a new tab, state was
 * one global slot, and popups did not say which task / site. Now each wait is a [Handoff] record per conversation + tab,
 * finished by events, and the agent is told (or woken) as soon as the user is done. Android side: [HandoffBroker].
 */
object HandoffCore {
    enum class Kind { CAPTCHA, LOGIN, OTP, CONFIRM, OAUTH_EXTERNAL }
    enum class State { WAITING, USER_ACTIVE, DONE, CANCELLED, EXPIRED }

    data class Handoff(
        val id: String,
        val sessionId: String,
        val tabId: Int,
        val url: String,
        val host: String,
        val kind: Kind,
        /** e.g. 「Cloudflare」「阿里滑块验证」 for a CAPTCHA */
        val detail: String = "",
        val taskLabel: String = "",
        val createdAt: Long,
        val state: State = State.WAITING,
        /** last time the user / page moved (watchdog clock) */
        val progressAt: Long = createdAt,
        /** watchdog stages already fired: 1 = reminded, 2 = chat card, 3 = paused */
        val stage: Int = 0,
        /** tabs with a larger id were opened during the handoff (user tabs / window.open) */
        val maxTabIdAtStart: Int = tabId,
        /** start of the handoff: the page had a password field / an auth-looking URL / these cookie names */
        val startPw: Boolean = false,
        val startAuthUrl: Boolean = false,
        val startCookies: Set<String> = emptySet(),
        /** one line under the popup, e.g. 「好像还没登录成功，请再看一下」 */
        val hint: String = "",
        /** bumped whenever the popup should show again (created / reminded / hint) */
        val showSeq: Int = 1,
        /** consecutive wait_for_handoff calls that ended with nothing new */
        val idleWaits: Int = 0,
    ) {
        val open: Boolean get() = state == State.WAITING || state == State.USER_ACTIVE
    }

    fun canMove(from: State, to: State): Boolean = when (from) {
        State.WAITING -> to != State.WAITING
        State.USER_ACTIVE -> to != State.USER_ACTIVE
        // paused after 5 min: 「继续接管」 still resumes, 「放弃这一步」 still cancels
        State.EXPIRED -> to == State.DONE || to == State.CANCELLED
        State.DONE, State.CANCELLED -> false
    }

    fun move(h: Handoff, to: State, now: Long): Handoff? = if (canMove(h.state, to)) h.copy(state = to, progressAt = now) else null

    fun forSession(all: List<Handoff>, sessionId: String?): List<Handoff> = all.filter { it.sessionId == sessionId }

    /** the handoff a wait_for_handoff without an id means: the newest open one of the conversation, else its newest */
    fun pick(all: List<Handoff>, sessionId: String?, id: String?): Handoff? {
        val mine = forSession(all, sessionId)
        if (!id.isNullOrBlank()) return mine.firstOrNull { it.id == id }
        return mine.filter { it.open }.maxByOrNull { it.createdAt } ?: mine.maxByOrNull { it.createdAt }
    }

    // ── watchdog (F5) ─────────────────────────────────────────────────
    const val REMIND_MS = 30_000L
    const val CARD_MS = 120_000L
    const val EXPIRE_MS = 300_000L
    const val MAX_IDLE_WAITS = 3
    /** one slice of wait_for_handoff, under the 75 s action guard */
    const val WAIT_SLICE_MS = 55_000L
    /** fallback poll while WAITING (was 1.5 s) */
    const val POLL_MS = 500L

    enum class Watch { NONE, REMIND, CARD, EXPIRE }

    fun watchdog(h: Handoff, now: Long): Watch {
        if (!h.open) return Watch.NONE
        val idle = now - h.progressAt
        return when {
            idle >= EXPIRE_MS && h.stage < 3 -> Watch.EXPIRE
            idle >= CARD_MS && h.stage < 2 -> Watch.CARD
            idle >= REMIND_MS && h.stage < 1 -> Watch.REMIND
            else -> Watch.NONE
        }
    }

    fun stageOf(w: Watch): Int = when (w) { Watch.REMIND -> 1; Watch.CARD -> 2; Watch.EXPIRE -> 3; Watch.NONE -> 0 }

    // ── Google / Apple: ask once per site per 10 min (F8) ────────────
    const val OAUTH_ASK_GAP_MS = 10 * 60_000L
    fun shouldAskOauth(lastAskedAt: Long?, now: Long): Boolean = lastAskedAt == null || now - lastAskedAt >= OAUTH_ASK_GAP_MS

    // ── is the user really signed in now? (F4) ────────────────────────
    val AUTH_URL = Regex("(?i)(log-?in|sign-?in|sign_in|sign-?up|sign_up|register|registration|join|passport|auth|account/new|注册|登录|登陆)")
    val SIGNED_IN_WORDS = Regex("(?i)(退出登录|退出|注销|登出|sign ?out|log ?out|my account|我的账户|我的账号|个人中心|控制台|dashboard)")
    private val SESSION_COOKIE = Regex("(?i)(sess|sid|token|auth|logged|login|user|remember|jwt|account)")

    data class Signals(
        /** the page had a password field when the handoff began and has none now */
        val passwordGone: Boolean,
        /** the handoff began on an auth-looking URL and the URL left it */
        val leftAuthUrl: Boolean,
        /** a session-looking cookie appeared that was not there at the start */
        val newSessionCookie: Boolean,
        /** 退出 / Sign out / 控制台 / avatar on the page */
        val signedInWords: Boolean,
    )

    fun signals(startPw: Boolean, startAuthUrl: Boolean, startCookies: Set<String>, pwNow: Boolean, urlNow: String, cookiesNow: Set<String>, wordsNow: Boolean) =
        Signals(startPw && !pwNow, startAuthUrl && !isAuthUrl(urlNow), newSessionCookie(startCookies, cookiesNow), wordsNow)

    /** two independent signals are needed (a welcome text alone, or a URL change alone, is not a sign-in) */
    fun loggedIn(s: Signals): Boolean = listOf(s.passwordGone, s.leftAuthUrl, s.newSessionCookie, s.signedInWords).count { it } >= 2

    fun isAuthUrl(url: String): Boolean = AUTH_URL.containsMatchIn(url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', ""))

    fun cookieNames(line: String?): Set<String> = (line ?: "").split(';').map { it.trim().substringBefore('=').trim() }.filter { it.isNotEmpty() }.toSet()

    fun newSessionCookie(before: Set<String>, after: Set<String>): Boolean = (after - before).any { SESSION_COOKIE.containsMatchIn(it) }

    // ── captcha cleared (F2) ──────────────────────────────────────────
    fun sameUrl(a: String, b: String): Boolean = a.substringBefore('#') == b.substringBefore('#')

    fun captchaCleared(startUrl: String, urlNow: String, hadClearance: Boolean, hasClearance: Boolean, kindNow: String): Boolean =
        !sameUrl(startUrl, urlNow) || (!hadClearance && hasClearance) || kindNow.isBlank()

    /** an OTP handoff is over when the code field is gone or the page moved on */
    fun otpCleared(startUrl: String, urlNow: String, otpFieldNow: Boolean): Boolean = !sameUrl(startUrl, urlNow) || !otpFieldNow

    // ── which tab the agent continues on (F3) ─────────────────────────
    data class TabInfo(val id: Int, val url: String, val loggedIn: Boolean)

    /** registrable domain, same rule as Keychain.root */
    fun site(hostOrUrl: String): String {
        val h = hostOrUrl.substringAfter("://").substringBefore('/').substringBefore(':').substringBefore('?').lowercase().removePrefix("www.")
        val p = h.split('.').filter { it.isNotBlank() }
        return if (p.size >= 3 && p[p.size - 2] in setOf("com", "net", "org", "co", "gov", "edu")) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
    }

    /**
     * The tab the agent drives after the handoff: the user's selected tab, else the newest tab opened during the handoff,
     * when it is on the same site and signed in; otherwise the tab the handoff began on (if it still exists), else the
     * selected tab. Tabs that existed before the handoff and are neither the handoff tab nor selected are never picked.
     */
    fun pickResumeTab(origTab: Int, selected: Int?, maxTabIdAtStart: Int, site: String, tabs: List<TabInfo>): Int {
        val cands = tabs.filter { (it.id == origTab || it.id == selected || it.id > maxTabIdAtStart) && site(it.url) == site && it.loggedIn }
        cands.firstOrNull { it.id == selected }?.let { return it.id }
        cands.filter { it.id > maxTabIdAtStart }.maxByOrNull { it.id }?.let { return it.id }
        if (tabs.any { it.id == origTab }) return origTab
        if (selected != null && tabs.any { it.id == selected }) return selected
        return origTab
    }

    // ── what the agent is told ────────────────────────────────────────
    /** browser actions that change the page: refused on a tab the user is working on */
    val WRITE_ACTIONS = setOf("navigate", "click", "type", "scroll", "hover", "execute_js", "set_cookies", "set_user_agent")

    fun blocks(h: Handoff?, action: String): Boolean = h != null && h.open && action in WRITE_ACTIONS

    fun pendingText(h: Handoff, verb: String): String =
        "[交接中 handoff_id=${h.id}] 已请用户在 ${h.host.ifBlank { "这个页面" }}（tab ${h.tabId}）$verb。不要重试点击、不要自己处理；" +
            "调用 browser 的 wait_for_handoff（text 填 ${h.id}），用户一完成就会返回。"

    fun busyText(h: Handoff): String =
        "[交接中 handoff_id=${h.id}] 用户正在 tab ${h.tabId} 上操作，Mines 不会动这一页。调用 browser 的 wait_for_handoff（text 填 ${h.id}）等用户完成。"

    fun doneText(h: Handoff, tabId: Int, url: String, verified: Boolean?, manual: Boolean, extra: String = ""): String = buildString {
        append("[交接完成 handoff_id=${h.id}] ")
        append(if (manual) "用户点了「继续接管」（用户手动确认）。" else "用户已完成。")
        if (extra.isNotBlank()) append(extra).append(' ')
        when (verified) {
            true -> append("已校验登录 ✅。")
            false -> append("还没检测到登录成功的迹象，先截图确认。")
            null -> {}
        }
        append("当前页 $url。")
        if (tabId != h.tabId) append("用户在另一个标签页完成，from now on use tab_id $tabId。") else append("继续用 tab_id $tabId。")
        append("先截图看当前页面，再继续原任务。")
    }

    fun cancelledText(h: Handoff, why: String = ""): String =
        "[交接取消 handoff_id=${h.id}] ${why.ifBlank { "用户关掉了提示，先别继续 ${h.host.ifBlank { "这个站点" }} 这一步" }}。简短告诉用户卡在哪，不要自己重试。"

    fun expiredText(h: Handoff): String =
        "[交接暂停 handoff_id=${h.id}] 用户 5 分钟内没有完成，已暂停；任务状态=等待用户。结束本轮，简短告诉用户在 ${h.host} 要做什么；" +
            "用户完成或点「继续接管」后 Mines 会自动叫你继续，不要再调用 wait_for_handoff。"

    fun stillWaitingText(h: Handoff): String =
        "[交接中 handoff_id=${h.id}] 用户还在操作，再调用一次 wait_for_handoff（text 填 ${h.id}）。"

    fun stopText(h: Handoff): String =
        "[交接中 handoff_id=${h.id}] 已经等了 $MAX_IDLE_WAITS 次还没有变化。现在停下：结束本轮回复，简短告诉用户在 ${h.host} 要做什么；" +
            "用户完成后 Mines 会自动叫你继续，不要再调用 wait_for_handoff，也不要重试点击。"

    /** back from Chrome after a Google / Apple sign-in (F8: that session stays in Chrome) */
    const val OAUTH_BACK_NOTE = "用户已从 Chrome 回来（Google / Apple 登录在 Chrome 里完成），但那个登录只在 Chrome 里，Mines 浏览器里仍未登录。" +
        "可选：a) 在网站上用同一个邮箱改用邮箱验证码 / 登录链接登录（Mines 会从邮箱读码）；b) 用户打开 Mines 无障碍后由 Mines 在 Chrome 里继续；" +
        "c) 请用户在 Chrome 里自己完成剩下的步骤（如创建 API key），把结果复制回来。不要再点 Google / Apple 按钮。"

    /** the message that starts a new turn when the agent's turn had already ended */
    fun resumePrompt(doneText: String): String = "$doneText 继续原任务。"
}

/** [Mines-38.9.13 handoff_v2.label] what every handoff popup / notification says (F7). */
object HandoffLabel {
    fun action(kind: HandoffCore.Kind, detail: String = "", host: String = ""): String = when (kind) {
        HandoffCore.Kind.OTP -> "输验证码"
        HandoffCore.Kind.LOGIN -> "登录"
        HandoffCore.Kind.CAPTCHA -> "点一下人机验证" + (detail.takeIf { it.isNotBlank() && it != "人机验证" }?.let { "（$it）" } ?: "")
        HandoffCore.Kind.CONFIRM -> "点确认"
        HandoffCore.Kind.OAUTH_EXTERNAL -> if (host.contains("apple.com")) "在 Chrome 里用 Apple 登录" else "在 Chrome 里用 Google 登录"
    }

    private val GENERIC_TITLE = Regex("(?i)^(新对话|新的对话|新聊天|new chat|untitled|未命名)$")
    private val REGISTER_URL = Regex("(?i)(sign-?up|sign_up|register|registration|join|account/new|注册)")

    /** 「Railway」 from railway.com / app.railway.com */
    fun siteWord(hostOrUrl: String): String = HandoffCore.site(hostOrUrl).substringBefore('.').replaceFirstChar { it.uppercaseChar() }

    /**
     * Task name: the conversation's project name (+ 「 · 第N次」 from the task-naming rules) → the conversation title →
     * 「{站点} 注册/登录」.
     */
    fun taskName(project: String?, attempt: Int, title: String?, siteName: String, url: String): String {
        project?.trim()?.takeIf { it.isNotEmpty() }?.let { p -> return if (attempt >= 2 && !p.contains("第")) "$p · 第${attempt}次" else p }
        title?.trim()?.takeIf { it.isNotEmpty() && !GENERIC_TITLE.matches(it) }?.let { return it.take(24) }
        return siteName + if (REGISTER_URL.containsMatchIn(url)) " 注册" else " 登录"
    }

    fun title(task: String, action: String): String = "$task · 需要你$action"
    fun body(host: String): String = "${HandoffCore.site(host).ifBlank { host }} · Mines 在等你，完成后自动继续"
}
