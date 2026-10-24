package com.openminis.app.plus

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.plus.AccountsCore.Way
import com.openminis.app.plus.group.GroupPrefs
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.ui.plus.LinkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.5 i] user 2026-10-17 19:33 「我账号关联这些啊，调用要搞好一点」 + 19:37 (#1 priority): the agent's `accounts`
 * tool and the automatic re-login.
 *  - list: every linked login / key service / identity with its status and the ways in — never a password, key or cookie;
 *  - pick: the account for a site (one → used, several → asks once, then remembered per site);
 *  - login: makes sure the site is signed in: kept session first (the agent's browser shares the same cookie store), else
 *    a hidden one-tap sign-in (saved password → Google / GitHub → a code read from the user's own mailboxes / SMS);
 *    a person-only challenge (易盾 / 滑块 / reCAPTCHA) stops it with the reason — Mines never solves or bypasses one;
 *  - status: re-checks the sign-in for real.
 * A CONFIRMED sign-out found by [Relogin.recheck] starts the same hidden re-login by itself ([autoAfterSignOut]).
 */
object AccountsTool {
    const val TOOL_NAME = "accounts"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastAuto = ConcurrentHashMap<String, Long>()
    /** site → last hidden re-login result line (shown on the account row / detail and to the agent) */
    val lastResult = ConcurrentHashMap<String, String>()

    const val LEDGER_RULE = AccountsPrompt.LEDGER_RULE

    const val PROMPT = LEDGER_RULE + "\n" +
        "Linked accounts: before signing in anywhere call accounts(action=pick or login, site) — Mines signs in with the user's linked account (kept session → saved password → Google/GitHub → code from the user's own mailbox). Never ask the user for a password; only if the 台账 cannot decide, ask once which one, then accounts(action=remember). " +
        "Any password / API key / SSH or VPS password / token you need (not a website sign-in): call accounts(action=secret, site=<domain or server IP>, kind=ssh|password|api_key, account=<user if known>) — a saved one is used silently, otherwise Mines pops a secure input card for the user; you get {{secret:ID}} / {{user:ID}} placeholders to put in shell commands (Mines substitutes them and masks output). NEVER ask the user to type a password, key or code in chat, and never echo, store or write it to files."

    fun definition() = AgentToolDefinition(
        name = TOOL_NAME,
        description = "The user's linked accounts in Mines (账号: logins, API-key services, identities). Never returns passwords, keys or cookies. " +
            "action=list: everything with status and the ways in. action=pick site=<domain or URL>: which account to use there (asks the user once when there are several). " +
            "action=login site=…: sign that site in (reuses the kept session — the browser tool shares it — else signs in by itself; a human-only captcha returns the reason). " +
            "action=status site=…: check the sign-in for real. action=remember site=… account=…: remember the user's choice. " +
            "action=register_pick site=… count=N: 多号注册 — N e-mails from the 台账 not yet registered on that site, each pre-saved with a generated password; returns {{user:ID}} / {{secret:ID}} (type them in the browser as is). action=register_done site=… account=… ok=true|false [note=…]: write the result back to the 台账 (备注 注册于 日期) or drop a failed one. " +
            "action=ledger site=…: the 台账 (saved-password) accounts for that site, best first, and which one Mines fills (no card pops for them). action=use site=… account=…: use that 台账 account in this conversation. " +
            "action=secret site=… kind=ssh|password|api_key [account=…] [name=…] [purpose=…]: get a password / key WITHOUT seeing it — saved one silently, else a secure input card pops for the user; returns {{secret:ID}} / {{user:ID}} placeholders for shell commands.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "list | pick | login | status | remember | secret | ledger | use | register_pick | register_done", enumValues = listOf("list", "pick", "login", "status", "remember", "secret", "ledger", "use", "register_pick", "register_done")),
            "kind" to AgentToolParam("string", "secret: ssh (server / VPS password), password (any other account), api_key (key / token)"),
            "name" to AgentToolParam("string", "secret: what it is, shown on the card (e.g. 梦云 VPS, OpenAI)"),
            "purpose" to AgentToolParam("string", "secret: why you need it, a few words in the user's language"),
            "site" to AgentToolParam("string", "Domain or URL, e.g. aliyun.com or https://bailian.console.aliyun.com"),
            "account" to AgentToolParam("string", "remember / use / register_done: the account (address / user name)"),
            "count" to AgentToolParam("integer", "register_pick: how many accounts to register (1–20)"),
            "ok" to AgentToolParam("boolean", "register_done: true = the account was created, false = failed (removed from the 台账)"),
            "note" to AgentToolParam("string", "register_done: extra 备注 (a few words)"),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "site", "account", "count", "ok", "note", "kind", "name", "purpose"),
    )

    // ---------------- building the account list ----------------

    private fun providersSignedIn(): List<String> = runCatching {
        IdentityStore.signedInProviders().keys.filter { it == IdentityCore.IdType.GOOGLE || it == IdentityCore.IdType.GITHUB || it == IdentityCore.IdType.MICROSOFT }.map { it.label }
    }.getOrDefault(emptyList())

    private fun codeSources(c: Context): List<String> = runCatching {
        CodeSources.enabledBoxes(c).map { it.label } + (if (IdentityStore.current(c).any { it.type == IdentityCore.IdType.PHONE }) listOf("短信") else emptyList())
    }.getOrDefault(emptyList())

    /** Logins (账号 › 登录) + key services (账号 › 服务) as [AccountsCore.Acct]. */
    fun accounts(c: Context): List<AccountsCore.Acct> {
        val out = ArrayList<AccountsCore.Acct>()
        val prov = providersSignedIn()
        val codes = codeSources(c)
        runCatching {
            Keychain.all(c).forEach { a ->
                val ways = HashSet<Way>()
                if (a.status == KeychainCore.Status.OK) ways += Way.SESSION
                if (a.hasPw) ways += Way.PASSWORD
                if (prov.isNotEmpty() || a.method == IdentityCore.Method.OAUTH) ways += Way.THIRD_PARTY
                if (codes.isNotEmpty()) ways += Way.CODE
                val pill = when (a.status) { KeychainCore.Status.OK -> "OK"; KeychainCore.Status.EXPIRED -> "WARN"; KeychainCore.Status.ABNORMAL -> "BAD"; else -> "UNCHECKED" }
                out += AccountsCore.Acct(Keychain.root(a.host).ifBlank { a.host }, a.site, a.account, pill, ways,
                    if (a.method == IdentityCore.Method.OAUTH && a.provider != null) listOf(a.provider.label) else prov, a.source)
            }
        }
        runCatching {
            PlusVault.init(c)
            PlusVault.entries.value.forEach { (id, e) ->
                val k = PlusVault.kind(id) ?: return@forEach
                if (k.method != ConnMethod.KEY || PlusVault.secret(id) == null) return@forEach
                val host = Keychain.hostOf(k.keyUrl ?: k.loginUrl ?: k.site ?: "").ifBlank { k.id }
                val lv = AccountPatrol.status.value[id.substringBefore('#')]?.level?.name
                out += AccountsCore.Acct(Keychain.root(host).ifBlank { host }, k.name, e.account.orEmpty(), SessionStatus.keyPill(lv).name, setOf(Way.KEY), source = "账号 › 服务")
            }
        }
        return out.distinctBy { it.key + it.ways.contains(Way.KEY) }
    }

    private fun remembered(site: String): String? = GroupPrefs.get("acctpick_" + AccountsCore.accountSite(site))
    fun remember(site: String, key: String?) = GroupPrefs.put("acctpick_" + AccountsCore.accountSite(site), key)

    // ---------------- the tool ----------------

    suspend fun runTool(c: Context, sid: String, argsJson: String): ToolExecutionResult = runCatching {
        val a = JSONObject(argsJson)
        val site = a.optString("site").trim()
        val all = accounts(c)
        when (a.optString("action")) {
            "list" -> {
                val idents = runCatching { IdentityStore.current(c).map { "${it.type.label} ${AccountsCore.maskPhone(it.value)}" + if (it.signedIn) "（Mines 里已登录）" else "" } }.getOrDefault(emptyList())
                val sb = StringBuilder("已关联的账号（不含密码 / 密钥）：\n")
                all.sortedBy { it.site }.forEach { sb.append(AccountsCore.line(it)).append('\n') }
                if (idents.isNotEmpty()) sb.append("身份：").append(idents.joinToString("、")).append('\n')
                val codes = codeSources(c)
                sb.append("验证码来源：").append(codes.ifEmpty { listOf("没有（可在 AI 服务 › 验证码来源 打开）") }.joinToString("、"))
                ToolExecutionResult(AccountsCore.scrub(sb.toString()), true)
            }
            "remember" -> {
                val acct = a.optString("account").trim()
                val hit = all.firstOrNull { AccountsCore.sameSite(it.site, AccountsCore.accountSite(site)) && it.identity.equals(acct, true) }
                    ?: return@runCatching ToolExecutionResult("没找到 $site 上的账号 $acct", false)
                remember(site, hit.key)
                ToolExecutionResult("记住了：$site 以后用 ${AccountsCore.maskPhone(acct)}", true)
            }
            "pick" -> ToolExecutionResult(pickText(c, sid, site, all), true)
            // [38.9.11.3] 台账: accounts with saved passwords for a site, ranked (named in chat > bound > 备注 > last success > 默认)
            "ledger" -> {
                if (site.isBlank()) return@runCatching ToolExecutionResult("site 是空的", false)
                val h = Keychain.hostOf(if (site.contains("://")) site else "https://$site").ifBlank { site }
                ToolExecutionResult(Ledger.agentLines(c, h, sid, boundFor(sid, h)), true)
            }
            "register_pick" -> {
                if (site.isBlank()) return@runCatching ToolExecutionResult("site 是空的", false)
                ToolExecutionResult(Ledger.registerPick(c, site, a.optInt("count", 1).coerceIn(1, 20)), true)
            }
            "register_done" -> {
                val acct = a.optString("account").trim()
                if (site.isBlank() || acct.isBlank()) return@runCatching ToolExecutionResult("site 和 account 都要填", false)
                ToolExecutionResult(Ledger.registerDone(c, site, acct, a.optBoolean("ok", true), a.optString("note").trim()), true)
            }
            "use" -> {
                val acct = a.optString("account").trim()
                if (site.isBlank() || acct.isBlank()) return@runCatching ToolExecutionResult("site 和 account 都要填", false)
                val h = Keychain.hostOf(if (site.contains("://")) site else "https://$site").ifBlank { site }
                val hit = Ledger.cands(c, h).firstOrNull { it.account.equals(acct, true) || it.account.substringBefore('@').equals(acct, true) }
                    ?: return@runCatching ToolExecutionResult("台账里没有 $h 的账号 $acct", false)
                com.openminis.app.browser.ConvProfiles.bind(sid, Keychain.root(h).ifBlank { h }, hit.account)
                ToolExecutionResult("好：这个对话在 $h 用 ${AccountsCore.maskPhone(hit.account)}，登录页出现时自动填（如果已登录了别的账号，先退出再打开登录页）", true)
            }
            // [Mines-38.9.11 b] secure input for any password / key the agent needs (never asked in chat)
            "secret" -> {
                TaskProgress.onWaiting(sid, "等你在安全输入框里填写")
                val r = SecretRefs.ask(c, a.optString("kind"), site, a.optString("account").trim(), a.optString("name").trim(), a.optString("purpose").trim())
                ToolExecutionResult(r, !r.startsWith("site 是空的"))
            }
            "status" -> {
                if (site.isBlank()) return@runCatching ToolExecutionResult("site 是空的", false)
                val r = Relogin.recheck(c, Keychain.root(AccountsCore.accountSite(site)), force = true)
                ToolExecutionResult(when (r) { true -> "$site 已登录（刚确认）"; false -> "$site 确认已退出登录"; null -> "$site 现在判断不了（网络或没有可检查的页面），不算失效" }, true)
            }
            "login" -> {
                if (site.isBlank()) return@runCatching ToolExecutionResult("site 是空的", false)
                val pick = AccountsCore.pick(site, all, remembered(site))
                if (pick is AccountsCore.Pick.Ask) return@runCatching ToolExecutionResult(pickText(c, sid, site, all), true)
                val acct = (pick as? AccountsCore.Pick.One)?.acct
                acct?.let { TaskProgress.usedAccount(sid, "${it.name} · ${AccountsCore.maskPhone(it.identity)}") }
                if (acct != null && Way.KEY in acct.ways) return@runCatching ToolExecutionResult("${acct.name} 是 API 密钥服务：直接调用即可（密钥在保险箱，由 Mines 带上）", true)
                val root = Keychain.root(AccountsCore.accountSite(site))
                if (Relogin.recheck(c, root, force = true) == true) return@runCatching ToolExecutionResult("$site 已登录" + (acct?.let { "（${AccountsCore.maskPhone(it.identity)}）" } ?: "") + "：用 browser 直接打开，会话是共享的", true)
                TaskProgress.onWaiting(sid, "正在登录 $site")
                val res = hiddenLogin(c, root)
                if (res.ok) TaskProgress.onTurnStart(sid) else if (res.needsUser) TaskProgress.onWaiting(sid, res.line)
                ToolExecutionResult(res.line, res.ok)
            }
            else -> ToolExecutionResult("action 只能是 list / pick / login / status / remember / secret / ledger / use / register_pick / register_done", false)
        }
    }.getOrElse { ToolExecutionResult("Error: ${it.message}", false) }

    /** [38.9.11.3] several accounts and nothing remembered: the 台账 decides (named / bound / 备注 / last success / 默认) — no question */
    private fun ledgerPick(c: Context, sid: String, site: String): String? = runCatching {
        val h = Keychain.hostOf(if (site.contains("://")) site else "https://$site").ifBlank { site }
        val p = Ledger.choose(c, h, sid, boundFor(sid, h)); val cand = p.cand ?: return null
        "用台账里的 ${AccountsCore.maskPhone(cand.account)}（${p.why?.zh.orEmpty()}）；登录页出现时 Mines 自动填，不用问用户。要换：accounts(action=use, site, account)"
    }.getOrNull()

    private fun boundFor(sid: String, host: String): String? = runCatching {
        com.openminis.app.browser.ConvProfiles.bindings()[com.openminis.app.browser.ConvProfilesCore.bindKey(sid, Keychain.root(host).ifBlank { host })]
    }.getOrNull()

    private fun pickText(c: Context, sid: String, site: String, all: List<AccountsCore.Acct>): String =
        when (val p = AccountsCore.pick(site, all, remembered(site))) {
            is AccountsCore.Pick.One -> "用 ${p.acct.name} 的账号 ${AccountsCore.maskPhone(p.acct.identity).ifBlank { "（已登录的那个）" }}" + (if (p.remembered) "（你之前选的）" else "") +
                "\n" + AccountsCore.line(p.acct)
            is AccountsCore.Pick.Ask -> ledgerPick(c, sid, site) ?: run {
                TaskProgress.onWaiting(sid, "选 $site 用哪个账号")
                "这个网站有 ${p.choices.size} 个账号，先问用户用哪个（只问这一次，答案用 accounts(action=remember) 记住）：\n" +
                    p.choices.joinToString("\n") { "- " + AccountsCore.maskPhone(it.identity) + "（" + it.status + "）" }
            }
            AccountsCore.Pick.None -> "$site 还没有关联账号：可以用 accounts(action=login) 试一键登录（会用已关联的 Google / GitHub / 邮箱验证码），或请用户在「账号」里关联"
        }

    // ---------------- hidden one-tap sign-in ----------------

    data class Res(val ok: Boolean, val line: String, val needsUser: Boolean = false)

    /** [Mines-38.9.5 i11] hard deadline of one hidden re-login (the smoke's own wait is 150 s). */
    const val HIDDEN_LOGIN_MS = 60_000L
    /** sites with a hidden re-login running now (the 账号 detail shows 「正在重新登录…」 instead of a second start) */
    val running = ConcurrentHashMap<String, Boolean>()
    /** page state when the deadline hits: a captcha widget, a one-time-code field, a password field, document ready */
    private const val DIAG_JS = """(function(){try{var h=(document.documentElement.innerHTML||'').toLowerCase();
var t=(document.body&&document.body.innerText||'').slice(0,4000);
var cap=/recaptcha|hcaptcha|turnstile|geetest|captcha|yidun|nc_wrapper|slider-verify|tcaptcha/.test(h)||/人机验证|安全验证|拖动滑块|点击验证|请完成验证|向右滑动/.test(t);
var code=!!document.querySelector('input[autocomplete="one-time-code"],input[name*="code" i],input[name*="otp" i],input[id*="otp" i]')||/验证码|verification code|enter the code|two-factor|2fa/i.test(t);
var pw=!!document.querySelector('input[type="password"]');
return JSON.stringify({captcha:cap,code:code,pw:pw,ready:document.readyState==='complete',url:location.host});}catch(e){return '{}';}})()"""

    /** The sign-in page of [root]'s platform, or null when Mines has none. */
    private fun loginUrl(pid: String): String? {
        LinkAll.connKind(pid)?.let { k -> return k.loginUrl ?: k.site ?: k.panelUrl }
        return com.openminis.app.plus.group.AiCatalog.spec(pid)?.webUrl
    }

    /**
     * Signs [root] in in a hidden WebView with 一键关联's automatic mode ([LoginAssist.run], auto = true): saved password →
     * Google / GitHub → mail / SMS code. Stops at a challenge only a person can pass and says so. ≤ 120 s.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun hiddenLogin(c: Context, root: String): Res {
        val pid = runCatching { LinkAll.platformFor(root) }.getOrNull() ?: return Res(false, "Mines 没有 $root 的登录入口：请用户在「账号」里关联一次")
        val url = loginUrl(pid) ?: return Res(false, "$root 没有登录页地址")
        val k = LinkAll.connKind(pid)
        var wv: WebView? = null
        var userStep: String? = null
        var last = ""
        var signed = false
        var netErr: String? = null
        var loaded = false
        var stuck: AccountsCore.Stuck? = null
        val app = c.applicationContext
        running[root] = true
        try {
            wv = withContext(Dispatchers.Main) {
                WebView(app).also { v ->
                    LoginWeb.configure(v, null)
                    // [Mines-38.9.5 i11] remember what the page did, so a timeout can say WHY (network / captcha / code)
                    v.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: WebView?, u: String?) { loaded = true }
                        override fun onReceivedError(view: WebView?, req: android.webkit.WebResourceRequest?, err: android.webkit.WebResourceError?) {
                            if (req?.isForMainFrame == true) netErr = err?.description?.toString() ?: "net::ERR"
                        }
                    }
                    v.loadUrl(url)
                }
            }
            val done = { signed || userStep != null }
            // [Mines-38.9.5 i11] one hard deadline the caller always gets an answer from: the sign-in runs as its own job and is
            // awaited with a timeout (await is cancellable even when something inside the sign-in is not) — 38.9.5 hung silently
            val job = CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                val checker = launch {
                    while (!done()) {
                        delay(2500)
                        signed = if (k != null) LoginWeb.signedIn(k) else runCatching {
                            val spec = com.openminis.app.plus.group.AiCatalog.spec(pid)
                            val o = wv?.let { LinkAll.eval(it, com.openminis.app.plus.group.WebChat.probeJs(com.openminis.app.plus.group.WebChat.site(pid))) }
                            o != null && com.openminis.app.plus.group.WebChat.classifyFor(spec, pid, JSONObject(o)) == com.openminis.app.plus.group.WebChat.PageState.SIGNED_IN
                        }.getOrDefault(false)
                    }
                }
                runCatching { LoginAssist.run(app, { wv }, auto = true, isDone = done, onStatus = { last = it }, needsUser = { userStep = it }) }
                    .onFailure { if (it !is kotlinx.coroutines.CancellationException) last = it.message ?: last }
                // LoginAssist returning is not the end: keep waiting for the session until the deadline
                while (!done()) delay(1000)
                checker.cancel()
            }
            val finished = withTimeoutOrNull(HIDDEN_LOGIN_MS) { job.join(); true } ?: false
            if (!finished && !done()) {
                job.cancel()
                // what the page shows right now decides the reason
                val diag = withTimeoutOrNull(4000) {
                    withContext(Dispatchers.Main) { wv?.let { runCatching { LinkAll.eval(it, DIAG_JS) }.getOrNull() } }
                }
                val d = runCatching { JSONObject(diag?.ifBlank { null } ?: "{}") }.getOrElse { JSONObject() }
                stuck = AccountsCore.stuck(d.optBoolean("captcha"), d.optBoolean("code"), d.optBoolean("pw"), netErr, loaded && d.optBoolean("ready", loaded))
            } else job.cancel()
        } catch (e: Exception) {
            last = e.message ?: last
        } finally {
            running.remove(root)
            withTimeoutOrNull(5000) { withContext(Dispatchers.Main) { runCatching { LoginWeb.flush(); wv?.stopLoading(); wv?.destroy() } } }
        }
        val res = when {
            signed -> {
                if (k != null) LoginWeb.markSignedIn(k.id, null) else com.openminis.app.plus.group.WebChat.markSignedIn(pid, true)
                runCatching { SessionKeeper.snapshot(app, 0L) }
                runCatching { Relogin.recheck(app, root, force = true) }
                Res(true, "$root 已重新登录（${last.ifBlank { "自动" }}）")
            }
            userStep != null -> Res(false, "$root 要你操作一步才能登录：${AccountsCore.failReason(userStep.orEmpty())}（在「账号」里点这个网站的「一键重新登录」，验证完会自动继续）", needsUser = true)
            stuck != null -> Res(false, AccountsCore.stuckLine(root, stuck!!, (HIDDEN_LOGIN_MS / 1000).toInt()), needsUser = stuck!!.needsUser)
            else -> Res(false, "$root 没登录上：${AccountsCore.failReason(last)}")
        }
        lastResult[root] = res.line
        LinkAll.setNote(pid, if (res.ok) null else if (res.needsUser) "验证" else "未完成")
        runCatching { Keychain.rev.value++ }
        return res
    }

    /**
     * Called by [Relogin.recheck] after a CONFIRMED sign-out: with a one-tap way in, sign in again by itself (at most once
     * every 6 h per site, so a site's risk control is not provoked).
     */
    fun autoAfterSignOut(c: Context, root: String) {
        val now = System.currentTimeMillis()
        if (now - (lastAuto[root] ?: 0L) < 6 * 3_600_000L) return
        val acct = accounts(c).firstOrNull { AccountsCore.sameSite(it.site, root) }
        val ways = acct?.ways.orEmpty() - Way.SESSION   // the session is what just went away
        val ignored = runCatching { LinkAll.platformFor(root)?.let { LinkAll.ignored(it) } }.getOrNull() == true
        if (AccountsCore.relogin(true, ways, ignored) != AccountsCore.Relogin.AUTO) return
        lastAuto[root] = now
        scope.launch { runCatching { hiddenLogin(c.applicationContext, root) } }
    }
}

/**
 * [Mines-38.9.5 i] user 17:39: a service that cannot be claimed / signed in (百炼, 硅基流动 …) can be 「忽略」: it is not
 * shown in red, not counted in 需要处理 / 账号巡查 / notifications, and comes back from 「已忽略」. 硅基流动 starts ignored
 * (its SMS sign-in is behind a NetEase Yidun captcha — user rule 17:45); the user can restore it.
 */
object ServiceIgnore {
    private val DEFAULT_IGNORED = setOf("siliconflow")
    fun ignored(kindOrBase: String): Boolean {
        val b = PlusVault.baseId(kindOrBase)
        return when (GroupPrefs.get("svcignore_$b")) { "1" -> true; "0" -> false; else -> b in DEFAULT_IGNORED }
    }
    fun set(kindOrBase: String, on: Boolean) = GroupPrefs.put("svcignore_" + PlusVault.baseId(kindOrBase), if (on) "1" else "0")
    fun list(ids: Collection<String>): List<String> = ids.filter { ignored(it) }
}
