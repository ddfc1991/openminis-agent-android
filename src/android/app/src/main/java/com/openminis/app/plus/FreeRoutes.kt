package com.openminis.app.plus

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.plus.group.AiClient
import com.openminis.app.plus.group.Partner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * [Mines-38.9.8] Live test of every 「免登录 AI（Mines）」 route (see [FreeRouteCore]): one tiny real chat per route,
 * time-limited, sequential (web routes share the phone's WebView). Results persist; a route that does not answer is left
 * out of the gateway's /v1/models (so 模型组 stops offering it) until a later test passes. Runs ≤ once per 12 h per route
 * at launch, and on demand from 自检.
 */
object FreeRoutes {
    private const val PREFS = "mines_free_routes"
    private val _live = MutableStateFlow<Map<String, FreeRouteCore.Live>>(emptyMap())
    val live: StateFlow<Map<String, FreeRouteCore.Live>> = _live.asStateFlow()
    @Volatile private var loaded = false
    @Volatile var testing: Boolean = false; private set

    fun init(c: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("live", "{}") ?: "{}")
            _live.value = o.keys().asSequence().associateWith { k ->
                val x = o.getJSONObject(k); FreeRouteCore.Live(k, x.optBoolean("ok"), x.optString("reason"), x.optLong("at"), x.optLong("ms"))
            }
        }
    }

    fun record(c: Context, l: FreeRouteCore.Live) {
        init(c)
        _live.value = _live.value + (l.id to l)
        runCatching {
            val o = JSONObject(); _live.value.values.forEach { v -> o.put(v.id, JSONObject().put("ok", v.ok).put("reason", v.reason).put("at", v.at).put("ms", v.ms)) }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("live", o.toString()).apply()
        }
    }

    fun visible(c: Context, id: String): Boolean { init(c); return FreeRouteCore.visible(id, _live.value) }

    /** One route, one tiny chat (≤ 45 s, detached so a stuck page can not hold the timeout). */
    suspend fun testOne(c: Context, p: Partner): FreeRouteCore.Live {
        val t0 = System.currentTimeMillis()
        val call = CoroutineScope(Dispatchers.IO).async { runCatching { AiClient.reply(p, "", listOf(AiClient.Turn("user", FreeRouteCore.TEST_PROMPT)), 32) } }
        val r = withTimeoutOrNull(FreeRouteCore.TEST_TIMEOUT_MS) { call.await() }
        val ms = System.currentTimeMillis() - t0
        val l = when {
            r == null -> { call.cancel(); FreeRouteCore.Live(p.id, false, FreeRouteCore.timeoutReason(FreeRouteCore.TEST_TIMEOUT_MS), System.currentTimeMillis(), ms) }
            r.isFailure -> FreeRouteCore.Live(p.id, false, ((r.exceptionOrNull() as? com.openminis.app.plus.group.MemberError)?.message ?: r.exceptionOrNull()?.message ?: "没有回复").take(80), System.currentTimeMillis(), ms)
            FreeRouteCore.answered(r.getOrNull()?.text) -> FreeRouteCore.Live(p.id, true, "", System.currentTimeMillis(), ms)
            else -> FreeRouteCore.Live(p.id, false, "回复是空的", System.currentTimeMillis(), ms)
        }
        record(c, l)
        return l
    }

    /** Tests the routes that need it ([force] = all), then refreshes the gateway provider's model list. */
    suspend fun testAll(c: Context, force: Boolean = false): Pair<Boolean, String> {
        init(c)
        if (testing) return true to "正在实测…"
        testing = true
        try {
            val routes = PartnerGateway.readyRoutes(c)
            val now = System.currentTimeMillis()
            for (p in routes) if (force || FreeRouteCore.needsTest(p.id, _live.value, now)) testOne(c, p)
            runCatching {
                val repo = (c.applicationContext as? MinisApp)?.providerRepositoryOrNull
                c.getSharedPreferences("mines_partner_gateway", Context.MODE_PRIVATE).getString("inst", null)?.let { repo?.triggerAsyncModelReconcile(it, forceRefresh = true) }
            }
            val names = routes.associate { it.id to it.spec.name }
            return FreeRouteCore.summary(names, _live.value.filterKeys { it in names })
        } finally { testing = false }
    }
}
