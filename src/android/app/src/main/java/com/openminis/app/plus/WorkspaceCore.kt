package com.openminis.app.plus

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.5 i] user 2026-10-17 16:55 / 16:59 / 17:44: a server or cloud browser the agent obtains (free VPS, trial,
 * remote desktop) registers itself under 工作位置 — no manual step, nothing forgotten — with provider, account used,
 * 到期 (countdown, grey when over), 限制, 费用, IP 归属地 (country / region / city + ASN), where its credential sits (a vault
 * reference, never the secret), one 主设备 used by default, and what runs on each box (services / ports / containers,
 * read by a read-only ps / ss / docker / systemctl probe). Pure part, unit-tested in WorkspaceCoreTest.
 */
object WorkspaceCore {
    enum class Kind(val id: String, val label: String) {
        SERVER("server", "服务器"), CLOUD_BROWSER("cloud_browser", "云浏览器"), DESKTOP("desktop", "远程桌面");
        companion object { fun of(s: String?): Kind = values().firstOrNull { it.id == s?.lowercase() || it.label == s } ?: SERVER }
    }

    data class Meta(
        val id: String,                 // VpsServer.id for servers, own id for browsers / desktops
        val kind: Kind = Kind.SERVER,
        val name: String = "",
        val host: String = "",          // host / IP / URL
        val provider: String = "",
        val account: String = "",
        val expiresAt: Long = 0L,       // 0 = no end
        val limits: String = "",
        val cost: String = "",          // "免费" / "¥0（试用）" / "$5/月"
        val vaultRef: String = "",      // id of the vault entry holding the credential (never the secret)
        val sourceUrl: String = "",
        val geo: String = "",           // 「日本 东京 · AS9370 Sakura」
        val running: List<String> = emptyList(),
        val runningAt: Long = 0L,
        val notes: List<String> = emptyList(),   // what the agent deployed / the user wrote (「:8090 下载服务」)
        val addedBy: String = "agent",
        val addedAt: Long = 0L,
    )

    // ---------------- expiry ----------------

    private const val DAY = 24 * 3_600_000L

    fun expired(m: Meta, now: Long): Boolean = m.expiresAt in 1..now

    /** 「还剩 3 天」 / 「还剩 5 小时」 / 「今天到期」 / 「已过期 2 天」 / "" (no end). */
    fun expiryText(m: Meta, now: Long): String {
        if (m.expiresAt <= 0L) return ""
        val d = m.expiresAt - now
        return when {
            d <= 0 -> { val ago = (-d) / DAY; if (ago >= 1) "已过期 $ago 天" else "已过期" }
            d < 3_600_000L -> "不到 1 小时到期"
            d < DAY -> "还剩 ${d / 3_600_000L} 小时"
            else -> "还剩 ${(d + DAY - 1) / DAY} 天"
        }
    }

    /** Reminder points before the end: 3 days, 1 day, 2 hours. Returns which one is due now (null = none). */
    fun reminderDue(m: Meta, now: Long, alreadySent: Set<String>): String? {
        if (m.expiresAt <= 0L || expired(m, now)) return null
        val left = m.expiresAt - now
        return listOf("3d" to 3 * DAY, "1d" to DAY, "2h" to 2 * 3_600_000L)
            .lastOrNull { (k, at) -> left <= at && k !in alreadySent }?.first
    }

    /** "2026-11-01" / "2026-11-01 18:00" / "7d" / "30 days" / epoch ms → epoch ms (0 = no end / unreadable). */
    fun parseExpiry(s: String?, now: Long): Long {
        val t = s?.trim().orEmpty()
        if (t.isBlank()) return 0L
        t.toLongOrNull()?.let { return if (it > 1_000_000_000_000L) it else if (it > 1_000_000_000L) it * 1000 else 0L }
        Regex("^(\\d+)\\s*(d|day|days|天)$", RegexOption.IGNORE_CASE).find(t)?.let { return now + it.groupValues[1].toLong() * DAY }
        Regex("^(\\d+)\\s*(h|hour|hours|小时)$", RegexOption.IGNORE_CASE).find(t)?.let { return now + it.groupValues[1].toLong() * 3_600_000L }
        for (p in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd", "yyyy/MM/dd")) {
            val f = java.text.SimpleDateFormat(p, java.util.Locale.US).apply { isLenient = false; timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }
            runCatching { return f.parse(t.take(p.replace("'", "").length))!!.time.let { if (p.contains("HH")) it else it + DAY - 1000 } }
        }
        return 0L
    }

    // ---------------- 主设备 ----------------

    /** Exactly one primary among [ids]: the chosen one, else the current one if still there, else the first. */
    fun primary(ids: List<String>, current: String?, chosen: String? = null): String? = when {
        ids.isEmpty() -> null
        chosen != null && chosen in ids -> chosen
        current != null && current in ids -> current
        else -> ids.first()
    }

    // ---------------- IP 归属地 ----------------

    /** ip-api.com JSON (fields country,regionName,city,as,isp) → 「日本 东京 · AS9370 Sakura Internet」. */
    fun geoLine(json: String): String = runCatching {
        val o = JSONObject(json)
        if (o.optString("status") == "fail") return ""
        val place = listOf(o.optString("country"), o.optString("regionName"), o.optString("city")).filter { it.isNotBlank() }.distinct().joinToString(" ")
        val asn = o.optString("as").trim().ifBlank { o.optString("isp").trim() }
        listOf(place, asn.take(48)).filter { it.isNotBlank() }.joinToString(" · ")
    }.getOrDefault("")

    // ---------------- what runs on a server (read-only probe) ----------------

