package com.openminis.app.plus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.plus.WorkspaceCore.Kind
import com.openminis.app.plus.WorkspaceCore.Meta
import com.openminis.app.plus.group.GroupPrefs
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.5 i] 工作位置 resources the agent obtained (see [WorkspaceCore]): the `workspace_register` tool, the 主设备,
 * IP 归属地 (ipwho.is), expiry reminders, and the read-only 「在跑什么」 probe run on an SSH check (≤ every 30 min). The probe
 * only reads (ss / docker ps / systemctl list-units / ps) — nothing on any server, trading boxes included, is changed.
 */
object WorkspaceStore {
    const val TOOL_NAME = "workspace_register"
    private const val KEY = "ws_meta"
    private const val PRIMARY = "ws_primary"
    private const val CHANNEL = "mines_workspace"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _metas = MutableStateFlow<List<Meta>>(emptyList())
    val metas: StateFlow<List<Meta>> = _metas.asStateFlow()
    private val probing = ConcurrentHashMap<String, Boolean>()
    @Volatile private var loaded = false

    const val PROMPT = "工作位置: when you sign up for / obtain a server (VPS, free tier, trial) or a cloud browser / remote desktop, call workspace_register right away with provider, account used, expiry, limits, cost and where the credential is stored (vault reference, never the secret) — the user must not have to add it by hand."

    fun init(c: Context) {
        if (loaded) return
        GroupPrefs.init(c)
        _metas.value = WorkspaceCore.listFromJson(GroupPrefs.get(KEY)); loaded = true
    }

    private fun save() = GroupPrefs.put(KEY, WorkspaceCore.listToJson(_metas.value))
    fun meta(id: String): Meta? = _metas.value.firstOrNull { it.id == id }
    fun upsert(m: Meta) { _metas.value = _metas.value.filter { it.id != m.id } + m; save() }
    fun remove(id: String) { _metas.value = _metas.value.filter { it.id != id }; save(); if (primaryId() == id) GroupPrefs.put(PRIMARY, null) }

    /** One 主设备: the chosen one if it still exists, else the first server. */
    fun primaryId(c: Context? = null): String? {
        val ids = PlusPrefs.state.value.servers.map { it.id } + _metas.value.filter { it.kind != Kind.SERVER }.map { it.id }
        return WorkspaceCore.primary(ids, GroupPrefs.get(PRIMARY))
    }
    fun setPrimary(id: String) = GroupPrefs.put(PRIMARY, id)
    /** Re-emits the list so cards re-read the 主设备 after [setPrimary]. */
    fun touch() { _metas.value = _metas.value.toList() }

    /** Servers with the 主设备 first (used by PlusVps.orderedTargets). */
    fun primaryFirst(list: List<VpsServer>): List<VpsServer> {
        val p = GroupPrefs.get(PRIMARY) ?: return list
        val i = list.indexOfFirst { it.id == p }
        return if (i <= 0) list else listOf(list[i]) + list.filterIndexed { j, _ -> j != i }
    }

    fun addNote(id: String, note: String) {
        val m = meta(id) ?: Meta(id, addedBy = "user", addedAt = System.currentTimeMillis())
        upsert(m.copy(notes = (m.notes + note.trim()).filter { it.isNotBlank() }.distinct().takeLast(12)))
    }

    // ---------------- the tool ----------------

