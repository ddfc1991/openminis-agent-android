package com.openminis.app.plus

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.14 chat_home] user 2026-10-24/25: one main conversation (聊天); the model decides per message whether to
 * answer inline (quick) or to start a background task with its own conversation (task). Pure rules, unit-tested in
 * ChatHomeCoreTest: router prompt + reply parsing + fallback, task state machine (from the task conversation's
 * [TaskProgressCore.Task]), start gate (memory / concurrency), chip text, the tags the main chat stores for a task.
 */
object ChatHomeCore {
    enum class Route { QUICK, TASK }
    data class Decision(val route: Route, val name: String = "", val byModel: Boolean = false)

    // ── router ──

    const val ROUTER_MARK = "MINES_ROUTER"
    const val ROUTER_SYSTEM = "你是 Mines 的任务分流器。只输出一行 JSON，不要解释。"

    fun routerPrompt(text: String): String = buildString {
        append(ROUTER_MARK).append('\n')
        append("判断用户这条消息怎么处理：\n")
        append("- quick：能直接回答的（日期时间、常识、简单解释、闲聊、一句话能说清的问题）。\n")
        append("- task：需要动手做事的（注册/登录/部署/安装/配置、操作网页或服务器或文件、查资料并整理、多个步骤、要等结果的）。\n")
        append("task 要给任务起名：用正在做的项目命名，如「Railway 注册」「siyun 内存检查」「GLaDOS 续费」，不超过 12 个字。\n")
        append("只输出 {\"mode\":\"quick\"} 或 {\"mode\":\"task\",\"name\":\"…\"}\n\n")
        append("用户：").append(text.trim().take(600))
    }

    /** the router model's reply → decision (null = unreadable) */
    fun parse(reply: String?): Decision? {
        val s = reply?.trim().orEmpty()
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        val o = runCatching { JSONObject(s.substring(a, b + 1)) }.getOrNull() ?: return null
        return when (o.optString("mode").trim().lowercase()) {
            "quick" -> Decision(Route.QUICK, byModel = true)
            "task" -> Decision(Route.TASK, cleanName(o.optString("name")), byModel = true)
            else -> null
        }
    }

    private val ACTION = Regex("""(?i)注册|开通|激活|部署|安装|配置|搭建|迁移|修复|爬取|抓取|批量|监控|续费|购买|下单|申请|创建|发布|上传|下载|整理成|写一[个份篇]|做一[个份]|生成一[份个]|帮我把|替我|登录到|跑一下|检查一下.+(服务器|内存|端口|日志)|sign ?up|deploy|install""")

    /** used when no model answers: action words → task, anything else → quick */
    fun heuristic(text: String): Decision =
        if (ACTION.containsMatchIn(text)) Decision(Route.TASK, nameFor(text)) else Decision(Route.QUICK)

    /** short and no action word (「今天几号」): answered inline without asking the router model */
    fun obviousQuick(text: String): Boolean = text.trim().length <= 12 && heuristic(text).route == Route.QUICK

    /** the headless send's refusal token → one Chinese line */
    fun rejectZh(token: String?): String = when {
        token.isNullOrBlank() -> "没开始运行"
        token.startsWith("no_provider") -> "没有可用的模型，先在模型组里加一个"
        token == "context_near_capacity" -> "对话太长，先压缩后再继续"
        token == "session_compacting" -> "正在压缩对话，稍后再试"
        else -> "没开始运行（$token）"
    }

    /** model first, [heuristic] when the model is missing / fails / answers nonsense; a task always gets a name */
    suspend fun decide(text: String, ask: suspend (system: String, prompt: String) -> String?): Decision {
        val t = text.trim()
        if (t.isEmpty()) return Decision(Route.QUICK)
        val d = runCatching { parse(ask(ROUTER_SYSTEM, routerPrompt(t))) }.getOrNull() ?: return heuristic(t)
        return if (d.route == Route.TASK && d.name.isBlank()) d.copy(name = nameFor(t)) else d
    }

    private val BRAND_AFTER = Regex("""(?:注册|开通|激活|续费|部署到|登录)\s*([A-Za-z][A-Za-z0-9.\-]{1,30})""")

    /** the project word of a registration (「帮我注册 zeabur」 → zeabur; a domain wins) */
    fun siteWord(text: String): String? =
        TaskProjectsCore.siteOf(text) ?: BRAND_AFTER.find(text)?.groupValues?.get(1)?.lowercase()?.trimEnd('.')

    /** 「Zeabur 注册」 for a registration, else the request itself trimmed to a short title */
    fun nameFor(text: String): String {
        if (TaskProjectsCore.registerIntent(text)) siteWord(text)?.let { return TaskProjectsCore.projectName(it, TaskProjectsCore.verbOf(text)) }
        val t = text.trim().replace(Regex("""^(请|麻烦)?(你)?(帮我|替我|给我)?"""), "").replace(Regex("""[。！!？?，,\s]+$"""), "")
        return cleanName(t.lineSequence().firstOrNull().orEmpty()).ifBlank { "新任务" }
    }

