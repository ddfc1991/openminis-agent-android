package com.openminis.app.plus

import org.json.JSONObject

/**
 * [Mines-38.9.1] ONE identity model for every account record in Mines (pure part, unit-tested on the JVM).
 *
 * User 2026-10-16: 「一键关联只会手机号，不会邮箱登录」, 「有谷歌，有 x 很多种登录方式…账号之间记录也没打通」,
 * 「应该是用户有什么就登记什么」. So:
 *  - an [Ident] is anything the user signs in WITH: any phone, any number of mailboxes, Google / X / GitHub / Apple /
 *    Microsoft / 微信 / QQ / 支付宝 / Telegram / wallet accounts, user names, passkeys. Nothing is pre-filled: identities
 *    come from sign-ins, autofill saves, mail / SMS, 一键关联 results, or the user's own + (free-form, any type);
 *  - a [Rec] is one account ON a site: which identity, which [Method] (e.g. 「通过 Google 登录 · x@gmail.com」), which
 *    provider identity it hangs off ([Rec.via]), status, sources;
 *  - [parseOffers] reads what a login page offers (tabs, provider buttons, fields) and [choose] orders the attempts:
 *    (a) the method already recorded for this site, (b) a third-party provider the user is already signed into in
 *    Mines (one tap), (c) e-mail, (d) phone — every attempt falls through to the next instead of stopping;
 *  - [groups] builds the 账号关联 groups from the identities that exist (no fixed 手机号 / 邮箱 slots, empty types hidden;
 *    accounts signed in 「通过 Google」 sit under that Gmail).
 */
object IdentityCore {

    enum class IdType(val id: String, val label: String, val icon: String, val provider: Boolean, val host: String = "") {
        PHONE("phone", "手机号", "phone", false),
        EMAIL("email", "邮箱", "mail", false),
        GOOGLE("google", "Google", "google", true, "google.com"),
        X("x", "X", "x", true, "x.com"),
        GITHUB("github", "GitHub", "github", true, "github.com"),
        APPLE("apple", "Apple", "apple", true, "apple.com"),
        MICROSOFT("microsoft", "Microsoft", "microsoft", true, "microsoft.com"),
        WECHAT("wechat", "微信", "wechat", true, "weixin.qq.com"),
        QQ("qq", "QQ", "qq", true, "qq.com"),
        ALIPAY("alipay", "支付宝", "alipay", true, "alipay.com"),
        TELEGRAM("telegram", "Telegram", "telegram", true, "telegram.org"),
        WALLET("wallet", "钱包", "wallet", true, "metamask.io"),
        USERNAME("username", "用户名", "user", false),
        PASSKEY("passkey", "通行密钥", "key-round", false),
        OTHER("other", "其他", "user", false);

        companion object {
            fun of(id: String?): IdType? = values().firstOrNull { it.id == id }
            /** The types the user can pick from 「+ 添加」 (all of them, nothing assumed). */
            val addable get() = values().toList()
        }
    }

    enum class Method(val id: String, val label: String) {
        PHONE_SMS("phone_sms", "手机号 + 短信"), PHONE_PW("phone_pw", "手机号 + 密码"),
        EMAIL_CODE("email_code", "邮箱 + 验证码"), EMAIL_PW("email_pw", "邮箱 + 密码"),
        USER_PW("user_pw", "账号 + 密码"), OAUTH("oauth", "第三方登录"), PASSKEY("passkey", "通行密钥"),
        QR("qr", "扫码"), KEY("key", "密钥"), WEB("web", "网页登录"), UNKNOWN("", "");

        companion object { fun of(id: String?): Method = values().firstOrNull { it.id == id } ?: UNKNOWN }
    }

    data class Ident(val type: IdType, val value: String, val note: String = "", val source: String = "",
                     val signedIn: Boolean = false, val at: Long = 0L, val uses: Int = 0) {
        val key: String get() = keyOf(type, value)
    }

    const val LINKED = "已关联"
    const val USER = "需要你操作"
    const val EXPIRED = "过期了"
    /** [Mines-38.9.3] had an account, state not confirmed (never probed / no cookie seen / blip) — shown grey 「未检查」. */
    const val UNCHECKED = "未检查"

