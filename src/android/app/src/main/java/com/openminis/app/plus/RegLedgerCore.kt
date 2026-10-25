package com.openminis.app.plus

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.15 register_auto] pure side of the 临时邮箱台账 (user 2026-10-25 23:29 「临时邮箱注册我觉得这个也需要台账」),
 * the per-row 自动延时 / 探针保活 switches (23:30) and the one-off 整理临时邮箱账号 (23:32 「先整理好，没有的补全，补全不了的删掉」).
 *
 * - [Rec]: one sign-up made with a temporary box: box address (its Mail.tm password / token live in the vault), platform,
 *   sign-up URL (custom ones too), status (进行中 / 已验证 / 卡在人机 / 卡在手机 / 卡在付款 / 未完成), times. The platform
 *   account's own password is the 台账 (SavedLogins) entry for host + box.
 * - [Ka]: keep-alive settings of one row (default both off) + what the last 续期 / 探活 did.
 * - [tidy]: the clean-up plan (group by box, dedupe, fill what the box's mail tells, delete what can't be filled when the
 *   box is dead). Only temporary boxes; gmail / 163 / qq … are never touched. Deletions go to 最近删除 for 7 days.
 * No Android, unit-tested (RegLedgerCoreTest).
 */
object RegLedgerCore {
    const val TRASH_KEEP_MS = 7L * 24 * 3600_000L
    val HOURS = listOf(6, 12, 24)

    enum class RSt(val zh: String, val done: Boolean, val ok: Boolean) {
        RUNNING("进行中", false, false), VERIFIED("已验证", true, true),
        CAPTCHA("卡在人机验证", true, false), PHONE("卡在手机号", true, false), PAYMENT("卡在付款", true, false),
        PENDING("未完成", true, false);
        /** the pill: 已验证 / 进行中 / 未完成 (the 卡在… reason is the line under it) */
        val pill: String get() = when (this) { VERIFIED -> "已验证"; RUNNING -> "进行中"; else -> "未完成" }
    }

    data class Rec(
        val box: String, val host: String, val platform: String = "", val url: String = "", val provider: String = "Mail.tm",
        val st: RSt = RSt.RUNNING, val why: String = "", val createdAt: Long = 0L, val updatedAt: Long = 0L,
    ) { val id: String get() = box.lowercase() + "|" + host.lowercase() }

    // ── which boxes are temporary ──

    private val LONG = Regex("(?i)@(gmail|googlemail|163|126|yeah|qq|foxmail|outlook|hotmail|live|msn|icloud|me|yahoo|proton|protonmail|sina|sohu|aliyun|139)\\.")

    /** temp boxes all start with the same prefix (mines…@domain): keep the last 4 of the local part so two boxes never look the same */
    fun maskBox(a: String): String {
        val at = a.indexOf('@'); if (at <= 0) return a
        val u = a.substring(0, at)
        return if (u.length >= 10) u.take(4) + "••" + u.takeLast(4) + a.substring(at) else Inbox3912Core.maskMail(a)
    }

    /** a temporary box: one Mines made / knows the password of, or on a temp-mail domain; long-term providers never are */
    fun isTemp(addr: String, tempDomains: Set<String>, known: Set<String>): Boolean {
        val a = addr.trim().lowercase()
        if (!a.contains('@') || LONG.containsMatchIn(a)) return false
        return a in known || a.substringAfter('@') in tempDomains
    }

    // ── records ──

    fun upsert(list: List<Rec>, r: Rec): List<Rec> {
        val old = list.firstOrNull { it.id == r.id }
        val m = if (old == null) r else r.copy(
            platform = r.platform.ifBlank { old.platform }, url = r.url.ifBlank { old.url },
            createdAt = if (old.createdAt > 0) old.createdAt else r.createdAt)
        return list.filterNot { it.id == r.id } + m
    }

    /** a restart cuts a sign-up that was still running */
    fun afterRestart(list: List<Rec>, now: Long): List<Rec> =
        list.map { if (it.st == RSt.RUNNING) it.copy(st = RSt.PENDING, why = "Mines 重启，中断了", updatedAt = now) else it }

    /** 「按邮箱」: temporary boxes, newest first, each with its platforms */
    fun byBox(list: List<Rec>): List<Pair<String, List<Rec>>> =
        list.groupBy { it.box.lowercase() }.entries.sortedByDescending { e -> e.value.maxOf { it.updatedAt.coerceAtLeast(it.createdAt) } }
            .map { it.key to it.value.sortedBy { r -> r.platform.ifBlank { r.host } } }

    fun recJson(r: Rec): JSONObject = JSONObject().put("b", r.box).put("h", r.host).put("p", r.platform).put("u", r.url).put("v", r.provider)
        .put("s", r.st.name).put("w", r.why).put("c", r.createdAt).put("t", r.updatedAt)
    fun recOf(o: JSONObject): Rec = Rec(o.getString("b"), o.optString("h"), o.optString("p"), o.optString("u"), o.optString("v", "Mail.tm"),
        runCatching { RSt.valueOf(o.optString("s")) }.getOrDefault(RSt.PENDING), o.optString("w"), o.optLong("c"), o.optLong("t"))
    fun recsJson(l: List<Rec>): String = JSONArray().apply { l.forEach { put(recJson(it)) } }.toString()
    fun recsOf(s: String?): List<Rec> = runCatching { JSONArray(s ?: "[]").let { a -> (0 until a.length()).map { recOf(a.getJSONObject(it)) } } }.getOrDefault(emptyList())