    fun cleanName(s: String): String = s.trim().trim('「', '」', '"', '“', '”').replace(Regex("""\s+"""), " ").take(16)

    // ── task state machine ──

    enum class St(val label: String, val id: String) {
        QUEUED("排队", "queued"), RUNNING("进行中", "running"), WAITING("等你", "waiting"), ERROR("出错", "error"), DONE("完成", "done");
        companion object { fun of(s: String?): St = values().firstOrNull { it.id == s } ?: QUEUED }
    }

    data class AutoTask(
        val id: String, val sid: String, val name: String, val goal: String,
        val st: St = St.QUEUED, val step: Int = 0, val total: Int = 0,
        /** 等你 / 出错: the one-line ask or reason; 完成: the result summary */
        val line: String = "",
        val createdAt: Long = 0L, val updatedAt: Long = 0L,
        /** the state whose notice was already posted into 聊天 ("" = none yet) */
        val told: String = "",
        /** [Mines-38.10 cloud_brain] the mines-brain task id when this task runs 「在云端」 ("" = on the phone) */
        val cloud: String = "",
    )

    /** a task the gate let start */
    fun started(t: AutoTask, now: Long): AutoTask = t.copy(st = St.RUNNING, updatedAt = now)

    /**
     * The task conversation's progress changed ([p] = TaskProgress row; [running] = its agent loop is still going).
     * QUEUED stays QUEUED until [started]; a finished run without progress (no row) counts as 完成.
     */
    fun onProgress(t: AutoTask, p: TaskProgressCore.Task?, running: Boolean, now: Long): AutoTask {
        if (t.st == St.QUEUED) return t
        // [38.10] a cloud task is driven only by the brain's events (CloudBrainCore.apply): its conversation holds ☁ rows,
        // and reading them as a phone run made 完成 「已回答：☁ 第 3 步 …」 before the brain's own result arrived
        if (t.cloud.isNotEmpty()) return t
        val steps = p?.steps.orEmpty()
        val total = steps.size; val step = steps.count { it.st == TaskProgressCore.StepSt.DONE }
        val st = when {
            p == null -> if (running) St.RUNNING else t.st
            p.st == TaskProgressCore.St.WAITING -> St.WAITING
            p.st == TaskProgressCore.St.ERROR -> if (running) St.RUNNING else St.ERROR
            p.st == TaskProgressCore.St.STOPPED -> St.ERROR
            p.st == TaskProgressCore.St.DONE -> if (running) St.RUNNING else St.DONE
            else -> St.RUNNING
        }
        val line = when (st) {
            St.WAITING -> p?.reason?.ifBlank { null } ?: "需要你回复"
            St.ERROR -> p?.reason?.ifBlank { null } ?: if (p?.st == TaskProgressCore.St.STOPPED) "已停止" else "没完成"
            St.DONE -> if (t.st == St.DONE && t.line.isNotBlank()) t.line else p?.let { TaskProgressCore.oneLine(it) }.orEmpty().ifBlank { t.line }
            else -> t.line
        }
        if (st == t.st && step == t.step && total == t.total && line == t.line) return t
        return t.copy(st = st, step = step, total = total, line = line.take(200), updatedAt = now)
    }

    /** the headless run itself ended badly (rejected / no model) before the conversation could say so */
    fun runFailed(t: AutoTask, why: String, now: Long): AutoTask = t.copy(st = St.ERROR, line = why.take(120), updatedAt = now)

    /** the run returned: its reply is the result when the progress row did not give one */
    fun runEnded(t: AutoTask, reply: String?, now: Long): AutoTask {
        if (t.st == St.WAITING || t.st == St.ERROR) return t
        val r = reply?.trim().orEmpty()
        return t.copy(st = St.DONE, line = (if (r.isNotEmpty()) summary(r) else t.line).ifBlank { "已完成" }, updatedAt = now)
    }

    /** the user opened the task and wrote in it: it is running again */
    fun resumed(t: AutoTask, now: Long): AutoTask = if (t.st == St.QUEUED) t else t.copy(st = St.RUNNING, told = "", updatedAt = now)

    /** a notice goes into 聊天 once per 等你 / 出错 / 完成 */
    fun needsNotice(t: AutoTask): Boolean = t.st in setOf(St.WAITING, St.ERROR, St.DONE) && t.told != t.st.id

    /** first 160 characters of a reply without markdown noise */
    fun summary(reply: String): String = reply.lineSequence().map { it.trim().trimStart('#', '>', '-', '*', ' ') }.filter { it.isNotEmpty() && !it.startsWith("```") }
        .joinToString(" ").replace(Regex("""\*\*|__|`"""), "").take(160)

