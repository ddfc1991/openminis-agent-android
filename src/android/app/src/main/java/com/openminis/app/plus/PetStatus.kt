package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * [Mines-pet-25] What the desk pet shows and reacts to, from anywhere in the app.
 */
object PetBus {
    /** A live voice session is playing speech (pet shows its talking animation). */
    val talking = MutableStateFlow(false)
    /** [Mines-pet-32] adb self-test only: hold one frame (blink/mouth_a/mouth_o/think) until [forceUntil]. */
    @Volatile var forceFrame: String? = null
    @Volatile var forceUntil = 0L
    /** [Mines-pet-34] adb self-test only: play one body motion (WAVE, LOOK, HAPPY, JUMP …) once. */
    @Volatile var forceMode: String? = null
    /** [Mines-pet-32] Voice output loudness 0..1 and "brightness" (zero-crossing rate) for lip-sync. */
    @Volatile var level = 0f
    @Volatile var zcr = 0f
    @Volatile var levelAt = 0L
    /** Called with every chunk of 16-bit little-endian mono PCM written to the speaker. */
    fun feedPcm(b: ByteArray, n: Int) {
        var sum = 0.0; var cross = 0; var prev = 0; var cnt = 0
        var i = 0
        while (i + 1 < n) {
            val s = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)
            sum += s.toDouble() * s; if ((s >= 0) != (prev >= 0)) cross++; prev = s; cnt++
            i += 2
        }
        if (cnt == 0) return
        val rms = kotlin.math.sqrt(sum / cnt) / 32768.0
        level = (rms * 6.0).coerceIn(0.0, 1.0).toFloat()
        zcr = (cross.toFloat() / cnt).coerceIn(0f, 1f)
        levelAt = System.currentTimeMillis()
    }
    /** A live voice session is open and listening. */
    val inCall = MutableStateFlow(false)
    /** The wake word was heard (pet waves, then the voice screen opens). */
    val wake = MutableStateFlow(0L)
    /** [Mines-voice-26] The wake-word listener currently holds the microphone. */
    val wakeMicHeld = MutableStateFlow(false)
    /** [Mines-voiceprint-35] something else (声纹录入 / 试一下) needs the mic exclusively; the wake word lets go. */
    val micExclusive = MutableStateFlow(false)
    /** [Mines-voice-35.2] The 声纹录入 page is open: calls keep their hands off the mic. */
    val enrolling = MutableStateFlow(false)
    /** [Mines-voice-35.2] The full-screen call page is showing: the desk pet hides (no second character). */
    val voicePageOpen = MutableStateFlow(false)
}

/**
 * [Mines-pet-25] Model / API balances for the pet's status panel, read from
 * each provider's own balance API with the key saved under 账号连接.
 * Only providers with a documented balance endpoint; nothing is pre-filled.
 */
object Balances {
    data class Item(val name: String, val text: String, val low: Boolean = false)

    @Volatile var last: List<Item> = emptyList(); private set
    @Volatile var lastAt: Long = 0L; private set

    private fun key(kind: String): String? =
        PlusVault.defaultAccount(kind)?.let { PlusVault.secret(it) } ?: PlusVault.secret(kind)

    private fun get(url: String, key: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return JSONObject(body)
    }

    private data class Endpoint(
        val kind: String,
        val name: String,
        val url: String,
        val decode: (JSONObject) -> Item,
    )

    /** Fetches configured balances concurrently (max 4), preserving provider order and isolated failures. */
    suspend fun refresh(context: Context, maxAgeMs: Long = 5 * 60_000L): List<Item> = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - lastAt < maxAgeMs && last.isNotEmpty()) return@withContext last
        PlusVault.init(context)
        val endpoints = listOf(
            Endpoint("deepseek", "DeepSeek", "https://api.deepseek.com/user/balance") { j ->
                // https://api-docs.deepseek.com/api/get-user-balance
                val b = j.optJSONArray("balance_infos")?.optJSONObject(0)
                val total = b?.optString("total_balance") ?: "?"
                val cur = if (b?.optString("currency") == "USD") "$" else "¥"
                Item("DeepSeek", "$cur$total", low = (total.toDoubleOrNull() ?: 99.0) < 2.0 || !j.optBoolean("is_available", true))
            },
            Endpoint("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1/user/info") { j ->
                // https://docs.siliconflow.cn/cn/api-reference/userinfo/get-user-info
                val d = j.optJSONObject("data")
                val total = d?.optString("totalBalance") ?: "?"
                Item("硅基流动", "¥$total", low = (total.toDoubleOrNull() ?: 99.0) < 2.0)
            },
            Endpoint("kimi", "Kimi", "https://api.moonshot.cn/v1/users/me/balance") { j ->
                // https://platform.moonshot.cn/docs/api/misc#查询余额
                val d = j.optJSONObject("data")
                val v = d?.optDouble("available_balance") ?: Double.NaN
                Item("Kimi", "¥" + "%.2f".format(v), low = v < 2.0)
            },
            Endpoint("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/credits") { j ->
                // https://openrouter.ai/docs/api-reference/get-credits
                val d = j.optJSONObject("data")
                val left = (d?.optDouble("total_credits") ?: 0.0) - (d?.optDouble("total_usage") ?: 0.0)
                Item("OpenRouter", if (left < 0) "欠费 $" + "%.2f".format(-left) else "$" + "%.2f".format(left), low = left < 1.0)   // [i] was 「$-0.11」
            },
        )
        val requests = endpoints.mapNotNull { endpoint -> key(endpoint.kind)?.let { endpoint to it } }
        val results = boundedOrderedMap(requests, parallelism = 4) { (endpoint, apiKey) ->
            try {
                endpoint.decode(get(endpoint.url, apiKey))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                Item(endpoint.name, "读取失败（${failure.message}）")
            }
        }
        val out = results.mapIndexed { index, result ->
            result.getOrElse { Item(requests[index].first.name, "读取失败") }
        }
        last = out; lastAt = System.currentTimeMillis()
        out
    }
}
