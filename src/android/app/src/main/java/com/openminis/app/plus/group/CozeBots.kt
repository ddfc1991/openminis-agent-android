package com.openminis.app.plus.group

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.6] 扣子 with no technical fields (user 2026-10-15 「扣子接入后，有直接调用的办法吗？不然给我接口用不了没啥用」).
 * With the token 账号连接 saved, Mines lists the user's bots (GET /v1/workspaces → GET /v1/bots?workspace_id=…&publish_status=all,
 * docs.coze.cn/developer_guides/bots_list_draft_published), finds which ones are on the API channel
 * (publish_status=published_online&connector_id=1024) and publishes the rest there (POST /v1/bot/publish
 * {bot_id, connector_ids:["1024"]}, docs.coze.cn/developer_guides/publish_bot). Every bot then is its own member
 * "coze:<bot id>" with its name and icon; Bot ID / token / address only appear under 「详情」.
 * Old tokens without listBot fall back to the deprecated /v1/space/published_bots_list.
 */
object CozeBots {
    data class Bot(val id: String, val name: String, val icon: String?, val ready: Boolean, val problem: String? = null)
    data class Sync(val bots: List<Bot>, val problem: String? = null, val relogin: Boolean = false, val published: List<String> = emptyList())

    private val json = "application/json".toMediaType()
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    /** api.coze.cn root from the member base (".../v3"). */
    fun root(base: String) = base.trimEnd('/').removeSuffix("/v3").removeSuffix("/v1")

    private class Res(val http: Int, val o: JSONObject?) {
        val code: Int get() = o?.optInt("code", if (http in 200..299) 0 else -1) ?: -1
        val msg: String get() = o?.optString("msg").orEmpty()
        val ok: Boolean get() = http in 200..299 && code == 0
    }

    private fun call(url: String, key: String, body: JSONObject? = null): Res = runCatching {
        val b = Request.Builder().url(url).header("Authorization", "Bearer $key").header("Content-Type", "application/json")
        if (body != null) b.post(body.toString().toRequestBody(json))
        http.newCall(b.build()).execute().use { r -> Res(r.code, runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()) }
    }.getOrElse { Res(0, null) }

    /** One plain line for a failed call; relogin = the token is bad / missing a permission → 「重新登录」. */
    private fun problem(r: Res): Pair<String, Boolean> = when {
        r.http == 0 -> "连不上扣子（网络问题）" to false
        r.http == 401 || r.code == 4100 || r.msg.contains("token", true) && r.msg.contains("invalid", true) ->
            "扣子登录过期了，点「重新登录」" to true
        r.http == 403 || r.code == 4101 || r.msg.contains("permission", true) || r.msg.contains("权限") ->
            "扣子令牌少了权限，点「重新登录」重新拿" to true
        else -> "扣子暂时没回应，稍后会再试" to false
    }

    /** [Mines-38.7] one cache per 扣子 account (token): two accounts never mix their bots. */
    private val caches = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Sync>>()

    fun cached(): Sync? {
        val all = caches.values.map { it.second }
        if (all.isNotEmpty()) return Sync(all.flatMap { it.bots }.distinctBy { it.id }, all.firstNotNullOfOrNull { it.problem }, all.any { it.relogin })
        return GroupPrefs.get("coze_bots")?.let { decode(it) }
    }
    private fun cachedFor(key: String): Sync? = caches[key]?.second