    data class Rec(
        val site: String, val name: String, val identity: String, val identType: IdType?, val method: Method,
        val provider: IdType? = null, val via: String = "", val status: String = LINKED,
        val sources: Set<String> = emptySet(), val hasPw: Boolean = false, val at: Long = 0L,
    ) { val key: String get() = recKey(site, identity) }

    // ---------------- identity normalisation ----------------

    private val EMAIL = Regex("^[^@\\s]+@([A-Za-z0-9-]+\\.)+[A-Za-z]{2,}$")
    private val PHONE = Regex("^\\+?[0-9][0-9 \\-()]{5,18}[0-9]$")
    private val WALLET = Regex("^0x[0-9a-fA-F]{40}$")

    fun digits(s: String) = s.filter { it.isDigit() }

    fun guessType(v: String): IdType {
        val t = v.trim()
        return when {
            t.isBlank() -> IdType.OTHER
            EMAIL.matches(t) -> IdType.EMAIL
            WALLET.matches(t) -> IdType.WALLET
            PHONE.matches(t) && digits(t).length >= 7 -> IdType.PHONE
            else -> IdType.USERNAME
        }
    }

    fun canon(type: IdType?, v: String): String {
        val t = v.trim()
        return when (type ?: guessType(t)) {
            IdType.PHONE -> digits(t).let { d -> if (d.length > 11 && d.startsWith("86")) d.takeLast(11) else d.takeLast(minOf(d.length, 11)) }
            else -> t.lowercase()
        }
    }

    fun keyOf(type: IdType, v: String) = type.id + ":" + canon(type, v)

    fun recKey(site: String, identity: String) = site.lowercase().removePrefix("www.") + "|" + (if (identity.isBlank()) "" else canon(null, identity))

    /** The group an identity belongs to: a provider account that IS an e-mail (Google = Gmail) joins that mailbox. */
    fun groupKey(type: IdType, v: String): String =
        if (type.provider && EMAIL.matches(v.trim())) "email:" + v.trim().lowercase() else keyOf(type, v)

    fun groupKeyOfIdentity(identity: String): String = groupKey(guessType(identity), identity)

    fun emailLabel(addr: String): String {
        val dom = addr.substringAfter('@', "").lowercase()
        return when {
            dom == "gmail.com" || dom == "googlemail.com" -> "Gmail"
            dom == "163.com" -> "163 邮箱"
            dom == "126.com" -> "126 邮箱"
            dom == "qq.com" -> "QQ 邮箱"
            dom.startsWith("outlook.") || dom.startsWith("hotmail.") || dom == "live.com" -> "Outlook"
            dom == "icloud.com" || dom == "me.com" -> "iCloud"
            else -> "邮箱"
        }
    }

    /** Plain words for how an account signs in: 「通过 Google 登录」, 「邮箱 + 验证码」… */
    fun methodText(r: Rec): String = when {
        r.method == Method.OAUTH && r.provider != null -> "通过 ${r.provider.label} 登录"
        r.method == Method.UNKNOWN -> ""
        else -> r.method.label
    }

    // ---------------- merge (one record per site + identity) ----------------

    private fun statusRank(s: String) = when (s) { LINKED -> 0; USER -> 1; UNCHECKED -> 2; EXPIRED -> 3; else -> 4 }

    /**
     * [Mines-38.9.3] user 2026-10-17: a row 「Matt from WarpStati.」 sat under Gmail — a welcome mail's From display name had
     * been taken as the site name. A site name is the catalog name / the registrable domain, never a person or a sentence.
     */
    fun looksLikeSenderName(name: String): Boolean {
        val n = name.trim()
        if (n.isBlank()) return false
        return Regex("(?i)\\b(from|at|team|support|no-?reply|the)\\b|\\bvia\\b|[<>@\"]|团队|客服").containsMatchIn(n) ||
            n.count { it == ' ' } >= 3 || n.endsWith(".") || n.endsWith("…")
    }

