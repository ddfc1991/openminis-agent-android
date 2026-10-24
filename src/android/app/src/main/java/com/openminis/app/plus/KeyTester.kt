package com.openminis.app.plus

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * [Mines-keys-31] One cheap call per provider to prove a key works, and read
 * the account name when the provider says it ("who am I"). Never spends money
 * beyond a 1-token request where no free endpoint exists (Perplexity).
 * Domestic providers go direct; overseas ones try direct, then the local
 * proxies Mines knows (分流代理 / VPN-app ports).
 */
object KeyTester {
    data class Result(val ok: Boolean, val message: String, val identity: String? = null, val needsCredit: Boolean = false)

    private val domestic = setOf("qwen", "deepseek", "kimi", "zhipu", "siliconflow", "aliyun", "sensenova", "volcengine", "minimax")

    private fun clients(base: String): List<OkHttpClient> {
        fun c(p: Proxy) = OkHttpClient.Builder().proxy(p).connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
        val out = arrayListOf(c(Proxy.NO_PROXY))
        if (base !in domestic) {
            if (SplitProxy.active) out += c(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", SplitProxy.PORT)))
            SplitProxy.settings.value.all.take(2).forEach { u ->
                runCatching { out += c(Proxy(if (u.type == "socks5") Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(u.host, u.port))) }
            }
        }
        return out
    }

    private data class Http(val code: Int, val body: String)

    private fun call(base: String, req: Request): Http {
        var last: Exception? = null
        for (cl in clients(base)) {
            try { cl.newCall(req).execute().use { r -> return Http(r.code, r.body?.string().orEmpty().take(4000)) } }
            catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("连不上")
    }

    /**
     * [Mines-keys-35.1] Cloudflare (user 2026-10-14: a new token kept showing the old error). User tokens verify at
     * /user/tokens/verify; account-owned tokens only at /accounts/{id}/tokens/verify (the user endpoint says 401
     * for them), so on a non-2xx we look up the accounts the token can see and verify there. A Global API Key is
     * not a token: say so instead of "invalid". The verify body's status (active/disabled/expired) is checked too.
     */
    private fun cloudflare(base: String, key: String, verdict: (Http, (JSONObject) -> String?) -> Result): Result {
        if (Regex("^[0-9a-f]{37}$").matches(key)) return Result(false, "这是 Global API Key，不是 API 令牌。请在 Cloudflare「API 令牌」里创建一个令牌再填")
        fun active(h: Http): Boolean = h.code in 200..299 && runCatching {
            val o = JSONObject(h.body); o.optBoolean("success", true) && o.optJSONObject("result")?.optString("status", "active").let { it == null || it == "active" }
        }.getOrDefault(true)
        fun statusOf(h: Http): String? = runCatching { JSONObject(h.body).optJSONObject("result")?.optString("status") }.getOrNull()?.takeIf { it.isNotBlank() && it != "active" }
        val u = call(base, bearer("https://api.cloudflare.com/client/v4/user/tokens/verify", key))
        if (active(u)) return Result(true, "可以用")
        statusOf(u)?.let { return Result(false, if (it == "expired") "令牌已过期" else "令牌已停用（$it）") }
        val acc = runCatching { call(base, bearer("https://api.cloudflare.com/client/v4/accounts?per_page=20", key)) }.getOrNull()
        val ids = acc?.takeIf { it.code in 200..299 }?.let { h -> runCatching {
            val a = JSONObject(h.body).optJSONArray("result"); (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it).optString("id") to a.getJSONObject(it).optString("name") }
        }.getOrNull() }.orEmpty().filter { it.first.isNotBlank() }
        for ((id, name) in ids) {
            val h = call(base, bearer("https://api.cloudflare.com/client/v4/accounts/$id/tokens/verify", key))
            if (active(h)) return Result(true, "可以用（账户令牌）", name.ifBlank { null })
            statusOf(h)?.let { return Result(false, if (it == "expired") "令牌已过期" else "令牌已停用（$it）") }
        }
        // the token is valid for some resources but can read neither endpoint → only a real 401/403 on both is "invalid"
        if (ids.isEmpty() && acc != null && acc.code in 200..299) return Result(true, "可以用（令牌权限受限，无法读取账户列表）")
        return verdict(u) { null }
    }

    private fun bearer(url: String, key: String) = Request.Builder().url(url).header("Authorization", "Bearer $key").build()

    /** [Mines-keys-35.3] adb self-test only: fake keys the smoke's fake key page hands out */
    val smokeKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    fun test(baseId: String, key: String): Result = if (key.trim() in smokeKeys) Result(true, "可以用（自测假密钥）") else runCatching { testInner(baseId, key.trim()) }
        .getOrElse { Result(false, "测试时连不上服务器：${it.message?.take(80)}") }

    private fun testInner(base: String, key: String): Result {
        fun verdict(h: Http, ident: (JSONObject) -> String? = { null }): Result = when {
            h.code in 200..299 -> Result(true, "可以用", runCatching { ident(JSONObject(h.body)) }.getOrNull()?.takeIf { it.isNotBlank() })
            h.code == 401 || h.code == 403 -> Result(false, "密钥无效或没有权限（HTTP ${h.code}）")
            h.code == 402 || h.body.contains("insufficient", true) || h.body.contains("credit", true) || h.body.contains("余额", true) ->
                Result(true, "密钥有效，但账户余额不足，需要你自己充值（Mines 不会替你付钱）", needsCredit = true)
            h.code == 429 -> Result(true, "密钥有效（暂时限流）")
            else -> Result(false, "服务器回复 HTTP ${h.code}")
        }
        return when (base) {
            "gemini" -> verdict(call(base, Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1&key=$key").build()))
            "mistral" -> verdict(call(base, bearer("https://api.mistral.ai/v1/models", key)))
            "perplexity" -> verdict(call(base, Request.Builder().url("https://api.perplexity.ai/chat/completions")
                .header("Authorization", "Bearer $key")
                .post("""{"model":"sonar","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}""".toRequestBody("application/json".toMediaType())).build()))
            "huggingface" -> verdict(call(base, bearer("https://huggingface.co/api/whoami-v2", key))) { it.optString("name") }
            "digitalocean" -> verdict(call(base, bearer("https://api.digitalocean.com/v2/account", key))) { it.optJSONObject("account")?.optString("email") }
            "cloudflare" -> cloudflare(base, key, ::verdict)
            "github" -> verdict(call(base, bearer("https://api.github.com/user", key))) { it.optString("login") }
            "qwen" -> verdict(call(base, bearer(RouteStatusCore.bailianBase(key, null, com.openminis.app.plus.group.GroupPrefs.get("bailian_ws")) + "/models", key)))  // [Mines-38.9.5 i] sk-ws- → workspace endpoint
            "openai" -> verdict(call(base, bearer("https://api.openai.com/v1/models", key)))
            "deepseek" -> verdict(call(base, bearer("https://api.deepseek.com/models", key)))
            "openrouter" -> verdict(call(base, bearer("https://openrouter.ai/api/v1/key", key))) { it.optJSONObject("data")?.optString("label") }
            "siliconflow" -> verdict(call(base, bearer("https://api.siliconflow.cn/v1/user/info", key))) { it.optJSONObject("data")?.optString("name") }
            "kimi" -> verdict(call(base, bearer("https://api.moonshot.cn/v1/models", key)))
            // [Mines-38.1b] 扣子: Coze answers with a JSON code (4100 = bad token, 4101 = token OK but lacks this permission)
            "coze" -> {
                val h = call(base, bearer("https://api.coze.cn/v1/workspaces?page_num=1&page_size=1", key))
                val code = runCatching { JSONObject(h.body).optInt("code", -1) }.getOrDefault(-1)
                when {
                    code == 0 && h.code in 200..299 -> Result(true, "可以用")
                    code == 4101 -> Result(true, "令牌有效（没勾「工作空间」权限，不影响和智能体对话）")
                    code == 4100 || h.code == 401 -> Result(false, "令牌无效或已过期（扣子 4100）")
                    else -> verdict(h)
                }
            }
            "zhipu" -> verdict(call(base, bearer("https://open.bigmodel.cn/api/paas/v4/models", key)))
            "xai" -> verdict(call(base, bearer("https://api.x.ai/v1/api-key", key))) { it.optString("name") }
            "anthropic" -> verdict(call(base, Request.Builder().url("https://api.anthropic.com/v1/models")
                .header("x-api-key", key).header("anthropic-version", "2023-06-01").build()))
            // [Mines-apps-32]
            "zai" -> verdict(call(base, bearer("https://api.z.ai/api/paas/v4/models", key)))
            "groq" -> verdict(call(base, bearer("https://api.groq.com/openai/v1/models", key)))
            "volcengine" -> verdict(call(base, bearer("https://ark.cn-beijing.volces.com/api/v3/models", key)))
            "elevenlabs" -> verdict(call(base, Request.Builder().url("https://api.elevenlabs.io/v1/user").header("xi-api-key", key).build()))
            "linear" -> verdict(call(base, Request.Builder().url("https://api.linear.app/graphql").header("Authorization", key)
                .post("""{"query":"{ viewer { email } }"}""".toRequestBody("application/json".toMediaType())).build())) { it.optJSONObject("data")?.optJSONObject("viewer")?.optString("email") }
            "stripe" -> verdict(call(base, bearer("https://api.stripe.com/v1/balance", key)))
            else -> Result(true, "已保存（这个服务没有测试接口）")
        }
    }
}
