package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.13 vps_page_v2] Android side of [VpsPlanCore]: the user's overrides (SharedPreferences), the feature flag
 * (设置 → 实验功能, on by default), and the live facts the routing needs — which 工作位置 entry is which machine, whether it
 * answered the last check, and its memory % (read by the VPS / 电脑 page over the app's own SSH link). The result goes into the
 * system prompt next to the 自我地图 and into `app_self section=routing`, so the agent places tasks by these rules.
 */
object VpsPlan {
    private const val PREFS = "mines_vps_plan"
    private const val KEY = "overrides"
    const val FLAG = "vps_page_v2"

    private val _o = MutableStateFlow(VpsPlanCore.Overrides())
    val overrides: StateFlow<VpsPlanCore.Overrides> = _o.asStateFlow()
    @Volatile private var loaded = false

    /** server id → memory % read by the page (live, not persisted) */
    val memPct = ConcurrentHashMap<String, Int>()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(c: Context) {
        if (loaded) return
        _o.value = VpsPlanCore.fromJson(prefs(c).getString(KEY, null)); loaded = true
    }

    fun update(c: Context, f: (VpsPlanCore.Overrides) -> VpsPlanCore.Overrides) {
        init(c)
        val n = f(_o.value)
        if (n == _o.value) return
        _o.value = n
        prefs(c).edit().putString(KEY, VpsPlanCore.toJson(n)).apply()
    }

    fun flagOn(c: Context): Boolean = prefs(c).getBoolean("flag_$FLAG", true)
    fun setFlag(c: Context, on: Boolean) = prefs(c).edit().putBoolean("flag_$FLAG", on).apply()

    /** Which 工作位置 server / registered box is each machine (by name or address). */
    data class Bound(val server: VpsServer? = null, val meta: WorkspaceCore.Meta? = null) {
        val host: String? get() = server?.host ?: meta?.host?.takeIf { it.isNotBlank() }
    }

    fun bind(c: Context): Map<String, Bound> {
        PlusPrefs.init(c); WorkspaceStore.init(c)
        val servers = PlusPrefs.state.value.servers
        val metas = WorkspaceStore.metas.value.filter { it.kind != WorkspaceCore.Kind.SERVER }
        val sNames = servers.map { "${it.name} ${it.host}" }
        val mNames = metas.map { "${it.name} ${it.host} ${it.provider}" }
        return VpsPlanCore.MACHINES.associate { m ->
            val si = VpsPlanCore.matchIndex(m, sNames)
            val mi = if (si >= 0) -1 else VpsPlanCore.matchIndex(m, mNames)
            m.id to Bound(servers.getOrNull(si), metas.getOrNull(mi))
        }
    }

    fun health(bound: Map<String, Bound>): Map<String, VpsPlanCore.Health> = VpsPlanCore.MACHINES.associate { m ->
        val b = bound[m.id]
        m.id to when {
            m.id == VpsPlanCore.PHONE -> VpsPlanCore.Health(true, true)
            m.id == VpsPlanCore.GHA -> VpsPlanCore.Health(true)
            b?.server != null -> VpsPlanCore.Health(b.server.enabled, when { !b.server.enabled -> false; b.server.lastLatencyMs >= 0 -> true; b.server.lastLatencyMs == -2 -> false; else -> null },
                memPct[b.server.id])
            b?.meta != null -> VpsPlanCore.Health(true)
            else -> VpsPlanCore.Health(false)
        }
    }

    /** For the system prompt (empty when the feature is off). */
    fun promptBlock(c: Context): String = runCatching {
        if (!flagOn(c)) return ""
        init(c)
        val b = bind(c)
        VpsPlanCore.promptBlock(_o.value, b.mapNotNull { (k, v) -> v.host?.let { k to it } }.toMap(), health(b), System.currentTimeMillis())
    }.getOrDefault("")
}
