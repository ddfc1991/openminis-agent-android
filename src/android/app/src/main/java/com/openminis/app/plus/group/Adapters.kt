package com.openminis.app.plus.group

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.8] Adapters for transports 38.7 has no branch for. AiClient.replyOnce sends [Proto.ADAPTER] members here.
 * OPENAI_CHAT presets (nanoMuse) keep running on AiClient's own OpenAI path; WEBVIEW presets on [WebBridge].
 */
object Adapters {
    private val byTransport: Map<Transport, ChatAdapter> by lazy { mapOf(Transport.MEMOH to MemohAdapter) }

    fun of(preset: PlatformPreset): ChatAdapter? = byTransport[preset.transport]

    suspend fun reply(p: Partner, system: String, turns: List<AiClient.Turn>, maxTokens: Int): AiClient.Reply {
        val preset = PresetRegistry.preset(p.id) ?: throw MemberError("不认识这个成员（${p.id}）")
        val a = of(preset) ?: throw MemberError("${preset.name} 还不能在群里用")
        return a.reply(p, preset, TurnRequest(system, turns, maxTokens))
    }

    suspend fun probe(p: Partner): AiClient.Probe {
        val preset = PresetRegistry.preset(p.id) ?: return AiClient.Probe(false, null, false)
        return of(preset)?.probe(p, preset) ?: AiClient.Probe(false, null, false)
    }
}

/**
 * [Mines-38.8] Memoh (self-hosted, memohai/Memoh, spec/swagger.yaml): JWT from POST /auth/login; a turn is
 * POST /bots/{bot}/web/messages {"message":{"text":…}} (ack only), the answer is read by polling
 * GET /bots/{bot}/messages?limit=30 (UITurn list) for an assistant turn that was not there before we sent and whose text
 * stopped changing. 401 → log in again once with the saved password.
 */
object MemohAdapter : AsyncTaskAdapter() {
    override val transport: Transport = Transport.MEMOH

    private class State(val bot: String, val seen: Set<String>, var lastText: String = "", var stable: Int = 0, var answer: String = "")
    private val states = ConcurrentHashMap<String, State>()

    fun botOf(p: Partner): String = p.id.substringAfter("${PresetRegistry.MEMOH}:", "").ifBlank { GroupPrefs.bot(p.id).orEmpty() }

    private fun base(p: Partner): String = p.base.ifBlank { PresetAuth.memohBase().orEmpty() }.trimEnd('/')

    /** One authorised call; a 401 logs in again once. */
    private suspend fun authed(url: String, body: JSONObject? = null): PresetHttp.Res {
        // [Mines-38.9.4] hosted app.memoh.net: the WebView sign-in cookie, no JWT
        if (PresetAuth.key(PresetRegistry.MEMOH) == null) PresetAuth.memohWebCookie()?.let { ck ->
            val r = PresetHttp.call(url, null, body, cookie = ck)
            if (r.code == 401) throw MemberError("Memoh 网页登录过期了：在「应用代理」里点「去登录」", r.body.take(160))
            if (r.code == 403) throw MemberError("Memoh 不让这个账号用这个机器人", r.body.take(160))
            if (r.code == 404) throw MemberError("Memoh 找不到这个机器人（可能已删除）", r.body.take(160))
            if (!r.ok) throw MemberError(if (r.code >= 500) "Memoh 服务器出错，稍后再试" else "Memoh 没有正常回复", "HTTP ${r.code}：" + r.body.take(160))
            return r
        }
        val t = PresetAuth.memohToken() ?: throw MemberError("Memoh 还没登录：在「AI 伙伴」里点「登录」")
        var r = PresetHttp.call(url, t, body)
        if (r.code == 401) {
            val t2 = PresetAuth.memohToken(force = true) ?: throw MemberError("Memoh 登录过期了：在「AI 伙伴」里点「登录」", r.body.take(160))
            r = PresetHttp.call(url, t2, body)
        }
        if (r.code == 401 || r.code == 403) throw MemberError("Memoh 不让这个账号用这个机器人", r.body.take(160))
        if (r.code == 404) throw MemberError("Memoh 找不到这个机器人（可能已删除）", r.body.take(160))
        if (!r.ok) throw MemberError(if (r.code >= 500) "Memoh 服务器出错，稍后再试" else "Memoh 没有正常回复", "HTTP ${r.code}：" + r.body.take(160))
        return r
    }

