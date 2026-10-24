package com.openminis.app.plus

/**
 * [Mines-38.9.11.3] 「测试全部密钥」 on the provider screen: every key of every API-key provider is checked once
 * (GET <base>/models; Bearer, or x-api-key for Anthropic-compatible, x-goog-api-key for Gemini); 401 = invalid (marked bad, skipped by the key pool), 2xx = good (mark
 * cleared), anything else = could not tell. Pure parts, unit-tested (KeyCheckCoreTest). Keys are only shown as #n.
 */
object KeyCheckCore {
    enum class Verdict(val zh: String) { OK("能用"), BAD("无效"), UNKNOWN("没测出") }

    fun verdict(httpCode: Int?): Verdict = when {
        httpCode == null -> Verdict.UNKNOWN
        httpCode == 401 -> Verdict.BAD          // 403 = quota / region / model permission on relays: not a dead key
        httpCode in 200..299 -> Verdict.OK
        else -> Verdict.UNKNOWN
    }

    /** models URL for an OpenAI-compatible base ("https://h/v1" → "https://h/v1/models") */
    fun modelsUrl(base: String): String = base.trim().trimEnd('/').let { if (it.endsWith("/models")) it else "$it/models" }

    enum class Auth { BEARER, ANTHROPIC, GEMINI }
    /** the auth header(s) for a provider type name (ProviderType.name) */
    fun headers(auth: Auth, key: String): List<Pair<String, String>> = when (auth) {
        Auth.BEARER -> listOf("Authorization" to "Bearer $key")
        Auth.ANTHROPIC -> listOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        Auth.GEMINI -> listOf("x-goog-api-key" to key)
    }
    fun authOf(typeName: String): Auth = when (typeName) { "anthropic" -> Auth.ANTHROPIC; "gemini" -> Auth.GEMINI; else -> Auth.BEARER }

    data class Row(val label: String, val index: Int, val total: Int, val verdict: Verdict)

    /** one line per provider: 「商汤：5 个密钥，#2 #4 无效」 / 「AMD：14 个全部能用」 */
    fun summary(rows: List<Row>): List<String> = rows.groupBy { it.label }.map { (label, rs) ->
        val bad = rs.filter { it.verdict == Verdict.BAD }.map { "#${it.index + 1}" }
        val unk = rs.count { it.verdict == Verdict.UNKNOWN }
        val n = rs.size
        when {
            bad.isEmpty() && unk == 0 -> if (n == 1) "$label：密钥能用" else "$label：$n 个全部能用"
            bad.size == n -> if (n == 1) "$label：密钥无效" else "$label：$n 个全部无效"
            else -> "$label：" + (if (n > 1) "$n 个密钥，" else "") + (if (bad.isNotEmpty()) bad.joinToString(" ") + " 无效" else "") +
                (if (unk > 0) (if (bad.isNotEmpty()) "，" else "") + "$unk 个没测出" else "")
        }
    }
}