    /** Read-only: lists listening ports, containers, running services, top processes. Never changes anything. */
    const val RUNNING_PROBE = "(ss -ltnpH 2>/dev/null || ss -ltnp 2>/dev/null | tail -n +2) | head -40; echo '---D'; " +
        "(docker ps --format '{{.Names}}|{{.Image}}|{{.Ports}}' 2>/dev/null | head -20); echo '---S'; " +
        "(systemctl list-units --type=service --state=running --no-legend --no-pager --plain 2>/dev/null | head -60); echo '---P'; " +
        "(ps -eo comm= --sort=-%cpu 2>/dev/null | head -12)"

    private val BORING = Regex("^(systemd.*|dbus.*|cron|crond|rsyslog|ssh|sshd|getty@.*|serial-getty@.*|polkit|unattended-upgrades|" +
        "networkd-dispatcher|snapd.*|multipathd|irqbalance|udisks2|accounts-daemon|chrony.*|ntp.*|qemu-guest-agent|cloud-.*|atd|" +
        "user@.*|ModemManager|packagekit|thermald|upower|wpa_supplicant|NetworkManager|containerd|docker|aliyun.*|AssistDaemon|" +
        "tuned|firewalld|auditd|lvm2.*|blk-availability|kdump|irqbalance|ksm.*|gssproxy|rpcbind|postfix|exim4|fwupd|apport|" +
        "haveged|google.*|walinuxagent|hv-.*|vmtoolsd|open-vm-tools|tailscaled|fail2ban|iscsid|lxcfs|php.*-fpm)\\.service$", RegexOption.IGNORE_CASE)

    /**
     * The probe's output → short lines: 「端口 8090 · python3」, 「容器 mines-link（nginx:alpine）」, 「服务 hermes」.
     * Ports on 22 / 53 (sshd / resolver) and stock system services are left out.
     */
    fun parseRunning(out: String): List<String> {
        val res = LinkedHashSet<String>()
        var sec = "L"
        for (raw in out.lines()) {
            val line = raw.trim()
            when (line) { "---D" -> { sec = "D"; continue }; "---S" -> { sec = "S"; continue }; "---P" -> { sec = "P"; continue } }
            if (line.isBlank()) continue
            when (sec) {
                "L" -> {
                    val cols = line.split(Regex("\\s+"))
                    val local = cols.firstOrNull { Regex(":(\\d+)$").containsMatchIn(it) && (it.contains('.') || it.contains('*') || it.contains('[') || it.startsWith("0")) } ?: continue
                    val port = Regex(":(\\d+)$").find(local)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (port == 22 || port == 53) continue
                    if (local.startsWith("127.") || local.startsWith("[::1]")) continue
                    val proc = Regex("users:\\(\\(\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    res += "端口 $port" + (proc?.let { " · $it" } ?: "")
                }
                "D" -> {
                    val p = line.split('|')
                    if (p.size >= 2) res += "容器 ${p[0]}（${p[1].substringAfterLast('/').take(30)}）"
                }
                "S" -> {
                    val unit = line.split(Regex("\\s+")).firstOrNull().orEmpty()
                    if (unit.endsWith(".service") && !BORING.matches(unit)) res += "服务 " + unit.removeSuffix(".service")
                }
            }
        }
        return res.toList().take(24)
    }

    /** Re-read what runs at most every 30 min (the 工作位置 check runs every 90 s). */
    fun runningStale(m: Meta?, now: Long): Boolean = m == null || now - m.runningAt > 30 * 60_000L

    /** Card lines (order the user asked: provider / account, expiry, limits, cost, IP 归属地, vault). */
    fun cardLines(m: Meta, now: Long): List<String> = listOfNotNull(
        listOf(m.provider, m.account.takeIf { it.isNotBlank() }?.let { "账号 ${AccountsCore.maskPhone(it)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
        expiryText(m, now).ifBlank { null }?.let { "到期：$it" },
        m.limits.ifBlank { null }?.let { "限制：$it" },
        m.cost.ifBlank { null }?.let { "费用：$it" },
        m.geo.ifBlank { null }?.let { "IP 归属地：$it" },
        m.vaultRef.ifBlank { null }?.let { "凭据：在保险箱（$it）" },
    )

    // ---------------- JSON ----------------

    fun toJson(m: Meta): JSONObject = JSONObject().put("id", m.id).put("kind", m.kind.id).put("name", m.name).put("host", m.host)
        .put("provider", m.provider).put("account", m.account).put("expiresAt", m.expiresAt).put("limits", m.limits).put("cost", m.cost)
        .put("vaultRef", m.vaultRef).put("sourceUrl", m.sourceUrl).put("geo", m.geo).put("running", JSONArray(m.running))
        .put("runningAt", m.runningAt).put("notes", JSONArray(m.notes)).put("addedBy", m.addedBy).put("addedAt", m.addedAt)

    fun fromJson(o: JSONObject): Meta {
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return Meta(o.optString("id"), Kind.of(o.optString("kind")), o.optString("name"), o.optString("host"), o.optString("provider"),
            o.optString("account"), o.optLong("expiresAt"), o.optString("limits"), o.optString("cost"), o.optString("vaultRef"),
            o.optString("sourceUrl"), o.optString("geo"), list("running"), o.optLong("runningAt"), list("notes"), o.optString("addedBy", "agent"), o.optLong("addedAt"))
    }

    fun listToJson(l: List<Meta>): String = JSONArray(l.map { toJson(it) }).toString()
    fun listFromJson(s: String?): List<Meta> = runCatching { JSONArray(s ?: "[]").let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
}
