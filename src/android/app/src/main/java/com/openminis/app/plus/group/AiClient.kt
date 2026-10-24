package com.openminis.app.plus.group

import com.openminis.app.plus.SplitProxy
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A plain-words failure shown under the member's name; never blocks the others. [detail] = the raw server text (shown under 「详情」). */
open class MemberError(message: String, val detail: String? = null) : Exception(message)
/** [Mines-38.5] the member's model does not exist / is not open to this key → switch to another model of the same provider. */
class ModelUnavailable(message: String, detail: String?) : MemberError(message, detail)
/** [Mines-38.5] HTTP 429 → back off and retry. */
class RateLimited(message: String, detail: String?, val retryAfterSec: Int) : MemberError(message, detail)

/**
 * [Mines-group-37] One reply from one member. OpenAI-compatible chat
 * (most vendors), Anthropic Messages, Manus tasks (create → poll → last
 * assistant message) and Coze v3 chat (create → poll → answer). Calls are
 * cancellable (「停止」 cancels the HTTP call). Overseas vendors try direct,
 * then Mines' 分流代理 and the configured upstream proxies, like KeyTester.
 */
object AiClient {
    data class Turn(val role: String, val text: String)
    data class Reply(val text: String, val inTok: Int = 0, val outTok: Int = 0, val model: String = "", val note: String? = null)
    data class Probe(val reachable: Boolean, val firstModel: String?, val needsKey: Boolean)

    private val json = "application/json".toMediaType()

