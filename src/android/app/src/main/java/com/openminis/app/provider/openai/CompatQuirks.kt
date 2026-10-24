package com.openminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.9] Per-endpoint request / error quirks of OpenAI-compatible hosts the user has (pure, unit-tested):
 *  - Cloudflare Workers AI (`api.cloudflare.com/client/v4/accounts/{id}/ai/v1`): model ids need `@cf/` / `@hf/`; the
 *    OpenAI extras Mines adds (`stream_options`, `max_completion_tokens`, `parallel_tool_calls`, `service_tier`,
 *    `thinking`, `reasoning_effort`) are not part of its schema; errors come as `{"errors":[{"message":…}]}`.
 *  - GitHub Models (`models.github.ai/inference`): no `/v1`, ids are `publisher/model`, list at /catalog/models,
 *    401 = token without the Models permission, 403 / 429 = free daily quota used up.
 *  - AMD (developer.amd.com.cn): `thinking` rejected → [ThinkingParamFallback]; errors in the OpenAI shape.
 * [errorText] turns any upstream body into one short Chinese line that keeps the server's own message.
 */
object CompatQuirks {
    fun isCloudflare(base: String?): Boolean = base?.lowercase()?.let { it.contains("api.cloudflare.com") && it.contains("/ai/") } == true
    fun isGitHubModels(base: String?): Boolean = base?.lowercase()?.contains("models.github.ai") == true
    fun isAmd(base: String?): Boolean = base?.lowercase()?.contains("amd.com") == true

    const val GITHUB_MODELS_BASE = "https://models.github.ai/inference"
    const val GITHUB_MODELS_CATALOG = "https://models.github.ai/catalog/models"

    /** Cloudflare model id with its catalogue prefix (`@cf/…`); ids that already carry `@` are kept. */
    fun cfModelId(id: String): String = if (id.startsWith("@")) id else "@cf/" + id.trimStart('/')

    /** Adjusts the request body in place for the endpoint; returns the names of what was changed (for the log). */
    fun sanitize(body: JSONObject, base: String?): List<String> {
        val changed = mutableListOf<String>()
        fun drop(k: String) { if (body.remove(k) != null) changed += "-$k" }
        if (isCloudflare(base)) {
            body.optString("model").takeIf { it.isNotEmpty() && !it.startsWith("@") }?.let { body.put("model", cfModelId(it)); changed += "model" }
            if (body.has("max_completion_tokens")) { body.put("max_tokens", body.opt("max_completion_tokens")); body.remove("max_completion_tokens"); changed += "max_tokens" }
            drop("stream_options"); drop("parallel_tool_calls"); drop("service_tier"); drop("thinking"); drop("reasoning_effort")
        } else if (isGitHubModels(base)) {
            if (body.has("max_completion_tokens") && !body.optString("model").startsWith("openai/o")) {
                body.put("max_tokens", body.opt("max_completion_tokens")); body.remove("max_completion_tokens"); changed += "max_tokens"
            }
            drop("service_tier"); drop("thinking")
        }
        return changed
    }

    /** The server's own message from an error body (OpenAI `error.message`, Cloudflare `errors[0].message`, `detail`, `message`). */
    fun upstreamMessage(body: String): String {
        val t = body.trim()
        if (t.isEmpty()) return ""
        val o = runCatching { JSONObject(t) }.getOrNull() ?: return t.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(200)
        o.optJSONObject("error")?.let { e -> e.optString("message").ifBlank { e.optString("code") }.takeIf { it.isNotBlank() }?.let { return it.take(300) } }
        o.optString("error").takeIf { it.isNotBlank() && !it.startsWith("{") }?.let { return it.take(300) }
        (o.opt("errors") as? JSONArray)?.optJSONObject(0)?.let { e -> e.optString("message").takeIf { it.isNotBlank() }?.let { return it.take(300) } }
        o.optString("detail").takeIf { it.isNotBlank() }?.let { return it.take(300) }
        o.optString("message").takeIf { it.isNotBlank() }?.let { return it.take(300) }
        return t.take(200)
    }

    private fun errorCode(body: String): String = runCatching {
        val o = JSONObject(body.trim()); o.optJSONObject("error")?.optString("code").orEmpty().ifBlank { o.optString("code") }
    }.getOrDefault("")

    /**
     * One short Chinese line for an upstream HTTP error that always ends with the server's own words, or null when there is
     * nothing to add (empty body). Never hides the real text behind a generic 「服务器暂无响应」.
     */
    fun errorText(code: Int, body: String, base: String?): String? {
        val up = upstreamMessage(body)
        val low = (up + " " + errorCode(body)).lowercase()
        val prefix = when {
            isCopilot(base) -> com.openminis.app.auth.CopilotDeviceFlow.chatErrorZh(code, body)
            isGitHubModels(base) && code == 401 -> "GitHub 令牌没有 Models 权限（新建细粒度令牌，勾选 Models: read）"
            isGitHubModels(base) && (code == 429 || (code == 403 && (low.contains("rate") || low.contains("quota") || low.contains("limit")))) -> "GitHub Models 今天的免费额度用完了，明天再试或换模型"
            isGitHubModels(base) && code == 403 -> "GitHub 拒绝了这个令牌（权限不够或账号没开通 Models）"
            isGitHubModels(base) && (low.contains("unknown_model") || low.contains("unknown model") || code == 404) -> "GitHub Models 没有这个模型 id（要写成 发布者/模型，例如 openai/gpt-4.1）"
            isCloudflare(base) && (code == 401 || code == 403) -> "Cloudflare 令牌没有 Workers AI 权限，或账号 ID 不对"
            isCloudflare(base) && (low.contains("no route") || low.contains("model not found") || low.contains("not found") || code == 404) -> "Cloudflare 没有这个模型（id 要以 @cf/ 开头）"
            isCloudflare(base) && code in 500..599 -> "Cloudflare Workers AI 出错（$code）"
            code == 429 -> "请求太频繁或额度用完（429）"
            code == 502 -> "上游网关出错（502）"
            code == 503 -> "上游暂时不可用（503）"
            code == 504 -> "上游超时（504）"
            else -> "HTTP $code"
        }
        return if (up.isBlank()) prefix else "$prefix：$up"
    }

