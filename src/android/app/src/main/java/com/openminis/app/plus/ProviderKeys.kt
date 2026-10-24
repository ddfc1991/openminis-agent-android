package com.openminis.app.plus

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ProviderType
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * [Mines-apps-32] Keys the user already put into Mines' 模型组 (Settings →
 * providers) count as connected on 「应用」. build-31 showed 「连接」 on every AI
 * row although DeepSeek etc. were in use. On opening the page each provider
 * key is matched to a catalog entry (by API host, or by provider type for the
 * official endpoints) and adopted into the vault once (via = "model"), so the
 * row shows 已连接 + masked key, balance and patrol work, and KeyAgent never
 * asks for a key that already exists. Nothing is sent anywhere; the key stays
 * on the phone (both stores are Android-Keystore encrypted).
 */
object ProviderKeys {
    data class Found(val kindId: String, val instanceId: String, val label: String, val key: String)

    private val hosts: List<Pair<String, List<String>>> = listOf(
        "deepseek" to listOf("api.deepseek.com"),
        "openai" to listOf("api.openai.com"),
        "anthropic" to listOf("api.anthropic.com"),
        "gemini" to listOf("generativelanguage.googleapis.com"),
        "openrouter" to listOf("openrouter.ai"),
        "xai" to listOf("api.x.ai"),
        "kimi" to listOf("api.moonshot.cn", "api.moonshot.ai"),
        "qwen" to listOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "maas.aliyuncs.com"),
        "zhipu" to listOf("open.bigmodel.cn"),
        "zai" to listOf("api.z.ai"),
        "siliconflow" to listOf("api.siliconflow.cn", "api.siliconflow.com"),
        "mistral" to listOf("api.mistral.ai"),
        "groq" to listOf("api.groq.com"),
        "minimax" to listOf("api.minimaxi.com", "api.minimax.chat", "api.minimax.io", "api.minimaxi.chat"),
        "volcengine" to listOf("volces.com", "volcengineapi.com"),
        "sensenova" to listOf("sensenova", "sensetime", "sensecore"),
        "perplexity" to listOf("api.perplexity.ai"),
        "hunyuan" to listOf("hunyuan.cloud.tencent.com"), // [Mines-group-37]
    )

    fun kindFor(baseUrl: String?, type: ProviderType): String? {
        val host = PlusConnect.hostOf(baseUrl ?: "")
        if (host.isNotEmpty()) return hosts.firstOrNull { (_, hs) -> hs.any { host == it || host.endsWith(".$it") || host.contains(it) } }?.first
        return when (type) {
            ProviderType.openAI, ProviderType.openAIResponses -> "openai"
            ProviderType.anthropic -> "anthropic"
            ProviderType.gemini -> "gemini"
            ProviderType.openRouter -> "openrouter"
            ProviderType.xAI -> "xai"
            else -> null
        }
    }

    fun scan(context: Context): List<Found> {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return emptyList()
        return runCatching {
            repo.config.value.instances.mapNotNull { inst ->
                val key = repo.loadApiKey(inst.id)?.trim()?.takeIf { it.length >= 8 } ?: return@mapNotNull null
                val kind = kindFor(inst.customBaseURL, inst.providerType) ?: return@mapNotNull null
                Found(kind, inst.id, inst.label, key)
            }
        }.getOrDefault(emptyList())
    }

    /** Adopt every model-group key not yet in the vault. Returns how many were added. */
    fun adopt(context: Context): Int {
        PlusVault.init(context)
        var n = 0
        val tombs = PlusVault.removedKeys()
        for (f in scan(context)) {
            val held = PlusVault.accountsOf(f.kindId).mapNotNull { PlusVault.secret(it) }
            if (KeyTombstone.toAdopt(listOf(f.key), held, tombs).isEmpty()) continue   // held already, or deleted by the user
            val id = PlusVault.newAccountId(f.kindId)
            val kind = PlusVault.kind(id) ?: continue
            PlusVault.save(context, kind, f.label, f.key, share = false, via = "model")
            PlusVault.setExtra(id, "from_model", f.instanceId)
            n++
        }
        return n
    }
}

/**
 * [Mines-apps-32] Balance per key, plain HTTP, no model calls. Endpoints
 * (checked against the providers' docs):
 *  - DeepSeek  GET https://api.deepseek.com/user/balance            (api-docs.deepseek.com/api/get-user-balance)
 *  - SiliconFlow GET https://api.siliconflow.cn/v1/user/info        (docs.siliconflow.cn → userinfo)
 *  - Kimi      GET https://api.moonshot.cn/v1/users/me/balance      (platform.moonshot.cn/docs → 查询余额)
 *  - OpenRouter GET /api/v1/credits needs a management key (openrouter.ai/docs/api-reference/get-credits),
 *              so a normal key falls back to GET /api/v1/key → limit_remaining / usage.
 * Everything else → no API: the row says 「余额：去控制台看」 and opens [billingUrl] in a Custom Tab.
 */