    private fun client(p: Proxy, readSec: Long) = OkHttpClient.Builder().proxy(p).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS).callTimeout(readSec + 20, TimeUnit.SECONDS).build()

    private fun clients(spec: AiSpec, base: String, readSec: Long = 120): List<OkHttpClient> {
        val out = arrayListOf(client(Proxy.NO_PROXY, readSec))
        val local = base.contains("127.0.0.1") || base.contains("localhost") || base.contains("10.0.2.2")
        if (!spec.domestic && !local) {
            if (SplitProxy.active) out += client(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", SplitProxy.PORT)), readSec)
            SplitProxy.settings.value.all.take(2).forEach { u ->
                runCatching { out += client(Proxy(if (u.type == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(u.host, u.port)), readSec) }
            }
        }
        return out
    }

    private data class Http(val code: Int, val body: String, val retryAfter: Int = 0)

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }

    private suspend fun exec(spec: AiSpec, base: String, req: Request, readSec: Long = 120): Http {
        var last: Exception? = null
        for (cl in clients(spec, base, readSec)) {
            try {
                cl.newCall(req).await().use { r -> return Http(r.code, r.body?.string().orEmpty(), r.header("Retry-After")?.trim()?.toIntOrNull() ?: 0) }
            } catch (e: IOException) { last = e }
        }
        // [Mines-38.9.1] 127.0.0.1:<port> refused on the phone → the gateway may run on one of the user's servers: SSH forward, retry once
        val ctx = PhoneTyping.appContext
        val local = com.openminis.app.plus.GatewayTunnel.isLocal(base)
        var tried = false
        if (local != null && ctx != null && (last?.message ?: "").lowercase().let { it.contains("refused") || it.contains("failed to connect") }) {
            tried = true
            if (com.openminis.app.plus.GatewayTunnel.ensure(ctx, local.second) != null) {
                for (cl in clients(spec, base, readSec)) {
                    try {
                        cl.newCall(req).await().use { r -> return Http(r.code, r.body?.string().orEmpty(), r.header("Retry-After")?.trim()?.toIntOrNull() ?: 0) }
                    } catch (e: IOException) { last = e }
                }
            }
        }
        throw MemberError(com.openminis.app.plus.GatewayTunnel.explain(base, last?.message ?: "", com.openminis.app.plus.GatewayTunnel.proxyOn(ctx), tried), last?.message)
    }

    private fun plainNet(e: Exception?): String {
        val m = (e?.message ?: "").lowercase()
        return when {
            m.contains("timeout") || m.contains("timed out") -> "等太久没回复（超时）"
            m.contains("unable to resolve") || m.contains("no address") -> "连不上（域名解析失败，可能要开代理）"
            m.contains("refused") -> "连不上（对方拒绝连接）"
            m.contains("canceled") -> "已停止"
            else -> "连不上（网络问题，可能要开代理）"
        }
    }

    private fun check(h: Http) {
        if (h.code in 200..299) return
        val msg = runCatching {
            val o = JSONObject(h.body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("message").takeIf { it.isNotBlank() } ?: o.optString("msg")
        }.getOrNull()?.take(120) ?: h.body.take(120)
        val low = h.body.lowercase()
        val raw = "HTTP ${h.code}：$msg"
        // [Mines-38.5] a model that is missing / not open to this key (Ark: 404 "The model or endpoint … does not exist or you do
        // not have access to it"; OpenAI: model_not_found; others 400 "invalid model") is switched, not shown as a wall of English.
        val modelGone = (h.code == 404 || h.code == 400 || h.code == 403) && (low.contains("model") || h.body.contains("模型")) &&
            listOf("not exist", "not found", "does not exist", "no access", "not have access", "invalidendpointormodel", "model_not_found",
                "invalid model", "unknown model", "not supported", "不存在", "无权", "没有权限", "not available").any { low.contains(it) || h.body.contains(it) }
        if (modelGone) throw ModelUnavailable("模型不存在或不可用", raw)
        if (h.code == 429 && !(low.contains("insufficient") || low.contains("quota") && low.contains("exceeded your current quota")))
            throw RateLimited("现在太忙（被限流）", raw, h.retryAfter)
        throw MemberError(when {
            h.code == 401 || h.code == 403 -> "鉴权失败（HTTP ${h.code}）：密钥无效或没有权限"
            h.code == 402 || h.code == 429 || low.contains("insufficient") || low.contains("balance") || h.body.contains("余额") -> "账户余额不足，需要充值"
            h.code >= 500 -> "对方服务器出错，稍后再试"
            else -> "对方没有正常回复"
        }, raw)
    }

    private fun bearer(b: Request.Builder, key: String?) = if (key.isNullOrBlank()) b else b.header("Authorization", "Bearer $key")

    /** Reachability for key-optional endpoints (WorkBuddy, nanoMuse). */
    suspend fun probe(base: String): Probe = runCatching {
        val c = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
        c.newCall(Request.Builder().url(base.trimEnd('/') + "/models").build()).await().use { r ->
            val body = r.body?.string().orEmpty()
            val first = runCatching { JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("id") }.getOrNull()?.takeIf { it.isNotBlank() }
            Probe(true, first, r.code == 401 || r.code == 403)
        }
    }.getOrDefault(Probe(false, null, false))

    /** Picks a model from the endpoint's list by [AiSpec.prefer] when the user/模型组 gave none. Cached. */
    private suspend fun ensureModel(p: Partner): String {
        GroupPrefs.model(p.id)?.let { return it }
        // [Mines-38.9.1] a gateway's model list changes (a saved 「gemma-…-MLX-4bit」 was only served by a Mac): keep using the
        // saved one only while the gateway's own /models still lists it, else take the gateway's first chat model
        if (p.spec.keyOptional || com.openminis.app.plus.GatewayTunnel.isLocal(p.base) != null) {
            val ids = listModels(p)
            val cur = GroupPrefs.autoModel(p.id) ?: p.model
            if (ids.isNotEmpty()) return (if (cur in ids) cur else ids.first().also { GroupPrefs.setAutoModel(p.id, it) })
        }
        if (p.spec.prefer.isEmpty()) return p.model
        GroupPrefs.autoModel(p.id)?.let { return it }
        if (p.model.isNotBlank() && p.model != p.spec.defaultModel) return p.model
        val ids = runCatching {
            val req = bearer(Request.Builder().url(p.base.trimEnd('/') + "/models"), p.key).build()
            val h = exec(p.spec, p.base, req, 20)
            if (h.code !in 200..299) return@runCatching emptyList<String>()
            val a = JSONObject(h.body).optJSONArray("data") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
        val pick = p.spec.prefer.firstNotNullOfOrNull { pre -> ids.firstOrNull { it.startsWith(pre) || it.removePrefix("models/").startsWith(pre.removePrefix("models/")) } }
            ?.removePrefix("models/")
        if (pick != null) GroupPrefs.setAutoModel(p.id, pick)
        return pick ?: p.model.ifBlank { p.spec.defaultModel }
    }

    /**
     * [Mines-38.5] One reply with the group's safety net: HTTP 429 → wait (Retry-After or 2 s / 5 s / 10 s) and retry up to
     * 3 times; model missing / not allowed → pick another chat model of the SAME provider from its /models list (remembered
     * for next time) and say so in one line; nothing left → a plain error with the raw text under 「详情」.
     */
    suspend fun reply(p: Partner, system: String, turns: List<Turn>, maxTokens: Int = 700): Reply {
        var cur = p
        var switched: String? = null
        val tried = HashSet<String>()
        var waits = 0
        while (true) {
            try {
                val r = replyOnce(cur, system, turns, maxTokens)
                return if (switched != null) r.copy(note = "原模型不可用，已自动换成 $switched") else r
            } catch (e: RateLimited) {
                if (waits >= 3) throw MemberError("现在太忙（被限流），已自动重试 3 次", e.detail)
                val sec = if (e.retryAfterSec in 1..30) e.retryAfterSec else intArrayOf(2, 5, 10)[waits]
                waits++
                delay(sec * 1000L)
            } catch (e: ModelUnavailable) {
                if (cur.spec.proto != Proto.OPENAI && cur.spec.proto != Proto.ANTHROPIC) throw MemberError("模型不可用，可在成员设置换模型", e.detail)
                tried += currentModel(cur)
                val alt = alternative(cur, tried) ?: throw MemberError("模型不可用，已跳过（可在成员设置换模型）", e.detail)
                if (GroupPrefs.model(p.id) != null && GroupPrefs.model(p.id) in tried) GroupPrefs.setModel(p.id, null)
                GroupPrefs.setAutoModel(p.id, alt)
                cur = cur.copy(model = alt)
                switched = alt
            }
        }
    }

    /** The model id a request for [p] would use right now (mirrors ensureModel without the network). */
    private fun currentModel(p: Partner): String =
        GroupPrefs.model(p.id) ?: (if (p.spec.prefer.isNotEmpty() || p.spec.keyOptional || com.openminis.app.plus.GatewayTunnel.isLocal(p.base) != null) GroupPrefs.autoModel(p.id) else null) ?: p.model.ifBlank { p.spec.defaultModel }

    /** Chat models this key can list, best first: the provider's preferred prefixes, then small / fast chat models. */
    suspend fun listModels(p: Partner): List<String> = runCatching {
        val req = bearer(Request.Builder().url(p.base.trimEnd('/') + "/models"), p.key).apply {
            if (p.spec.proto == Proto.ANTHROPIC) { header("anthropic-version", "2023-06-01"); p.key?.let { header("x-api-key", it) } }
        }.build()
        val h = exec(p.spec, p.base, req, 20)
        if (h.code !in 200..299) return@runCatching emptyList<String>()
        val a = JSONObject(h.body).optJSONArray("data") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.removePrefix("models/")?.takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
        .filterNot { id -> listOf("embed", "rerank", "tts", "whisper", "audio", "image", "vision", "dall", "moderation", "realtime", "transcribe", "speech", "seedream", "seedance", "video")
            .any { id.lowercase().contains(it) } }
        .sortedBy { id ->
            val pi = p.spec.prefer.indexOfFirst { pre -> id.startsWith(pre.removePrefix("models/")) }
            if (pi >= 0) pi else 100 + listOf("flash", "lite", "mini", "turbo", "chat", "pro").indexOfFirst { id.lowercase().contains(it) }.let { if (it < 0) 50 else it }
        }

    private suspend fun alternative(p: Partner, tried: Set<String>): String? {
        val listed = listModels(p).filter { it !in tried }
        if (listed.isNotEmpty()) return listed.first()
        return (p.spec.prefer.map { it.removePrefix("models/") } + p.spec.defaultModel).firstOrNull { it.isNotBlank() && it !in tried }
    }

    private suspend fun replyOnce(p: Partner, system: String, turns: List<Turn>, maxTokens: Int): Reply = when (p.spec.proto) {
        Proto.OPENAI -> openai(p, system, turns, maxTokens)
        Proto.ANTHROPIC -> anthropic(p, system, turns, maxTokens)
        Proto.MANUS -> manus(p, system, turns)
        Proto.COZE -> coze(p, system, turns)
        Proto.PHONE -> PhoneTyping.ask(p, turns.lastOrNull { it.role == "user" }?.text ?: "")
        Proto.WEBAPP -> if (WebBridge.handles(p.id)) WebBridge.ask(PhoneTyping.appContext ?: throw MemberError("还没准备好"), p, flat(webIntro(system), turns, 1800))  // [Mines-38.8]
            else WebChat.ask(PhoneTyping.appContext ?: throw MemberError("还没准备好"), p, flat(webIntro(system), turns, 1800))
        Proto.ADAPTER -> Adapters.reply(p, system, turns, maxTokens)  // [Mines-38.8] Memoh …
        Proto.MINES -> minesAgent(p, system, turns)
    }

    /** Web chats get a short plain intro (their own persona stays); the group context follows. */
    private fun webIntro(system: String) = system.substringBefore("别人的发言") + "别人的发言以【名字】开头。请直接回答最后一条，200 字以内。"

    /**
     * [Mines-38.6] Mines itself as a member: the real agent loop (tools, files, web) in its own background chat, one per
     * member, found later under 对话. Slow turns are fine — other members do not wait for it.
     */
    private suspend fun minesAgent(p: Partner, system: String, turns: List<Turn>): Reply {
        val ctx = PhoneTyping.appContext ?: throw MemberError("还没准备好")
        val key = "mines_sid"
        val saved = GroupPrefs.get(key)
        val alive = saved != null && runCatching { (ctx as? com.openminis.app.MinisApp)?.chatRepository?.getSession(saved) != null }.getOrDefault(true)
        val sid = if (alive && saved != null) saved else com.openminis.app.debug.HeadlessChatRunner.ensureSession(ctx).also { GroupPrefs.put(key, it) }
        // [Mines-38.7] short prompt in Mines' own background chat (38.6 pasted the whole group system prompt + history, ~4000 chars):
        // just the question, plus at most 3 one-line notes of what others said this round
        val q = turns.lastOrNull { it.role == "user" && it.text.startsWith("【群主】") }?.text?.removePrefix("【群主】")
            ?: turns.lastOrNull { it.role == "user" }?.text.orEmpty()
        val others = turns.takeLastWhile { !(it.role == "user" && it.text.startsWith("【群主】")) }.filter { it.role == "user" && it.text.startsWith("【") }
            .takeLast(3).joinToString("\n") { "- " + it.text.replace(Regex("\\s+"), " ").take(80) }
        val direct = !system.contains("群聊")
        val prompt = (if (direct) "" else "（群聊里有人问你，简短回答）\n") + q.take(1200) + if (others.isNotBlank()) "\n其他成员刚说：\n$others" else ""
        val r = com.openminis.app.debug.HeadlessChatRunner.prompt(ctx, sid, prompt, wait = true, timeoutMs = 5 * 60_000L)
        val text = r.responseText?.trim().orEmpty()
        if (r.timedOut) throw MemberError("Mines 智能体 5 分钟还没做完，结果会留在它的对话里")
        if (r.status == "Error" || r.status == "Rejected") throw MemberError("Mines 智能体没能开始（${r.responseText?.take(60) ?: r.status}）")
        if (text.isBlank()) throw MemberError("Mines 智能体没有给出回复")
        return Reply(text.take(4000), model = "带工具")
    }

    /** OpenAI wants alternating roles; merge neighbours with the same role. */
    private fun merged(turns: List<Turn>): List<Turn> {
        val out = ArrayList<Turn>()
        for (t in turns) {
            val last = out.lastOrNull()
            if (last != null && last.role == t.role) out[out.lastIndex] = Turn(t.role, last.text + "\n\n" + t.text) else out += t
        }
        if (out.firstOrNull()?.role == "assistant") out.add(0, Turn("user", "（群聊开始）"))
        return out
    }

    private suspend fun openai(p: Partner, system: String, turns: List<Turn>, maxTokens: Int): Reply {
        val model = ensureModel(p)
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        merged(turns).forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject().put("messages", msgs).put(if (p.spec.id == "openai") "max_completion_tokens" else "max_tokens", maxTokens).put("stream", false)
        if (model.isNotBlank()) body.put("model", model)
        val req = bearer(Request.Builder().url(p.base.trimEnd('/') + "/chat/completions"), p.key)
            .post(body.toString().toRequestBody(json)).build()
        val h = exec(p.spec, p.base, req)
        check(h)
        val o = runCatching { JSONObject(h.body) }.getOrElse { throw MemberError("回复看不懂（不是 JSON）") }
        val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val text = msg?.optString("content")?.takeIf { it.isNotBlank() && it != "null" }
            ?: msg?.optString("reasoning_content")?.takeIf { it.isNotBlank() && it != "null" }
            ?: throw MemberError("回复是空的")
        val u = o.optJSONObject("usage")
        return Reply(stripThink(text), u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0, o.optString("model", model))
    }

    private fun stripThink(s: String) = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    private suspend fun anthropic(p: Partner, system: String, turns: List<Turn>, maxTokens: Int): Reply {
        val model = ensureModel(p)
        val msgs = JSONArray()
        merged(turns).forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system).put("messages", msgs)
        val b = Request.Builder().url(p.base.trimEnd('/') + "/messages").header("anthropic-version", "2023-06-01")
        if (!p.key.isNullOrBlank()) b.header("x-api-key", p.key)
        val h = exec(p.spec, p.base, b.post(body.toString().toRequestBody(json)).build())
        check(h)
        val o = JSONObject(h.body)
        val parts = o.optJSONArray("content") ?: JSONArray()
        val text = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.takeIf { x -> x.optString("type") == "text" }?.optString("text") }
            .joinToString("").ifBlank { throw MemberError("回复是空的") }
        val u = o.optJSONObject("usage")
        return Reply(text, u?.optInt("input_tokens") ?: 0, u?.optInt("output_tokens") ?: 0, o.optString("model", model))
    }

    /** Group context squeezed into one prompt for task-style agents (Manus / 扣子). */
    private fun flat(system: String, turns: List<Turn>, cap: Int = 6000): String {
        val sb = StringBuilder(system).append("\n\n群聊记录：\n")
        turns.dropLast(1).takeLast(12).forEach { sb.append(if (it.role == "assistant") "（你）" else "").append(it.text).append('\n') }
        sb.append("\n请回复最新一条：\n").append(turns.lastOrNull()?.text ?: "")
        return sb.toString().takeLast(cap)
    }

    // Manus: POST /v2/task.create → GET /v2/task.detail until stopped/waiting/error → task.listMessages (open.manus.ai/docs/v2)
    private suspend fun manus(p: Partner, system: String, turns: List<Turn>): Reply {
        val key = p.key ?: throw MemberError("缺 Manus 密钥")
        fun r(path: String) = Request.Builder().url(p.base.trimEnd('/') + path).header("x-manus-api-key", key)
        val body = JSONObject().put("message", JSONObject().put("content", flat(system, turns)))
            .put("agent_profile", GroupPrefs.model(p.id) ?: "lite").put("hide_in_task_list", true).put("locale", "zh-CN")
        val h = exec(p.spec, p.base, r("/task.create").post(body.toString().toRequestBody(json)).build(), 60)
        check(h)
        val id = JSONObject(h.body).optString("task_id").ifBlank { throw MemberError("Manus 没有返回任务号") }
        val t0 = System.currentTimeMillis()
        var status = "running"
        while (System.currentTimeMillis() - t0 < 8 * 60_000) {
            delay(5000)
            val d = exec(p.spec, p.base, r("/task.detail?task_id=$id").build(), 30)
            if (d.code !in 200..299) continue
            status = JSONObject(d.body).optJSONObject("task")?.optString("status") ?: status
            if (status != "running") break
        }
        if (status == "running") throw MemberError("Manus 8 分钟还没做完，可在 Manus 里看任务")
        if (status == "error") throw MemberError("Manus 任务出错")
        val m = exec(p.spec, p.base, r("/task.listMessages?task_id=$id&order=desc&limit=20").build(), 30)
        check(m)
        val arr = JSONObject(m.body).optJSONArray("messages") ?: JSONArray()
        val text = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "assistant_message" }?.optJSONObject("assistant_message")?.optString("content")
            ?: throw MemberError("Manus 没有给出回复")
        return Reply(text + if (status == "waiting") "\n（Manus 在等你补充信息）" else "", model = "Manus ${GroupPrefs.model(p.id) ?: "lite"}")
    }

    // Coze v3: POST /v3/chat (stream=false) → GET /v3/chat/retrieve until completed → /v3/chat/message/list (type=answer)
    private suspend fun coze(p: Partner, system: String, turns: List<Turn>): Reply {
        val key = p.key ?: throw MemberError("缺扣子令牌")
        val bot = CozeBots.botIdOf(p) ?: throw MemberError("扣子里还没有智能体")
        fun r(path: String) = Request.Builder().url(p.base.trimEnd('/') + path).header("Authorization", "Bearer $key")
        val body = JSONObject().put("bot_id", bot).put("user_id", "mines").put("stream", false).put("auto_save_history", true)
            .put("additional_messages", JSONArray().put(JSONObject().put("role", "user").put("content", flat(system, turns)).put("content_type", "text")))
        val h = exec(p.spec, p.base, r("/chat").post(body.toString().toRequestBody(json)).build(), 60)
        check(h)
        val o = JSONObject(h.body)
        val cc = o.optInt("code", 0)
        if (cc == 4100) throw MemberError("扣子登录过期了，点「重新登录」", o.optString("msg"))
        if (cc == 4101) throw MemberError("扣子令牌少了权限，点「重新登录」", o.optString("msg"))
        if (cc != 0) throw MemberError("扣子：" + o.optString("msg").take(100))
        val data = o.optJSONObject("data") ?: throw MemberError("扣子没有返回对话")
        val chatId = data.optString("id"); val conv = data.optString("conversation_id")
        val t0 = System.currentTimeMillis()
        var status = data.optString("status")
        while (status !in setOf("completed", "failed", "requires_action", "canceled") && System.currentTimeMillis() - t0 < 3 * 60_000) {
            delay(1500)
            val d = exec(p.spec, p.base, r("/chat/retrieve?conversation_id=$conv&chat_id=$chatId").build(), 30)
            status = runCatching { JSONObject(d.body).optJSONObject("data")?.optString("status") }.getOrNull() ?: status
        }
        if (status != "completed") throw MemberError("扣子对话没完成（$status）")
        val m = exec(p.spec, p.base, r("/chat/message/list?conversation_id=$conv&chat_id=$chatId").build(), 30)
        check(m)
        val arr = JSONObject(m.body).optJSONArray("data") ?: JSONArray()
        val text = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("role") == "assistant" && it.optString("type") == "answer" }.joinToString("\n") { it.optString("content") }
            .ifBlank { throw MemberError("扣子没有给出回复") }
        return Reply(text, model = "扣子智能体")
    }
}
