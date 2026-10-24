package com.openminis.app.plus

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [Mines-38.9.8] 自我地图 at run time (see [SelfMapCore]): features (from the generated settings map), the user's providers,
 * 模型组 and agent-loop groups, and every 工作位置 server with the ports it is known to serve, probed from this phone
 * (plain TCP connect, 1.5 s each, cached 10 min, refreshed in the background — the prompt never waits for the network).
 * Everything comes from the user's own config; no address is in the code. Tool: `app_self`.
 */
object SelfMap {
    const val TOOL_NAME = "app_self"
    private const val TTL = 10 * 60_000L
    private data class Probe(val ports: List<SelfMapCore.Port>, val at: Long)
    private val probes = ConcurrentHashMap<String, Probe>()
    private val running = ConcurrentHashMap<String, Boolean>()
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "mines-selfmap").apply { isDaemon = true } }

    private fun version(c: Context) = runCatching { com.openminis.app.BuildConfig.VERSION_NAME }.getOrDefault("?")

    private fun features(c: Context): List<String> = runCatching {
        AppSettingsMap.rows(c).mapNotNull { it.path.split(" › ").getOrNull(1)?.trim() }.filter { it.isNotBlank() }.distinct().take(24)
    }.getOrDefault(emptyList()) + listOf("工作流记录", "未完成的事（首页角标）")

    fun snapshot(c: Context, refresh: Boolean = true): SelfMapCore.Snapshot {
        val repo = (c.applicationContext as? MinisApp)?.providerRepositoryOrNull
        val cfg = runCatching { repo?.config?.value }.getOrNull()
        val providers = cfg?.instances?.map { inst ->
            val host = runCatching { java.net.URI(inst.effectiveBaseURL ?: "").host.orEmpty() }.getOrDefault("")
            SelfMapCore.Provider(inst.label.ifBlank { inst.providerType.displayName }, inst.providerType.displayName, inst.isEnabled,
                cfg.modelEntries.count { it.providerInstanceId == inst.id && !it.isHidden }, host)
        }.orEmpty()
        val names = cfg?.modelEntries?.associate { it.id to it.model.displayName }.orEmpty()
        val groups = cfg?.modelGroups?.map { g ->
            SelfMapCore.Group(g.name, g.memberEntryIds.mapNotNull { names[it] }, g.id == cfg.defaultPrimaryGroupId, cfg.agentLoopGroupIds.contains(g.id))
        }.orEmpty()
        val servers = runCatching {
            PlusPrefs.init(c); WorkspaceStore.init(c)
            PlusPrefs.state.value.servers.filter { it.enabled && it.host.isNotBlank() }.map { s ->
                val m = WorkspaceStore.meta(s.id)
                val pr = probes[s.id]
                if (refresh && (pr == null || System.currentTimeMillis() - pr.at > TTL)) probeAsync(c, s.id)
                SelfMapCore.Server(s.name.ifBlank { s.host }, s.host, s.port, pr?.ports.orEmpty(), pr?.at ?: 0L,
                    listOfNotNull(m?.geo?.takeIf { it.isNotBlank() }, s.lastError.takeIf { it.isNotBlank() }?.let { "上次 SSH：$it" }).joinToString(" · "))
            }
        }.getOrDefault(emptyList())
        val skills = runCatching { (c.applicationContext as MinisApp).skillRepository.skills.value.size }.getOrDefault(0)
        val wf = runCatching { WorkflowLog.list(c).size }.getOrDefault(0)
        return SelfMapCore.Snapshot(version(c), features(c), providers, groups, servers, skills, wf)
    }

    fun promptBlock(c: Context): String = runCatching { SelfMapCore.promptBlock(snapshot(c), System.currentTimeMillis()) }.getOrDefault("")

    /** Probes one server's ports now (blocking ≤ ~4 s for 12 ports in parallel). */
    fun probeNow(c: Context, serverId: String, extra: List<Int> = emptyList()): List<SelfMapCore.Port> {
        PlusPrefs.init(c); WorkspaceStore.init(c)
        val s = PlusPrefs.state.value.servers.firstOrNull { it.id == serverId } ?: return emptyList()
        val m = WorkspaceStore.meta(s.id)
        val running = m?.running.orEmpty(); val notes = m?.notes.orEmpty()
        val ports = SelfMapCore.portsFor(s.port, running, notes, extra)
        val futures = ports.map { p -> pool.submit<SelfMapCore.Port> {
            val t0 = System.currentTimeMillis()
            val ok = runCatching { Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress(s.host, p), 1500) }; true }.getOrDefault(false)
            SelfMapCore.Port(p, ok, if (ok) (System.currentTimeMillis() - t0).toInt() else -1, SelfMapCore.labelFor(p, running, notes))
        } }
        val out = futures.mapNotNull { f -> runCatching { f.get(4, TimeUnit.SECONDS) }.getOrNull() }
        probes[s.id] = Probe(out, System.currentTimeMillis())
        return out
    }

    private fun probeAsync(c: Context, serverId: String) {
        if (running.putIfAbsent(serverId, true) != null) return
        val app = c.applicationContext
        Thread({ try { probeNow(app, serverId) } catch (_: Throwable) {} finally { running.remove(serverId) } }, "mines-selfmap-probe").apply { isDaemon = true; start() }
    }

    fun definition() = AgentToolDefinition(
        name = TOOL_NAME,
        description = "Mines' map of ITSELF and of the user's own setup: its features / settings pages, the configured providers, 模型组 (model groups, default, agent-loop groups), " +
            "and the user's 工作位置 servers with which ports are open as seen from this phone (probe=true re-tests now; ports adds extra ports, e.g. \"8794,7860\"). " +
            "Use it before answering anything about what Mines can do, what is configured, or whether a server port is reachable — never guess.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
            "section" to AgentToolParam("string", "all | features | providers | groups | servers"),
            "probe" to AgentToolParam("boolean", "servers: re-test the ports now from the phone"),
            "ports" to AgentToolParam("string", "servers: extra ports to test, comma separated"),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "section", "probe", "ports"),
    )

    fun runTool(c: Context, argsJson: String): ToolExecutionResult = runCatching {
        val a = JSONObject(argsJson.ifBlank { "{}" })
        val section = a.optString("section", "all")
        val extra = a.optString("ports").split(',', ' ', '，').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..65535 }
        if (a.optBoolean("probe", false) || extra.isNotEmpty()) {
            PlusPrefs.init(c)
            PlusPrefs.state.value.servers.filter { it.enabled && it.host.isNotBlank() }.forEach { probeNow(c, it.id, extra) }
        }
        ToolExecutionResult(SelfMapCore.detail(snapshot(c, refresh = false), section, System.currentTimeMillis()), true)
    }.getOrElse { ToolExecutionResult("Error: ${it.message}", false) }
}
