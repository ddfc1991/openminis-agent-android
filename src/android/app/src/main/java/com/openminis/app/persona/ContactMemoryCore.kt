package com.openminis.app.persona

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.15 伪人核心 · 联系人记忆] First-person memory of the people the user talks to: who they are to ME, what we
 * last talked about. Consulted before every reply the persona writes so the person on the other side meets the same
 * "me" every time. Unit-tested in PersonaCoreTest.
 */
object ContactMemoryCore {
    data class Contact(
        val key: String,               // address / handle, lower-case
        val name: String = "",
        val relation: String = "",     // 同事 / 朋友 / 客户 … in the user's words
        val topics: List<String> = emptyList(),   // newest last, at most [MAX_TOPICS]
        val lastAt: Long = 0L,
        val note: String = "",
    )

    const val MAX_TOPICS = 5
    const val MAX_CONTACTS = 500

    fun keyOf(addressOrHandle: String): String {
        val s = addressOrHandle.trim()
        val inAngle = Regex("<([^>]+)>").find(s)?.groupValues?.get(1)
        return (inAngle ?: s).trim().lowercase()
    }

    /** remember one exchange: the topic goes to the end (a repeat moves to the end instead of doubling) */
    fun touch(all: Map<String, Contact>, who: String, topic: String?, now: Long, name: String? = null, relation: String? = null, note: String? = null): Map<String, Contact> {
        val k = keyOf(who)
        if (k.isBlank()) return all
        val c = all[k] ?: Contact(k)
        val t = topic?.trim()?.take(40)?.takeIf { it.isNotBlank() }
        val topics = if (t == null) c.topics else (c.topics.filter { it != t } + t).takeLast(MAX_TOPICS)
        val upd = c.copy(
            name = name?.trim()?.takeIf { it.isNotBlank() } ?: c.name,
            relation = relation?.trim()?.takeIf { it.isNotBlank() } ?: c.relation,
            note = note?.trim()?.takeIf { it.isNotBlank() }?.take(200) ?: c.note,
            topics = topics, lastAt = now,
        )
        val m = LinkedHashMap(all); m[k] = upd
        if (m.size > MAX_CONTACTS) m.entries.sortedBy { it.value.lastAt }.take(m.size - MAX_CONTACTS).forEach { m.remove(it.key) }
        return m
    }

    /** the line the rewrite / the agent reads before answering this person — first person, never "the user" */
    fun brief(c: Contact?): String {
        if (c == null) return "这是我第一次和 TA 联系，语气按我平时的来。"
        return buildString {
            append("对方是").append(c.name.ifBlank { c.key })
            if (c.relation.isNotBlank()) append("，是我的").append(c.relation)
            append("。")
            if (c.topics.isNotEmpty()) append("我们最近聊过：").append(c.topics.joinToString("、")).append("。")
            if (c.note.isNotBlank()) append("我记着：").append(c.note).append("。")
        }
    }

    fun toJson(m: Map<String, Contact>): String = JSONArray().apply {
        m.values.forEach { c ->
            put(JSONObject().put("k", c.key).put("n", c.name).put("r", c.relation).put("t", JSONArray(c.topics)).put("at", c.lastAt).put("note", c.note))
        }
    }.toString()

    fun fromJson(s: String?): Map<String, Contact> = runCatching {
        if (s.isNullOrBlank()) return emptyMap()
        val a = JSONArray(s)
        val m = LinkedHashMap<String, Contact>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val t = o.optJSONArray("t")
            val c = Contact(o.optString("k"), o.optString("n"), o.optString("r"),
                (0 until (t?.length() ?: 0)).map { t!!.optString(it) }.filter { it.isNotBlank() }, o.optLong("at"), o.optString("note"))
            if (c.key.isNotBlank()) m[c.key] = c
        }
        m
    }.getOrDefault(emptyMap())
}
