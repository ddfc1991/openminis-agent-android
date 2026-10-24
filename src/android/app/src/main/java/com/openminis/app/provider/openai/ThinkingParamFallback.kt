package com.openminis.app.provider.openai

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.4] issue #2 log: some OpenAI-compatible endpoints (e.g. developer.amd.com.cn/radeon …/v1/chat/completions
 * serving MiMo) answer `400 "thinking" is not supported on /v1/chat/completions … Use "reasoning_effort"`. For such an
 * endpoint the `thinking` object is dropped (reasoning_effort, which the DeepSeek-sibling wire format also sends, stays),
 * the request is retried once, and the endpoint is remembered so later requests go out without it.
 * Remembered per base URL; persisted through [store] when the app wires it (MinisApp), memory-only otherwise.
 */
object ThinkingParamFallback {
    private val noThinking = ConcurrentHashMap.newKeySet<String>()
    @Volatile var store: ((Set<String>) -> Unit)? = null

    fun load(saved: Collection<String>) { noThinking.addAll(saved.map { key(it) }) }

    fun key(base: String): String = base.trim().trimEnd('/').lowercase()

    fun known(base: String): Boolean = key(base) in noThinking

    fun remember(base: String) { if (noThinking.add(key(base))) runCatching { store?.invoke(noThinking.toSet()) } }

    /** The provider says the `thinking` field is unsupported (400 / 422 naming it). */
    fun isUnsupported(code: Int, body: String): Boolean {
        if (code != 400 && code != 422) return false
        val low = body.lowercase()
        if (!low.contains("thinking")) return false
        val o = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        if (o != null && o.optString("param") == "thinking") return true
        return low.contains("not supported") || low.contains("unsupported") || low.contains("unrecognized key") || low.contains("unknown field")
            || low.contains("extra inputs are not permitted") || low.contains("reasoning_effort")
    }

    /** Remove the field; true when something was removed (then the request is worth one retry). */
    fun strip(body: JSONObject): Boolean = body.remove("thinking") != null

    fun shouldRetry(code: Int, errorBody: String, body: JSONObject): Boolean = body.has("thinking") && isUnsupported(code, errorBody)
}
