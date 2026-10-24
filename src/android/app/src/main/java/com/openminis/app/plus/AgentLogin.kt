package com.openminis.app.plus

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.11.1 hotfix] user: when the AI's browser hits a sign-in page with no saved login (or the saved one is
 * refused), Mines just waited — no account / password box. Now, after each page-changing browser action:
 *  1. a password form with an empty password field → a saved login for that site ([SavedLogins]) is filled silently;
 *  2. none saved, or the page says the password was wrong → the secure [LoginCard] pops (site logo + name, 账号/手机号,
 *     masked 密码 with eye toggle, optional 验证码, 保存到密码库 on, 提交), with a notification when Mines is in the
 *     background; the values go straight into the page — the agent only gets a line saying it was filled.
 * At most one card per site every 2 minutes unless the password was refused.
 * [38.9.11.3] 台账 accounts are filled in any conversation (no card); the card only when the 台账 has nothing for the site or
 * the page refused the password.
 */
object AgentLogin {
    private val asked = ConcurrentHashMap<String, Long>()
    private val filled = ConcurrentHashMap<String, Long>()
    const val ASK_GAP_MS = 120_000L
    const val REFUSED_WINDOW_MS = 90_000L
    /** [38.9.11.3] host → (account, filled at): confirmed as a success when the password form goes away */
    private val pendingOk = ConcurrentHashMap<String, Pair<String, Long>>()
    const val OK_WINDOW_MS = 180_000L

