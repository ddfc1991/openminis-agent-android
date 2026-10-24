package com.openminis.app.plus

/**
 * [Mines-38.9.7] user 2026-10-18 21:30: Memoh 「去登录」 crashed the app on the phone, and the card said 「自建：memoh.net」.
 * The user's Memoh is an OpenAI-compatible endpoint on their own server (port 8794, /v1/models answers 401 without a key),
 * so it is set up with an address + key and checked against /v1/models — no web sign-in. Pure (unit-tested). The address
 * is never written into the code (public repo): it is suggested from the user's own 工作位置 servers.
 */
object MemohKeyCore {
    const val PORT = 8794

    /** "8.8.8.8:8794", "http://h:8794/", "http://h:8794/v1" → "http://h:8794/v1"; blank → null. */
    fun v1Base(input: String): String? {
        var u = input.trim().trimEnd('/')
        if (u.isEmpty()) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        val host = runCatching { java.net.URI(u).host }.getOrNull() ?: return null
        if (host.isBlank()) return null
        return if (u.endsWith("/v1")) u else "$u/v1"
    }

    /** Suggested address from a server host the user already has under 工作位置. */
    fun suggestFrom(serverHosts: List<String>): String? = serverHosts.firstOrNull { it.isNotBlank() }?.let { "http://$it:$PORT" }

    /** What a GET /v1/models answer means, in one plain line. */
    fun verdict(code: Int, hasKey: Boolean): Pair<Boolean, String> = when (code) {
        in 200..299 -> true to "通了"
        401, 403 -> false to (if (hasKey) "key 不对（服务器拒绝了这个 key）" else "服务器在线，要填 key")
        404 -> false to "这个地址没有 /v1/models（不像 OpenAI 兼容接口）"
        in 500..599 -> false to "服务器出错（HTTP $code）"
        else -> false to "没通（HTTP $code）"
    }

    /** First model id of a /v1/models body (OpenAI shape: {"data":[{"id":…}]}). */
    fun firstModel(body: String): String? = runCatching {
        val a = org.json.JSONObject(body).optJSONArray("data") ?: return null
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }.firstOrNull()
    }.getOrNull()

    /** 应用代理 subtitle: 「自建：host:port」. */
    fun where(v1: String?): String? = v1?.let { runCatching { java.net.URI(it) }.getOrNull() }?.let { "自建：" + it.host + (if (it.port > 0) ":${it.port}" else "") }
}