    /** Assistant turns of a UITurn list: id → text (text field, or the text parts of its messages). */
    fun assistantTurns(body: String): List<Pair<String, String>> {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val items = o?.optJSONArray("items") ?: runCatching { JSONArray(body) }.getOrNull() ?: JSONArray()
        val out = ArrayList<Triple<String, String, String>>()
        for (i in 0 until items.length()) {
            val t = items.optJSONObject(i) ?: continue
            if (t.optString("role") != "assistant") continue
            val id = t.optString("id").ifBlank { t.optString("turn_id") }.ifBlank { "#$i" }
            var text = t.optString("text").takeIf { it != "null" }.orEmpty()
            if (text.isBlank()) {
                val ms = t.optJSONArray("messages") ?: JSONArray()
                text = (0 until ms.length()).mapNotNull { ms.optJSONObject(it) }
                    .filter { m -> m.optString("type").let { it.isBlank() || it == "text" || it == "assistant" || it == "message" } }
                    .mapNotNull { m -> m.optString("content").takeIf { it.isNotBlank() && it != "null" } }.joinToString("\n")
            }
            out += Triple(id, text.trim(), t.optString("timestamp"))
        }
        // oldest → newest by timestamp when every turn has one (the list order is not specified)
        val sorted = if (out.all { it.third.isNotBlank() }) out.sortedBy { it.third } else out
        return sorted.map { it.first to it.second }
    }

    private suspend fun list(p: Partner, bot: String): List<Pair<String, String>> =
        assistantTurns(authed("${base(p)}/bots/$bot/messages?limit=30").body)

    override fun prompt(req: TurnRequest): String {
        // a 1:1 chat sends the user's words as they are; a group sends the shared context squeezed into one message
        val direct = !req.system.contains("群聊")
        return if (direct) req.turns.lastOrNull { it.role == "user" }?.text.orEmpty() else req.flat(4000)
    }

    override suspend fun create(p: Partner, preset: PlatformPreset, prompt: String): AsyncTaskAdapter.Created {
        val bot = botOf(p).ifBlank { throw MemberError("Memoh 里还没选机器人") }
        val seen = runCatching { list(p, bot).map { it.first }.toSet() }.getOrDefault(emptySet())
        authed("${base(p)}/bots/$bot/web/messages", JSONObject().put("message", JSONObject().put("text", prompt)))
        val key = bot + ":" + System.nanoTime()
        states[key] = State(bot, seen)
        return AsyncTaskAdapter.Created(key, viewUrl = base(p).removeSuffix("/api"))
    }

    override suspend fun poll(p: Partner, preset: PlatformPreset, c: AsyncTaskAdapter.Created): AsyncTaskAdapter.Phase {
        val s = states[c.id] ?: return AsyncTaskAdapter.Phase.FAILED
        val fresh = list(p, s.bot).lastOrNull { it.first !in s.seen && it.second.isNotBlank() } ?: return AsyncTaskAdapter.Phase.RUNNING
        if (fresh.second == s.lastText) s.stable++ else { s.lastText = fresh.second; s.stable = 0 }
        // the answer is persisted when the turn ends, but a streaming store could grow: wait for 2 equal reads
        if (s.stable >= 1) { s.answer = fresh.second; return AsyncTaskAdapter.Phase.DONE }
        return AsyncTaskAdapter.Phase.RUNNING
    }

    override suspend fun fetch(p: Partner, preset: PlatformPreset, c: AsyncTaskAdapter.Created): String = states.remove(c.id)?.answer.orEmpty()

    override suspend fun cancel(p: Partner, preset: PlatformPreset, c: AsyncTaskAdapter.Created) { states.remove(c.id) }

    override suspend fun probe(p: Partner, preset: PlatformPreset): AiClient.Probe = runCatching {
        val r = authed("${base(p)}/bots")
        AiClient.Probe(true, r.json()?.optJSONArray("items")?.optJSONObject(0)?.optString("id"), false)
    }.getOrElse { AiClient.Probe(false, null, it is MemberError && it.message?.contains("登录") == true) }
}