object KeyBalance {
    data class Bal(val text: String, val low: Boolean)

    val billingUrl: Map<String, String> = mapOf(
        "deepseek" to "https://platform.deepseek.com/usage",
        "siliconflow" to "https://cloud.siliconflow.cn/expensebill",
        "kimi" to "https://platform.moonshot.cn/console/info",
        "openrouter" to "https://openrouter.ai/settings/credits",
        "openai" to "https://platform.openai.com/settings/organization/billing/overview",
        "anthropic" to "https://console.anthropic.com/settings/billing",
        "gemini" to "https://aistudio.google.com/usage",
        "zhipu" to "https://open.bigmodel.cn/finance/overview",
        "zai" to "https://z.ai/manage-apikey/billing",
        "qwen" to "https://usercenter2.aliyun.com/finance/fund-management/overview",
        "xai" to "https://console.x.ai/",
        "mistral" to "https://admin.mistral.ai/organization/billing",
        "groq" to "https://console.groq.com/settings/billing",
        "minimax" to "https://platform.minimaxi.com/user-center/payment/balance",
        "volcengine" to "https://console.volcengine.com/finance/account-overview/",
        "sensenova" to "https://console.sensecore.cn/finance/overview",
        "perplexity" to "https://www.perplexity.ai/account/api/billing",
        "elevenlabs" to "https://elevenlabs.io/app/subscription",
    )

    fun hasApi(base: String) = base in setOf("deepseek", "siliconflow", "kimi", "openrouter")

    private fun get(url: String, key: String): Pair<Int, JSONObject?> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        val body = runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        return code to runCatching { JSONObject(body) }.getOrNull()
    }

    /** Blocking; null = this provider has no balance API. */
    fun fetch(base: String, key: String): Bal? = runCatching {
        when (base) {
            "deepseek" -> {
                val j = get("https://api.deepseek.com/user/balance", key).second ?: return@runCatching Bal("余额读取失败", false)
                val b = j.optJSONArray("balance_infos")?.optJSONObject(0)
                val total = b?.optString("total_balance") ?: "?"
                val cur = if (b?.optString("currency") == "USD") "$" else "¥"
                Bal("余额 $cur$total", (total.toDoubleOrNull() ?: 99.0) < 2.0 || !j.optBoolean("is_available", true))
            }
            "siliconflow" -> {
                val d = get("https://api.siliconflow.cn/v1/user/info", key).second?.optJSONObject("data")
                val total = d?.optString("totalBalance") ?: "?"
                Bal("余额 ¥$total", (total.toDoubleOrNull() ?: 99.0) < 2.0)
            }
            "kimi" -> {
                val d = get("https://api.moonshot.cn/v1/users/me/balance", key).second?.optJSONObject("data")
                val v = d?.optDouble("available_balance") ?: Double.NaN
                Bal("余额 ¥" + "%.2f".format(v), v < 2.0)
            }
            "openrouter" -> {
                val (code, j) = get("https://openrouter.ai/api/v1/credits", key)
                if (code in 200..299 && j?.optJSONObject("data") != null) {
                    val d = j.getJSONObject("data")
                    val left = d.optDouble("total_credits") - d.optDouble("total_usage")
                    Bal(if (left < 0) "欠费 $" + "%.2f".format(-left) else "余额 $" + "%.2f".format(left), left < 1.0)   // [i] not 「$-0.11」
                } else {
                    val d = get("https://openrouter.ai/api/v1/key", key).second?.optJSONObject("data")
                    when {
                        d == null -> Bal("余额读取失败", false)
                        !d.isNull("limit_remaining") -> d.optDouble("limit_remaining").let { Bal("额度剩 $" + "%.2f".format(it), it < 1.0) }
                        d.optBoolean("is_free_tier") -> Bal("免费层 · 已用 $" + "%.2f".format(d.optDouble("usage")), false)
                        else -> Bal("已用 $" + "%.2f".format(d.optDouble("usage")), false)
                    }
                }
            }
            else -> null
        }
    }.getOrElse { Bal("余额读取失败", false) }

    /** Refresh one account's balance into the vault extras (shown on its row). */
    fun refresh(kindId: String) {
        val base = PlusVault.baseId(kindId)
        if (!hasApi(base)) return
        val key = PlusVault.secret(kindId) ?: return
        val b = fetch(base, key) ?: return
        PlusVault.setExtra(kindId, "balance", b.text)
        PlusVault.setExtra(kindId, "balance_low", if (b.low) "1" else "0")
        PlusVault.setExtra(kindId, "balance_at", System.currentTimeMillis().toString())
    }

    fun text(kindId: String): String? = PlusVault.extra(kindId, "balance")
    fun low(kindId: String): Boolean = PlusVault.extra(kindId, "balance_low") == "1"
}
