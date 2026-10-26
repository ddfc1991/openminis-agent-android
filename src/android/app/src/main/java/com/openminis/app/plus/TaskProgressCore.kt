package com.openminis.app.plus

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.5 i] user 2026-10-17 19:17–19:30 (eyes not good, many tasks at once): every conversation carries a
 * 「目标 + 步骤」 table and one state, so the home list can show progress without changing its layout:
 *  - icon ring arc = progress (done steps / all steps), small dot = state colour;
 *  - states: 进行中 (orange) / 等你 (yellow: waiting for the user's answer, a captcha, a code) / 出错 (red: 502, time-out,
 *    stream cut — set by the runtime, with the retry count and the reason) / 已停止 (grey) / 完成 (green);
 *  - the 「都在正常跑」 line becomes 「进行中 N · 等你 N · 出错 N · 完成 N」.
 * Pure part (state machine, error classification, texts, JSON), unit-tested in TaskProgressCoreTest.
 */
object TaskProgressCore {
    enum class St(val label: String, val id: String) {
        RUNNING("进行中", "running"), WAITING("等你", "waiting"), ERROR("出错", "error"), STOPPED("已停止", "stopped"), DONE("完成", "done");
        companion object { fun of(s: String?): St = values().firstOrNull { it.id == s } ?: RUNNING }
    }

    enum class StepSt(val id: String) {
        TODO("todo"), DOING("doing"), DONE("done"), FAILED("failed"), WAITING("waiting");
        companion object { fun of(s: String?): StepSt = values().firstOrNull { it.id == s } ?: TODO }
    }

    data class Step(val title: String, val st: StepSt = StepSt.TODO, val at: Long = 0L, val note: String = "")

    data class Task(
        val sid: String,
        val goal: String = "",
        val steps: List<Step> = emptyList(),
        val st: St = St.RUNNING,
        val reason: String = "",
        val retries: Int = 0,
        val account: String = "",
        val updatedAt: Long = 0L,
        val stateAt: Long = 0L,
        /** [Mines-38.9.6 i12] steps kept by the app itself (questions, tools, answers), not an agent plan */
        val auto: Boolean = false,
        /** "live" = built while the chat ran, "history" = rebuilt from a chat's saved messages ("" = agent / old row) */
        val origin: String = "",
        /** one line that always says something (「已回答：…」, 「共 3 轮问答 · 最近：…」) */
        val summary: String = "",
    ) {
        val doneSteps: Int get() = steps.count { it.st == StepSt.DONE }
        /** 0..1 arc length; a task without steps shows a full ring when done, a small stub otherwise */
        val fraction: Float get() = when {
            st == St.DONE -> 1f
            steps.isEmpty() -> 0.12f
            else -> (doneSteps.toFloat() / steps.size).coerceIn(0f, 1f)
        }
    }

    // ---------------- agent side (task_progress tool) ----------------

