package com.openminis.app.plus

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Finds OpenAI-compatible model gateways running on this phone (e.g. a
 * WorkBuddy reverse-proxy app on 127.0.0.1:8788, Ollama, LM Studio, LiteLLM)
 * and adds one as a provider in one tap.
 */
object LocalGateway {
    /** 8788 first: the user's WorkBuddy proxy app. 8790 is Mines' own plugin gateway, skipped. */
    val PORTS = listOf(8788, 8317, 11434, 1234, 4000, 8000, 8080, 3000, 5000, 8888)
    private const val PREFS = "mines_local_gateway"

    data class Found(
        val port: Int,
        val models: List<String>,
        /** The gateway answered 401/403: it wants a key. */
        val needsKey: Boolean,
    ) {
        val baseUrl: String get() = "http://127.0.0.1:$port/v1"
        val title: String get() = when (port) {
            8788 -> "WorkBuddy 网关"
            11434 -> "Ollama"
            1234 -> "LM Studio"
            4000 -> "LiteLLM"
            else -> "本机模型网关"
        }
    }

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private fun probe(port: Int, key: String? = null): Found? = runCatching {
        val c = URL("http://127.0.0.1:$port/v1/models").openConnection() as HttpURLConnection
        c.connectTimeout = 600; c.readTimeout = 2500
        if (!key.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $key")
        val code = c.responseCode
        if (code == 401 || code == 403) return@runCatching Found(port, emptyList(), needsKey = true)
        if (code !in 200..299) return@runCatching null
        val body = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        val arr = JSONObject(body).optJSONArray("data") ?: return@runCatching null
        Found(port, (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }, false)
    }.getOrNull()

    /** Ports already added as a provider (by base URL). */
    private fun addedPorts(context: Context): Set<Int> {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return emptySet()
        return repo.config.value.instances.mapNotNull { inst ->
            val b = inst.customBaseURL ?: return@mapNotNull null
            Regex("^https?://(127\\.0\\.0\\.1|localhost):(\\d+)").find(b)?.groupValues?.get(2)?.toIntOrNull()
        }.toSet()
    }

    fun dismissed(context: Context): Set<Int> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("dismissed", emptySet())!!
            .mapNotNull { it.toIntOrNull() }.toSet()

    fun dismiss(context: Context, port: Int) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putStringSet("dismissed", dismissed(context).map { it.toString() }.toSet() + port.toString()).apply()
        _found.value = _found.value.filter { it.port != port }
    }

    /** Probe all ports in parallel; keeps only gateways not yet added or dismissed. */
    suspend fun scan(context: Context, includeDismissed: Boolean = false): List<Found> = withContext(Dispatchers.IO) {
        val skip = addedPorts(context) + if (includeDismissed) emptySet() else dismissed(context)
        val res = coroutineScope { PORTS.filter { it !in skip }.map { p -> async { probe(p) } }.awaitAll() }.filterNotNull()
        _found.value = res
        res
    }

    /** Add [port] as an OpenAI-compatible provider; returns a status line. */
    suspend fun add(context: Context, f: Found, key: String?): String = withContext(Dispatchers.IO) {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return@withContext "供应商还没加载，稍后再试"
        val check = probe(f.port, key) ?: return@withContext "连不上 127.0.0.1:${f.port}，确认网关 App 正在运行"
        if (check.needsKey) return@withContext "密钥不对，网关拒绝了"
        val inst = ProviderInstance(
            id = UUID.randomUUID().toString(),
            label = "${f.title} :${f.port}",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = f.baseUrl,
            appendV1Suffix = true,
        )
        repo.addInstance(inst)
        if (!key.isNullOrBlank()) repo.saveApiKey(inst.id, key.trim())
        repo.triggerAsyncModelReconcile(inst.id, forceRefresh = true)
        _found.value = _found.value.filter { it.port != f.port }
        "已添加 ${f.title}，${check.models.size} 个模型"
    }
}