/**
 * [Mines-38.8] Members that a preset lists itself (Memoh: one member per bot of the signed-in account, "memoh:<bot id>").
 * Partners appends [collect]'s rows; nothing is pasted by the user.
 */
object PresetPartners {
    data class Bot(val id: String, val name: String, val avatar: String?)

    private fun cachedBots(): List<Bot> = runCatching {
        val a = JSONArray(GroupPrefs.get("memoh_bots") ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { Bot(it.getString("id"), it.optString("name"), it.optString("avatar").ifBlank { null }) }
    }.getOrDefault(emptyList())

    private fun saveBots(l: List<Bot>) = GroupPrefs.put("memoh_bots",
        JSONArray(l.map { JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar ?: "") }).toString())

    /** Lists the account's bots (GET /bots), remembered for offline display. */
    suspend fun memohBots(): Result<List<Bot>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = PresetAuth.memohBase() ?: throw MemberError("Memoh 还没登录")
            val ck = if (PresetAuth.key(PresetRegistry.MEMOH) == null) PresetAuth.memohWebCookie() else null
            var r: PresetHttp.Res
            if (ck != null) {
                r = PresetHttp.call("$base/bots", null, cookie = ck)
                if (r.code == 401) throw MemberError("Memoh 网页登录过期了，点「去登录」")
            } else {
                var t = PresetAuth.memohToken() ?: throw MemberError("Memoh 还没登录")
                r = PresetHttp.call("$base/bots", t)
                if (r.code == 401) { t = PresetAuth.memohToken(force = true) ?: throw MemberError("Memoh 登录过期了，点「登录」"); r = PresetHttp.call("$base/bots", t) }
            }
            if (!r.ok) throw MemberError("读不到 Memoh 的机器人（HTTP ${r.code}）", r.body.take(160))
            val items = r.json()?.optJSONArray("items") ?: JSONArray()
            val l = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .filter { it.optBoolean("is_active", true) }
                .map { Bot(it.optString("id"), it.optString("display_name").ifBlank { it.optString("name") }.ifBlank { "Memoh 机器人" }, it.optString("avatar_url").ifBlank { null }) }
                .filter { it.id.isNotBlank() }
            saveBots(l)
            l
        }
    }

    fun memohSpec(id: String): AiSpec {
        val base = PresetRegistry.preset(PresetRegistry.MEMOH)!!.toSpec()
        val b = cachedBots().firstOrNull { "${PresetRegistry.MEMOH}:" + it.id == id }
        return base.copy(id = id, name = b?.name ?: "Memoh 机器人", iconUrl = b?.avatar, note = "你在 Memoh 的机器人")
    }

    /** Rows for Partners.refresh (IO thread). */
    suspend fun collect(context: Context): List<Partner> {
        val out = ArrayList<Partner>()
        val base = PresetAuth.memohBase()
        val signed = base != null && (PresetAuth.key(PresetRegistry.MEMOH) != null || PresetAuth.memohWebCookie() != null)
        if (!signed) {
            out += Partner(PresetRegistry.specOf(PresetRegistry.MEMOH)!!, Partner.State.NEED_LOGIN, "", null, "", "",
                reason = "填你的 Memoh 服务器地址和账号登录，机器人会自动出现")
            return out
        }
        val res = memohBots()
        val bots = res.getOrNull() ?: cachedBots()
        if (bots.isEmpty()) {
            // a hosted web session that the server no longer accepts is a sign-out, not 连不上
            val signedOut = res.exceptionOrNull()?.message?.contains("登录过期") == true
            out += Partner(PresetRegistry.specOf(PresetRegistry.MEMOH)!!, if (res.isFailure && !signedOut) Partner.State.UNREACHABLE else Partner.State.NEED_LOGIN,
                base!!, null, "", "已登录",
                reason = res.exceptionOrNull()?.message ?: "Memoh 里还没有机器人：在 Memoh 建一个，回来自动出现")
            return out
        }
        for (b in bots) {
            val spec = memohSpec("${PresetRegistry.MEMOH}:" + b.id)
            out += if (res.isSuccess) Partner(spec, Partner.State.READY, base!!, null, "", "Memoh")
            else Partner(spec, Partner.State.UNREACHABLE, base!!, null, "", "Memoh", reason = res.exceptionOrNull()?.message ?: "连不上 Memoh")
        }
        return out
    }
}