    /** Add or upgrade a record. A record with no identity merges into the site's single known record (and vice versa). */
    fun merge(list: List<Rec>, inc: Rec): List<Rec> {
        val site = inc.site.lowercase().removePrefix("www.")
        if (site.isBlank()) return list
        val same = list.indexOfFirst { it.key == inc.key }.takeIf { it >= 0 }
            ?: if (inc.identity.isBlank()) list.indexOfFirst { it.site.equals(site, true) }.takeIf { it >= 0 }
            else list.indexOfFirst { it.site.equals(site, true) && it.identity.isBlank() }.takeIf { it >= 0 }
        if (same == null) return listOf(inc.copy(site = site)) + list
        val old = list[same]
        val keepMethod = inc.method == Method.UNKNOWN || (inc.method == Method.WEB && old.method != Method.UNKNOWN)
        val m = old.copy(
            name = old.name.ifBlank { inc.name },
            identity = old.identity.ifBlank { inc.identity },
            identType = old.identType ?: inc.identType,
            method = if (keepMethod) old.method else inc.method,
            provider = if (keepMethod) old.provider else inc.provider ?: old.provider.takeIf { inc.method == Method.OAUTH },
            via = if (keepMethod) old.via else inc.via.ifBlank { if (inc.method == Method.OAUTH) old.via else "" },
            status = if (inc.at >= old.at || statusRank(inc.status) < statusRank(old.status)) inc.status.ifBlank { old.status } else old.status,
            sources = old.sources + inc.sources,
            hasPw = old.hasPw || inc.hasPw,
            at = maxOf(old.at, inc.at),
        )
        return list.toMutableList().also { it[same] = m }
    }

    fun mergeAll(list: List<Rec>, inc: List<Rec>): List<Rec> = inc.fold(list) { acc, r -> merge(acc, r) }

    /** Add an identity, or fill in what was missing (signed-in flag, note, source). Blank values are ignored. */
    fun mergeIdent(list: List<Ident>, inc: Ident): List<Ident> {
        if (inc.value.isBlank()) return list
        val i = list.indexOfFirst { it.key == inc.key }
        if (i < 0) return list + inc
        val o = list[i]
        return list.toMutableList().also { it[i] = o.copy(note = o.note.ifBlank { inc.note }, source = o.source.ifBlank { inc.source },
            signedIn = o.signedIn || inc.signedIn, at = maxOf(o.at, inc.at), uses = maxOf(o.uses, inc.uses)) }
    }

    // ---------------- what the login page offers ----------------

    data class Offer(val method: Method, val provider: IdType? = null, val label: String = "")

    private val PROVIDER_WORDS: List<Pair<IdType, Regex>> = listOf(
        IdType.GOOGLE to Regex("(?i)(google|谷歌)"),
        IdType.GITHUB to Regex("(?i)github"),
        IdType.MICROSOFT to Regex("(?i)(microsoft|outlook|微软)"),
        IdType.APPLE to Regex("(?i)(apple|苹果)"),
        IdType.X to Regex("(?i)(twitter|\\bx\\.com\\b|with x\\b|使用 ?x ?登录|^x$|推特)"),
        IdType.WECHAT to Regex("(微信|wechat|weixin)", RegexOption.IGNORE_CASE),
        IdType.QQ to Regex("(?i)(^qq|qq ?登录|qq ?login|with qq|使用 ?qq)"),
        IdType.ALIPAY to Regex("(支付宝|alipay)", RegexOption.IGNORE_CASE),
        IdType.TELEGRAM to Regex("(?i)telegram"),
        IdType.WALLET to Regex("(?i)(metamask|wallet ?connect|connect ?wallet|钱包|wallet)"),
    )

    fun providerOf(text: String): IdType? {
        val t = text.trim()
        if (t.isBlank() || t.length > 40) return null
        return PROVIDER_WORDS.firstOrNull { it.second.containsMatchIn(t) }?.first
    }

    private val EMAIL_TAB = Regex("(?i)(邮箱|e-?mail|电子邮件)")
    private val PHONE_TAB = Regex("(?i)(手机|短信|phone|mobile|sms)")
    private val PW_TAB = Regex("(?i)(密码登录|账号登录|账号密码|帐号密码|password)")
    private val CODE_WORDS = Regex("(?i)(验证码|获取验证码|发送验证码|send code|get code|one-time|otp|code)")

