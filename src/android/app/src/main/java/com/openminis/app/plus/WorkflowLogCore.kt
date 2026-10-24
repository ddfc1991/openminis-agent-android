package com.openminis.app.plus

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.8] 工作流记录 (user 2026-10-19): every finished agent run is kept as one record — the task in the user's
 * words, the steps (tool + short argument + ok / failed), the result, start / end time — browsable in 设置 › 工作流记录,
 * and one tap turns a record into a reusable skill (SKILL.md). Secrets never get in: arguments are already shortened and
 * masked by Experience.brief, and [redact] masks anything that still looks like a key / token / password.
 * Pure, unit-tested in WorkflowLogCoreTest.
 */
object WorkflowLogCore {
    const val MAX = 200

    data class Step(val tool: String, val brief: String, val ok: Boolean = true, val error: String = "")
    data class Run(
        val id: String,
        val sid: String,
        val task: String,
        val steps: List<Step>,
        val result: String,
        val status: String,          // "done" / "failed" / "stopped"
        val startedAt: Long,
        val endedAt: Long,
        val skillId: String = "",    // set once saved as a skill
    ) {
        val ok: Boolean get() = status == "done"
        val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
    }

    private val secret = Regex("(?i)(sk-[a-z0-9_\\-]{8,}|ghp_[a-z0-9]{10,}|xox[abp]-[a-z0-9\\-]{8,}|AIza[0-9a-z_\\-]{20,}|eyJ[a-z0-9_\\-]{15,}\\.[a-z0-9_\\-.]+|" +
        "(?:password|passwd|pwd|token|secret|api[_-]?key|cookie|验证码|密码)\\s*[=:：]\\s*\\S+)")

    fun redact(s: String): String = s.replace(secret) { m ->
        val v = m.value
        val k = Regex("(?i)^(password|passwd|pwd|token|secret|api[_-]?key|cookie|验证码|密码)\\s*[=:：]\\s*").find(v)?.value
        if (k != null) "$k***" else "***"
    }

    fun status(failedSteps: Int, cancelled: Boolean, finalText: String): String = when {
        cancelled -> "stopped"
        finalText.isBlank() -> "failed"
        Regex("(没成功|失败了|做不到|无法完成|没能完成|failed to|unable to|could not)", RegexOption.IGNORE_CASE).containsMatchIn(finalText.take(300)) -> "failed"
        else -> "done"
    }

    fun statusLabel(st: String) = when (st) { "done" -> "完成"; "stopped" -> "已停止"; else -> "没成功" }

    /** Newest first, one record per run id, at most [MAX]. */
    /** [Mines-38.9.9] the skills a run used, in order (a skill = its SKILL.md read from /var/minis/skills/<id>/), repeats folded */
    fun skillChain(steps: List<Step>): List<String> {
        val out = ArrayList<String>()
        for (st in steps) Regex("/skills/([A-Za-z0-9._\\-]+)/").findAll(st.brief).forEach { m ->
            val id = m.groupValues[1]; if (out.lastOrNull() != id) out += id
        }
        return out.distinct()
    }

    /** [Mines-38.9.9] the inputs the run started from (first line of the task, secrets redacted) */
    fun inputsOf(r: Run): String = redact(r.task.lineSequence().firstOrNull().orEmpty()).take(80)

    /** one line 「技能组合：a → b（输入：…）」, "" when the run used no skill */
    fun comboLine(r: Run): String = skillChain(r.steps).takeIf { it.isNotEmpty() }?.let { "技能组合：" + it.joinToString(" → ") + "（输入：" + inputsOf(r) + "）" }.orEmpty()

    fun add(list: List<Run>, r: Run): List<Run> = (listOf(r) + list.filter { it.id != r.id }).take(MAX)

    fun toJson(l: List<Run>): JSONArray = JSONArray().apply {
        l.forEach { r ->
            put(JSONObject().put("id", r.id).put("sid", r.sid).put("task", r.task).put("result", r.result).put("status", r.status)
                .put("startedAt", r.startedAt).put("endedAt", r.endedAt).put("skillId", r.skillId)
                .put("steps", JSONArray().apply { r.steps.forEach { s -> put(JSONObject().put("tool", s.tool).put("brief", s.brief).put("ok", s.ok).put("error", s.error)) } }))
        }
    }

    fun fromJson(a: JSONArray): List<Run> = (0 until a.length()).mapNotNull { i ->
        runCatching {
            val o = a.getJSONObject(i)
            val st = o.optJSONArray("steps") ?: JSONArray()
            Run(o.getString("id"), o.optString("sid"), o.optString("task"),
                (0 until st.length()).map { j -> st.getJSONObject(j).let { s -> Step(s.optString("tool"), s.optString("brief"), s.optBoolean("ok", true), s.optString("error")) } },
                o.optString("result"), o.optString("status", "done"), o.optLong("startedAt"), o.optLong("endedAt"), o.optString("skillId"))
        }.getOrNull()
    }

    /** Builds a run from what the agent loop saw ([calls] = tool, brief, failed, error). */
    fun build(id: String, sid: String, task: String, calls: List<Step>, finalText: String, cancelled: Boolean, startedAt: Long, endedAt: Long): Run {
        // [Mines-38.9.11 i32] verification codes after 验证码 / code / otp are masked too (XpViewCore.scrub)
        val steps = calls.map { it.copy(brief = XpViewCore.scrub(redact(it.brief)).take(120), error = XpViewCore.scrub(redact(it.error)).take(120)) }.take(40)
        return Run(id, sid, redact(task.trim()).take(300), steps, redact(finalText.trim()).take(600),
            status(steps.count { !it.ok }, cancelled, finalText), startedAt, endedAt)
    }

    /** Directory id for a skill made from [r]: latin words of the task, else "workflow-<base36 time>". */
    fun skillId(r: Run): String {
        val latin = Regex("[a-z0-9]+").findAll(r.task.lowercase()).map { it.value }.filter { it.length >= 2 }.take(4).joinToString("-")
        return (if (latin.length >= 4) "wf-$latin" else "workflow-" + java.lang.Long.toString(r.startedAt.coerceAtLeast(1), 36)).take(48)
    }

    /** SKILL.md text (front matter name + description, then the steps that worked, the pitfalls and the result). */
    fun toSkillMd(r: Run, name: String = r.task.lineSequence().firstOrNull().orEmpty().take(40)): String {
        val sb = StringBuilder()
        val nm = name.ifBlank { "工作流" }.replace("\"", "'")
        sb.append("---\nname: \"").append(nm).append("\"\ndescription: \"")
            .append(("当用户要「" + r.task.lineSequence().firstOrNull().orEmpty().take(60) + "」这类事时用（从工作流记录存的）").replace("\"", "'")).append("\"\n---\n\n")
        sb.append("# ").append(nm).append("\n\n## 做法（上次成功的步骤）\n")
        r.steps.filter { it.ok }.forEachIndexed { i, s -> sb.append(i + 1).append(". `").append(s.tool).append("` ").append(s.brief).append('\n') }
        val bad = r.steps.filter { !it.ok }
        if (bad.isNotEmpty()) {
            sb.append("\n## 踩过的坑\n")
            bad.forEach { s -> sb.append("- `").append(s.tool).append("` ").append(s.brief).append(if (s.error.isNotBlank()) " → ${s.error}" else "").append('\n') }
        }
        if (r.result.isNotBlank()) sb.append("\n## 结果\n").append(r.result.take(400)).append('\n')
        sb.append("\n（不含任何密码、密钥或验证码）\n")
        return sb.toString()
    }
}
