package com.openminis.app.plus

import android.content.Context
import com.openminis.app.MinisApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [Mines-38.9.8] 技能优先 run by the app (see [SkillMatchCore]): for the latest user request, search the enabled skills and
 * the saved 工作流 (successful ones) and pick one; the pick is put into the system prompt and shown in the chat (chip
 * 「用技能：xxx」 above the input) per conversation. In-memory lists only (no disk on the main thread).
 */
object SkillMatch {
    private val _chips = MutableStateFlow<Map<String, String>>(emptyMap())
    /** session id → chip text */
    val chips: StateFlow<Map<String, String>> = _chips.asStateFlow()
    @Volatile var lastQuery: String = ""
    @Volatile var last: SkillMatchCore.Match? = null

    fun candidates(c: Context, sid: String): List<SkillMatchCore.Cand> {
        val out = ArrayList<SkillMatchCore.Cand>()
        runCatching {
            val repo = (c.applicationContext as MinisApp).skillRepository
            repo.skills.value.filter { it.isEnabled && repo.isEnabledForSession(it.id, sid) }.forEach { s ->
                out += SkillMatchCore.Cand(SkillMatchCore.Kind.SKILL, s.id, s.name, s.description, s.body.take(1500), "/var/minis/skills/${s.id}/SKILL.md")
            }
        }
        runCatching {
            WorkflowLog.list(c).filter { it.ok && it.skillId.isBlank() && it.steps.isNotEmpty() }.take(60).forEach { r ->
                out += SkillMatchCore.Cand(SkillMatchCore.Kind.WORKFLOW, r.id, r.task.lineSequence().firstOrNull().orEmpty().take(40), r.result.take(200),
                    // [Mines-38.9.9] a recorded skill combination goes first so the agent re-applies the same skills in order
                    (WorkflowLogCore.comboLine(r).takeIf { it.isNotBlank() }?.let { it + "\n" }.orEmpty() +
                    r.steps.filter { it.ok }.mapIndexed { i, s -> "${i + 1}. ${s.tool} ${s.brief}" }.joinToString("\n")))
            }
        }
        return out
    }

    /** The prompt line for this request ("" = nothing matched); also publishes the chip for [sid]. */
    fun promptLine(c: Context, sid: String, query: String): String {
        if (query.isBlank()) return ""
        val m = if (query == lastQuery) last else SkillMatchCore.best(query, candidates(c, sid)).also { lastQuery = query; last = it }
        val chip = SkillMatchCore.chip(m)
        val cur = _chips.value
        if (sid.isNotBlank() && cur[sid] != chip.ifBlank { null }) _chips.value = if (chip.isBlank()) cur - sid else cur + (sid to chip)
        return SkillMatchCore.promptLine(m)
    }
}