    /** SSE payloads that carry nothing and must be skipped silently (keepalive comments are already non-`data:` lines). */
    fun isEmptyPayload(payload: String): Boolean {
        val p = payload.trim()
        if (p.isEmpty() || p == "{}" || p == "null") return true
        if (!p.startsWith("{")) return false
        val o = runCatching { JSONObject(p) }.getOrNull() ?: return false
        if (o.has("usage") && !o.isNull("usage")) return false
        if (o.has("error") || o.has("type")) return false
        val ch = o.optJSONArray("choices") ?: return o.length() == 0 || (o.length() <= 3 && (o.has("id") || o.has("object")) && !o.has("output"))
        if (ch.length() == 0) return true
        val c0 = ch.optJSONObject(0) ?: return true
        if (!c0.isNull("finish_reason") && c0.optString("finish_reason").isNotEmpty()) return false
        val d = c0.optJSONObject("delta") ?: return !c0.has("message") && !c0.has("text")
        return d.length() == 0 || (d.keys().asSequence().all { k -> d.isNull(k) || (d.opt(k) is String && d.optString(k).isEmpty() && k != "role") })
    }

    /** GitHub Models catalogue (`[{"id":"openai/gpt-4.1","name":"OpenAI GPT-4.1",...}]`) → (id, name) pairs. */
    fun parseGitHubCatalog(body: String): List<Pair<String, String>> {
        val arr = runCatching { JSONArray(body.trim()) }.getOrNull()
            ?: runCatching { JSONObject(body.trim()).optJSONArray("data") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { o -> o.optJSONArray("supported_output_modalities")?.let { m -> (0 until m.length()).any { m.optString(it) == "text" } } ?: true }
            .mapNotNull { o -> o.optString("id").takeIf { it.isNotBlank() }?.let { it to o.optString("name").ifBlank { it } } }
    }

    /**
     * plain-Chinese reason for a token-exchange HTTP status + body (pure, unit-tested). [Mines-38.9.11 i32] [diag] =
     * api.github.com/copilot_internal/user read with the same token: when it says the account HAS Copilot (chat_enabled /
     * access_type_sku), a 403 is the token being refused — never 「没有开通」 (the user's account: free_limited_copilot).
     * A plain-text 403 «…scraping GitHub…» is GitHub refusing that token for this endpoint.
     */
    fun copilotExchangeError(status: Int, body: String, diag: JSONObject? = null): String {
        val up = upstreamMessage(body)
        val low = up.lowercase()
        val sku = diag?.optString("access_type_sku").orEmpty()
        val hasCopilot = diag != null && !diag.has("_status") && (diag.optBoolean("chat_enabled", false) || sku.isNotBlank())
        val plan = when {
            sku.contains("free") -> "免费版"
            sku.isNotBlank() || diag?.optString("copilot_plan").orEmpty().isNotBlank() -> diag?.optString("copilot_plan").orEmpty().ifBlank { sku }
            else -> ""
        }
        val scraping = low.contains("scraping") || (status == 403 && body.trim().let { it.isNotEmpty() && !it.startsWith("{") })
        val head = when {
            status == 401 -> "GitHub 登录已失效，请点「重新登录」"
            !scraping && status == 403 && (low.contains("enterprise") || low.contains("organization") || low.contains("policy")) -> "这个 GitHub 账号的组织不允许使用 Copilot"
            hasCopilot && (status == 403 || status == 404) ->
                "你的账号有 Copilot（${plan.ifBlank { "已开通" }}），但 GitHub 不肯用这次的登录换 Copilot 令牌：请点「重新登录」重新授权"
            diag?.optInt("_status", 0) == 401 -> "GitHub 登录已失效（令牌被撤销），请点「重新登录」"
            scraping -> "GitHub 拒绝了这次登录令牌（不是 Copilot 授权发的，或授权被撤销）：请点「重新登录」"
            status == 403 || status == 404 -> "这个 GitHub 账号没有开通 Copilot（需要 Copilot Free/Pro 订阅，先在 github.com/settings/copilot 开通）"
            status == 429 -> "GitHub Copilot 请求太频繁，稍后再试"
            status in 500..599 -> "GitHub Copilot 服务出错（$status）"
            else -> "换取 Copilot 令牌失败（HTTP $status）"
        }
        val tail = if (scraping) "" else up.take(160)
        return if (tail.isBlank()) "$head（HTTP $status）" else "$head（HTTP $status：$tail）"
    }

    fun isCopilot(base: String?): Boolean = base?.lowercase()?.contains("githubcopilot.com") == true
}
