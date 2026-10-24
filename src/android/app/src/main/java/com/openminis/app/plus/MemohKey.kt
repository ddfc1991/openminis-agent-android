package com.openminis.app.plus

import com.openminis.app.plus.group.PresetHttp
import org.json.JSONArray
import org.json.JSONObject

/** [Mines-38.9.7] Memoh as an OpenAI-compatible endpoint (address + key, encrypted in PlusVault). See [MemohKeyCore]. */
object MemohKey {
    private const val V = "memoh_key"
    fun base(): String? = PlusVault.secretExtra(V, "base")?.takeIf { it.isNotBlank() }
    fun key(): String? = PlusVault.secretExtra(V, "key")?.takeIf { it.isNotBlank() }
    fun model(): String? = PlusVault.secretExtra(V, "model")?.takeIf { it.isNotBlank() }
    fun configured(): Boolean = base() != null && key() != null
    fun save(base: String, key: String, model: String?) {
        PlusVault.setSecretExtra(V, "base", base); PlusVault.setSecretExtra(V, "key", key.trim()); PlusVault.setSecretExtra(V, "model", model)
    }
    fun clear() { PlusVault.setSecretExtra(V, "base", null); PlusVault.setSecretExtra(V, "key", null); PlusVault.setSecretExtra(V, "model", null) }

    fun suggestion(c: android.content.Context): String? = runCatching {
        PlusPrefs.init(c); MemohKeyCore.suggestFrom(PlusPrefs.state.value.servers.map { it.host })
    }.getOrNull()

    data class Check(val ok: Boolean, val line: String, val model: String?)

    /** GET <v1>/models with the key. */
    suspend fun check(v1: String, key: String?): Check = runCatching {
        val r = PresetHttp.call("$v1/models", token = key?.trim()?.takeIf { it.isNotBlank() }, method = "GET", readSec = 15)
        val (ok, line) = MemohKeyCore.verdict(r.code, !key.isNullOrBlank())
        val m = if (ok) MemohKeyCore.firstModel(r.body) else null
        Check(ok, if (ok) "$line · ${m ?: "没列出模型"}" else line, m)
    }.getOrElse { Check(false, it.message ?: "连不上", null) }

    /** One chat turn (OpenAI chat/completions). */
    suspend fun reply(system: String, user: String, maxTokens: Int = 2000): String {
        val v1 = base() ?: throw IllegalStateException("Memoh 还没填地址和 key")
        val m = model() ?: check(v1, key()).model ?: throw IllegalStateException("Memoh 没列出可用的模型")
        val body = JSONObject().put("model", m).put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
        val r = PresetHttp.call("$v1/chat/completions", token = key(), body = body, readSec = 60)
        if (!r.ok) throw IllegalStateException(MemohKeyCore.verdict(r.code, true).second)
        return r.json()?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
    }
}