    // ── 自动延时 / 探针保活 ──

    data class Ka(
        val key: String, val extend: Boolean = false, val probe: Boolean = false, val everyH: Int = 12,
        val lastProbeAt: Long = 0L, val probeOk: Boolean = true, val probeNote: String = "",
        val lastExtendAt: Long = 0L, val extendNote: String = "",
    )

    /** row key: 「acct|host|account」 for a 台账 account, 「box|address」 for a temporary box */
    fun acctKey(host: String, account: String) = "acct|" + host.lowercase() + "|" + account.lowercase()
    fun boxKey(addr: String) = "box|" + addr.lowercase()

    fun nextProbe(k: Ka): Long = if (!k.probe) 0L else if (k.lastProbeAt == 0L) 0L else k.lastProbeAt + k.everyH * 3600_000L
    /** 续期 runs once a day (most free hosts give days; earlier than the deadline is always safe) */
    fun nextExtend(k: Ka): Long = if (!k.extend) 0L else if (k.lastExtendAt == 0L) 0L else k.lastExtendAt + 24 * 3600_000L
    fun probeDue(k: Ka, now: Long) = k.probe && now >= nextProbe(k)
    fun extendDue(k: Ka, now: Long) = k.extend && now >= nextExtend(k)
    /** the worker is needed while any switch is on */
    fun anyOn(l: Collection<Ka>) = l.any { it.extend || it.probe }

    fun cycleHours(h: Int): Int = HOURS[(HOURS.indexOf(h).coerceAtLeast(0) + 1) % HOURS.size]

