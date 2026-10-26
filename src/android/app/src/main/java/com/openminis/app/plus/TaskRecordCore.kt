package com.openminis.app.plus

import com.openminis.app.plus.ChatHomeCore.AutoTask
import com.openminis.app.plus.ChatHomeCore.St
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.14.3 chat_task_fold] user 2026-10-26 06:38: 「首页的对话任务，我希望应该是折叠的，点击可以打开查看。这个任务就要记录好点，
 * 我点进去它应该是以这个对话开的新的对话页」.
 *
 * - [Ev]: the task's own record — every state change (建立 / 进行中 / 等你 / 出错 / 完成) with its time and line, so a task
 *   keeps its original request, steps, retries and result / error even after 聊天 folded its cards.
 * - [rowLine]: the ONE compact row in 聊天 (title · state · time), collapsed by default.
 * - [details]: what the row shows when tapped open (inline).
 * - [seed]: the first two rows of the task's own new conversation (「打开」): the originating message as the user's row,
 *   then the full record as Mines' row, so the user can keep talking about this task there.
 * No Android, unit-tested (TaskRecordCoreTest).
 */
object TaskRecordCore {
    const val MAX_EVENTS = 60

    data class Ev(val at: Long, val st: St, val text: String)

    /** a new record line when the state or its line changed; null when nothing did ([prev] null = the task was just made) */
    fun diff(prev: AutoTask?, next: AutoTask, now: Long): Ev? {
        if (prev == null) return Ev(next.createdAt.takeIf { it > 0 } ?: now, St.QUEUED, "建立任务：" + next.name)
        if (prev.st == next.st && prev.line == next.line) return null
        if (prev.st == next.st && next.st == St.RUNNING) return null   // progress lines while running are the steps, not record lines
        val t = when (next.st) {
            St.QUEUED -> "排队等待"
            St.RUNNING -> if (prev.st == St.ERROR || prev.st == St.WAITING || prev.st == St.DONE) "重新开始" else "开始运行"
            St.WAITING -> "等你：" + next.line.ifBlank { "需要你回复" }
            St.ERROR -> "出错：" + next.line.removePrefix("没完成：").ifBlank { "出错了" }
            St.DONE -> "完成：" + next.line.ifBlank { "已完成" }
        }
        return Ev(now, next.st, t)
    }

    /** append, dropping an exact repeat of the last line and keeping the newest [MAX_EVENTS] */
    fun append(log: List<Ev>, e: Ev?): List<Ev> {
        if (e == null) return log
        val last = log.lastOrNull()
        if (last != null && last.st == e.st && last.text == e.text) return log
        return (log + e).takeLast(MAX_EVENTS)
    }

    /** 已重试 N 次 = runs that failed before the latest one (from the record; at least what 聊天's fold counted) */
    fun retries(log: List<Ev>, folded: Int = 0): Int {
        val errs = log.count { it.st == St.ERROR }
        val endsInError = log.lastOrNull()?.st == St.ERROR
        return maxOf(folded, if (endsInError) errs - 1 else errs).coerceAtLeast(0)
    }

    fun stateWord(t: AutoTask): String = when (t.st) {
        St.WAITING -> "等你"; St.ERROR -> "出错"; St.DONE -> "完成"; St.RUNNING -> "进行中"; St.QUEUED -> "排队"
    }

    /** the collapsed row: 「Zeabur 注册 · 等你 · 06:28」 (the a11y text says how to open it) */
    data class Row(val title: String, val state: String, val time: String, val a11y: String)

    fun rowLine(t: AutoTask, retries: Int, hm: (Long) -> String, open: Boolean): Row {
        val st = stateWord(t) + if (t.st == St.RUNNING && t.total > 0) " ${t.step.coerceAtMost(t.total)}/${t.total}" else ""
        val time = hm(t.updatedAt.coerceAtLeast(t.createdAt))
        val a11y = "${t.name}，$st，$time" + (if (retries > 0) "，已重试 $retries 次" else "") + if (open) "。点按收起" else "。点按展开"
        return Row(t.name, st, time, a11y)
    }

