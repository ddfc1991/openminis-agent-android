package com.openminis.app.plus

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.9.15] Cloudflare by API instead of the dashboard (user 2026-10-25 12:26: Mines looped 64 times trying to
 * sign in to dash.cloudflare.com). With a user-saved API token (encrypted vault, set in 账号台账 or through the secure
 * input card) DNS and tunnels go through api.cloudflare.com; without one the tool says so and the browser stays the
 * fallback. The token never reaches the model.
 */
object CloudflareApi {
    const val TOOL_NAME = "cloudflare"
    private const val V = "cloudflare_api"
    private const val BASE = "https://api.cloudflare.com/client/v4"
    /** smoke only: a fake API base */
    @Volatile var baseOverride: String? = null
    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    fun token(c: Context): String? { PlusVault.init(c); return PlusVault.secretExtra(V, "token")?.trim()?.takeIf { it.isNotBlank() } }
    fun setToken(c: Context, t: String?) { PlusVault.init(c); PlusVault.setSecretExtra(V, "token", t?.trim()?.takeIf { it.isNotBlank() }) }

    fun definition() = AgentToolDefinition(
        name = TOOL_NAME,
        description = "Cloudflare through its API with the user's saved API token (no dashboard sign-in). " +
            "actions: zones; dns_list zone=<domain>; dns_create zone type name content [proxied] [ttl]; dns_update zone id [type name content proxied ttl]; " +
            "dns_delete zone id (always waits for the user's 同意 first); tunnels [account_id]; verify. Use this INSTEAD of the browser for DNS / tunnel work. " +
            "If it answers that no token is saved, ask the user to save one (accounts action=secret is not needed: 应用 → 账号台账 → API Key → Cloudflare), " +
            "and only then fall back to the browser.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user, in their language."),
            "action" to AgentToolParam("string", "zones | dns_list | dns_create | dns_update | dns_delete | tunnels | verify",
                enumValues = listOf("zones", "dns_list", "dns_create", "dns_update", "dns_delete", "tunnels", "verify")),
            "zone" to AgentToolParam("string", "the domain (example.com) or zone id"),
            "id" to AgentToolParam("string", "dns record id (dns_update / dns_delete)"),
            "type" to AgentToolParam("string", "A / AAAA / CNAME / TXT / MX …"),
            "name" to AgentToolParam("string", "record name, e.g. www or www.example.com"),
            "content" to AgentToolParam("string", "record value"),
            "proxied" to AgentToolParam("boolean", "orange cloud"),
            "ttl" to AgentToolParam("integer", "1 = auto"),
            "account_id" to AgentToolParam("string", "tunnels: account id (default: the token's first account)"),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "zone", "id", "type", "name", "content", "proxied", "ttl", "account_id"),
    )

    private fun call(token: String, method: String, path: String, body: JSONObject? = null): JSONObject {
        val req = Request.Builder().url((baseOverride ?: BASE) + path).header("Authorization", "Bearer $token")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            return runCatching { JSONObject(txt) }.getOrElse { JSONObject().put("success", false).put("errors", JSONArray().put(JSONObject().put("message", "HTTP ${r.code}"))) }
        }
    }

    fun errors(o: JSONObject): String = (o.optJSONArray("errors") ?: JSONArray()).let { a ->
        (0 until a.length()).joinToString("; ") { a.optJSONObject(it)?.optString("message").orEmpty() }.ifBlank { "Cloudflare 拒绝了请求" }
    }

    private fun zoneId(token: String, zone: String): String? {
        if (Regex("^[0-9a-f]{32}$").matches(zone)) return zone
        val o = call(token, "GET", "/zones?name=" + java.net.URLEncoder.encode(zone.trim().lowercase(), "UTF-8"))
        return o.optJSONArray("result")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
    }

    suspend fun runTool(c: Context, sid: String, argsJson: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        val a = runCatching { JSONObject(argsJson) }.getOrElse { return@withContext ToolExecutionResult("Error: bad arguments", false) }
        val t = token(c) ?: return@withContext ToolExecutionResult(
            "还没有保存 Cloudflare API token。请用户到 应用 → 账号台账 → API Key → Cloudflare 填一个（权限：Zone.DNS 编辑 + Account.Cloudflare Tunnel 读）。" +
                "没有 token 时才用浏览器打开 dash.cloudflare.com；登录失败两次就交给用户，不要循环。", false)
        runCatching {
            when (a.optString("action")) {
                "verify" -> call(t, "GET", "/user/tokens/verify").let { o -> ToolExecutionResult(if (o.optBoolean("success")) "token 有效（${o.optJSONObject("result")?.optString("status")}）" else errors(o), o.optBoolean("success")) }
                "zones" -> call(t, "GET", "/zones?per_page=50").let { o ->
                    if (!o.optBoolean("success")) return@let ToolExecutionResult(errors(o), false)
                    val r = o.optJSONArray("result") ?: JSONArray()
                    ToolExecutionResult((0 until r.length()).joinToString("\n") { i -> r.getJSONObject(i).let { "${it.optString("name")}  id=${it.optString("id")}  ${it.optString("status")}" } }.ifBlank { "这个 token 看不到任何域名" }, true)
                }
                "dns_list" -> {
                    val z = zoneId(t, a.optString("zone")) ?: return@runCatching ToolExecutionResult("找不到域名 ${a.optString("zone")}（token 没权限或不在这个账号）", false)
                    val o = call(t, "GET", "/zones/$z/dns_records?per_page=100")
                    if (!o.optBoolean("success")) return@runCatching ToolExecutionResult(errors(o), false)
                    val r = o.optJSONArray("result") ?: JSONArray()
                    ToolExecutionResult((0 until r.length()).joinToString("\n") { i -> r.getJSONObject(i).let { "${it.optString("type")} ${it.optString("name")} → ${it.optString("content")}${if (it.optBoolean("proxied")) " (代理)" else ""}  id=${it.optString("id")}" } }.ifBlank { "没有记录" }, true)
                }
                "dns_create", "dns_update" -> {
                    val z = zoneId(t, a.optString("zone")) ?: return@runCatching ToolExecutionResult("找不到域名 ${a.optString("zone")}", false)
                    val body = JSONObject()
                    listOf("type", "name", "content").forEach { k -> a.optString(k).takeIf { it.isNotBlank() }?.let { body.put(k, it) } }
                    if (a.has("proxied")) body.put("proxied", a.optBoolean("proxied"))
                    body.put("ttl", a.optInt("ttl", 1))
                    val o = if (a.optString("action") == "dns_create") call(t, "POST", "/zones/$z/dns_records", body)
                        else call(t, "PATCH", "/zones/$z/dns_records/" + a.optString("id"), body)
                    ToolExecutionResult(if (o.optBoolean("success")) "好了：" + o.optJSONObject("result")?.let { "${it.optString("type")} ${it.optString("name")} → ${it.optString("content")} id=${it.optString("id")}" } else errors(o), o.optBoolean("success"))
                }
                "dns_delete" -> {
                    val z = zoneId(t, a.optString("zone")) ?: return@runCatching ToolExecutionResult("找不到域名 ${a.optString("zone")}", false)
                    // [38.9.15] deleting a record always waits for the user's 同意 in 应用 → 伪人核心 → 等你点头 (限制层, user-only)
                    val zoneName = a.optString("zone").trim().lowercase()
                    val d = com.openminis.app.persona.Persona.confirmAlways(c, "cloudflare.com:$zoneName", com.openminis.app.persona.LimitGuardCore.Act.DELETE,
                        "删除 DNS 记录 ${a.optString("id").take(40)}（$zoneName）")
                    if (!d.allowed) return@runCatching com.openminis.app.persona.Persona.blocked(sid, d)
                    val o = call(t, "DELETE", "/zones/$z/dns_records/" + a.optString("id"))
                    ToolExecutionResult(if (o.optBoolean("success")) "已删除记录 ${a.optString("id")}" else errors(o), o.optBoolean("success"))
                }
                "tunnels" -> {
                    val acct = a.optString("account_id").ifBlank { call(t, "GET", "/accounts?per_page=5").optJSONArray("result")?.optJSONObject(0)?.optString("id").orEmpty() }
                    if (acct.isBlank()) return@runCatching ToolExecutionResult("这个 token 看不到账号（要 Account 权限）", false)
                    val o = call(t, "GET", "/accounts/$acct/cfd_tunnel?is_deleted=false")
                    if (!o.optBoolean("success")) return@runCatching ToolExecutionResult(errors(o), false)
                    val r = o.optJSONArray("result") ?: JSONArray()
                    ToolExecutionResult((0 until r.length()).joinToString("\n") { i -> r.getJSONObject(i).let { "${it.optString("name")}  ${it.optString("status")}  id=${it.optString("id")}" } }.ifBlank { "没有隧道" }, true)
                }
                else -> ToolExecutionResult("action 只能是 zones / dns_list / dns_create / dns_update / dns_delete / tunnels / verify", false)
            }
        }.getOrElse { ToolExecutionResult("Cloudflare API 连不上（${it.javaClass.simpleName}）。网络问题时再试一次；仍不行再用浏览器。", false) }
    }
}
