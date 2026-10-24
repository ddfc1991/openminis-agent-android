package com.openminis.app.plus

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * [Mines-sources-33] Every mailbox the user is already signed in to, read with no
 * extra setup, for one-time codes (HumanVerify) and for 消息汇总.
 *
 *  1. Gmail through the signed-in web session: the Atom feed
 *     https://mail.google.com/mail/u/N/feed/atom (newest unread inbox mail, title +
 *     snippet + time), fetched with the app's own WebView cookies. Works whenever
 *     Google cookies exist in Mines' cookie jar (signed in to Gmail inside Mines).
 *  2. Outlook / Hotmail through the Microsoft connection (device-flow token already has
 *     Mail.ReadWrite): Graph /me/messages.
 *  3. IMAP with a saved app password / 授权码: Gmail (imap.gmail.com), 163 / 126 / yeah
 *     (imap.163.com …, with the IMAP ID command 163 requires), QQ (imap.qq.com).
 *  4. Phone notifications stay the fallback (HumanVerify.onNotification).
 *
 * Mail text stays in memory. Codes are never logged, never written to disk and
 * never shown to the model.
 */
object MailSources {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS).followRedirects(false).build()

    /** Test hook (adb-only SelfTestProvider): an extra Atom feed URL read like Gmail's. */
    @Volatile var testFeedUrl: String? = null
    /** [Mines-38.9.2] adb self-test only (msgfeed): extra IMAP boxes (label, host|user, pass) read like real ones. */
    @Volatile var testBoxes: List<Triple<String, String, String>> = emptyList()
    /** the failures of the last real read, repeated when a later caller is served from the cache */
    @Volatile private var lastFails: List<String> = emptyList()

    @Volatile private var cache: List<MailParse.Mail> = emptyList()
    @Volatile private var cacheAt = 0L

    data class Box(val id: String, val label: String, val how: String)

    // ---------------- which mailboxes are usable ----------------

    fun gmailWebSignedIn(): Boolean = runCatching {
        val cm = CookieManager.getInstance()
        MailParse.cookieHas(cm.getCookie("https://mail.google.com/mail/"), listOf("SID", "__Secure-1PSID", "OSID")) ||
            MailParse.cookieHas(cm.getCookie("https://accounts.google.com/"), listOf("SID", "__Secure-1PSID"))
    }.getOrDefault(false)

    private fun imapHost(email: String, kindBase: String): String? {
        val d = email.substringAfter('@', "").lowercase()
        return when {
            d == "smoke.test" && MailImap.plainOverride != null -> "smoke"   // [Mines-38.9.1] smoke only
            kindBase == "gmail" || d == "gmail.com" || d == "googlemail.com" -> "imap.gmail.com"
            d == "163.com" -> "imap.163.com"
            d == "126.com" -> "imap.126.com"
            d == "yeah.net" -> "imap.yeah.net"
            d == "qq.com" || d == "foxmail.com" || kindBase == "qqmail" -> "imap.qq.com"
            kindBase == "mail163" -> "imap.163.com"
            d == "sina.com" || d == "sina.cn" -> "imap.sina.com"
            d.endsWith("aliyun.com") -> "imap.aliyun.com"
            else -> null
        }
    }

    private fun imapAccounts(): List<Triple<String, String, String>> = // (label, host+user, pass)
        listOf("gmail", "mail163", "qqmail").flatMap { base ->
            PlusVault.accountsOf(base).mapNotNull { id ->
                val pass = PlusVault.secret(id) ?: return@mapNotNull null
                val user = PlusVault.entries.value[id]?.account?.trim().orEmpty()
                if (!user.contains('@')) return@mapNotNull null
                val host = imapHost(user, base) ?: return@mapNotNull null
                Triple(label(base), "$host|$user", MailConfig.normalizeCode(pass))   // [Mines-38.9.5 i] NBSP / zero-width too, like the verify
            }
        } + testBoxes

    private fun label(base: String) = when (base) { "gmail" -> "Gmail"; "mail163" -> "网易邮箱"; "qqmail" -> "QQ 邮箱"; else -> base }

    fun boxes(context: Context): List<Box> {
        PlusVault.init(context)
        val out = ArrayList<Box>()
        if (gmailWebSignedIn()) out += Box("gmail_web", "Gmail", "网页登录")
        if (PlusVault.defaultAccount("microsoft")?.let { PlusVault.secret(it) } != null) out += Box("outlook", "Outlook", "微软授权")
        imapAccounts().forEach { (l, hu, _) -> out += Box("imap:" + hu.substringAfter('|'), l, "IMAP") }
        return out
    }

    fun available(context: Context): Boolean = testFeedUrl != null || boxes(context).isNotEmpty()

    // ---------------- reading ----------------

    /**
     * Recent mail from every usable mailbox (cached for [maxAgeMs]). Never throws.
     * [Mines-38.9.2] each box that can't be read adds one plain line to [fails] (「163 授权码失效」, 「Gmail 网页登录过期」…);
     * before, a refused login or a timeout just gave an empty list and the pull looked like 「no new mail」.
     */
    suspend fun recent(context: Context, maxAgeMs: Long = 60_000L, fails: MutableList<String>? = null): List<MailParse.Mail> = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - cacheAt < maxAgeMs) { fails?.addAll(lastFails); return@withContext cache }
        PlusVault.init(context)
        val bad = java.util.Collections.synchronizedList(ArrayList<String>())
        val jobs = coroutineScope {
            val list = ArrayList<kotlinx.coroutines.Deferred<List<MailParse.Mail>>>()
            testFeedUrl?.let { u -> list += async { runCatching { atomAt(u, "Gmail") }.getOrDefault(emptyList()) } }
            if (gmailWebSignedIn()) list += async { runCatching { gmailAtomChecked() }.getOrElse { bad += FeedRefresh.mailFail("Gmail", it); emptyList() } }
            if (PlusVault.defaultAccount("microsoft") != null) list += async { runCatching { outlook(context) }.getOrElse { bad += FeedRefresh.mailFail("Outlook", it); emptyList() } }
            val boxes = imapAccounts()
            boxes.forEach { (l, hu, p) ->
                val user = hu.substringAfter('|')
                val host = hu.substringBefore('|')
                // the name the user knows: 「163」/「QQ 邮箱」…, with the start of the address when they have several there
                val base = if (host == "smoke") l else FeedRefresh.mailLabel(user)
                val many = boxes.count { (l2, hu2, _) -> (if (hu2.startsWith("smoke|")) l2 else FeedRefresh.mailLabel(hu2.substringAfter('|'))) == base } > 1
                val name = if (many) "$base ${user.substringBefore('@').take(4)}…" else base
                list += async {
                    val r = withTimeoutOrNull(20_000L) { runCatching { Imap.recent(host, user, p, l) } }
                    when {
                        r == null -> { bad.add(FeedRefresh.mailFail(name, null)); emptyList<MailParse.Mail>() }
                        r.isFailure -> { bad.add(FeedRefresh.mailFail(name, r.exceptionOrNull())); emptyList<MailParse.Mail>() }
                        else -> r.getOrDefault(emptyList())
                    }
                }
            }
            list.awaitAll()
        }
        val all = jobs.flatten().distinctBy { it.id }.sortedByDescending { it.at }
        lastFails = bad.toList()
        fails?.addAll(lastFails)
        cache = all; cacheAt = System.currentTimeMillis()
        all
    }

    fun invalidate() { cacheAt = 0L }

    /** A code from mail that arrived at or after [sinceMs], or null. */
    suspend fun code(context: Context, sinceMs: Long): String? {
        if (!available(context)) return null
        invalidate()
        return MailParse.codeFrom(recent(context, 0L), sinceMs)?.first
    }

    private fun cookieGet(url: String): String? {
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        val b = Request.Builder().url(url).header("User-Agent", MINES_MOBILE_UA).header("Accept", "application/atom+xml,text/xml,*/*")
        if (!cookie.isNullOrBlank()) b.header("Cookie", cookie)
        return http.newCall(b.build()).execute().use { r -> if (r.code == 200) r.body?.string() else null }
    }

    private fun atomAt(url: String, source: String): List<MailParse.Mail> = MailParse.atom(cookieGet(url).orEmpty(), source).orEmpty()

    /** [Mines-38.9.2] like [gmailAtom] but says why u/0 could not be read (no network vs. signed out). */
    private fun gmailAtomChecked(): List<MailParse.Mail> {
        val body = cookieGet("https://mail.google.com/mail/u/0/feed/atom") ?: throw FeedRefresh.SourceFail("Gmail 网页登录过期")
        val a = MailParse.atom(body, "Gmail") ?: throw FeedRefresh.SourceFail("Gmail 网页登录过期")
        val b = runCatching { atomAt("https://mail.google.com/mail/u/1/feed/atom", "Gmail") }.getOrDefault(emptyList())
        return a + b.filter { m -> a.none { it.id == m.id } }
    }

    /** Gmail inbox (u/0 and u/1 when the user has two accounts signed in). */
    private fun gmailAtom(): List<MailParse.Mail> {
        val a = atomAt("https://mail.google.com/mail/u/0/feed/atom", "Gmail")
        val b = runCatching { atomAt("https://mail.google.com/mail/u/1/feed/atom", "Gmail") }.getOrDefault(emptyList())
        return a + b.filter { m -> a.none { it.id == m.id } }
    }

    private fun outlook(context: Context): List<MailParse.Mail> {
        val token = PlusConnect.microsoftToken(context) ?: return emptyList()
        val url = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?\$top=15&\$orderby=receivedDateTime%20desc&\$select=id,subject,bodyPreview,from,receivedDateTime,webLink"
        val body = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json").build())
            .execute().use {
                if (it.code == 401) throw FeedRefresh.SourceFail("Outlook 授权过期")
                if (it.isSuccessful) it.body?.string() else null
            } ?: return emptyList()
        val arr = JSONObject(body).optJSONArray("value") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val from = o.optJSONObject("from")?.optJSONObject("emailAddress")
            MailParse.Mail("ol:" + o.optString("id").hashCode(), "Outlook", from?.optString("name")?.ifBlank { from.optString("address") }.orEmpty(),
                o.optString("subject"), o.optString("bodyPreview"), MailParse.parseIso(o.optString("receivedDateTime")), o.optString("webLink").ifBlank { null })
        }
    }

    // ---------------- 消息汇总 items ----------------

    private val noisy = Regex("(?i)newsletter|unsubscribe|digest|promo|marketing|退订|广告|推广")

    /** Mail as feed items: security / code / personal mail first, newsletters dropped. */
    suspend fun digestItems(context: Context, maxAgeMs: Long = 5 * 60_000L, fails: MutableList<String>? = null): List<Digest.Item> {
        if (!available(context)) return emptyList()
        val since = System.currentTimeMillis() - 3 * 86_400_000L
        return recent(context, maxAgeMs, fails).filter { it.at == 0L || it.at >= since }.filterNot { noisy.containsMatchIn(it.subject + " " + it.from) }.take(25).map { m ->
            val hasCode = MailParse.extractCode(m.subject + " " + m.text) != null
            // a code never reaches the feed: say that there is one, not what it is
            val summary = if (hasCode) "一封验证码邮件（Mines 需要时会自动填，不在这里显示）" else m.text.replace(Regex("\\s+"), " ").take(140)
            Digest.Item("ml:" + m.id, m.source, "mail:" + m.source, m.from.ifBlank { m.source }, m.subject.ifBlank { "（无主题）" }, summary,
                if (m.at > 0) m.at else System.currentTimeMillis(), url = m.url)
        }
    }

    // ---------------- tiny IMAP client ----------------

    object Imap {
        private val DATE = java.time.format.DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss Z", java.util.Locale.US)

        /** Newest [max] messages of INBOX (first 12 KB of each). */
        fun recent(host: String, user: String, pass: String, label: String, max: Int = 8): List<MailParse.Mail> {
            // [Mines-38.9.1] smoke: plain TCP to the runner's fake IMAP (same switch as MailImap); real boxes always TLS :993
            val s: java.net.Socket = MailImap.plainOverride?.takeIf { host == "smoke" }?.let { o ->
                java.net.Socket().apply { connect(java.net.InetSocketAddress(o.substringBefore(':'), o.substringAfter(':').toIntOrNull() ?: 1143), 8000); soTimeout = 15_000 }
            } ?: MailNet.open(host, 993)   // [Mines-38.9.5 i] split proxy for Gmail from CN
            s.use { sock ->
                val inp = BufferedInputStream(sock.inputStream)
                val out = sock.outputStream
                readLine(inp) // greeting
                var n = 0
                fun cmd(c: String): List<Any> { n++; val tag = "m$n"; send(out, "$tag $c"); return readTagged(inp, tag) }
                if (host.contains("163") || host.contains("126") || host.contains("yeah")) cmd("ID (\"name\" \"Mines\" \"version\" \"33\" \"vendor\" \"Mines\")")
                val login = cmd("LOGIN ${q(user)} ${q(pass)}")
                if (!lastOk(login)) throw FeedRefresh.LoginRejected(login.filterIsInstance<String>().lastOrNull().orEmpty().take(120))   // [Mines-38.9.2] → 「163 授权码失效」
                val sel = cmd("SELECT INBOX")
                val exists = sel.filterIsInstance<String>().firstNotNullOfOrNull { Regex("^\\* (\\d+) EXISTS").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
                if (exists == 0) return emptyList()
                val from = maxOf(1, exists - max + 1)
                val res = cmd("FETCH $from:$exists (UID INTERNALDATE BODY.PEEK[]<0.12000>)")
                runCatching { cmd("LOGOUT") }
                // res alternates "* n FETCH (UID x INTERNALDATE "…" BODY[]<0> {len}" + literal bytes
                val mails = ArrayList<MailParse.Mail>()
                var i = 0
                while (i < res.size) {
                    val line = res[i] as? String
                    val lit = res.getOrNull(i + 1) as? ByteArray
                    if (line != null && line.contains("FETCH") && lit != null) {
                        val uid = Regex("UID (\\d+)").find(line)?.groupValues?.get(1) ?: line.hashCode().toString()
                        val at = Regex("INTERNALDATE \"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let {
                            runCatching { java.time.ZonedDateTime.parse(it.trim(), DATE).toInstant().toEpochMilli() }.getOrDefault(0L)
                        } ?: 0L
                        val raw = String(lit, Charsets.UTF_8)
                        val headEnd = Regex("\\r?\\n\\r?\\n").find(raw)?.range?.first ?: raw.length
                        val (subject, fromH) = MailParse.headers(raw.substring(0, headEnd))
                        val text = runCatching { MailParse.mime(raw) }.getOrDefault("")
                        mails += MailParse.Mail("im:$host:$user:$uid".hashCode().toString(), label,
                            fromH.substringBefore('<').trim().ifBlank { fromH }, subject, text.take(4000), at)
                        i += 2
                    } else i++
                }
                return mails
            }
        }

        internal fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        internal fun send(out: OutputStream, line: String) { out.write((line + "\r\n").toByteArray(Charsets.UTF_8)); out.flush() }

        internal fun lastOk(r: List<Any>) = (r.lastOrNull() as? String)?.split(' ')?.getOrNull(1)?.equals("OK", true) == true

        internal fun readLine(inp: BufferedInputStream): String {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = inp.read()
                if (c < 0) break
                if (c == '\n'.code) break
                if (c != '\r'.code) b.write(c)
            }
            return String(b.toByteArray(), Charsets.UTF_8)
        }

        /** Lines (String) and literals (ByteArray) up to and including the tagged status line. */
        internal fun readTagged(inp: BufferedInputStream, tag: String): List<Any> {
            val out = ArrayList<Any>()
            var guard = 0
            while (guard++ < 5000) {
                val line = readLine(inp)
                out += line
                val lit = Regex("\\{(\\d+)\\}$").find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (lit != null) {
                    val buf = ByteArray(lit); var got = 0
                    while (got < lit) { val r = inp.read(buf, got, lit - got); if (r < 0) break; got += r }
                    out += buf
                    continue
                }
                if (line.startsWith("$tag ")) break
                if (line.isEmpty() && inp.available() == 0 && guard > 4000) break
            }
            return out
        }
    }
}
