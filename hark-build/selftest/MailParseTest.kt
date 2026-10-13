import com.openminis.app.plus.MailParse

var fails = 0
fun eq(name: String, got: Any?, want: Any?) { if (got != want) { fails++; println("FAIL $name: got=$got want=$want") } else println("ok   $name") }

fun main() {
    val now = System.currentTimeMillis()
    // codes
    eq("cn sms", MailParse.extractCode("【阿里云】您的验证码为 482913，5分钟内有效。"), "482913")
    eq("en google", MailParse.extractCode("G-731205 is your Google verification code."), "731205")
    eq("en github", MailParse.extractCode("Your GitHub launch code\nHere is your GitHub launch code: 59103842"), "59103842")
    eq("spaced", MailParse.extractCode("Your verification code is 482 913"), "482913")
    eq("year skip", MailParse.extractCode("© 2026 Supabase Inc. Your one-time code: 774201"), "774201")
    eq("amount skip", MailParse.extractCode("您已消费¥1288，验证码 6612"), "6612")
    eq("no keyword", MailParse.extractCode("Order 123456 shipped"), null)
    eq("2026年", MailParse.extractCode("2026年10月13日 动态码：3391"), "3391")
    eq("time skip", MailParse.extractCode("verification code sent at 16:20, code 902114"), "902114")
    eq("supabase magic", MailParse.extractCode("Confirm your signup\nEnter the code: 381920\nThis code expires in 1 hour"), "381920")
    // atom
    val atom = """<?xml version="1.0" encoding="UTF-8"?><feed version="0.3" xmlns="http://purl.org/atom/ns#"><title>Gmail - Inbox for den@gmail.com</title>
<fullcount>2</fullcount><entry><title>Your Cloudflare verification code</title><summary>Use code 640913 to finish signing in. This code expires in 10 minutes.</summary>
<link rel="alternate" href="https://mail.google.com/mail?account_id=den@gmail.com&amp;message_id=1&amp;view=conv" type="text/html" /><modified>2026-10-13T08:21:05Z</modified><issued>2026-10-13T08:21:05Z</issued><id>tag:gmail.google.com,2004:1</id><author><name>Cloudflare</name><email>noreply@notify.cloudflare.com</email></author></entry>
<entry><title>Weekly digest</title><summary>Top stories 2025</summary><issued>2026-10-12T08:00:00Z</issued><id>tag:2</id><author><name>News</name></author></entry></feed>"""
    val mails = MailParse.atom(atom)!!
    eq("atom count", mails.size, 2)
    eq("atom from", mails[0].from, "Cloudflare")
    eq("atom at", mails[0].at, java.time.Instant.parse("2026-10-13T08:21:05Z").toEpochMilli())
    eq("atom link", mails[0].url?.contains("&message_id=1"), true)
    eq("atom code since", MailParse.codeFrom(mails, java.time.Instant.parse("2026-10-13T08:20:00Z").toEpochMilli())?.first, "640913")
    eq("atom code too old", MailParse.codeFrom(mails, java.time.Instant.parse("2026-10-13T08:30:00Z").toEpochMilli()), null)
    eq("atom not feed", MailParse.atom("<html><body>Sign in</body></html>"), null)
    // mime
    val b64 = java.util.Base64.getEncoder().encodeToString("<html><body><p>您的验证码是 <b>551203</b></p></body></html>".toByteArray(charset("GB18030")))
    val msg = "Subject: =?gbk?B?" + java.util.Base64.getEncoder().encodeToString("网易邮箱验证".toByteArray(charset("GB18030"))) + "?=\r\nFrom: \"163\" <a@163.com>\r\nContent-Type: multipart/alternative; boundary=\"XX\"\r\n\r\n--XX\r\nContent-Type: text/html; charset=gbk\r\nContent-Transfer-Encoding: base64\r\n\r\n$b64\r\n--XX--\r\n"
    val (subj, from) = MailParse.headers(msg.substringBefore("\r\n\r\n"))
    eq("mime subject", subj, "网易邮箱验证")
    eq("mime from", from.contains("163"), true)
    val body = MailParse.mime(msg)
    eq("mime body code", MailParse.extractCode(body), "551203")
    eq("qp", MailParse.qp("Your code =3D 77=\r\n8812 verification"), "Your code = 778812 verification")
    eq("words utf8 q", MailParse.decodeWords("=?UTF-8?Q?Code_=E9=AA=8C=E8=AF=81?="), "Code 验证")
    // github
    val gh = """<ul><li class="notifications-list-item js-notification" ><a class="notification-list-item-link no-underline" href="/ddfc1991/openminis-agent-android/pull/12">x</a>
<p class="markdown-title text-normal">Fix login cookies</p><span>review requested</span></li>
<li class="notifications-list-item"><a href="/a/b/issues/3" class="notification-list-item-link">y</a><p class="markdown-title">Crash &amp; burn</p> mention</li></ul>"""
    val notes = MailParse.githubNotifications(gh)
    eq("gh count", notes.size, 2)
    eq("gh repo", notes[0].repo, "ddfc1991/openminis-agent-android")
    eq("gh title2", notes[1].title, "Crash & burn")
    eq("gh reason", notes[0].reason, "review requested")
    // balance
    eq("bal cn", MailParse.balance("账户概览\n可用余额\n¥ 12.34\n充值"), "¥12.34")
    eq("bal en", MailParse.balance("Credits\n$4.87\nBuy credits"), "$4.87")
    eq("bal yuan", MailParse.balance("余额：88.00 元"), "¥88.00")
    eq("bal none", MailParse.balance("Welcome back"), null)
    // cookies
    eq("cookie name", MailParse.cookieHas("_ga=1; logged_in=yes; user_session=abc", listOf("logged_in=yes", "user_session")), true)
    eq("cookie pair no", MailParse.cookieHas("logged_in=no", listOf("logged_in=yes")), false)
    eq("cookie SID not HSID", MailParse.cookieHas("HSID=x; NID=1", listOf("SID")), false)
    eq("cookie SID", MailParse.cookieHas("HSID=x; SID=abc", listOf("SID")), true)
    eq("cookie empty val", MailParse.cookieHas("auth_token=", listOf("auth_token")), false)
    eq("acct gh", MailParse.accountFromCookies("github", "dotcom_user=ddfc1991; x=1"), "ddfc1991")
    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    if (fails > 0) kotlin.system.exitProcess(1)
}