    // ── start gate: user-waiting > scheduled > background ──

    const val AUTO_CAP = 2

    /**
     * A background task may start when the Java heap is below 88 % ([heapCritical] false), fewer than [AUTO_CAP] auto
     * tasks run, and one agent slot stays free for the user's own chat / scheduled tasks ([allRunning] of [maxAll]).
     */
    fun canStart(autoRunning: Int, allRunning: Int, maxAll: Int, heapCritical: Boolean): Boolean =
        !heapCritical && autoRunning < AUTO_CAP && allRunning < maxAll - 1

    /** oldest queued first */
    fun nextQueued(tasks: List<AutoTask>): AutoTask? = tasks.filter { it.st == St.QUEUED }.minByOrNull { it.createdAt }

    // ── chip ──

    enum class Tone { WAIT, RUN, ERR, DONE, IDLE }
    data class Chip(val title: String, val state: String, val progress: String, val tone: Tone) {
        val a11y: String get() = "已建任务 $title，$state" + if (progress.isNotEmpty()) "，第 $progress 步" else ""
    }

    fun chip(t: AutoTask): Chip = Chip(
        // [38.10] a cloud task keeps 「· 云端」 in every state, 完成 included, so the user can tell where it ran
        t.name, if (t.cloud.isNotEmpty()) t.st.label + " · 云端" else t.st.label,
        if (t.total > 0 && t.st != St.QUEUED) "${t.step.coerceAtMost(t.total)}/${t.total}" else "",
        when (t.st) { St.WAITING -> Tone.WAIT; St.RUNNING -> Tone.RUN; St.ERROR -> Tone.ERR; St.DONE -> Tone.DONE; St.QUEUED -> Tone.IDLE },
    )

    /** 任务 page groups: 等你处理 (等你 + 出错) / 进行中 (进行中 + 排队) / 已完成, newest first */
    fun groups(tasks: List<AutoTask>): Triple<List<AutoTask>, List<AutoTask>, List<AutoTask>> {
        val s = tasks.sortedByDescending { it.updatedAt.coerceAtLeast(it.createdAt) }
        return Triple(s.filter { it.st == St.WAITING || it.st == St.ERROR }, s.filter { it.st == St.RUNNING || it.st == St.QUEUED }, s.filter { it.st == St.DONE })
    }

    // ── what the main chat stores (assistant rows, no model turn) ──

    enum class Kind(val id: String) { CREATED(""), ASK("ask"), ERROR("error"), DONE("done") }
    data class Tag(val taskId: String, val kind: Kind, val body: String)

    private const val OPEN = "⟦mines_task:"
    private val TAG = Regex("""^⟦mines_task:([A-Za-z0-9_\-]+)(?::(ask|error|done))?⟧\s*""")

    fun tagText(taskId: String, kind: Kind, body: String): String =
        OPEN + taskId + (if (kind.id.isEmpty()) "" else ":" + kind.id) + "⟧ " + body.trim()

    fun parseTag(text: String?): Tag? {
        val s = text?.trimStart() ?: return null
        if (!s.startsWith(OPEN)) return null
        val m = TAG.find(s) ?: return null
        val k = when (m.groupValues[2]) { "ask" -> Kind.ASK; "error" -> Kind.ERROR; "done" -> Kind.DONE; else -> Kind.CREATED }
        return Tag(m.groupValues[1], k, s.substring(m.range.last + 1).trim())
    }

    fun createdLine(name: String): String = "已创建任务：$name。在后台运行，完成或需要你处理时会在这里告诉你。"

    fun noticeOf(t: AutoTask): Pair<Kind, String>? = when (t.st) {
        St.WAITING -> Kind.ASK to t.line.ifBlank { "需要你回复" }
        St.ERROR -> Kind.ERROR to "没完成：" + t.line.ifBlank { "出错了" }
        St.DONE -> Kind.DONE to t.line.ifBlank { "已完成" }
        else -> null
    }

    // ── JSON ──

    fun toJson(l: List<AutoTask>): String = JSONArray().apply {
        l.forEach { t ->
            put(JSONObject().put("id", t.id).put("sid", t.sid).put("n", t.name).put("g", t.goal).put("st", t.st.id).put("s", t.step)
                .put("t", t.total).put("l", t.line).put("c", t.createdAt).put("u", t.updatedAt).put("told", t.told).put("cl", t.cloud))
        }
    }.toString()

    fun fromJson(s: String?): List<AutoTask> = runCatching {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            AutoTask(o.optString("id"), o.optString("sid"), o.optString("n"), o.optString("g"), St.of(o.optString("st")),
                o.optInt("s"), o.optInt("t"), o.optString("l"), o.optLong("c"), o.optLong("u"), o.optString("told"), o.optString("cl"))
        }
    }.getOrDefault(emptyList())
}
