package com.openminis.app.plus

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.repository.ProviderRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** [Mines-38.9.11.3] Android side of [KeyCheckCore]: runs off the main thread, ≤ 4 at a time, routes like chat requests. */
object KeyCheck {
    private val base = OkHttpClient.Builder().connectTimeout(ProviderRouteCore.CONNECT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).dns(ProviderRoutes.TimedDns).build()

    private fun code(url: String, key: String, auth: KeyCheckCore.Auth, ua: String?): Int? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        for (r in ProviderRoutes.order(host)) {
            val c = base.newBuilder().proxy(ProviderRoutes.proxyFor(r)).build()
            val res = runCatching { c.newCall(Request.Builder().url(url).apply { KeyCheckCore.headers(auth, key).forEach { (k, v) -> header(k, v) }; if (!ua.isNullOrBlank()) header("User-Agent", ua) }.build()).execute().use { it.code } }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    /** blocking — call on Dispatchers.IO */
    fun runAll(repo: ProviderRepository, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<KeyCheckCore.Row> {
        val jobs = ArrayList<Triple<String, Int, Pair<String, List<String>>>>()   // label, index, (url, pool)
        val meta = ArrayList<Pair<KeyCheckCore.Auth, String?>>()
        for (i in repo.config.value.instances.filter { it.credentialType == ProviderCredential.apiKey && it.isEnabled && !it.azureMode }) {
            val baseUrl = i.effectiveBaseURL ?: continue
            val pool = repo.keyPool(i.id).ifEmpty { listOfNotNull(repo.loadApiKey(i.id)) }.filter { it.isNotBlank() }
            val auth = KeyCheckCore.authOf(i.providerType.name); val ua = i.customUserAgent
            pool.forEachIndexed { ix, _ -> jobs.add(Triple(i.label.ifBlank { baseUrl }, ix, KeyCheckCore.modelsUrl(baseUrl) to pool)); meta.add(auth to ua) }
        }
        val ex = java.util.concurrent.Executors.newFixedThreadPool(4)
        val done = java.util.concurrent.atomic.AtomicInteger()
        try {
            val fs = jobs.mapIndexed { j, (label, ix, up) ->
                ex.submit<KeyCheckCore.Row> {
                    val key = up.second[ix]
                    val v = KeyCheckCore.verdict(code(up.first, key, meta[j].first, meta[j].second))
                    when (v) { KeyCheckCore.Verdict.BAD -> BadKeys.mark(key); KeyCheckCore.Verdict.OK -> BadKeys.clear(key); else -> {} }
                    onProgress(done.incrementAndGet(), jobs.size)
                    KeyCheckCore.Row(label, ix, up.second.size, v)
                }
            }
            return fs.map { runCatching { it.get(60, TimeUnit.SECONDS) }.getOrNull() }.filterNotNull()
        } finally { ex.shutdownNow() }
    }
}