    /** Lists, auto-publishes to the API channel, remembers. Cached for 10 min unless [force]. */
    fun sync(base: String, key: String, force: Boolean = false): Sync {
        if (!force) caches[key]?.let { (at, c) -> if (System.currentTimeMillis() - at < 600_000) return c }
        val root = root(base)
        val ws = call("$root/v1/workspaces?page_num=1&page_size=20", key)
        if (!ws.ok) {
            val (p, re) = problem(ws)
            return remember(key, Sync(cachedFor(key)?.bots ?: emptyList(), p, re))
        }
        val spaces = ws.o?.optJSONObject("data")?.optJSONArray("workspaces") ?: JSONArray()
        val bots = LinkedHashMap<String, Bot>()
        val published = ArrayList<String>()
        var lastProblem: Pair<String, Boolean>? = null
        for (i in 0 until spaces.length()) {
            val wid = spaces.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() } ?: continue
            val all = call("$root/v1/bots?workspace_id=$wid&publish_status=all&page_num=1&page_size=50", key)
            if (!all.ok) {
                lastProblem = problem(all)
                // old token without listBot: the deprecated list of API-published bots still works
                val old = call("$root/v1/space/published_bots_list?space_id=$wid&page_size=50", key)
                old.o?.optJSONObject("data")?.optJSONArray("space_bots")?.let { a ->
                    for (j in 0 until a.length()) a.optJSONObject(j)?.let { b ->
                        val id = b.optString("bot_id"); if (id.isNotBlank()) bots[id] = Bot(id, b.optString("bot_name").ifBlank { "扣子智能体" }, b.optString("icon_url").ifBlank { null }, true)
                    }
                }
                continue
            }
            val items = all.o?.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()
            val onApi = call("$root/v1/bots?workspace_id=$wid&publish_status=published_online&connector_id=1024&page_num=1&page_size=50", key)
            val apiIds = HashSet<String>()
            onApi.o?.optJSONObject("data")?.optJSONArray("items")?.let { a -> for (j in 0 until a.length()) a.optJSONObject(j)?.optString("id")?.let { apiIds += it } }
            for (j in 0 until items.length()) {
                val b = items.optJSONObject(j) ?: continue
                val id = b.optString("id").ifBlank { b.optString("bot_id") }.takeIf { it.isNotBlank() } ?: continue
                val name = b.optString("name").ifBlank { "扣子智能体" }
                val icon = b.optString("icon_url").ifBlank { null }
                if (id in apiIds) { bots[id] = Bot(id, name, icon, true); continue }
                // not on the API channel yet → publish it there for the user
                val pub = call("$root/v1/bot/publish", key, JSONObject().put("bot_id", id).put("connector_ids", JSONArray().put("1024")))
                if (pub.ok) { published += id; bots[id] = Bot(id, name, icon, true) }
                else {
                    val (p, re) = problem(pub)
                    bots[id] = Bot(id, name, icon, false, if (re) p else "没能自动发布到 API（${pub.msg.take(40).ifBlank { "扣子拒绝" }}）")
                }
            }
        }
        val list = bots.values.toList()
        val pr = if (list.isEmpty()) (lastProblem ?: ("扣子里还没有智能体：在扣子建一个，回来自动出现" to false)) else null
        return remember(key, Sync(list, pr?.first, pr?.second == true, published))
    }

    private fun remember(key: String, s: Sync): Sync {
        caches[key] = System.currentTimeMillis() to s
        cached()?.let { GroupPrefs.put("coze_bots", encode(it)) }
        return s
    }

    private fun encode(s: Sync) = JSONObject().put("problem", s.problem ?: "").put("relogin", s.relogin)
        .put("bots", JSONArray(s.bots.map { JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon ?: "").put("ready", it.ready).put("problem", it.problem ?: "") }))
        .toString()

    private fun decode(t: String): Sync? = runCatching {
        val o = JSONObject(t)
        val a = o.optJSONArray("bots") ?: JSONArray()
        Sync((0 until a.length()).map { a.getJSONObject(it) }.map { Bot(it.getString("id"), it.optString("name"), it.optString("icon").ifBlank { null }, it.optBoolean("ready"), it.optString("problem").ifBlank { null }) },
            o.optString("problem").ifBlank { null }, o.optBoolean("relogin"))
    }.getOrNull()

    /** The member spec of one bot (id "coze:<bot id>"). */
    fun spec(id: String): AiSpec? {
        if (!id.startsWith("coze:")) return null
        val b = cached()?.bots?.firstOrNull { "coze:" + it.id == id }
        return specFor(id.removePrefix("coze:"), b?.name ?: "扣子智能体", b?.icon)
    }

    fun specFor(botId: String, name: String, icon: String?): AiSpec {
        val base = AiCatalog.specs.first { it.id == "coze" }
        return base.copy(id = "coze:$botId", name = name, iconUrl = icon, note = "你在扣子的智能体", kind = Kind.AGENT)
    }

    /** (bot id, name) pairs for the old member dialog. */
    fun list(key: String): List<Pair<String, String>> = sync(GroupPrefs.get("base_coze") ?: AiCatalog.specs.first { it.id == "coze" }.baseUrl, key).bots.map { it.id to it.name }

    fun botIdOf(p: Partner): String? = if (p.id.startsWith("coze:")) p.id.removePrefix("coze:") else GroupPrefs.bot(p.id)
}
