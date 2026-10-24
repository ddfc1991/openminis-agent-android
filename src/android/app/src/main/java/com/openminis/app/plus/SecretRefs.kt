package com.openminis.app.plus

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.11 b] Secure password input for EVERYTHING the agent needs (not only websites / Memoh): SSH / VPS passwords,
 * API keys, database / panel passwords, any account. The agent calls `accounts(action=secret, …)`:
 *  1. a saved login for that site + account (保险箱 / 账号关联, [SavedLogins]) is used silently — no card;
 *  2. otherwise the secure [LoginCard] pops (masked, FLAG_SECURE, 保存到保险箱 on), never a chat question;
 *  3. the agent gets back only placeholders {{secret:ID}} / {{user:ID}}; [ExecutionCoordinator] turns them into an env var
 *     for that one command and masks the value in everything printed.
 * Placeholder ids → (host, account) are kept in plain prefs (no secret in them); values stay in [SavedLogins].
 */
object SecretRefs {
    private const val PREFS = "mines_secret_refs"
    /** values handed to a command in this process (for masking output even after the vault entry changed) */
    private val used = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var app: Context? = null

    data class Ref(val id: String, val host: String, val account: String)

    fun init(c: Context) { if (app == null) app = c.applicationContext }

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun remember(c: Context, r: Ref) { p(c).edit().putString("r_" + r.id, r.host + "\n" + r.account).apply() }

    fun lookup(c: Context, id: String): Ref? = p(c).getString("r_$id", null)?.let {
        Ref(id, it.substringBefore('\n'), it.substringAfter('\n', ""))
    }

    private fun urlFor(kind: String, host: String): String = when (kind) {
        "ssh", "vps", "server" -> "ssh://$host"
        "api_key", "key", "token" -> "https://$host/#key"
        else -> if (host.startsWith("http")) host else "https://$host/"
    }

    /**
     * Ask (or reuse) one secret. Returns a short tool answer for the agent with the placeholders, or why not.
     * [kind] ssh | password | api_key | login, [host] site / server, [account] user name if known, [name] what to show.
     */
    suspend fun ask(c: Context, kind: String, host0: String, account0: String, name0: String, purpose: String): String {
        init(c)
        val host = host0.trim().removePrefix("https://").removePrefix("http://").removePrefix("ssh://").trimEnd('/')
        if (host.isBlank()) return "site 是空的：告诉 Mines 是哪个网站或服务器（域名或 IP）"
        val k = kind.ifBlank { "password" }.lowercase()
        val name = name0.ifBlank { if (k == "ssh") "服务器 $host" else AutoAccounts.siteOf(host).ifBlank { host } }
        // 1) saved → silent
        val saved = runCatching { SavedLogins.find(c, if (k == "ssh") host else host, account0, anyUser = account0.isBlank()) }.getOrNull()
            ?.takeIf { it.pass.isNotBlank() && (k != "ssh" || it.url.startsWith("ssh://") || it.host == host) }
        if (saved != null) {
            val id = SecretRefsCore.idFor(k, host, saved.user)
            remember(c, Ref(id, host, saved.user))
            return answer(id, saved.user, name, reused = true)
        }
        // 2) the secure card
        val isKey = k == "api_key" || k == "key" || k == "token"
        val fields = buildList {
            if (!isKey && account0.isBlank()) add(LoginMemoryCore.Field.ACCOUNT)
            add(LoginMemoryCore.Field.PASSWORD)
        }
        val title = when {
            isKey -> "填 $name 的 API 密钥" + if (purpose.isNotBlank()) "（${purpose.take(20)}）" else ""
            k == "ssh" -> "登录服务器 $host" + if (purpose.isNotBlank()) "（${purpose.take(20)}）" else ""
            else -> "登录 $name" + if (purpose.isNotBlank()) "（${purpose.take(20)}）" else ""
        }
        val a = LoginCard.ask(LoginCard.Req(host, name, LoginMemoryCore.Ask(fields, title, false), account0, null), c)
            ?: return "用户关掉了安全输入框，没有填。不要在聊天里要密码；需要时再调用 accounts(action=secret)。"
        val user = a.account.ifBlank { account0 }.ifBlank { if (isKey) "key" else "" }
        if (a.password.isBlank()) return "没有填密码"
        val entry = SavedLogins.Entry(name, urlFor(k, host), user, a.password)
        if (a.save) SavedLogins.put(c, entry) else session[SecretRefsCore.idFor(k, host, user)] = entry
        val id = SecretRefsCore.idFor(k, host, user)
        remember(c, Ref(id, host, user))
        return answer(id, user, name, reused = false)
    }

