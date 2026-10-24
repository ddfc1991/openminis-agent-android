package com.openminis.app.plus

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.8] 消息 → 「邮箱」: one inbox for every mailbox the user signed in to (Gmail + several 163 / 126 / QQ …).
 *
 *  - IMAP accounts: address + 授权码 / 应用专用密码 kept in the Connections vault (gmail / mail163 / qqmail entries,
 *    one entry per address). Added only after a real LOGIN + EXAMINE INBOX succeeded ([verifyAndAdd]); a wrong code is
 *    never saved and the account never shows 「已连接」 without a successful read.
 *  - Gmail web: signed in inside Mines (own cookie jar) → the Atom feed (newest unread). Shown as connected only when
 *    the feed really answered; an expired session says 「登录过期」.
 *  - Read-only: BODY.PEEK / EXAMINE — nothing is marked read, sent or deleted. Mail text stays in memory only;
 *    per-account status (ok / error text / time) is the only thing written to disk.
 */
object MailInbox {
    enum class St { OK, AUTH, NET, CHECKING, NEW }

    data class Account(val id: String, val address: String, val provider: String, val how: String, val base: String)
    data class AcctState(val st: St, val msg: String = "", val at: Long = 0L, val unread: Int = 0, val total: Int = 0)
    data class Mail(
        val id: String, val accountId: String, val address: String, val provider: String,
        val from: String, val fromAddr: String, val subject: String, val text: String, val at: Long,
        val unread: Boolean, val url: String? = null,
    )

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts
    private val _mails = MutableStateFlow<List<Mail>>(emptyList())
    val mails: StateFlow<List<Mail>> = _mails
    private val _states = MutableStateFlow<Map<String, AcctState>>(emptyMap())
    val states: StateFlow<Map<String, AcctState>> = _states
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    @Volatile private var lastAt = 0L
    /** adb self-test: fixed accounts / mail for screenshots (never set in normal use). */
    @Volatile var fake: Boolean = false

    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS).followRedirects(false).build()

    fun providerOf(address: String): String {
        val d = address.substringAfter('@', "").lowercase()
        return when {
            d == "gmail.com" || d == "googlemail.com" -> "Gmail"
            d == "163.com" || d == "vip.163.com" -> "163"
            d == "126.com" -> "126"
            d == "yeah.net" -> "yeah"
            d == "qq.com" || d == "foxmail.com" || d == "vip.qq.com" -> "QQ"
            d.contains("outlook") || d.contains("hotmail") || d == "live.com" -> "Outlook"
            d == "sina.com" || d == "sina.cn" -> "新浪"
            d == "aliyun.com" -> "阿里"
            else -> d.substringBefore('.').ifBlank { "邮箱" }
        }
    }

    private fun baseFor(address: String): String = when (providerOf(address)) {
        "Gmail" -> "gmail"; "QQ" -> "qqmail"; else -> "mail163"
    }

    private fun prefs(c: Context) = c.getSharedPreferences("mines_mailbox", Context.MODE_PRIVATE)

    // ---------------- accounts ----------------

    fun gmailWebSignedIn(): Boolean = runCatching {
        val cm = CookieManager.getInstance()
        MailParse.cookieHas(cm.getCookie("https://mail.google.com/mail/"), listOf("SID", "__Secure-1PSID", "OSID")) ||
            MailParse.cookieHas(cm.getCookie("https://accounts.google.com/"), listOf("SID", "__Secure-1PSID"))
    }.getOrDefault(false)

    fun load(context: Context): List<Account> {
        if (fake) return _accounts.value
        PlusVault.init(context)
        val out = ArrayList<Account>()
        listOf("gmail", "mail163", "qqmail").forEach { base ->
            PlusVault.accountsOf(base).forEach { id ->
                val user = PlusVault.entries.value[id]?.account?.trim().orEmpty()
                if (user.contains('@') && PlusVault.secret(id) != null && MailImap.hostFor(user) != null)
                    out += Account(id, user, providerOf(user), if (base == "gmail") "应用专用密码" else "授权码", base)
            }
        }
        if (gmailWebSignedIn() && out.none { it.id == "gmail_web" }) {
            val addr = prefs(context).getString("gmail_web_addr", null) ?: "Gmail（网页登录）"
            out += Account("gmail_web", addr, "Gmail", "在 Mines 内登录", "gmail")
        }
        // restore last known status (no mail content on disk)
        val p = prefs(context)
        _states.value = out.associate { a ->
            a.id to (_states.value[a.id] ?: p.getString("st_${a.id}", null)?.let { s ->
                val parts = s.split('|', limit = 3)
                AcctState(runCatching { St.valueOf(parts[0]) }.getOrDefault(St.NEW), parts.getOrElse(2) { "" }, parts.getOrNull(1)?.toLongOrNull() ?: 0L)
            } ?: AcctState(St.NEW))
        }
        _accounts.value = out
        return out
    }

    private fun setState(context: Context, id: String, s: AcctState) {
        _states.value = _states.value + (id to s)
        if (s.st != St.CHECKING) prefs(context).edit().putString("st_$id", "${s.st.name}|${s.at}|${s.msg.take(120)}").apply()
    }

    // ---------------- reading ----------------

    private val gate = FeedRefresh.Gate()

    /**
     * Re-read every account in parallel (each one on its own: one broken mailbox never hides the others).
     * [Mines-38.9.2] a forced read (pull, 重试) waits for a running one and then reads again (it used to return at once
     * while busy, so a pull right after opening the tab did nothing). Returns new mail + one line per box that failed.
     */
    suspend fun refresh(context: Context, force: Boolean = false): FeedRefresh.Outcome? = withContext(Dispatchers.IO) {
        if (fake) return@withContext FeedRefresh.Outcome(emptyList(), emptyList())
        if (!force && System.currentTimeMillis() - lastAt < 30_000L && _mails.value.isNotEmpty()) return@withContext null
        gate.run(waitIfBusy = force) { readAll(context) }
    }

    private suspend fun readAll(context: Context): FeedRefresh.Outcome {
        _busy.value = true
        val before = _mails.value.map { it.id }.toSet()
        val fails = ArrayList<String>()
        try {
            val accts = load(context)
            accts.forEach { a -> setState(context, a.id, (_states.value[a.id] ?: AcctState(St.NEW)).copy(st = St.CHECKING)) }
            val got = coroutineScope {
                accts.map { a -> async { a to (withTimeoutOrNull(30_000L) { runCatching { read(context, a) } } ?: Result.failure(MailImap.ImapError(MailImap.Fail.NET, "30 秒没读完"))) } }.awaitAll()
            }
            val all = ArrayList<Mail>()
            got.forEach { (a, r) ->
                r.onSuccess { (mails, unseen, total) ->
                    all += mails
                    setState(context, a.id, AcctState(St.OK, "", System.currentTimeMillis(), unseen, total))
                }.onFailure { e ->
                    val kind = (e as? MailImap.ImapError)?.kind
                    val st = if (kind == MailImap.Fail.AUTH) St.AUTH else St.NET
                    val msg = when {
                        a.id == "gmail_web" && st == St.AUTH -> "Gmail 登录过期了，点这里在 Mines 内重新登录"
                        st == St.AUTH && a.provider == "Gmail" -> e.message ?: "应用专用密码不对或已失效"
                        st == St.AUTH -> "授权码不对或已失效：到 ${a.provider} 网页的 设置 → POP3/SMTP/IMAP 重新生成"
                        else -> e.message ?: "连不上"
                    }
                    // keep this account's mail from the last good read
                    all += _mails.value.filter { it.accountId == a.id }
                    val many = got.count { (b, _) -> b.provider == a.provider } > 1
                    val name = FeedRefresh.mailLabel(a.address, many).let { if (a.id == "gmail_web") "Gmail" else it }
                    fails += when {
                        a.id == "gmail_web" && st == St.AUTH -> "Gmail 网页登录过期"
                        st == St.AUTH && a.provider == "Gmail" -> "$name 应用专用密码失效"
                        st == St.AUTH -> "$name 授权码失效"
                        kind == MailImap.Fail.PROTOCOL -> "$name ${e.message ?: "没接受"}".take(40)
                        (e.message ?: "").contains("秒没读完") -> "$name 连接超时"
                        else -> "$name 连不上"
                    }
                    setState(context, a.id, AcctState(st, msg, System.currentTimeMillis(), _states.value[a.id]?.unread ?: 0))
                }
            }
            _mails.value = all.distinctBy { it.id }.sortedByDescending { it.at }
            // [Mines-38.9.12.1] the self-hosted domain inbox feeds the 验证码 tab too (when connected)
            if (!fake && AgentMailbox.ready("selfhost")) runCatching { withTimeoutOrNull(20_000L) { AgentMailbox.selfRecent() } }
            // [Mines-38.8] welcome / verify-your-email mail from a site not in 账号关联 yet → recorded (no reminder needed)
            if (!fake) _mails.value.take(60).forEach { m -> runCatching { AutoAccounts.onMail(context, m.from, m.fromAddr, m.subject, m.address) } }
            // [Mines-38.9] new-device / abnormal sign-in mails → 账号关联 alert (是我 / 不是我)
            _mails.value.take(80).forEach { m -> runCatching { Keychain.onMail(context, m) } }
            val countAll = lastAt > 0
            lastAt = System.currentTimeMillis()
            return FeedRefresh.Outcome(FeedRefresh.freshIds(before, _mails.value.map { it.id }, countAll), fails)
        } finally { _busy.value = false }
    }

    /** Addresses the user really has (identity store: mailboxes, signed-in Google …) — for the typo check. */
    fun knownAddresses(context: Context): List<String> = runCatching {
        IdentityStore.init(context)
        IdentityStore.current(context).map { it.value.trim() }.filter { it.contains('@') }
    }.getOrDefault(emptyList())

    private fun read(context: Context, a: Account): Triple<List<Mail>, Int, Int> {
        if (a.id == "gmail_web") return gmailWeb(context, a)
        val pass = PlusVault.secret(a.id)?.replace(" ", "") ?: throw MailImap.ImapError(MailImap.Fail.AUTH, "没有保存授权码")
        val host = MailImap.hostFor(a.address) ?: throw MailImap.ImapError(MailImap.Fail.PROTOCOL, "不认识这个邮箱")
        val box = try { MailImap.inbox(host, a.address, pass) } catch (e: MailImap.ImapError) {
            if (e.kind != MailImap.Fail.AUTH) throw e
            // [Mines-38.9.1] a near-typo of an address the user really has: try the SAME saved password with it; only a real
            // login rewrites the address (no re-paste of the password)
            val known = knownAddresses(context)
            val fixed = AddressFix.candidates(a.address, known).firstOrNull { c ->
                MailImap.hostFor(c) != null && runCatching { MailImap.inbox(MailImap.hostFor(c)!!, c, pass, 1); true }.getOrDefault(false)
            } ?: throw MailImap.ImapError(MailImap.Fail.AUTH, AddressFix.reason(a.address, known))
            PlusVault.kind(a.id)?.let { k -> PlusVault.entries.value[a.id]?.let { en -> PlusVault.save(context, k, fixed, null, share = en.shareWithAi, via = en.via) } }
            runCatching { IdentityStore.addIdent(context, IdentityCore.Ident(IdentityCore.IdType.EMAIL, fixed, source = "邮箱")) }
            AddressFix.fixedNote = "已把邮箱地址 ${a.address} 改成 $fixed（保存的密码能登录）"
            return read(context, a.copy(address = fixed))
        }
        val mails = box.msgs.map { m ->
            Mail("${a.id}:${m.uid}", a.id, a.address, a.provider, m.from, m.fromAddr, m.subject.ifBlank { "（无主题）" }, m.text,
                if (m.at > 0) m.at else 0L, m.unread, webUrl(a.address))
        }
        return Triple(mails, box.unseen, box.exists)
    }

    fun webUrl(address: String): String? = when (providerOf(address)) {
        "Gmail" -> "https://mail.google.com/mail/"
        "163" -> "https://mail.163.com/"; "126" -> "https://mail.126.com/"; "yeah" -> "https://mail.yeah.net/"
        "QQ" -> "https://wx.mail.qq.com/"
        else -> null
    }

    private fun gmailWeb(context: Context, a: Account): Triple<List<Mail>, Int, Int> {
        val out = ArrayList<Mail>(); var count = 0; var anyOk = false
        for (u in 0..1) {
            val url = "https://mail.google.com/mail/u/$u/feed/atom"
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            val b = Request.Builder().url(url).header("User-Agent", MINES_MOBILE_UA).header("Accept", "application/atom+xml,text/xml,*/*")
            if (!cookie.isNullOrBlank()) b.header("Cookie", cookie)
            val (code, xml) = try {
                http.newCall(b.build()).execute().use { r -> r.code to (if (r.code == 200) r.body?.string().orEmpty() else "") }
            } catch (e: java.io.IOException) { if (u == 0) throw MailImap.ImapError(MailImap.Fail.NET, "连不上 Gmail（需要代理时请把 Mines 加入代理）") else break }
            if (code != 200 || !xml.contains("<feed")) { if (u == 0) throw MailImap.ImapError(MailImap.Fail.AUTH, "Gmail 返回 $code") else break }
            anyOk = true
            val addr = Regex("Inbox for ([^<\\s]+@[^<\\s]+)").find(xml)?.groupValues?.get(1) ?: a.address
            if (u == 0 && addr.contains('@')) prefs(context).edit().putString("gmail_web_addr", addr).apply()
            count += Regex("<fullcount>(\\d+)</fullcount>").find(xml)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val entries = Regex("(?s)<entry>(.*?)</entry>").findAll(xml).toList()
            entries.forEach { m ->
                val e = m.groupValues[1]
                fun tag(n: String) = Regex("(?s)<$n>(.*?)</$n>").find(e)?.groupValues?.get(1)?.let { MailParse.unescape(it) }.orEmpty()
                val name = Regex("(?s)<author>.*?<name>(.*?)</name>").find(e)?.groupValues?.get(1)?.let { MailParse.unescape(it) }.orEmpty()
                val email = Regex("(?s)<author>.*?<email>(.*?)</email>").find(e)?.groupValues?.get(1).orEmpty()
                val link = Regex("<link[^>]*href=\"([^\"]+)\"").find(e)?.groupValues?.get(1)?.let { MailParse.unescape(it) }
                out += Mail("gw$u:" + tag("id").ifBlank { tag("title") }.hashCode(), a.id, addr, "Gmail", name.ifBlank { email }, email,
                    tag("title").ifBlank { "（无主题）" }, tag("summary"), MailParse.parseIso(tag("issued").ifBlank { tag("modified") }), true, link)
            }
            if (u == 0 && entries.isEmpty()) break
        }
        if (!anyOk) throw MailImap.ImapError(MailImap.Fail.AUTH, "Gmail 没有应答")
        return Triple(out, count, count)
    }

    // ---------------- adding / removing ----------------

    /**
     * Log in for real first; saved to the vault only when LOGIN + EXAMINE INBOX worked.
     * Returns null on success, else the plain reason shown under the form.
     */
    suspend fun verifyAndAdd(context: Context, address: String, code: String): String? = withContext(Dispatchers.IO) {
        // [Mines-38.9.1] cleaned address + code (Gmail shows the app password as 4 × 4 with spaces), one host table,
        // the server's own reply mapped to plain Chinese, and the code is saved under the entry for THIS address only
        val addr = MailConfig.normalizeAddress(address)
        val pass = MailConfig.normalizeCode(code)
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(addr)) return@withContext "邮箱地址看起来不对：$addr"
        val cfg = MailConfig.of(addr)
        if (cfg?.oauthOnly == true) return@withContext MailConfig.explain("", cfg, addr)
        if (pass.length < 4) return@withContext "请填${cfg?.codeName ?: "授权码"}（不是邮箱登录密码）"
        val host = MailImap.hostFor(addr) ?: return@withContext "暂时只支持 163 / 126 / yeah / QQ / Gmail / 新浪 / 阿里邮箱"
        try { MailImap.inbox(host, addr, pass, max = 1) } catch (e: MailImap.ImapError) {
            return@withContext when (e.kind) {
                MailImap.Fail.NET -> MailConfig.explain("timeout " + (e.message ?: ""), cfg, addr)
                else -> MailConfig.explain(e.message ?: "", cfg, addr)
            }
        }
        PlusVault.init(context)
        val base = baseFor(addr)
        val id = PlusVault.accountsOf(base).firstOrNull { PlusVault.entries.value[it]?.account.equals(addr, true) }
            ?: PlusVault.accountsOf(base).firstOrNull { PlusVault.entries.value[it]?.account.isNullOrBlank() && PlusVault.secret(it) == null }
            ?: if (PlusVault.entries.value[base] == null) base else PlusVault.newAccountId(base)
        val kind = PlusVault.kind(id) ?: return@withContext "保存失败（找不到 $base）"
        withContext(Dispatchers.Main) { PlusVault.save(context, kind, addr, pass, share = false, via = "mailbox") }
        runCatching { IdentityStore.addIdent(context, IdentityCore.Ident(IdentityCore.IdType.EMAIL, addr, source = "邮箱")) }
        MailSources.invalidate()
        lastAt = 0L
        null
    }

    /**
     * [Mines-38.9.1] one-time: a saved mailbox whose address is a known near-typo of the user's real address
     * (one extra digit typed on the phone) is re-verified with the SAME saved password; the address is
     * rewritten only when that real login works. Runs at most once per entry/address pair.
     */
    suspend fun migrateTypos(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            PlusVault.init(context)
            val p = prefs(context)
            val known = (knownAddresses(context) + googleSignedIn(context)).map { it.lowercase() }.distinct()
            listOf("gmail", "mail163", "qqmail").forEach { base ->
                PlusVault.accountsOf(base).forEach { id ->
                    val en = PlusVault.entries.value[id] ?: return@forEach
                    val a = MailConfig.normalizeAddress(en.account)
                    val pass = PlusVault.secret(id)?.let { MailConfig.normalizeCode(it) } ?: return@forEach
                    val fix = AddressFix.candidates(a, known).firstOrNull() ?: return@forEach
                    if (p.getBoolean("mig_${id}_$a", false)) return@forEach
                    p.edit().putBoolean("mig_${id}_$a", true).apply()
                    val host = MailImap.hostFor(fix) ?: return@forEach
                    val ok = runCatching { MailImap.inbox(host, fix, pass, 1); true }.getOrDefault(false)
                    val k = PlusVault.kind(id) ?: return@forEach
                    if (ok) {
                        withContext(Dispatchers.Main) { PlusVault.save(context, k, fix, null, share = en.shareWithAi, via = en.via) }
                        AddressFix.fixedNote = "已把邮箱地址 $a 改成 $fix（保存的密码能登录）"
                    } else AddressFix.fixedNote = AddressFix.reason(a, known)
                }
            }
            MailSources.invalidate(); lastAt = 0L
        }
    }
    /** addresses the user really signed in with inside Mines (Google identities, Gmail web address) — no addresses in code */
    private fun googleSignedIn(context: Context): List<String> = runCatching {
        listOfNotNull(prefs(context).getString("gmail_web_addr", null)) +
            IdentityStore.current(context).filter { it.type == IdentityCore.IdType.GOOGLE }.map { it.value.trim() }.filter { it.contains('@') }
    }.getOrDefault(emptyList())

    fun remove(context: Context, id: String) {
        if (id == "gmail_web") return
        PlusVault.kind(id)?.let { PlusVault.remove(context, it) }
        prefs(context).edit().remove("st_$id").apply()
        _mails.value = _mails.value.filterNot { it.accountId == id }
        load(context)
    }

    // ---------------- adb self-test ----------------

    fun fakeFill(now: Long = System.currentTimeMillis()) {
        fake = true
        val accts = listOf(
            Account("gmail", "alex.demo@gmail.com", "Gmail", "在 Mines 内登录", "gmail"),
            Account("mail163", "demo.one@163.com", "163", "授权码", "mail163"),
            Account("mail163#2", "demo.two@163.com", "163", "授权码", "mail163"),
            Account("mail163#3", "demo.three@163.com", "163", "授权码", "mail163"),
        )
        val h = 3_600_000L
        fun m(i: Int, a: Account, from: String, subj: String, text: String, ago: Long, unread: Boolean) =
            Mail("fake$i", a.id, a.address, a.provider, from, "", subj, text, now - ago, unread, webUrl(a.address))
        _accounts.value = accts
        _mails.value = listOf(
            m(1, accts[0], "GitHub", "[openminis-agent-android] Run succeeded: Build Mines APK", "Build Mines APK: all jobs have passed. View workflow run …", h / 6, true),
            m(2, accts[1], "网易邮箱", "你的邮箱已开启 IMAP 服务", "你好，你已成功开启 IMAP/SMTP 服务，可在第三方客户端中使用授权码登录。", h, true),
            m(3, accts[2], "阿里云", "云服务器 ECS 续费提醒", "尊敬的用户，您的实例将于 7 天后到期，请及时续费以免影响业务。", 3 * h, false),
            m(4, accts[0], "Google", "安全提醒：新设备登录", "你的 Google 账号刚刚在一台新的 Android 设备上登录。如果是你本人，无需执行任何操作。", 5 * h, false),
            m(5, accts[3], "Intigriti", "Your submission was triaged", "Good news! Your submission has been triaged and is now awaiting the program's review.", 26 * h, true),
            m(6, accts[1], "OKX", "资产变动通知", "您的账户于今日 09:12 完成一笔划转，如非本人操作请立即联系客服。", 50 * h, false),
            m(7, accts[2], "京东", "您的订单已发货", "您购买的商品已由顺丰快递发出，预计明天送达。", 80 * h, false),
        )
        _states.value = mapOf(
            accts[0].id to AcctState(St.OK, "", now, 2, 120),
            accts[1].id to AcctState(St.OK, "", now, 1, 58),
            accts[2].id to AcctState(St.OK, "", now, 0, 33),
            accts[3].id to AcctState(St.AUTH, "授权码不对或已失效：到 163 网页的 设置 → POP3/SMTP/IMAP 重新生成", now, 1),
        )
    }

    /** [Mines-38.9.12 b] adb self-test: two verification-code mails on top of [fakeFill] (验证码 tab screenshots) */
    fun fakeCodes(now: Long = System.currentTimeMillis()) {
        if (!fake) fakeFill(now)
        val a = _accounts.value.firstOrNull() ?: return
        _mails.value = listOf(
            Mail("fakec1", a.id, "k7q2demo@mail.tm", "mail.tm", "Cloudflare", "noreply@notify.cloudflare.com", "Your Cloudflare verification code",
                "Your verification code is 482917. It expires in 10 minutes.", now - 4 * 60_000L, true, null),
            Mail("fakec2", a.id, a.address, a.provider, "Railway", "team@railway.app", "Railway sign-in code",
                "Use the code 731 055 to finish signing in to Railway.", now - 2 * 3_600_000L, false, null),
        ) + _mails.value.filter { !it.id.startsWith("fakec") }
    }

    fun fakeOff(context: Context) { fake = false; _mails.value = emptyList(); _states.value = emptyMap(); lastAt = 0L; load(context) }

    fun unreadTotal(): Int = _states.value.values.sumOf { it.unread.coerceAtLeast(0) }
}
