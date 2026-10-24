package com.openminis.app.plus

/**
 * [Mines-38.9.11.1 hotfix] user: 「AI 邮箱没一个可以打开，注册都注册不了」. Pure helpers for the AI 邮箱 clients (AgentMailbox):
 * plain Chinese reasons for network / HTTP failures (was 「连不上 Mail.tm（UnknownHostException）」 or a silent no-op), the
 * Mail.tm domain pick and account-name rules. Unit-tested (AiMailCoreTest).
 */
object AiMailCore {
    /** a network exception (class simple name + message) → one plain Chinese line */
    fun netReason(service: String, errClass: String, msg: String? = null): String {
        val m = msg.orEmpty().lowercase()
        return when {
            errClass.contains("UnknownHost") -> "找不到 $service 的服务器（网络或代理问题，DNS 解析失败）"
            errClass.contains("SocketTimeout") || errClass.contains("InterruptedIOException") || m.contains("timeout") -> "连接 $service 超时（网络慢或代理不通）"
            errClass.contains("ConnectException") || m.contains("failed to connect") -> "连不上 $service（代理或网络拒绝连接）"
            errClass.contains("SSL") || errClass.contains("Handshake") || m.contains("ssl") -> "和 $service 的加密连接失败（可能被代理或网络拦截）"
            errClass.contains("ProtocolException") || m.contains("unexpected end") -> "$service 的连接被中途断开（代理问题）"
            errClass.contains("JSON") -> "$service 返回的内容看不懂"
            else -> "连不上 $service（$errClass）"
        }
    }

    enum class Step(val zh: String) { DOMAINS("取域名"), ACCOUNT("建邮箱"), TOKEN("登录邮箱") }

    /** Mail.tm HTTP status → reason; null = ok */
    fun mailTmHttp(step: Step, code: Int, body: String = ""): String? = when {
        code in 200..299 -> null
        code == 429 -> "Mail.tm 请求太频繁（${step.zh}），过一会儿再试"
        code == 422 && step == Step.ACCOUNT -> if (body.contains("already", true) || body.contains("used", true)) "这个地址已被占用" else "Mail.tm 不接受这个地址（422）"
        code == 401 && step == Step.TOKEN -> "Mail.tm 登录失败（账号或密码不对）"
        code in 500..599 -> "Mail.tm 服务器出错（${step.zh}，HTTP $code），稍后再试"
        code == 403 -> "Mail.tm 拒绝了请求（${step.zh}，HTTP 403，可能是网络出口被限制）"
        else -> "Mail.tm 没有正常回复（${step.zh}，HTTP $code）"
    }

    /** first active, public domain from GET /domains: a plain array (what `Accept: application/json` returns — smoke 39415674220
     *  showed 「没有可用域名」 because only the hydra object was read) or hydra:member / member (application/ld+json) */
    fun mailTmDomain(json: String): String? = runCatching {
        val t = json.trim()
        val a = if (t.startsWith("[")) org.json.JSONArray(t) else {
            val o = org.json.JSONObject(t)
            o.optJSONArray("hydra:member") ?: o.optJSONArray("member") ?: return null
        }
        (0 until a.length()).map { a.getJSONObject(it) }
            .firstOrNull { it.optBoolean("isActive", true) && !it.optBoolean("isPrivate", false) && it.optString("domain").isNotBlank() }
            ?.optString("domain")
    }.getOrNull()

    /** a fresh local part: "mines" + base36 time + 3 digits (lowercase, 3..64 chars) */
    fun userName(nowMs: Long, rnd: Int): String = "mines" + java.lang.Long.toString(nowMs % 1_000_000_000L, 36) + (100 + Math.floorMod(rnd, 900))
}
