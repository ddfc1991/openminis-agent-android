package com.openminis.app.plus.group

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** [Mines-group-37] Groups and their messages, one small JSON file per group (filesDir/mines_groups). */
data class AiGroup(
    val id: String,
    val name: String,
    val members: List<String>,
    /** Mines itself sits in the group as 主持 (can be @'d, summarises). */
    val host: Boolean = true,
    /** true = members answer one after another (each sees the earlier answers); false = all at once. */
    val sequential: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

data class GroupMsg(
    val id: String,
    /** "user", "host" or a member id. */
    val who: String,
    val name: String,
    val model: String,
    val text: String,
    /** ok / pending / error / stopped */
    val status: String = "ok",
    val inTok: Int = 0,
    val outTok: Int = 0,
    val ms: Long = 0,
    val at: Long = System.currentTimeMillis(),
    /** [Mines-38.5] raw server text behind a friendly error (shown under 「详情」), or a one-line note (「已自动换成 …」). */
    val detail: String = "",
    /** [Mines-38.5] host turn kind: "" (reply) / "wrap" (round wrap-up) / "summary" (structured 总结). */
    val kind: String = "",
)

object GroupStore {
    const val HOST = "host"
    private var dir: File? = null
    private val _groups = MutableStateFlow<List<AiGroup>>(emptyList())
    val groups: StateFlow<List<AiGroup>> = _groups.asStateFlow()
    private val msgFlows = HashMap<String, MutableStateFlow<List<GroupMsg>>>()

    fun init(context: Context) {
        if (dir != null) return
        synchronized(this) {
            if (dir != null) return
            val d = File(context.applicationContext.filesDir, "mines_groups").apply { mkdirs() }
            dir = d
            _groups.value = (d.listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()).mapNotNull { f ->
                runCatching { groupOf(JSONObject(f.readText()).getJSONObject("group")) }.getOrNull()
            }.sortedByDescending { it.createdAt }
            PhoneTyping.appContext = context.applicationContext
            GroupPrefs.init(context)
        }
    }

    private fun groupOf(o: JSONObject) = AiGroup(o.getString("id"), o.getString("name"),
        o.optJSONArray("members")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        o.optBoolean("host", true), o.optBoolean("seq", true), o.optLong("at"))

    private fun json(g: AiGroup) = JSONObject().put("id", g.id).put("name", g.name).put("members", JSONArray(g.members))
        .put("host", g.host).put("seq", g.sequential).put("at", g.createdAt)

    private fun msgJson(m: GroupMsg) = JSONObject().put("id", m.id).put("who", m.who).put("name", m.name).put("model", m.model)
        .put("text", m.text).put("st", m.status).put("in", m.inTok).put("out", m.outTok).put("ms", m.ms).put("at", m.at).put("detail", m.detail).put("kind", m.kind)

    private fun msgOf(o: JSONObject) = GroupMsg(o.getString("id"), o.getString("who"), o.optString("name"), o.optString("model"),
        o.optString("text"), o.optString("st", "ok"), o.optInt("in"), o.optInt("out"), o.optLong("ms"), o.optLong("at"), o.optString("detail"), o.optString("kind"))

    fun group(id: String): AiGroup? = _groups.value.firstOrNull { it.id == id }

    fun messages(id: String): StateFlow<List<GroupMsg>> = flow(id)

    private fun flow(id: String): MutableStateFlow<List<GroupMsg>> = synchronized(msgFlows) {
        msgFlows.getOrPut(id) {
            val f = File(dir ?: return@getOrPut MutableStateFlow<List<GroupMsg>>(emptyList()), "$id.json")
            MutableStateFlow<List<GroupMsg>>(runCatching {
                val a = JSONObject(f.readText()).optJSONArray("msgs") ?: JSONArray()
                (0 until a.length()).map { msgOf(a.getJSONObject(it)) }.map { if (it.status == "pending") it.copy(status = "stopped", text = "") else if (it.status == "stopped" && (it.text == "已停止" || it.text == "（已中断）")) it.copy(text = "") else it }
            }.getOrDefault(emptyList()))
        }
    }

    @Synchronized
    private fun save(id: String) {
        val g = group(id) ?: return
        val d = dir ?: return
        val arr = JSONArray(); messages(id).value.takeLast(400).forEach { arr.put(msgJson(it)) }
        runCatching { File(d, "$id.json").writeText(JSONObject().put("group", json(g)).put("msgs", arr).toString()) }
    }

    fun create(name: String, members: List<String>, host: Boolean, sequential: Boolean): AiGroup {
        val g = AiGroup("g" + UUID.randomUUID().toString().take(8), name.ifBlank { "AI 群聊" }, members, host, sequential)
        _groups.value = listOf(g) + _groups.value
        save(g.id)
        return g
    }

    /** [Mines-38.6] a 1:1 chat with one agent / app (tapping it in AI 伙伴): reused, no host. */
    fun direct(memberId: String, name: String): AiGroup =
        _groups.value.firstOrNull { it.members == listOf(memberId) && !it.host && it.name == name } ?: create(name, listOf(memberId), host = false, sequential = true)

    fun update(g: AiGroup) { _groups.value = _groups.value.map { if (it.id == g.id) g else it }; save(g.id) }

    fun delete(id: String) {
        _groups.value = _groups.value.filter { it.id != id }
        synchronized(msgFlows) { msgFlows.remove(id) }
        dir?.let { File(it, "$id.json").delete() }
    }

    fun add(groupId: String, m: GroupMsg) { flow(groupId).update { it + m }; save(groupId) }

    fun replace(groupId: String, m: GroupMsg) {
        flow(groupId).update { l -> l.map { if (it.id == m.id) m else it } }
        if (m.status != "pending") save(groupId)
    }

    fun clear(groupId: String) { flow(groupId).value = emptyList(); save(groupId) }

    fun newId() = UUID.randomUUID().toString().take(12)
}
