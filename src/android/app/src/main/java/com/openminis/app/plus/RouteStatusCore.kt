package com.openminis.app.plus

/**
 * [Mines-38.9.5 i] user 2026-10-17 19:50: models in 模型组 whose API comes from an automatic sign-in could not be called
 * (扣子 → PAT, Muse 中继 → nm_ key, Memoh → JWT / web cookie, the web-chat bridges). What was wrong / what this fixes:
 *  - those routes only existed as 「AI 伙伴」 members: 模型组 had no provider for them, so nothing there could call them →
 *    [PartnerGateway] serves them on 127.0.0.1 as an OpenAI-compatible provider 「免登录 AI（Mines）」 in 模型组;
 *  - Muse 中继: the relay's own API is /v1/auth/code + /v1/auth/verify and verify answers {api_key, base_url, …}; an
 *    address saved without /v1 sent the sign-in to the /auth paths (404 → 「中继地址不对」) and chat to /chat/completions → the base
 *    is normalised to …/v1 and base_url from verify is kept;
 *  - errors became one generic line → every HTTP / relay code maps to one short Chinese reason (502 from a self-hosted
 *    new-api gateway = its server is not running it, e.g. after a reinstall — said plainly, nothing is retried forever).
 * Pure part, unit-tested in RouteStatusCoreTest.
 */
object RouteStatusCore {
    enum class St(val label: String) { OK("能用"), RELOGIN("要重新登录"), NEED_LOGIN("没登录"), DOWN("连不上"), WARN("要注意"), UNCHECKED("未检查") }

    data class Row(val id: String, val name: String, val via: String, val st: St, val reason: String = "", val canAutoRelogin: Boolean = false)

    /** "http://1.2.3.4:8787" / ".../v1/" / ".../v1/chat/completions" → "http://1.2.3.4:8787/v1". */
    fun relayBase(input: String?): String {
        var u = input?.trim().orEmpty().trimEnd('/')
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        u = u.removeSuffix("/chat/completions").removeSuffix("/models").trimEnd('/')
        u = u.removeSuffix("/auth/verify").removeSuffix("/auth/code").removeSuffix("/auth").trimEnd('/')
        return if (Regex("/v\\d+$").containsMatchIn(u)) u else "$u/v1"
    }

    /** One short reason for an HTTP answer of a model route ([host] for the gateway hint). */
    fun httpReason(code: Int, body: String = "", host: String = ""): String {
        val b = body.lowercase()
        val err = Regex("\"(?:code|error|type)\"\\s*:\\s*\"([a-z_0-9]+)\"").find(b)?.groupValues?.get(1).orEmpty()
        return when {
            err == "bad_key" || err == "account_deleted" -> "密钥失效（$err）：重新登录一次"
            err == "account_disabled" -> "这个账号被停用了"
            err == "out_of_tokens" || err == "allowance_exhausted" || code == 402 -> "额度用完了"
            err == "model_not_offered" -> "这个模型对方不提供了，换一个"
            err == "too_many_in_flight" || code == 429 -> "太忙或请求太频繁，稍后再试"
            code == 401 || code == 403 -> "登录过期或密钥无效（$code）"
            code == 404 -> "地址不对（$code）"
            code == 502 && selfHostedGateway(host) -> "网关没有响应（502）：$host 所在的服务器上 new-api 没在跑（服务器重装过就会这样），换别的模型"
            code == 502 -> "服务器暂无响应（502）"
            code == 503 -> "服务器忙或没配置好（503）"
            code == 504 -> "服务器超时（504）"
            code in 500..599 -> "服务器出错（$code）"
            code == 0 -> "连不上（网络或地址）"
            else -> "没有正常回复（HTTP $code）"
        }
    }

    /** A gateway the user hosts (new-api / one-api on their own domain or IP), not a big vendor's API. */
    fun selfHostedGateway(host: String): Boolean {
        val h = host.lowercase().substringAfter("://").substringBefore('/').substringBefore(':')
        if (h.isBlank()) return false
        if (h.startsWith("newapi.") || h.startsWith("oneapi.") || h.startsWith("new-api.") || h.contains("dpdns.org") || h.endsWith(".eu.org")) return true
        return Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h)
    }

    /** AI 伙伴 state + reason → the 模型组 status row. */
    fun fromPartner(id: String, name: String, via: String, state: String, reason: String?, autoRelogin: Boolean): Row {
        val r = reason.orEmpty()
        val st = when (state) {
            "READY" -> St.OK
            "UNREACHABLE" -> St.DOWN
            "NEED_LOGIN" -> St.NEED_LOGIN
            "NEED_KEY" -> if (Regex("过期|失效|重新登录|4100|bad_key").containsMatchIn(r)) St.RELOGIN else St.NEED_LOGIN
            "OFF" -> St.UNCHECKED
            else -> St.UNCHECKED
        }
        return Row(id, name, via, st, r.ifBlank { if (st == St.OK) "" else st.label }, autoRelogin && (st == St.RELOGIN || st == St.NEED_LOGIN))
    }

    /**
     * 百炼 (DashScope): the legacy shared endpoint dashscope.aliyuncs.com is in maintenance since 2026-09-30; the new
     * workspace keys (sk-ws-…) only work on their workspace's MaaS endpoint. [saved] = the endpoint remembered for the
     * user's workspace (pref), [current] = what the provider / member was configured with.
     */
    const val BAILIAN_LEGACY = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val BAILIAN_WS_DEFAULT = "https://ws-celabh0rxdgadjmw.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
    fun bailianWorkspaceKey(key: String?): Boolean = key?.trim()?.startsWith("sk-ws-") == true
    fun bailianBase(key: String?, current: String?, saved: String? = null): String {
        val cur = current?.trim()?.trimEnd('/').orEmpty()
        if (!bailianWorkspaceKey(key)) return cur.ifEmpty { BAILIAN_LEGACY }
        if (cur.contains(".maas.aliyuncs.com")) return cur
        return saved?.trim()?.trimEnd('/')?.takeIf { it.contains(".maas.aliyuncs.com") } ?: BAILIAN_WS_DEFAULT
    }
    /** DashScope-style request quirks (enable_thinking etc.) apply to both the legacy and the MaaS hosts. */
    fun isDashScopeHost(base: String): Boolean = base.contains("dashscope") || base.contains(".maas.aliyuncs.com")

    /** 扣子 PAT answers: 4100 = token invalid / expired, 4101 = token lacks a permission. */
    fun cozeReason(code: Int): String? = when (code) {
        4100 -> "扣子令牌过期了：一键重新登录会自动拿新的"
        4101 -> "扣子令牌少了权限：重新拿一次（勾选全部权限）"
        4200 -> "扣子里找不到这个智能体（可能删了）"
        else -> null
    }
}