    /** not saved to the vault (保存 off): kept for this app run only */
    private val session = ConcurrentHashMap<String, SavedLogins.Entry>()

    private fun answer(id: String, user: String, name: String, reused: Boolean): String =
        (if (reused) "已用保险箱里存的 $name 账号（没有打扰用户）。" else "用户已在安全输入框里填好 $name（值不会给你看）。") +
            "在 shell 命令里用 {{secret:$id}} 代表密码/密钥" + (if (user.isNotBlank() && user != "key") "，{{user:$id}} 代表账号（$user）" else "") +
            "，例如服务器首次授权 MINES_SSH_PASSWORD={{secret:$id}} minis-vps setup {{user:$id}}@主机，或 curl -H \"Authorization: Bearer {{secret:$id}}\" …；Mines 执行时会替换成真实值并在输出里遮住。不要把它写进文件或聊天。"

    private fun valueOf(id: String): Pair<String, String>? {
        session[id]?.let { return it.user to it.pass }
        val c = app ?: return null
        val r = lookup(c, id) ?: return null
        val e = runCatching { SavedLogins.find(c, r.host, r.account, anyUser = r.account.isBlank() || r.account == "key") }.getOrNull() ?: return null
        return e.user to e.pass
    }

    /** env for one command: MINES_SECRET_<ID> = value for every {{secret:ID}} it uses (empty when none). */
    fun envFor(command: String): Map<String, String> {
        if (!command.contains("{{")) return emptyMap()
        val out = HashMap<String, String>()
        for (id in SecretRefsCore.secretIds(command)) valueOf(id)?.second?.takeIf { it.isNotEmpty() }?.let { v -> out[SecretRefsCore.envName(id)] = v; used += v }
        // {{user:…}} alone still needs the rewrite: mark with an empty-free sentinel so the caller rewrites
        if (out.isEmpty() && SecretRefsCore.userIds(command).isNotEmpty()) out["MINES_SECRET_REFS"] = "1"
        return out
    }

    fun rewrite(command: String): String {
        val users = HashMap<String, String>()
        for (id in SecretRefsCore.userIds(command)) valueOf(id)?.first?.let { users[id] = it }
        return SecretRefsCore.rewrite(command, users)
    }

    fun scrub(text: String): String = if (used.isEmpty()) text else SecretRefsCore.scrub(text, used)

    /**
     * [38.9.11.3] the browser's type action: {{secret:ID}} / {{user:ID}} in the text become the real values right before they
     * are typed into the page (the agent never sees them; the tool output is scrubbed). Unknown ids are left as they are.
     */
    fun fill(text: String): String {
        if (!text.contains("{{")) return text
        val r = Regex("""\{\{\s*(secret|user)\s*:\s*([A-Za-z0-9_]{1,48})\s*\}\}""")
        val out = r.replace(text) { m ->
            val v = valueOf(m.groupValues[2])
            val x = if (m.groupValues[1] == "secret") v?.second else v?.first
            if (x.isNullOrEmpty()) m.value else { if (m.groupValues[1] == "secret") used += x; x }
        }
        return out
    }

    /** [38.9.11.3] a 台账 account the agent may use: remembered, returned as its secret id (never the value) */
    fun idForSaved(c: Context, host: String, account: String): String {
        init(c)
        val id = SecretRefsCore.idFor("password", host, account)
        remember(c, Ref(id, host, account)); return id
    }
}