    fun definition() = AgentToolDefinition(
        name = TOOL_NAME,
        description = "Register a server / cloud browser / remote desktop you just obtained for the user under 工作位置 (no manual step). " +
            "Give provider, the account used, expiry (YYYY-MM-DD, or 7d), limits (CPU/RAM/disk/traffic/hours), cost (免费 / 试用 / price) and vault_ref " +
            "(id of the vault entry with the password or key — NEVER the secret itself). Also use it with note=… to record what you deployed on a server (e.g. ':8090 下载服务').",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
            "type" to AgentToolParam("string", "server | cloud_browser | desktop", enumValues = listOf("server", "cloud_browser", "desktop")),
            "name" to AgentToolParam("string", "Short name the user will recognise"),
            "host" to AgentToolParam("string", "IP / host name, or the URL of a cloud browser / desktop"),
            "port" to AgentToolParam("integer", "SSH port (servers, default 22)"),
            "user" to AgentToolParam("string", "SSH user (servers, default root)"),
            "provider" to AgentToolParam("string", "Who provides it, e.g. Katabump / 阿里云 / Oracle"),
            "account" to AgentToolParam("string", "The account it was obtained with (e-mail / phone / user name)"),
            "expires" to AgentToolParam("string", "End of the free period / trial: YYYY-MM-DD, YYYY-MM-DD HH:mm (Beijing time) or 7d / 24h; empty = no end"),
            "limits" to AgentToolParam("string", "Limits in one line"),
            "cost" to AgentToolParam("string", "免费 / 试用 / price"),
            "vault_ref" to AgentToolParam("string", "Vault entry id holding the credential (never the secret)"),
            "source_url" to AgentToolParam("string", "Console / sign-up page"),
            "note" to AgentToolParam("string", "What runs there / what it is for (appended)"),
            "primary" to AgentToolParam("boolean", "true = make it the 主设备 (only when the user asked)"),
        ),
        required = listOf("tool_title", "type", "host"),
        propertyOrdering = listOf("tool_title", "type", "name", "host", "port", "user", "provider", "account", "expires", "limits", "cost", "vault_ref", "source_url", "note", "primary"),
    )

    fun runTool(c: Context, argsJson: String): ToolExecutionResult = runCatching {
        init(c); PlusPrefs.init(c)
        val a = JSONObject(argsJson)
        val kind = Kind.of(a.optString("type"))
        val host = a.optString("host").trim()
        if (host.isBlank()) return@runCatching ToolExecutionResult("host 是空的", false)
        if (Regex("(?i)(password|passwd|BEGIN [A-Z ]*PRIVATE KEY|sk-[A-Za-z0-9]{10,})").containsMatchIn(argsJson.replace(Regex("\"vault_ref\"\\s*:\\s*\"[^\"]*\""), "")))
            return@runCatching ToolExecutionResult("不要把密码或密钥写进来：先存进保险箱，只传 vault_ref", false)
        val now = System.currentTimeMillis()
        val name = a.optString("name").ifBlank { a.optString("provider").ifBlank { host } }
        var id: String
        if (kind == Kind.SERVER) {
            val h = host.substringAfter("://").substringBefore('/').substringBefore(':')
            val existing = PlusPrefs.state.value.servers.firstOrNull { it.host.equals(h, true) }
            id = existing?.id ?: java.util.UUID.randomUUID().toString()
            if (existing == null) PlusPrefs.update(c) { st -> st.copy(servers = st.servers + VpsServer(id = id, name = name.take(30), host = h,
                port = a.optInt("port", 22).takeIf { it in 1..65535 } ?: 22, user = a.optString("user").ifBlank { "root" })) }
        } else {
            id = meta("w:" + host.lowercase())?.id ?: ("w:" + host.lowercase())
        }
        val old = meta(id)
        val m = (old ?: Meta(id, addedAt = now)).copy(
            kind = kind, name = name.take(40), host = host, provider = a.optString("provider").ifBlank { old?.provider.orEmpty() },
            account = a.optString("account").ifBlank { old?.account.orEmpty() },
            expiresAt = a.optString("expires").takeIf { it.isNotBlank() }?.let { WorkspaceCore.parseExpiry(it, now) } ?: (old?.expiresAt ?: 0L),
            limits = a.optString("limits").ifBlank { old?.limits.orEmpty() }, cost = a.optString("cost").ifBlank { old?.cost.orEmpty() },
            vaultRef = a.optString("vault_ref").ifBlank { old?.vaultRef.orEmpty() }, sourceUrl = a.optString("source_url").ifBlank { old?.sourceUrl.orEmpty() },
            notes = ((old?.notes ?: emptyList()) + a.optString("note").trim()).filter { it.isNotBlank() }.distinct().takeLast(12),
            addedBy = old?.addedBy ?: "agent",
        )
        upsert(m)
        if (a.optBoolean("primary", false) || primaryId() == null) setPrimary(id)
        geoAsync(id, host)
        val left = WorkspaceCore.expiryText(m, now)
        ToolExecutionResult("已加到 工作位置 › ${kind.label}：$name" + (if (left.isNotBlank()) "（$left）" else "") +
            (if (primaryId() == id) " · 主设备" else ""), true)
    }.getOrElse { ToolExecutionResult("Error: ${it.message}", false) }

    // ---------------- IP 归属地 ----------------

    /** ipwho.is (https, free, no key) → the ip-api shape [WorkspaceCore.geoLine] reads. */
    fun geoAsync(id: String, hostOrIp: String) {
        val h = hostOrIp.substringAfter("://").substringBefore('/').substringBefore(':').trim()
        if (h.isBlank() || meta(id)?.geo?.isNotBlank() == true) return
        scope.launch {
            runCatching {
                val ip = if (Regex("^[0-9.]+$").matches(h) || h.contains(':')) h else java.net.InetAddress.getByName(h).hostAddress ?: h
                val c = java.net.URL("https://ipwho.is/$ip?lang=zh-CN").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                val o = JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
                if (!o.optBoolean("success", false)) return@runCatching
                val conn = o.optJSONObject("connection")
                val asLine = conn?.let { cn -> listOfNotNull(cn.optLong("asn").takeIf { it > 0 }?.let { "AS$it" }, cn.optString("org").ifBlank { cn.optString("isp") }.takeIf { it.isNotBlank() }).joinToString(" ") }.orEmpty()
                val line = WorkspaceCore.geoLine(JSONObject().put("country", o.optString("country")).put("regionName", o.optString("region"))
                    .put("city", o.optString("city")).put("as", asLine).toString())
                if (line.isNotBlank()) (meta(id) ?: Meta(id)).let { upsert(it.copy(geo = line)) }
            }
        }
    }

    // ---------------- 在跑什么 (read-only) ----------------

    /** Called after a successful SSH check of [s]; re-reads at most every 30 min. Read-only commands only. */
    fun probeRunningAsync(c: Context, s: VpsServer) {
        init(c)
        if (!WorkspaceCore.runningStale(meta(s.id), System.currentTimeMillis()) || probing.putIfAbsent(s.id, true) != null) return
        val app = c.applicationContext
        scope.launch {
            try {
                val (r, _) = VpsSsh.run(app, s, WorkspaceCore.RUNNING_PROBE, timeoutMs = 15_000, attempts = 1)
                val list = WorkspaceCore.parseRunning(r.output)
                val m = meta(s.id) ?: Meta(s.id, Kind.SERVER, s.name, s.host, addedBy = "user", addedAt = System.currentTimeMillis())
                upsert(m.copy(running = list, runningAt = System.currentTimeMillis()))
                if (m.geo.isBlank()) geoAsync(s.id, s.egressIp.ifBlank { s.host })
            } catch (_: Exception) {
            } finally { probing.remove(s.id) }
        }
    }

    // ---------------- expiry reminders ----------------

    fun checkExpiry(c: Context) {
        init(c)
        val now = System.currentTimeMillis()
        val p = c.getSharedPreferences("mines_workspace_remind", Context.MODE_PRIVATE)
        for (m in _metas.value) {
            val sent = p.getStringSet("sent_" + m.id + "_" + m.expiresAt, emptySet()) ?: emptySet()
            val due = WorkspaceCore.reminderDue(m, now, sent) ?: continue
            p.edit().putStringSet("sent_" + m.id + "_" + m.expiresAt, sent + due).apply()
            runCatching {
                val nm = c.getSystemService(NotificationManager::class.java) ?: return@runCatching
                if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "工作位置到期提醒", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(c, 74, Intent(c, com.openminis.app.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(74000 + (m.id.hashCode() and 0xfff), android.app.Notification.Builder(c, CHANNEL)
                    .setSmallIcon(com.openminis.app.R.drawable.ic_launcher_monochrome)
                    .setContentTitle("${m.name.ifBlank { m.host }} ${WorkspaceCore.expiryText(m, now)}")
                    .setContentText(listOf(m.provider, m.limits).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "到期后会停用，记得续期或换一台" })
                    .setContentIntent(open).setAutoCancel(true).build())
            }
        }
    }
}