    /**
     * Reads [OFFERS_JS]'s JSON: {"tabs":[…], "buttons":[…], "tel":b, "email":b, "user":b, "userHint":"…", "pw":b,
     * "code":b, "sendCode":b, "qr":b, "passkey":b}. Returns every way the page lets the user in (the tab / button
     * text to press is in [Offer.label]).
     */
    fun parseOffers(json: String): List<Offer> {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() } }.orEmpty()
        val tabs = arr("tabs"); val buttons = arr("buttons")
        val out = ArrayList<Offer>()
        val seenProv = HashSet<IdType>()
        for (b in buttons + tabs) {
            val p = providerOf(b) ?: continue
            // "Continue with Google", "使用微信登录", "GitHub" … but not a plain 「邮箱」 tab
            if (p == IdType.MICROSOFT && EMAIL_TAB.containsMatchIn(b) && !Regex("(?i)microsoft|微软").containsMatchIn(b)) continue
            if (seenProv.add(p)) out += Offer(Method.OAUTH, p, b)
        }
        val userHint = o.optString("userHint")
        val emailTab = tabs.firstOrNull { EMAIL_TAB.containsMatchIn(it) && providerOf(it) == null }
            ?: buttons.firstOrNull { it.length <= 12 && Regex("(邮箱登录|使用邮箱|用邮箱|邮箱验证码|Email|E-mail|电子邮件)", RegexOption.IGNORE_CASE).containsMatchIn(it) && providerOf(it) == null }
        val phoneTab = tabs.firstOrNull { PHONE_TAB.containsMatchIn(it) } ?: buttons.firstOrNull { it.length <= 12 && Regex("(手机号登录|短信登录|验证码登录|手机登录|Phone)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val pwTab = tabs.firstOrNull { PW_TAB.containsMatchIn(it) } ?: buttons.firstOrNull { it.length <= 10 && PW_TAB.containsMatchIn(it) }
        val userField = o.optBoolean("user")
        val emailAvail = o.optBoolean("email") || emailTab != null || (userField && EMAIL_TAB.containsMatchIn(userHint))
        val phoneAvail = o.optBoolean("tel") || phoneTab != null || (userField && PHONE_TAB.containsMatchIn(userHint))
        val pwAvail = o.optBoolean("pw") || pwTab != null
        val codeAvail = o.optBoolean("code") || o.optBoolean("sendCode") || (emailTab?.let { CODE_WORDS.containsMatchIn(it) } ?: false) ||
            (phoneTab?.let { CODE_WORDS.containsMatchIn(it) || Regex("短信").containsMatchIn(it) } ?: false)
        val stepwise = !pwAvail && !codeAvail       // 「输入邮箱 → 下一步」: code or password comes on the next page
        if (emailAvail) {
            if (codeAvail || stepwise) out += Offer(Method.EMAIL_CODE, null, emailTab.orEmpty())
            if (pwAvail || stepwise) out += Offer(Method.EMAIL_PW, null, if (o.optBoolean("email") || emailTab == null) pwTab.orEmpty() else emailTab)
        }
        if (phoneAvail) {
            if (codeAvail || stepwise || phoneTab != null) out += Offer(Method.PHONE_SMS, null, phoneTab.orEmpty())
            if (pwAvail) out += Offer(Method.PHONE_PW, null, pwTab.orEmpty())
        }
        if (userField && pwAvail && !emailAvail && !phoneAvail) out += Offer(Method.USER_PW, null, pwTab.orEmpty())
        else if (userField && pwAvail) out += Offer(Method.USER_PW, null, pwTab.orEmpty())
        if (o.optBoolean("passkey")) out += Offer(Method.PASSKEY)
        if (o.optBoolean("qr")) out += Offer(Method.QR)
        return out
    }

    // ---------------- choosing the attempts ----------------

    data class Plan(val method: Method, val ident: Ident?, val provider: IdType? = null, val click: String = "") {
        val text: String get() = when (method) {
            Method.OAUTH -> "用 ${provider?.label ?: ""} 登录" + (ident?.value?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            Method.EMAIL_CODE, Method.EMAIL_PW -> method.label + " · " + (ident?.value ?: "")
            Method.PHONE_SMS, Method.PHONE_PW -> method.label
            else -> method.label
        }
    }

    /** Provider order when several signed-in ones are on the page (one-tap ones first). */
    private val PROVIDER_ORDER = listOf(IdType.GOOGLE, IdType.GITHUB, IdType.MICROSOFT, IdType.X, IdType.APPLE,
        IdType.WECHAT, IdType.QQ, IdType.ALIPAY, IdType.TELEGRAM, IdType.WALLET)

    /**
     * The attempts for one login page, in order: (a) the recorded method of this site, (b) every saved password
     * (mailbox / phone / user name), (c) third-party providers the user is signed into in Mines, (d) a mail code, then
     * SMS, then providers the user has but is not signed into (the provider page asks them). Nothing the page does not offer is tried.
     */
    fun choose(offers: List<Offer>, recorded: Rec?, idents: List<Ident>, hasPw: (String) -> Boolean): List<Plan> {
        val out = ArrayList<Plan>()
        fun offer(m: Method, p: IdType? = null) = offers.firstOrNull { it.method == m && (p == null || it.provider == p) }
        fun add(p: Plan) { if (out.none { it.method == p.method && it.provider == p.provider && it.ident?.key == p.ident?.key }) out += p }
        val byUse = idents.sortedByDescending { it.uses }
        // (a) recorded
        if (recorded != null) {
            val rm = recorded.method
            val rid = recorded.identity.takeIf { it.isNotBlank() }?.let { v -> idents.firstOrNull { canon(it.type, it.value) == canon(null, v) } ?: Ident(guessType(v), v) }
            when (rm) {
                Method.OAUTH -> recorded.provider?.let { p -> offer(Method.OAUTH, p)?.let { o ->
                    add(Plan(Method.OAUTH, idents.firstOrNull { it.type == p && it.signedIn } ?: idents.firstOrNull { it.type == p }
                        ?: recorded.via.takeIf { it.isNotBlank() }?.let { Ident(p, it.substringAfter(':')) }, p, o.label)) } }
                Method.UNKNOWN, Method.WEB, Method.QR, Method.KEY -> {}
                else -> offer(rm)?.let { o -> if (rid != null || rm == Method.USER_PW) add(Plan(rm, rid, null, o.label)) }
            }
        }
        // [Mines-38.9.5 i] user 2026-10-17 19:33 order: kept session (the recorded way, above) → saved password (vault fills
        // it) → Google / GitHub signed in in Mines → a code read from the user's own mailboxes / SMS
        val mails = byUse.filter { it.type == IdType.EMAIL } +
            byUse.filter { it.type.provider && EMAIL.matches(it.value.trim()) && byUse.none { e -> e.type == IdType.EMAIL && canon(e.type, e.value) == it.value.trim().lowercase() } }
                .map { Ident(IdType.EMAIL, it.value.trim(), source = it.source, uses = it.uses) }
        val phones = byUse.filter { it.type == IdType.PHONE }
        // (b) saved passwords
        for (e in mails) offer(Method.EMAIL_PW)?.let { o -> if (hasPw(e.value)) add(Plan(Method.EMAIL_PW, e, null, o.label)) }
        for (ph in phones) offer(Method.PHONE_PW)?.let { o -> if (hasPw(ph.value)) add(Plan(Method.PHONE_PW, ph, null, o.label)) }
        offer(Method.USER_PW)?.let { o ->
            byUse.filter { it.type == IdType.USERNAME && hasPw(it.value) }.forEach { add(Plan(Method.USER_PW, it, null, o.label)) }
            if (hasPw("")) add(Plan(Method.USER_PW, null, null, o.label))
        }
        // (c) providers the user is signed into in Mines
        for (p in PROVIDER_ORDER) {
            val o = offer(Method.OAUTH, p) ?: continue
            val id = byUse.firstOrNull { it.type == p && it.signedIn } ?: continue
            add(Plan(Method.OAUTH, id, p, o.label))
        }
        // (d) codes: e-mail first (read from the linked mailbox), then SMS
        for (e in mails) offer(Method.EMAIL_CODE)?.let { o -> add(Plan(Method.EMAIL_CODE, e, null, o.label)) }
        for (ph in phones) offer(Method.PHONE_SMS)?.let { o -> add(Plan(Method.PHONE_SMS, ph, null, o.label)) }
        // providers the user has but is not signed into here (one step on the provider's page)
        for (p in PROVIDER_ORDER) {
            val o = offer(Method.OAUTH, p) ?: continue
            val id = byUse.firstOrNull { it.type == p } ?: continue
            add(Plan(Method.OAUTH, id, p, o.label))
        }
        return out
    }

    /** Page text that says this way in is not possible (→ try the next plan). */
    val REFUSED = Regex("(?i)(不支持|未注册|没有注册|格式不正确|格式错误|号码有误|请输入正确|无效|not supported|isn't supported|invalid|not registered|no account|couldn't find|找不到.{0,6}账号|该手机号|仅支持)")

    // ---------------- groups for 账号关联 ----------------

    data class Group(val key: String, val type: IdType, val title: String, val value: String,
                     val badges: Set<IdType>, val recs: List<Rec>, val ident: Ident?)

    /**
     * One group per identity that has something in it (user-added identities show even when empty, so a new + appears
     * at once). A record signed in through a provider sits under that provider's identity; records whose identity is
     * not known go to 「还不知道用的哪个账号」.
     */
    fun groups(idents: List<Ident>, recs: List<Rec>): List<Group> {
        data class G(var type: IdType, var value: String, val badges: MutableSet<IdType>, val recs: MutableList<Rec>, var ident: Ident?)
        val gs = LinkedHashMap<String, G>()
        for (i in idents) {
            val k = groupKey(i.type, i.value)
            val g = gs.getOrPut(k) { G(if (i.type.provider && EMAIL.matches(i.value.trim())) IdType.EMAIL else i.type, i.value.trim(), mutableSetOf(), mutableListOf(), i) }
            if (i.type.provider) g.badges += i.type
            if (g.ident == null || (g.ident?.type?.provider == true && !i.type.provider)) g.ident = i
        }
        for (r in recs) {
            val k = when {
                r.via.isNotBlank() -> groupKey(IdType.of(r.via.substringBefore(':')) ?: guessType(r.via.substringAfter(':')), r.via.substringAfter(':'))
                r.identity.isNotBlank() -> groupKey(r.identType ?: guessType(r.identity), r.identity)
                else -> "unknown"
            }
            val g = gs.getOrPut(k) {
                if (k == "unknown") G(IdType.OTHER, "", mutableSetOf(), mutableListOf(), null)
                else {
                    val v = if (r.via.isNotBlank()) r.via.substringAfter(':') else r.identity
                    val t = if (r.via.isNotBlank()) (IdType.of(r.via.substringBefore(':')) ?: guessType(v)).let { if (it.provider && EMAIL.matches(v)) IdType.EMAIL else it }
                        else (r.identType ?: guessType(v))
                    G(t, v, mutableSetOf(), mutableListOf(), null)
                }
            }
            if (r.method == Method.OAUTH && r.provider != null) g.badges += r.provider
            g.recs += r
        }
        return gs.entries.filter { (_, g) -> g.recs.isNotEmpty() || g.ident?.source == "手动添加" }
            .map { (k, g) ->
                val title = when {
                    k == "unknown" -> "还不知道用的哪个账号"
                    g.type == IdType.EMAIL -> emailLabel(g.value)
                    else -> g.type.label
                }
                Group(k, g.type, title, g.value, g.badges, g.recs.sortedBy { statusRank(it.status) }, g.ident)
            }
            .sortedWith(compareBy<Group> { if (it.key == "unknown") 1 else 0 }.thenByDescending { it.recs.size }.thenBy { it.type.ordinal })
    }

    /** Sites signed in through a provider identity (its detail page). */
    fun linkedVia(recs: List<Rec>, provider: IdType, value: String): List<Rec> =
        recs.filter { it.method == Method.OAUTH && it.provider == provider && (it.via.isBlank() || it.via.substringAfter(':').equals(value.trim(), true)) }
}
