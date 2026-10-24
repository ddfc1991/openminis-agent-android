package com.openminis.app.plus

import com.openminis.app.agent.ExperienceCore

/**
 * [Mines-38.9.8] 技能优先, made to actually run (user 2026-10-19): before the model starts a task the APP searches the
 * installed skills and the saved 工作流 for the user's request and picks one, so the choice no longer depends on whether the
 * model bothers to look. The pick goes into the system prompt (read it first, follow it) and is shown to the user above
 * the reply (「用技能：xxx」). Pure, unit-tested in SkillMatchCoreTest.
 */
object SkillMatchCore {
    enum class Kind(val label: String) { SKILL("技能"), WORKFLOW("工作流") }
    data class Cand(val kind: Kind, val id: String, val name: String, val description: String, val body: String = "", val path: String = "")
    data class Match(val cand: Cand, val score: Double)

    /** One-word chats / single questions do not get a skill. */
    fun worthMatching(q: String): Boolean {
        val t = q.trim()
        if (t.length < 4) return false
        if (Regex("^(你好|hi|hello|在吗|谢谢|好的|ok|嗯|继续|好|行|可以)[!！。.?？ ]*$", RegexOption.IGNORE_CASE).matches(t)) return false
        return true
    }

    /**
     * Best candidate for [query], or null. Score = query tokens found in the name (×3) / description (×2) / body (×0.5),
     * normalised by the query's token count; a pick needs ≥ [min] and at least two shared tokens (or the whole name).
     */
    fun best(query: String, cands: List<Cand>, min: Double = 0.34): Match? {
        if (!worthMatching(query)) return null
        val qt = ExperienceCore.tokens(query.take(600)).distinct()
        if (qt.isEmpty()) return null
        val ql = query.lowercase()
        return cands.mapNotNull { c ->
            val nameT = ExperienceCore.tokens(c.name.replace('-', ' ')).toSet()
            val descT = ExperienceCore.tokens(c.description).toSet()
            val bodyT = ExperienceCore.tokens(c.body.take(1500)).toSet()
            var shared = 0
            var s = 0.0
            for (t in qt) {
                val w = when { t in nameT -> 3.0; t in descT -> 2.0; t in bodyT -> 0.5; else -> 0.0 }
                if (w > 0) { shared++; s += w }
            }
            val wholeName = c.name.length >= 3 && ql.contains(c.name.lowercase())
            if (wholeName) s += 6.0
            val norm = s / (qt.size * 3.0)
            if ((shared >= 2 || wholeName) && norm >= min) Match(c, norm) else null
        }.sortedWith(compareByDescending<Match> { it.score }.thenBy { it.cand.kind.ordinal }).firstOrNull()
    }

    /** Line put into the system prompt for this turn (empty = nothing matched). */
    fun promptLine(m: Match?): String = when {
        m == null -> ""
        m.cand.kind == Kind.SKILL -> "Matched skill for this request (picked by the app): 「${m.cand.name}」" +
            (if (m.cand.path.isNotBlank()) " at ${m.cand.path}" else "") + ". Read it first (file_read its SKILL.md) and follow it; if it clearly does not fit, say so in one line and go on."
        else -> "Matched saved workflow for this request (picked by the app): 「${m.cand.name}」. Steps that worked last time:\n" +
            m.cand.body.lines().filter { it.isNotBlank() }.take(8).joinToString("\n") { "  $it" } + "\nReuse them where they fit." +
            (if (m.cand.body.startsWith("技能组合：")) " Apply the same skills in that order (read each SKILL.md first) unless the request clearly differs." else "")
    }

    /** Chip shown to the user above the reply. */
    fun chip(m: Match?): String = if (m == null) "" else "用${m.cand.kind.label}：${m.cand.name}".take(40)

    /** Name + description from a SKILL.md's YAML front matter (falls back to the directory name). */
    fun frontMatter(text: String, dirName: String): Pair<String, String> {
        val fm = Regex("^---\\s*\\n([\\s\\S]*?)\\n---").find(text.trimStart())?.groupValues?.get(1).orEmpty()
        fun key(k: String) = Regex("(?m)^$k\\s*:\\s*(.+)$").find(fm)?.groupValues?.get(1)?.trim()?.trim('"', '\'').orEmpty()
        return key("name").ifBlank { dirName } to key("description")
    }
}
