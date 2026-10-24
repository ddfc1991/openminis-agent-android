package com.openminis.app.plus.group

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.8] Multi-agent group without Feishu: a Mines group chat whose members are any presets — Mines' own agent,
 * Muse via the nanoMuse relay, Memoh bots, 扣子 bots, web-chat apps (Hark / Manus web / Muse web …). They all share one
 * context: GroupRunner gives every member the same history (others' lines as 【名字】…), Mines hosts and wraps up.
 */
object MultiAgentGroup {
    const val NAME = "Mines × Muse"

    /** Members for the default multi-agent group: Mines, Muse 中继, then every other ready AGENT / registry member. */
    fun defaultMembers(all: List<Partner> = Partners.list.value): List<String> {
        val ready = all.filter { it.ready }
        val first = listOf(AiCatalog.MINES_ID, "nanomuse").filter { id -> all.any { it.id == id } }
        val more = ready.filter { p ->
            p.id !in first && (p.spec.kind == Kind.AGENT || PresetRegistry.preset(p.id) != null) && p.spec.proto != Proto.PHONE
        }.map { it.id }
        return (first + more).distinct().take(8)
    }

    /** Creates (or reuses) the group; returns its id. Members that are not ready yet are kept and skipped quietly per round. */
    suspend fun ensure(context: Context, members: List<String>? = null): String {
        GroupStore.init(context)
        val list = Partners.refresh(context)
        val ids = members ?: defaultMembers(list)
        val old = GroupStore.groups.value.firstOrNull { it.name == NAME }
        if (old != null) {
            if (old.members.toSet() != ids.toSet()) GroupStore.update(old.copy(members = ids))
            return old.id
        }
        return GroupStore.create(NAME, ids, host = true, sequential = true).id
    }
}

/**
 * [Mines-38.8] adb-only smoke hooks for the presets (GroupSelfTest forwards `preset …`):
 *   preset list                     → every registry preset with its spec / visibility
 *   preset relaykey <nm_key>        → stores a relay key (as the code flow does) and returns the Muse 中继 state
 *   preset relayparse <json>        → what keyFromVerify takes out of a verify answer
 *   preset memoh <url>|<user>|<pw>  → logs in, lists bots, returns their member ids
 *   preset memohparse <json>        → assistant turns MemohAdapter reads from a /messages answer
 *   preset muse 1|0                 → Muse toggle
 *   preset group                    → creates the Mines × Muse group, returns id + members
 */
object PresetSelfTest {
    fun handle(context: Context, arg: String): String = runBlocking {
        GroupPrefs.init(context)
        val cmd = arg.substringBefore(' ')
        val rest = arg.substringAfter(' ', "")
        val parts = rest.split('|')
        when (cmd) {
            "list" -> JSONArray(PresetRegistry.all.map { p ->
                val s = AiCatalog.spec(p.id) ?: p.toSpec()
                JSONObject().put("id", p.id).put("transport", p.transport.name).put("proto", s.proto.name).put("keyOptional", s.keyOptional)
                    .put("visible", PresetRegistry.visible(p.id)).put("login", p.auth.login?.javaClass?.simpleName ?: "")
            }).toString()
            "relaykey" -> {
                PresetAuth.setKey("nanomuse", parts.getOrNull(0)?.takeIf { it.isNotBlank() })
                val p = Partners.refresh(context).firstOrNull { it.id == "nanomuse" }
                JSONObject().put("state", p?.state?.name).put("via", p?.via).put("model", p?.model).put("reason", p?.reason).toString()
            }
            "relayparse" -> PresetAuth.keyFromVerify(rest) ?: "null"
            "memoh" -> {
                val r = PresetAuth.memohLogin(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
                if (r.isFailure) "login failed: " + r.exceptionOrNull()?.message
                else JSONArray(Partners.refresh(context).filter { it.id.startsWith(PresetRegistry.MEMOH) }.map { "${it.id}:${it.state}" }).toString()
            }
            "memohparse" -> JSONArray(MemohAdapter.assistantTurns(rest).map { JSONArray().put(it.first).put(it.second) }).toString()
            // [Mines-38.8] smoke: open AI 伙伴 with a preset's own sign-in dialog up (nanomuse / memoh / coze)
            "openlogin" -> {
                val l = java.util.concurrent.CountDownLatch(1)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    com.openminis.app.ui.group.PartnersNav.open = rest.trim().ifBlank { null }
                    com.openminis.app.ui.plus.PlusNav.go(com.openminis.app.ui.group.GroupRoutes.PARTNERS); l.countDown() }
                l.await(5, java.util.concurrent.TimeUnit.SECONDS); "ok"
            }
            "muse" -> { PresetRegistry.setMuseOn(parts.getOrNull(0) == "1"); Partners.refresh(context); "ok" }
            "group" -> {
                val gid = MultiAgentGroup.ensure(context)
                JSONObject().put("id", gid).put("members", JSONArray(GroupStore.group(gid)?.members ?: emptyList<String>())).toString()
            }
            else -> "unknown: $cmd"
        }
    }
}