    /** 「下次续期 10-26 09:00 · 上次探活 10-25 23:40 ✓」 (empty when both off) */
    fun statusLine(k: Ka?, fmt: (Long) -> String): String {
        if (k == null || (!k.extend && !k.probe)) return ""
        val a = if (k.extend) "下次续期 " + (if (k.lastExtendAt == 0L) "马上" else fmt(nextExtend(k))) + (if (k.extendNote.isNotBlank()) "（${k.extendNote}）" else "") else ""
        val b = if (k.probe) "上次探活 " + (if (k.lastProbeAt == 0L) "还没有" else fmt(k.lastProbeAt) + (if (k.probeOk) " ✓" else " ✗ " + k.probeNote)) else ""
        return listOf(a, b).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun kaJson(l: Collection<Ka>): String = JSONArray().apply { l.forEach { k -> put(JSONObject().put("k", k.key).put("e", k.extend).put("p", k.probe).put("h", k.everyH)
        .put("pa", k.lastProbeAt).put("po", k.probeOk).put("pn", k.probeNote).put("ea", k.lastExtendAt).put("en", k.extendNote)) } }.toString()
    fun kaOf(s: String?): List<Ka> = runCatching { JSONArray(s ?: "[]").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
        Ka(o.getString("k"), o.optBoolean("e"), o.optBoolean("p"), o.optInt("h", 12).let { if (it in HOURS) it else 12 }, o.optLong("pa"), o.optBoolean("po", true),
            o.optString("pn"), o.optLong("ea"), o.optString("en")) } } } }.getOrDefault(emptyList())

    /** 续期 page: the free 「Renew / 续期 / 延长」 button; never anything that costs money */
    const val RENEW_JS = """(function(){try{
var vis=function(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4;};
var b=((document.body&&document.body.innerText)||'').toLowerCase();
if(document.querySelector('input[autocomplete^="cc-"],input[name*="card" i]'))return 'R:pay';
var btns=[].slice.call(document.querySelectorAll('button,input[type=submit],a,[role=button]')).filter(function(x){return vis(x)&&!x.disabled;});
var bad=/pay|upgrade|buy|purchase|checkout|subscribe|付款|支付|购买|升级|充值|订阅/i;
var good=/renew|extend|keep ?alive|add time|续期|延长|延期|续命|保活/i;
var t=btns.find(function(x){var s=(x.innerText||x.value||'');return good.test(s)&&!bad.test(s);});
if(!t)return 'R:none';t.click();return 'R:clicked';
}catch(x){return 'R:none';}})()"""

    // ── 整理临时邮箱账号 ──

    /** a 台账 entry (SavedLogins + 备注) that belongs to a box */
    data class Entry(val host: String, val account: String, val hasPw: Boolean, val note: String, val name: String = "")
    /** what the box's inbox says (null = could not sign in: the box is gone or its password is not known) */
    data class Mail(val from: String, val subject: String, val text: String, val at: Long)

    data class Plan(
        val keep: List<Rec>,                 // the box's records after the clean-up (filled / deduped)
        val trash: List<Entry>,              // 台账 entries to move to 最近删除 (with their record)
        val verify: List<Pair<Rec, String>>, // unverified sign-ups whose mail still has a link: open it
        val filled: Int,                     // records that got a field filled or became 已验证
    )

    private fun brandOf(m: Mail): String = Inbox3912Core.siteOf(m.from, m.subject).let { if (it == "未知来源") "" else it }

    /**
     * One temporary [box]: its 台账 [entries] + existing [recs] + its [mails] (null = dead) → [Plan].
     * Fill: platform from the sender / subject, URL from the host, time from the oldest mail, 已验证 from a verified 备注
     * or a 「verified / welcome」 mail; an unverified one with a link in its mail is verified by opening the link.
     * Delete only when the box is dead AND the platform or the account password can not be had.
     */
    fun tidy(box: String, entries: List<Entry>, recs: List<Rec>, mails: List<Mail>?, now: Long): Plan {
        val alive = mails != null
        val hosts = (entries.map { it.host.lowercase() } + recs.map { it.host.lowercase() }).filter { it.isNotBlank() }.distinct()
        val keep = ArrayList<Rec>(); val trash = ArrayList<Entry>(); val verify = ArrayList<Pair<Rec, String>>()
        var filled = 0
        for (h in hosts.ifEmpty { listOf("") }) {
            val es = entries.filter { it.host.equals(h, true) }
            val rs = recs.filter { it.host.equals(h, true) }.sortedByDescending { it.updatedAt }
            val e = es.firstOrNull { it.hasPw } ?: es.firstOrNull()
            val r0 = rs.firstOrNull() ?: Rec(box, h, createdAt = 0L, updatedAt = now, st = RSt.PENDING)
            val base = h.split('.').let { p -> if (p.size >= 2) p.takeLast(2).joinToString(".") else h }
            val ms = mails.orEmpty().filter { m ->
                h.isBlank() || m.from.lowercase().contains(base.substringBefore('.')) || m.text.lowercase().contains(base) || m.subject.lowercase().contains(base.substringBefore('.'))
            }.ifEmpty { if (hosts.size == 1) mails.orEmpty() else emptyList() }
            val brand = ms.map { brandOf(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            var r = r0
            if (r.platform.isBlank()) { val p = e?.name?.takeIf { it.isNotBlank() && !it.contains('.') } ?: brand.ifBlank { if (h.isNotBlank()) RegisterAutoCore.nameFor("https://$h/") else "" }; if (p.isNotBlank()) r = r.copy(platform = p) }
            if (r.url.isBlank() && h.isNotBlank()) r = r.copy(url = "https://$h/")
            if (r.createdAt == 0L) ms.minOfOrNull { it.at }?.takeIf { it > 0 }?.let { r = r.copy(createdAt = it) }
            val noteOk = e?.note?.startsWith("注册于") == true
            val mailOk = ms.any { Regex("(?i)(verified|confirmed|activated|welcome|验证成功|已激活|注册成功)").containsMatchIn(it.subject) }
            if (r.st != RSt.VERIFIED && (noteOk || mailOk)) r = r.copy(st = RSt.VERIFIED, why = "")
            if (r.st != RSt.VERIFIED && alive) {
                ms.sortedByDescending { it.at }.firstNotNullOfOrNull { RegisterAutoCore.pickLink(it.text, h.ifBlank { "x" }) }?.let { verify += r to it }
            }
            val pwMissing = e == null || !e.hasPw
            val platformMissing = r.platform.isBlank() && h.isBlank()
            if (!alive && (pwMissing || platformMissing)) { es.forEach { trash += it }; if (es.isEmpty()) trash += Entry(h, box, false, "", r.platform); continue }
            if (alive && pwMissing && r.st != RSt.VERIFIED && r.why.isBlank()) r = r.copy(why = "缺账号密码")
            if (r != r0 || rs.isEmpty()) filled++
            keep += r.copy(box = box, updatedAt = if (r != r0) now else r.updatedAt)
        }
        return Plan(keep, trash, verify, filled)
    }

    data class Summary(val total: Int, val filled: Int, val deleted: Int) {
        val line: String get() = "整理 $total 个 · 补全 $filled · 删除 $deleted ›"
    }

    // ── 最近删除 (7 days) ──

    data class Trashed(val id: String, val rec: Rec, val host: String, val account: String, val name: String, val at: Long)

    fun purge(l: List<Trashed>, now: Long): List<Trashed> = l.filter { now - it.at < TRASH_KEEP_MS }
    fun daysLeft(t: Trashed, now: Long): Int = (((t.at + TRASH_KEEP_MS) - now + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(0)

    fun trashJson(l: List<Trashed>): String = JSONArray().apply { l.forEach { t -> put(JSONObject().put("id", t.id).put("r", recJson(t.rec)).put("h", t.host).put("a", t.account).put("n", t.name).put("at", t.at)) } }.toString()
    fun trashOf(s: String?): List<Trashed> = runCatching { JSONArray(s ?: "[]").let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
        Trashed(o.getString("id"), recOf(o.getJSONObject("r")), o.optString("h"), o.optString("a"), o.optString("n"), o.optLong("at")) } } } }.getOrDefault(emptyList())
}
