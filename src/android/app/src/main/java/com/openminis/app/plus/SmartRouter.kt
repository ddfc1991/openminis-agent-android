package com.openminis.app.plus

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Smart routing (智能路由): keeps a speed / health reading per provider and
 * lets model groups use it.
 *
 *  - Speed test: one light request to each enabled provider's API host (any
 *    HTTP answer = reachable; the time to the answer = latency). Runs on
 *    demand and every few minutes while Mines is open.
 *  - With 智能路由 on, a 「按顺序备用」 group starts with the fastest
 *    healthy member instead of the first one, and its automatic fallback
 *    chain (on errors / timeouts, already part of Mines) is walked in the
 *    same fastest-first order.
 *  - 「同模型自动成组」 builds one group per model that several providers
 *    serve (e.g. the same DeepSeek model from three gateways), so the
 *    fastest working one is used and the others back it up.
 */
object SmartRouter {
    private const val FILE = "mines_smart_router"
    const val GROUP_PREFIX = "⚡ "

    data class Stat(val latencyMs: Int, val ok: Boolean, val code: Int, val error: String?, val at: Long)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _stats = MutableStateFlow<Map<String, Stat>>(emptyMap())
    val stats: StateFlow<Map<String, Stat>> = _stats.asStateFlow()
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            prefs = p
            _enabled.value = p.getBoolean("enabled", false)
        }
    }

    fun setEnabled(context: Context, on: Boolean) {
        init(context)
        _enabled.value = on
        prefs?.edit()?.putBoolean("enabled", on)?.apply()
    }

    private fun probeUrl(inst: ProviderInstance): String? {
        inst.effectiveBaseURL?.let { return it.trimEnd('/') + "/models" }
        return when (inst.providerType) {
            ProviderType.anthropic -> "https://api.anthropic.com/v1/models"
            ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta/models"
            ProviderType.openAI -> "https://api.openai.com/v1/models"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1/models"
            ProviderType.xAI -> "https://api.x.ai/v1/models"
            else -> null
        }
    }

    private fun probe(url: String): Stat {
        val t0 = System.nanoTime()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 6000
            c.requestMethod = "GET"
            val code = c.responseCode
            runCatching { (if (code < 400) c.inputStream else c.errorStream)?.use { it.read() } }
            c.disconnect()
            val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
            // 401/403/404 still means the host answered; 5xx / 429 means trouble.
            Stat(ms, code < 500 && code != 429, code, null, System.currentTimeMillis())
        } catch (e: Exception) {
            Stat(((System.nanoTime() - t0) / 1_000_000).toInt(), false, 0, e.javaClass.simpleName, System.currentTimeMillis())
        }
    }

    /** Measure every enabled provider in parallel. */
    suspend fun testAll(context: Context) = withContext(Dispatchers.IO) {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return@withContext
        _testing.value = true
        try {
            val insts = repo.config.value.instances.filter { it.isEnabled }
            val res = coroutineScope {
                insts.mapNotNull { i -> probeUrl(i)?.let { u -> i.id to u } }
                    .map { (id, u) -> async { id to probe(u) } }.awaitAll()
            }
            _stats.value = _stats.value + res.toMap()
        } finally {
            _testing.value = false
        }
    }

    /** Re-test when the last reading is older than [maxAgeMs]. */
    suspend fun refreshIfStale(context: Context, maxAgeMs: Long = 5 * 60_000L) {
        val newest = _stats.value.values.maxOfOrNull { it.at } ?: 0L
        if (System.currentTimeMillis() - newest > maxAgeMs) testAll(context)
    }

    private fun rank(instanceId: String?): Int {
        val s = instanceId?.let { _stats.value[it] } ?: return 50_000          // unknown: middle
        return if (s.ok) s.latencyMs else 100_000 + s.latencyMs              // broken: last
    }

    /** Fastest healthy first (stable for equal / unknown). No-op when off. */
    fun order(entries: List<ModelEntry>): List<ModelEntry> =
        if (!_enabled.value) entries else entries.sortedBy { rank(it.providerInstanceId) }

    fun <T> sortByHealth(items: List<T>, instanceIdOf: (T) -> String?): List<T> =
        if (!_enabled.value) items else items.sortedBy { rank(instanceIdOf(it)) }

    private fun family(modelId: String): String =
        modelId.lowercase().substringAfterLast('/').replace(Regex("[\\s_]+"), "-").removeSuffix(":free")

    /**
     * One 「⚡ 模型名」 group (fallback strategy) per model that two or more
     * enabled providers serve. Existing ⚡ groups are updated, others untouched.
     * @return number of groups created or updated.
     */
    /**
     * [Mines-38.7] one-time clean-up after 「同模型自动成组」 was removed: deletes the groups it made (name starts with
     * [GROUP_PREFIX] 「⚡ 」); the models go back to normal, groups the user made by hand are kept.
     */
    fun dropAutoGroups(context: Context): Int {
        val p = context.getSharedPreferences("mines_router", Context.MODE_PRIVATE)
        if (p.getBoolean("auto_groups_dropped", false)) return 0
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return 0
        val auto = repo.config.value.modelGroups.filter { it.name.startsWith(GROUP_PREFIX) }
        auto.forEach { runCatching { repo.removeGroup(it.id) } }
        p.edit().putBoolean("auto_groups_dropped", true).apply()
        return auto.size
    }

    fun buildSameModelGroups(context: Context): Int {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return 0
        val cfg = repo.config.value
        val enabledIds = cfg.instances.filter { it.isEnabled }.map { it.id }.toSet()
        val byFamily = cfg.modelEntries
            .filter { it.providerInstanceId in enabledIds && !it.isHidden && !it.isUnavailableFromProvider }
            .groupBy { family(it.model.id) }
            .filter { (_, es) -> es.map { it.providerInstanceId }.distinct().size >= 2 }
        var n = 0
        for ((fam, entries) in byFamily) {
            val members = entries.distinctBy { it.providerInstanceId }.map { it.id }.toMutableList()
            val name = GROUP_PREFIX + (entries.first().model.displayName.ifBlank { fam })
            val existing = cfg.modelGroups.firstOrNull { it.name == name }
            if (existing == null) {
                repo.addGroup(ModelGroup(name = name, memberEntryIds = members, strategy = RoutingStrategy.fallback))
                n++
            } else if (existing.memberEntryIds.toSet() != members.toSet()) {
                repo.updateGroup(existing.copy(memberEntryIds = members, strategy = RoutingStrategy.fallback))
                n++
            }
        }
        return n
    }
}