    /** the inline details when a row is open: label → value lines, then the record (newest last, at most [maxEv]) */
    fun details(t: AutoTask, log: List<Ev>, retries: Int, fmt: (Long) -> String, maxEv: Int = 4): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out += "原始请求" to t.goal.trim().ifBlank { t.name }
        val now = when (t.st) {
            St.WAITING -> t.line.ifBlank { "需要你回复" }
            St.ERROR -> t.line.removePrefix("没完成：").ifBlank { "出错了" }
            St.DONE -> t.line.ifBlank { "已完成" }
            St.RUNNING -> if (t.total > 0) "第 ${t.step.coerceAtMost(t.total)}/${t.total} 步" else "正在运行"
            St.QUEUED -> "排队等待"
        }
        out += (when (t.st) { St.WAITING -> "等你"; St.ERROR -> "错误"; St.DONE -> "结果"; else -> "状态" }) to now
        val prog = listOfNotNull(if (t.total > 0) "${t.step.coerceAtMost(t.total)}/${t.total} 步" else null, if (retries > 0) "已重试 $retries 次" else null)
        if (prog.isNotEmpty()) out += "进度" to prog.joinToString(" · ")
        out += "时间" to ("开始 " + fmt(t.createdAt) + (if (t.updatedAt > t.createdAt) " · 更新 " + fmt(t.updatedAt) else ""))
        log.takeLast(maxEv).forEach { out += fmt(it.at) to it.text }
        return out
    }

    /**
     * The task's own conversation: row 1 = the user's original message (as sent in 聊天), row 2 = Mines' full record of the
     * task (state, result / error, steps, retries, timeline). [steps] = (title, done?) of the run's progress list.
     */
    fun seed(t: AutoTask, log: List<Ev>, steps: List<Pair<String, Boolean>>, retries: Int, fmt: (Long) -> String): List<Pair<String, String>> {
        val b = StringBuilder()
        b.append("**任务记录 · ").append(t.name).append("**\n\n")
        b.append("- 状态：").append(stateWord(t))
        t.line.takeIf { it.isNotBlank() }?.let { b.append(" — ").append(it.removePrefix("没完成：")) }
        b.append('\n')
        if (t.total > 0) b.append("- 进度：").append(t.step.coerceAtMost(t.total)).append('/').append(t.total).append(" 步\n")
        if (retries > 0) b.append("- 已重试 ").append(retries).append(" 次\n")
        b.append("- 开始 ").append(fmt(t.createdAt)).append(" · 更新 ").append(fmt(t.updatedAt.coerceAtLeast(t.createdAt))).append('\n')
        if (steps.isNotEmpty()) {
            b.append("\n**步骤**\n")
            steps.forEachIndexed { i, (title, done) -> b.append(i + 1).append(". ").append(if (done) "✓ " else "· ").append(title).append('\n') }
        }
        if (log.isNotEmpty()) {
            b.append("\n**时间线**\n")
            log.forEach { b.append("- ").append(fmt(it.at)).append(' ').append(it.text).append('\n') }
        }
        b.append("\n这是这个任务自己的对话，接着说就行：问进展、补充信息、让我重试都可以。")
        return listOf("user" to t.goal.trim().ifBlank { t.name }, "assistant" to b.toString().trim())
    }

    /** what changed since the conversation was seeded / last updated (appended when it is opened again) */
    fun update(log: List<Ev>, since: Long, fmt: (Long) -> String): String? {
        val news = log.filter { it.at > since }
        if (news.isEmpty()) return null
        return "**任务更新**\n" + news.joinToString("\n") { "- " + fmt(it.at) + " " + it.text }
    }

    // ── JSON ──

    fun logJson(m: Map<String, List<Ev>>): String = JSONObject().apply {
        m.forEach { (id, l) -> put(id, JSONArray().apply { l.forEach { e -> put(JSONObject().put("a", e.at).put("s", e.st.id).put("t", e.text)) } }) }
    }.toString()

    fun logOf(s: String?): Map<String, List<Ev>> = runCatching {
        val o = JSONObject(s ?: "{}")
        o.keys().asSequence().associateWith { id ->
            val a = o.getJSONArray(id)
            (0 until a.length()).map { i -> a.getJSONObject(i).let { e -> Ev(e.optLong("a"), St.of(e.optString("s")), e.optString("t")) } }
        }
    }.getOrDefault(emptyMap())
}
