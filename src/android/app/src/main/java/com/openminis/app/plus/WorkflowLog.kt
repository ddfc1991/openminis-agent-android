package com.openminis.app.plus

import android.content.Context
import com.openminis.app.MinisApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/** [Mines-38.9.8] 工作流记录 store (see [WorkflowLogCore]): one JSON file in the app's private files, newest first. */
object WorkflowLog {
    private val _runs = MutableStateFlow<List<WorkflowLogCore.Run>>(emptyList())
    val runs: StateFlow<List<WorkflowLogCore.Run>> = _runs.asStateFlow()
    @Volatile private var loaded = false

    private fun file(c: Context) = File(c.applicationContext.filesDir, "mines_workflows.json")

    @Synchronized fun init(c: Context) {
        if (loaded) return
        _runs.value = runCatching { WorkflowLogCore.fromJson(JSONArray(file(c).readText())) }.getOrDefault(emptyList())
        loaded = true
    }

    fun list(c: Context): List<WorkflowLogCore.Run> { init(c); return _runs.value }
    fun get(c: Context, id: String) = list(c).firstOrNull { it.id == id }

    @Synchronized private fun save(c: Context, l: List<WorkflowLogCore.Run>) {
        _runs.value = l
        runCatching { val f = file(c); val tmp = File(f.path + ".tmp"); tmp.writeText(WorkflowLogCore.toJson(l).toString()); tmp.renameTo(f) }
    }

    /** Called when an agent run ends (one or more tool calls). */
    fun record(c: Context, r: WorkflowLogCore.Run) = runCatching { init(c); save(c, WorkflowLogCore.add(_runs.value, r)) }

    fun delete(c: Context, id: String) { init(c); save(c, _runs.value.filterNot { it.id == id }) }

    /** [Mines-38.9.11 i32] masks codes / keys already stored in step briefs, errors and results (titles are derived, not stored) */
    fun scrubAll(c: Context) {
        init(c)
        save(c, _runs.value.map { r -> r.copy(steps = r.steps.map { it.copy(brief = XpViewCore.scrub(it.brief), error = XpViewCore.scrub(it.error)) },
            result = XpViewCore.scrub(r.result)) })
    }

    /** Writes the record as a skill (SKILL.md under the skills folder) and reloads skills. Returns a Chinese line. */
    fun saveAsSkill(c: Context, id: String): String = runCatching {
        val r = get(c, id) ?: return "这条记录不见了"
        val sid = r.skillId.ifBlank { WorkflowLogCore.skillId(r) }
        val dir = File(c.applicationContext.filesDir, "minis-global/skills/$sid").apply { mkdirs() }
        File(dir, "SKILL.md").writeText(WorkflowLogCore.toSkillMd(r))
        runCatching { (c.applicationContext as MinisApp).skillRepository.requestReload("workflow-log", force = true) }
        save(c, _runs.value.map { if (it.id == id) it.copy(skillId = sid) else it })
        "已存成技能：$sid"
    }.getOrElse { "没存成：${it.message ?: it.javaClass.simpleName}" }
}