    /** The agent states the goal and the steps at the start of a task. Steps already done keep their state. */
    fun plan(prev: Task?, sid: String, goal: String, titles: List<String>, now: Long): Task {
        val clean = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12)
        val old = prev?.steps.orEmpty().associateBy { it.title }
        val steps = clean.mapIndexed { i, t -> old[t] ?: Step(t, if (i == 0) StepSt.DOING else StepSt.TODO, if (i == 0) now else 0L) }
        return (prev ?: Task(sid)).copy(goal = goal.trim().take(120), steps = steps, st = St.RUNNING, reason = "", updatedAt = now, stateAt = now,
            auto = false, origin = "live", summary = "")
    }

    /** One step changed ([index] 0-based, or matched by title). Finishing a step starts the next one. */
    fun step(t: Task, index: Int?, title: String?, st: StepSt, note: String, now: Long): Task {
        var i = index ?: -1
        if (i !in t.steps.indices && !title.isNullOrBlank()) i = t.steps.indexOfFirst { it.title == title.trim() }
        val steps = t.steps.toMutableList()
        if (i !in steps.indices) {
            if (title.isNullOrBlank()) return t
            steps += Step(title.trim(), st, now, note.take(80)); i = steps.size - 1
        } else steps[i] = steps[i].copy(st = st, at = now, note = note.take(80).ifBlank { steps[i].note })
        if (st == StepSt.DONE) steps.indexOfFirst { it.st == StepSt.TODO }.takeIf { it >= 0 }?.let { n -> steps[n] = steps[n].copy(st = StepSt.DOING, at = now) }
        val newSt = when (st) {
            StepSt.WAITING -> St.WAITING
            StepSt.FAILED -> St.ERROR
            else -> if (steps.isNotEmpty() && steps.all { it.st == StepSt.DONE }) St.DONE else St.RUNNING
        }
        val reason = when (st) { StepSt.FAILED, StepSt.WAITING -> note.take(80); else -> if (newSt == St.RUNNING) "" else t.reason }
        return t.copy(steps = steps, st = newSt, reason = reason, updatedAt = now, stateAt = if (newSt != t.st) now else t.stateAt)
    }

    // ---------------- runtime side ----------------

    fun turnStarted(t: Task?, sid: String, now: Long): Task =
        (t ?: Task(sid)).let { it.copy(st = St.RUNNING, reason = if (it.st == St.ERROR) it.reason else "", updatedAt = now, stateAt = now) }

    /**
     * The turn ended. [cancelled] = the user pressed stop. A finished turn whose steps are all done is 完成; a turn that
     * ended with steps still open hands the conversation back to the user (等你回复); a task with no steps at all is
     * 完成 once the reply is there.
     */
    fun turnEnded(t: Task?, sid: String, cancelled: Boolean, now: Long): Task {
        val b = t ?: Task(sid)
        if (b.st == St.ERROR && !cancelled) return b.copy(updatedAt = now)
        val st = when {
            cancelled -> St.STOPPED
            b.st == St.WAITING -> St.WAITING
            b.steps.isEmpty() || b.steps.all { it.st == StepSt.DONE } -> St.DONE
            else -> St.WAITING
        }
        val steps = if (st == St.DONE) b.steps.map { if (it.st == StepSt.DOING) it.copy(st = StepSt.DONE, at = now) else it } else b.steps
        val reason = when (st) { St.STOPPED -> "你停止了"; St.WAITING -> b.reason.ifBlank { "等你回复" }; else -> "" }
        return b.copy(st = st, steps = steps, reason = reason, updatedAt = now, stateAt = now)
    }

    /** An auto-retry is running (the countdown line): counts the retry, keeps 进行中, remembers the reason. */
    fun retrying(t: Task?, sid: String, raw: String, now: Long): Task {
        val b = t ?: Task(sid)
        return b.copy(st = St.RUNNING, retries = b.retries + 1, reason = errorReason(raw) ?: b.reason, updatedAt = now)
    }

    /** The turn failed for good. */
    fun failed(t: Task?, sid: String, raw: String, now: Long): Task {
        val b = t ?: Task(sid)
        val steps = b.steps.toMutableList()
        steps.indexOfFirst { it.st == StepSt.DOING }.takeIf { it >= 0 }?.let { steps[it] = steps[it].copy(st = StepSt.FAILED, at = now, note = errorReason(raw) ?: "") }
        return b.copy(st = St.ERROR, steps = steps, reason = errorReason(raw) ?: shortRaw(raw), updatedAt = now, stateAt = now)
    }

    /** A retry worked: back to 进行中, the failed step runs again. */
    fun recovered(t: Task?, sid: String, now: Long): Task {
        val b = t ?: Task(sid)
        val steps = b.steps.map { if (it.st == StepSt.FAILED) it.copy(st = StepSt.DOING, at = now) else it }
        return b.copy(st = St.RUNNING, steps = steps, reason = "", updatedAt = now, stateAt = now)
    }

    /** The agent is waiting for the user (an answer, a captcha, a code, a choice of account). */
    fun waiting(t: Task?, sid: String, why: String, now: Long): Task =
        (t ?: Task(sid)).copy(st = St.WAITING, reason = why.take(80), updatedAt = now, stateAt = now)

    private val HTTP = Regex("(?<![0-9])(5\\d\\d|429|408)(?![0-9])")

    /**
     * Runtime errors in plain, short Chinese (null = not something the runtime recognises). 502 / 503 / 504 / time-out /
     * stream cut / 429 / network.
     */
    fun errorReason(raw: String): String? {
        val t = raw.lowercase()
        val code = HTTP.find(raw)?.value
        // [Mines-38.9.9] the provider already wrote a Chinese reason + the server's own words (CompatQuirks / ConnRetry): keep it
        Regex("\\[(\\d{3})\\] (.*[\\u4e00-\\u9fff].*)").find(raw)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(160) }
        return when {
            com.openminis.app.provider.openai.ConnRetry.isDroppedText(raw) && !t.contains("stream") -> com.openminis.app.provider.openai.ConnRetry.zhText(raw)
            t.contains("software caused connection abort") || t.contains("econnaborted") -> "网络断了（手机这边断开了连接）"   // [38.9.14.1 net_drop]
            code == "502" || t.contains("bad gateway") -> "服务器暂无响应（502）"
            code == "503" || t.contains("service unavailable") || t.contains("overloaded") -> "服务器忙（503）"
            code == "504" || t.contains("gateway timeout") -> "服务器超时（504）"
            code == "429" || t.contains("rate limit") || t.contains("too many requests") -> "请求太频繁（429）"
            t.contains("timeout") || t.contains("timed out") || t.contains("超时") -> "等待超时"
            t.contains("stream") && (t.contains("closed") || t.contains("reset") || t.contains("interrupt") || t.contains("unexpected end")) ||
                t.contains("断流") || t.contains("connection reset") || t.contains("eof") -> "回复中途断了"
            t.contains("unable to resolve host") || t.contains("unknownhost") || t.contains("no address") || t.contains("network is unreachable") ||
                t.contains("failed to connect") || t.contains("没有网络") -> "网络连不上"
            code != null && code.startsWith("5") -> "服务器出错（$code）"
            else -> null
        }
    }

    private fun shortRaw(raw: String): String = raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(60)

    // ---------------- texts ----------------

    data class Summary(val running: Int, val waiting: Int, val error: Int, val done: Int) {
        val total: Int get() = running + waiting + error + done
        /** 「进行中 2 · 等你 1 · 出错 1 · 完成 2」 (zero counts are left out; all zero → "") */
        val line: String get() = listOf(St.RUNNING to running, St.WAITING to waiting, St.ERROR to error, St.DONE to done)
            .filter { it.second > 0 }.joinToString(" · ") { "${it.first.label} ${it.second}" }
    }

    /** Counts for the home line: tasks touched in the last [windowMs] (stopped ones are not counted). */
    fun summary(tasks: Collection<Task>, now: Long, windowMs: Long = 24 * 3_600_000L): Summary {
        val live = tasks.filter { now - it.updatedAt <= windowMs }
        return Summary(live.count { it.st == St.RUNNING }, live.count { it.st == St.WAITING }, live.count { it.st == St.ERROR }, live.count { it.st == St.DONE })
    }

    private fun hm(at: Long): String = if (at <= 0L) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))

    /** 「— 已停止 · 你停止了 · 19:12 —」 under the last message when the agent stopped / failed; null otherwise. */
    fun divider(t: Task?): String? {
        t ?: return null
        if (t.st != St.STOPPED && t.st != St.ERROR) return null
        return "— " + listOf(t.st.label, t.reason, hm(t.stateAt)).filter { it.isNotBlank() }.joinToString(" · ") + " —"
    }

    /** One line above the chat input while the agent is not working (null = nothing to say). */
    fun inputStatus(t: Task?): String? {
        t ?: return null
        return when (t.st) {
            St.STOPPED -> "已停止" + (t.reason.takeIf { it.isNotBlank() && it != "你停止了" }?.let { "：$it" } ?: "") + " · 发消息可以接着做"
            St.ERROR -> "出错了：${t.reason.ifBlank { "原因不明" }}" + (if (t.retries > 0) " · 已自动重试 ${t.retries} 次" else "") + " · 可以点重试"
            St.WAITING -> "在等你：${t.reason.ifBlank { "回复" }}"
            else -> null
        }
    }

    /** TalkBack text for a home row's ring + dot. */
    fun spoken(t: Task?): String {
        t ?: return ""
        val prog = if (t.steps.isNotEmpty()) "，${t.doneSteps}/${t.steps.size} 步" else ""
        val why = if ((t.st == St.ERROR || t.st == St.WAITING) && t.reason.isNotBlank()) "，${t.reason}" else ""
        return t.st.label + prog + why
    }

    /** 「已自动重试 2 次」 line for the card. */
    fun retryLine(t: Task): String = if (t.retries > 0) "已自动重试 ${t.retries} 次" else ""

    // ---------------- [Mines-38.9.6 i12] automatic steps for EVERY conversation ----------------
    // User 2026-10-18: 「还没有步骤表（这轮只是一问一答）」 is useless — every chat (new, running, idle, finished, old) shows a
    // goal + short steps + current step. The app keeps them itself from what happens in the chat (each question, each tool
    // the agent runs, each answer); an agent plan (task_progress) replaces them. Old chats get the same table rebuilt from
    // their saved messages the first time it is needed (then cached in the progress store).

    const val MAX_AUTO_STEPS = 8
    const val MAX_TOOLS_PER_TURN = 4
    const val Q = "问："
    const val A = "答："
    private const val MORE = "又做了 "

    private val REMINDER = Regex("<system-reminder>[\\s\\S]*?</system-reminder>", RegexOption.IGNORE_CASE)
    private val MD = Regex("[*_`#>|~]+|!?\\[([^\\]]*)]\\([^)]*\\)")
    private val WS = Regex("\\s+")

    /** One short readable line: no reminders, markdown, links or line breaks; [n] chars + 「…」. */
    fun clip(raw: String?, n: Int): String {
        if (raw.isNullOrBlank()) return ""
        var t = REMINDER.replace(raw, " ")
        t = MD.replace(t) { m -> m.groupValues.getOrNull(1).orEmpty() }
        t = WS.replace(t, " ").trim().trimStart('-', '•', '·', ' ')
        return if (t.length <= n) t else t.take(n).trimEnd() + "…"
    }

    private fun isQ(s: Step) = s.title.startsWith(Q)
    private fun isA(s: Step) = s.title.startsWith(A)

    /** The user sent a message (a turn starts). Agent plans are left alone; otherwise a 「问：…」 step is added. */
    fun autoTurnStart(prev: Task?, sid: String, userText: String?, now: Long): Task {
        val base = turnStarted(prev, sid, now)
        if (prev != null && !prev.auto && prev.steps.isNotEmpty()) return base
        val q = clip(userText, 26)
        val closed = base.steps.map { if (it.st == StepSt.DOING || it.st == StepSt.WAITING) it.copy(st = StepSt.DONE) else it }
        val steps = (closed + listOfNotNull(q.takeIf { it.isNotBlank() }?.let { Step(Q + it, StepSt.DOING, now) })).takeLast(MAX_AUTO_STEPS)
        val goal = base.goal.ifBlank { clip(userText, 40) }
        return base.copy(goal = goal, steps = steps, auto = true, origin = "live",
            summary = if (q.isNotBlank()) "正在处理：$q" else "正在处理")
    }

    /** The agent starts a tool ([title] = the tool's short title). At most [MAX_TOOLS_PER_TURN] tool steps per turn. */
    fun autoToolStart(t: Task?, sid: String, title: String?, now: Long): Task {
        val b = t ?: Task(sid, auto = true, origin = "live")
        if (!b.auto) return b
        val name = clip(title, 22).ifBlank { "处理中" }
        val steps = b.steps.toMutableList()
        val last = steps.lastOrNull()
        if (last != null && last.title == name && last.st == StepSt.DOING) return b
        val qi = steps.indexOfLast { isQ(it) }
        val tools = steps.size - qi - 1
        if (tools >= MAX_TOOLS_PER_TURN && last != null && !isQ(last)) {
            val n = (if (last.title.startsWith(MORE)) last.title.removePrefix(MORE).substringBefore(" ").toIntOrNull() ?: 1 else 0) + 1
            if (last.title.startsWith(MORE)) steps[steps.size - 1] = last.copy(title = "$MORE$n 步", st = StepSt.DOING, at = now, note = name)
            else steps += Step("${MORE}1 步", StepSt.DOING, now, name)
        } else steps += Step(name, StepSt.DOING, now)
        return b.copy(steps = trimAuto(steps), updatedAt = now, summary = "正在：$name")
    }

    /** A tool finished. A failed tool marks only its step (the agent often recovers); the conversation stays 进行中. */
    fun autoToolEnd(t: Task?, sid: String, title: String?, ok: Boolean, now: Long): Task {
        val b = t ?: return Task(sid, auto = true, origin = "live")
        if (!b.auto) return b
        val name = clip(title, 22).ifBlank { "处理中" }
        val steps = b.steps.toMutableList()
        var i = steps.indexOfLast { it.st == StepSt.DOING && (it.title == name || it.note == name) }
        if (i < 0) i = steps.indexOfLast { it.st == StepSt.DOING && !isQ(it) }
        if (i < 0) return b.copy(updatedAt = now)
        val s = steps[i]
        steps[i] = if (s.title.startsWith(MORE)) s.copy(st = if (ok) StepSt.DONE else s.st, at = now)
            else s.copy(st = if (ok) StepSt.DONE else StepSt.FAILED, at = now, note = if (ok) s.note else "没成功")
        return b.copy(steps = steps, updatedAt = now)
    }

    /** The turn ended: open steps close, the answer becomes a 「答：…」 step, the summary says how it ended. */
    fun autoTurnEnd(t: Task?, sid: String, cancelled: Boolean, answer: String?, now: Long): Task {
        val b = t ?: Task(sid, auto = true, origin = "live")
        if (!b.auto) return turnEnded(b, sid, cancelled, now)
        val a = clip(answer, 26)
        val tools = b.steps.size - b.steps.indexOfLast { isQ(it) } - 1
        val st = when {
            b.st == St.ERROR && !cancelled -> St.ERROR
            cancelled -> St.STOPPED
            b.st == St.WAITING -> St.WAITING
            else -> St.DONE
        }
        var steps = b.steps.map { s ->
            when {
                st == St.STOPPED && s.st == StepSt.DOING -> s
                s.st == StepSt.DOING -> s.copy(st = StepSt.DONE, at = now)
                else -> s
            }
        }
        if (a.isNotBlank() && st != St.ERROR) steps = steps + Step(A + a, StepSt.DONE, now)
        val toolNote = if (tools > 0) " · 用了 $tools 步工具" else ""
        val summary = when (st) {
            St.DONE -> if (a.isNotBlank()) "已回答：$a$toolNote" else "这轮做完了$toolNote"
            St.STOPPED -> "你停止了这一轮$toolNote"
            St.WAITING -> "在等你：${b.reason.ifBlank { "回复" }}"
            St.ERROR -> "出错了：${b.reason.ifBlank { "原因不明" }}"
            St.RUNNING -> b.summary
        }
        val reason = when (st) { St.STOPPED -> "你停止了"; St.WAITING -> b.reason.ifBlank { "等你回复" }; St.ERROR -> b.reason; else -> "" }
        return b.copy(st = st, steps = trimAuto(steps), reason = reason, summary = summary, updatedAt = now, stateAt = now)
    }

    /** keep the newest steps; the first 「问：」 of the window always stays readable */
    private fun trimAuto(steps: List<Step>): List<Step> = if (steps.size <= MAX_AUTO_STEPS) steps else steps.takeLast(MAX_AUTO_STEPS)

    /** One saved message, reduced to what the table needs. */
    data class HMsg(val role: String, val text: String, val tools: List<String> = emptyList(), val at: Long = 0L)

    /** Parse a stored parts_json (text parts + toolUse titles). */
    fun parseParts(role: String, partsJson: String?, at: Long): HMsg {
        val texts = ArrayList<String>(); val tools = ArrayList<String>()
        runCatching {
            val arr = JSONArray(partsJson ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                when (o.optString("type")) {
                    "text" -> o.optString("value").takeIf { it.isNotBlank() }?.let { texts += it }
                    "toolUse" -> o.optJSONObject("value")?.let { v ->
                        val title = runCatching { JSONObject(v.optString("input")).optString("tool_title") }.getOrDefault("")
                        tools += title.ifBlank { v.optString("name", "工具") }
                    }
                }
            }
        }
        return HMsg(role, texts.joinToString("\n"), tools, at)
    }

    /** An existing chat's table, rebuilt from its saved messages (old / idle chats). */
    fun fromHistory(sid: String, msgs: List<HMsg>, lastAt: Long, title: String? = null): Task {
        val real = msgs.filter { (it.role == "user" || it.role == "assistant") && (it.text.isNotBlank() || it.tools.isNotEmpty()) }
        val users = real.filter { it.role == "user" && clip(it.text, 26).isNotBlank() }
        val goal = clip(title, 40).ifBlank { clip(users.firstOrNull()?.text, 40) }.ifBlank { "这个对话" }
        val steps = ArrayList<Step>()
        var tools = 0
        var lastAnswer = ""
        real.forEach { m ->
            if (m.role == "user") {
                val q = clip(m.text, 26)
                if (q.isNotBlank()) { tools = 0; steps += Step(Q + q, StepSt.DONE, m.at) }
            } else {
                tools += m.tools.size
                val qi = steps.indexOfLast { isQ(it) }
                if (m.tools.isNotEmpty() && qi >= 0) steps[qi] = steps[qi].copy(note = "用了 $tools 步工具")
                val a = clip(m.text, 26)
                if (a.isNotBlank()) { lastAnswer = a; if (steps.lastOrNull()?.let { isA(it) } == true) steps[steps.size - 1] = Step(A + a, StepSt.DONE, m.at) else steps += Step(A + a, StepSt.DONE, m.at) }
            }
        }
        val lastIsUser = real.lastOrNull()?.role == "user"
        val st = if (lastIsUser) St.STOPPED else St.DONE
        val rounds = users.size
        val summary = when {
            real.isEmpty() -> "还没有消息"
            lastIsUser -> "最后一条没有回复 · 发消息可以接着做"
            rounds <= 1 -> if (lastAnswer.isNotBlank()) "已回答：$lastAnswer" else "已回答"
            else -> "共 $rounds 轮问答 · 最近：" + clip(users.last().text, 18)
        }
        val trimmed = if (steps.size > MAX_AUTO_STEPS) steps.takeLast(MAX_AUTO_STEPS) else steps
        return Task(sid, goal = goal, steps = trimmed, st = st, reason = if (lastIsUser) "没有回复完" else "", updatedAt = lastAt, stateAt = lastAt,
            auto = true, origin = "history", summary = summary)
    }

    /** The card's one line — never empty, never 「没有步骤表」. */
    fun oneLine(t: Task): String = t.summary.ifBlank {
        when {
            t.steps.isNotEmpty() -> "${t.st.label} · ${t.doneSteps}/${t.steps.size} 步" + (t.steps.firstOrNull { it.st == StepSt.DOING }?.let { " · 现在：${it.title}" } ?: "")
            t.goal.isNotBlank() -> "${t.st.label}：${t.goal}"
            else -> t.st.label
        }
    }

    /** the step that is happening now (or the last one), for the 「现在」 line */
    fun current(t: Task): Step? = t.steps.lastOrNull { it.st == StepSt.DOING || it.st == StepSt.WAITING || it.st == StepSt.FAILED } ?: t.steps.lastOrNull()

    // ---------------- JSON ----------------

    fun toJson(t: Task): String = JSONObject()
        .put("sid", t.sid).put("goal", t.goal).put("st", t.st.id).put("reason", t.reason).put("retries", t.retries)
        .put("account", t.account).put("updatedAt", t.updatedAt).put("stateAt", t.stateAt)
        .put("auto", t.auto).put("origin", t.origin).put("summary", t.summary)
        .put("steps", JSONArray(t.steps.map { JSONObject().put("t", it.title).put("s", it.st.id).put("at", it.at).put("n", it.note) }))
        .toString()

    fun fromJson(s: String): Task? = runCatching {
        val o = JSONObject(s)
        val a = o.optJSONArray("steps") ?: JSONArray()
        Task(
            sid = o.getString("sid"), goal = o.optString("goal"), st = St.of(o.optString("st")), reason = o.optString("reason"),
            retries = o.optInt("retries"), account = o.optString("account"), updatedAt = o.optLong("updatedAt"), stateAt = o.optLong("stateAt"),
            auto = o.optBoolean("auto", false), origin = o.optString("origin"), summary = o.optString("summary"),
            steps = (0 until a.length()).map { i -> a.getJSONObject(i).let { Step(it.optString("t"), StepSt.of(it.optString("s")), it.optLong("at"), it.optString("n")) } },
        )
    }.getOrNull()
}