    /** [eval] runs JS in the agent's page and returns its string result (null = failed). Returns a note for the agent, or null. */
    /**
     * [sessionId] = the conversation the page belongs to. [38.9.11.3] a 台账 account is filled in any conversation (the
     * 38.9.11.2 rule — only where the user had picked it — is gone) and then bound to it; the card's 提交 binds too.
     */
    suspend fun handle(ctx: Context, url: String, sessionId: String? = null, eval: suspend (String) -> String?): String? {
        if (LoginCard.pending.value != null) return null
        val host = Keychain.hostOf(url).ifBlank { return null }
        val fj = runCatching { JSONObject(eval(LoginAssist.FIELDS_JS) ?: return null) }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        // [38.9.11.3] a 台账 fill followed by a page without a password field = that account worked here (ranking: last success)
        if (!fj.optBoolean("pw")) {
            pendingOk.remove(host)?.let { (acct, at) -> if (now - at < OK_WINDOW_MS) runCatching { Ledger.markOk(ctx, host, acct) } }
            runCatching { AutoLogin.markOk(ctx, host) }
            // [38.9.12] Google's account chooser after 「Continue with Google」: pick the account
            return runCatching { AutoLogin.onChooser(ctx, url, eval) }.getOrNull()   // phone / e-mail code pages: the OTP flow handles them
        }
        val pwEmpty = fj.optBoolean("pwEmpty", true)
        val err = runCatching { eval(LoginAssist.ERROR_JS) }.getOrNull().orEmpty()
        val refused = filled[host]?.let { now - it < REFUSED_WINDOW_MS } == true && LoginAssist.PW_WRONG.containsMatchIn(err)
        if (!pwEmpty && !refused) return null
        val site = AutoAccounts.siteOf(host).ifBlank { host }
        val siteKey = Keychain.root(host).ifBlank { host }
        if (refused) pendingOk.remove(host)
        if (!refused) {
            // [38.9.11.3] user 2026-10-21: an account in the 台账 is filled in ANY conversation without the card. Order: named in
            // this chat > bound to this conversation > 备注 matching the task > last success here > 默认 > first (LedgerCore).
            val bound = runCatching { com.openminis.app.browser.ConvProfiles.bindings()[com.openminis.app.browser.ConvProfilesCore.bindKey(sessionId.orEmpty(), siteKey)] }.getOrNull()
            val pick = runCatching { Ledger.choose(ctx, host, sessionId, bound) }.getOrNull()
            val saved = pick?.cand?.let { c -> runCatching { SavedLogins.find(ctx, c.host.ifBlank { host }, c.account) }.getOrNull() }
            // [38.9.12] 账号自动调用: the saved session / the account's third-party login before any password (user: GitHub via
            // Google still asked for a password) — AutoLoginCore.decide
            runCatching { AutoLogin.tryBefore(ctx, url, sessionId, saved != null && saved.pass.isNotBlank(), eval) }.getOrNull()?.let { return it }
            if (AutoLogin.policy.value.autoUse && saved != null && saved.pass.isNotBlank() && eval(SavedLogins.fillJs(saved, false)) != "none") {
                filled[host] = now; pendingOk[host] = saved.user to now
                runCatching { AutoLogin.notePassword(ctx, host) }
                runCatching { com.openminis.app.browser.ConvProfiles.bind(sessionId, siteKey, saved.user) }
                runCatching { Ledger.showChip(ctx, sessionId, host, site, saved.user, eval) }
                val others = ((pick?.ordered?.size ?: 1) - 1).coerceAtLeast(0)
                return "[登录] 已用台账里的 $site 账号 ${AccountsCore.maskPhone(saved.user)} 填好账号密码（${pick?.why?.zh ?: "台账"}；密码不显示）" +
                    (if (others > 0) "，台账里还有 $others 个，要换用 accounts(action=use, site, account)" else "") +
                    "。点登录按钮继续；如果提示密码不对，Mines 会弹安全输入框让用户重填。"
            }
            if (asked[host]?.let { now - it < ASK_GAP_MS } == true) return null
        }
        asked[host] = now
        val code = fj.optBoolean("code") && fj.optBoolean("codeEmpty")
        val page = LoginMemoryCore.Page(fj.optString("user"), true, code)
        val ask = LoginMemoryCore.cardFields(page, haveAccount = false, havePassword = false,
            codeWaitMs = if (code) Long.MAX_VALUE else -1L, refused = refused, siteName = site) ?: return null
        val memo = runCatching { LoginMemory.memoFor(ctx, host) }.getOrNull()
        // [38.9.11.2] a saved account not yet picked in this conversation: offered (prefilled), filled only after 提交
        val savedHere = if (!refused) runCatching { SavedLogins.forUrl(ctx, url) }.getOrNull()?.takeIf { it.pass.isNotBlank() } else null
        runCatching { Ledger.clearChip(null) }
        val a = LoginCard.ask(LoginCard.Req(host, site, ask, savedHere?.user ?: memo?.account.orEmpty(), runCatching { LoginMemoryCore.lastLine(memo) }.getOrNull()), ctx)
            ?: return "[登录] 用户关掉了安全登录框，没有填。不要在聊天里问账号密码；需要时再打开登录页，Mines 会再弹框。"
        val sb = StringBuilder()
        if (a.account.isNotBlank() || a.password.isNotBlank()) {
            // the saved account picked as offered (password left empty) → its saved password; either way bound to this conversation
            val pw = if (a.password.isBlank() && savedHere != null && a.account.equals(savedHere.user, ignoreCase = true)) savedHere.pass else a.password
            runCatching { com.openminis.app.browser.ConvProfiles.bind(sessionId, Keychain.root(host).ifBlank { host }, a.account) }
            val e = SavedLogins.Entry(site, "https://$host/", a.account, pw)
            if (a.save && pw.isNotBlank() && pw == a.password) runCatching { SavedLogins.put(ctx, e) }
            if (pw.isNotBlank()) pendingOk[host] = a.account to System.currentTimeMillis()
            val r = eval(SavedLogins.fillJs(e, a.code.isBlank()))
            filled[host] = System.currentTimeMillis()
            sb.append(if (r != null && r != "none") "[登录] 用户已在 Mines 的安全输入框里填好账号密码，已直接填进页面" + (if (a.code.isBlank()) "并提交" else "") + "（内容不会给你看）。"
                else "[登录] 用户填好了账号密码，但这个页面上找不到账号/密码输入框。")
            if (a.save && a.password.isNotBlank()) sb.append("已保存到密码库，下次自动填。")
        }
        if (a.code.isNotBlank()) {
            val r = eval(HumanVerify.fillJs(a.code, null))
            sb.append(if (r == "ok") "验证码已填入。" else "验证码没找到输入框。")
            runCatching { eval(LoginAssist.SUBMIT_JS) }
        }
        sb.append("截图确认是否登录成功。")
        return sb.toString()
    }
}
