package com.openminis.app.plus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.plus.TaskProgressCore.St
import com.openminis.app.plus.TaskProgressCore.StepSt
import com.openminis.app.plus.TaskProgressCore.Task
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.5 i] The conversation progress store (local SQLite `mines_progress.db`, one row per conversation) + the
 * agent's `task_progress` tool + the runtime hooks (ChatViewModel: turn start / end / auto-retry / final error / stop).
 * The 「⋯」 card's 重试 / 停止 call back into the conversation's ViewModel through [actions]. See [TaskProgressCore].
 */
object TaskProgress {
    const val TOOL_NAME = "task_progress"
    private val _tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    val tasks: StateFlow<Map<String, Task>> = _tasks.asStateFlow()
    @Volatile private var db: Helper? = null

    /** What the card's buttons do, registered by the live ViewModel of each conversation. */
    class Actions(val retry: () -> Unit, val stop: () -> Unit)
    private val actions = ConcurrentHashMap<String, Actions>()
    fun register(sid: String, a: Actions) { if (sid.isNotBlank()) actions[sid] = a }
    fun unregister(sid: String, a: Actions) { actions.remove(sid, a) }
    fun canRetry(sid: String): Boolean = actions.containsKey(sid)
    fun retry(sid: String): Boolean = actions[sid]?.let { runCatching { it.retry() }.isSuccess } ?: false

    /**
     * [Mines-38.9.6 i14] user 「这个重试键不行的，点了不能继续重试」 (1000130013): 重试 is never disabled. With the conversation's
     * ViewModel alive it stops the running turn (if any) and starts it again, as often as tapped; without one (the chat
     * was not opened since Mines started) the chat opens and retries there ([pendingRetry]). Returns the restart count, or
     * 0 when the chat has to be opened first.
     */
    fun retryNow(sid: String): Int {
        if (sid.isBlank()) return 0
        if (!retry(sid)) { pendingRetry = sid; return 0 }
        return manualRetries.merge(sid, 1, Int::plus) ?: 1
    }
    /** a chat whose ViewModel should retry as soon as it registers (opened from the card) */
    @Volatile var pendingRetry: String? = null
    fun consumePendingRetry(sid: String): Boolean = (pendingRetry == sid && sid.isNotBlank()).also { if (it) pendingRetry = null }
    /** manual restarts per chat since Mines started (card line 「已重新开始（第 N 次）」, smoke) */
    val manualRetries = ConcurrentHashMap<String, Int>()
    /** the ViewModel restarted the turn by hand: 进行中 now, the old reason cleared */
    fun onManualRetry(sid: String) {
        val t = get(sid) ?: return
        put(t.copy(st = St.RUNNING, reason = "", stateAt = now(), updatedAt = now()))
    }
    fun stop(sid: String): Boolean {
        val ok = actions[sid]?.let { runCatching { it.stop() }.isSuccess } ?: false
        if (!ok) put(TaskProgressCore.turnEnded(get(sid), sid, cancelled = true, now = now()))
        return true
    }

    private class Helper(c: Context) : SQLiteOpenHelper(c, "mines_progress.db", null, 1) {
        override fun onCreate(d: SQLiteDatabase) { d.execSQL("CREATE TABLE IF NOT EXISTS task (sid TEXT PRIMARY KEY, json TEXT NOT NULL, updated INTEGER NOT NULL)") }
        override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
    }

    fun init(c: Context) {
        if (db != null) return
        synchronized(this) {
            if (db != null) return
            val h = Helper(c.applicationContext); db = h
            runCatching {
                val m = HashMap<String, Task>()
                // [Mines-38.9.6] keep 120 days (rows rebuilt from history carry the chat's own last-message time)
                h.writableDatabase.delete("task", "updated < ?", arrayOf((now() - 120 * 24 * 3_600_000L).toString()))
                h.readableDatabase.rawQuery("SELECT json FROM task", null).use { cur -> while (cur.moveToNext()) TaskProgressCore.fromJson(cur.getString(0))?.let { m[it.sid] = it } }
                // a conversation that was 进行中 when Mines was killed is not running any more
                m.replaceAll { _, t -> if (t.st == St.RUNNING) t.copy(st = St.STOPPED, reason = "Mines 被关掉了", stateAt = t.updatedAt) else t }
                _tasks.value = m
            }
        }
    }

    private fun now() = System.currentTimeMillis()
    fun get(sid: String): Task? = _tasks.value[sid]

    private fun put(t: Task) {
        if (t.sid.isBlank()) return
        _tasks.value = _tasks.value + (t.sid to t)
        val h = db ?: return
        runCatching {
            h.writableDatabase.insertWithOnConflict("task", null, ContentValues().apply {
                put("sid", t.sid); put("json", TaskProgressCore.toJson(t)); put("updated", t.updatedAt)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** [Mines-38.9.5 i11] a new chat's first turn starts before it has its real id: carry that row over to the real id. */
    fun move(from: String, to: String) {
        if (from.isBlank() || to.isBlank() || from == to) return
        val old = _tasks.value[from] ?: return
        if (_tasks.value[to] == null) put(old.copy(sid = to))
        forget(from)
        actions.remove(from)
    }

    /** [Mines-38.9.5 i11] ids of the conversations the home list shows (null = not known yet): the summary counts only those. */
    private val _visible = MutableStateFlow<Set<String>?>(null)
    val visible: StateFlow<Set<String>?> = _visible.asStateFlow()
    fun setVisible(ids: Set<String>) { if (_visible.value != ids) _visible.value = ids }

    fun forget(sid: String) {
        _tasks.value = _tasks.value - sid
        runCatching { db?.writableDatabase?.delete("task", "sid = ?", arrayOf(sid)) }
    }

    // ---------------- runtime hooks (ChatViewModel) ----------------

    fun onTurnStart(sid: String, userText: String? = null) { if (sid.isNotBlank()) put(TaskProgressCore.autoTurnStart(get(sid), sid, userText, now())) }
    fun onTurnEnd(sid: String, cancelled: Boolean, answer: String? = null) { if (sid.isNotBlank()) put(TaskProgressCore.autoTurnEnd(get(sid), sid, cancelled, answer, now())) }
    /** [Mines-38.9.6 i12] every tool the agent runs becomes a short step (auto tables only). */
    fun onToolStart(sid: String, title: String?) { if (sid.isNotBlank()) put(TaskProgressCore.autoToolStart(get(sid), sid, title, now())) }
    fun onToolEnd(sid: String, title: String?, ok: Boolean) { if (sid.isNotBlank()) get(sid)?.let { put(TaskProgressCore.autoToolEnd(it, sid, title, ok, now())) } }

    /** true when this row has nothing to show yet (old Q&A rows of 38.9.5, or no row at all) */
    fun needsHistory(t: Task?): Boolean = t == null || (t.steps.isEmpty() && t.summary.isBlank())

    /**
     * [Mines-38.9.6 i12] An existing chat's table rebuilt from its saved messages (once; then cached here). A live state
     * that is already recorded (出错 / 等你 / 已停止 + reason, retries, account) is kept; goal / steps / summary come from the history.
     */
    suspend fun ensure(c: Context, sid: String, title: String?) {
        if (sid.isBlank()) return
        init(c)
        val cur = get(sid)
        if (!needsHistory(cur)) return
        val built = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val rows = com.openminis.app.data.db.AppDatabase.getInstance(c.applicationContext).chatDao().loadMessages(sid)
                val msgs = rows.map { TaskProgressCore.parseParts(it.role, it.partsJson, it.createdAt) }
                TaskProgressCore.fromHistory(sid, msgs, rows.maxOfOrNull { it.updatedAt ?: it.createdAt } ?: now(), title)
            }.getOrNull()
        } ?: return
        if (!needsHistory(get(sid))) return   // a live turn wrote it meanwhile
        val keep = cur?.takeIf { it.st == St.ERROR || it.st == St.WAITING || it.st == St.RUNNING }
        put(if (keep == null) built else built.copy(st = keep.st, reason = keep.reason, retries = keep.retries, account = keep.account,
            updatedAt = maxOf(built.updatedAt, keep.updatedAt), stateAt = keep.stateAt, summary = TaskProgressCore.oneLine(keep.copy(summary = ""))))
    }

    /** Background pass at home: every listed chat without a table gets one from its history (newest first, capped). */
    suspend fun backfill(c: Context, chats: List<Pair<String, String?>>, max: Int = 80) {
        init(c)
        chats.asSequence().filter { needsHistory(get(it.first)) }.take(max).forEach { (id, title) ->
            ensure(c, id, title)
            kotlinx.coroutines.yield()
        }
    }
    fun onRetrying(sid: String, raw: String) { if (sid.isNotBlank()) put(TaskProgressCore.retrying(get(sid), sid, raw, now())) }
    fun onFailed(sid: String, raw: String) { if (sid.isNotBlank()) put(TaskProgressCore.failed(get(sid), sid, raw, now())) }
    fun onRecovered(sid: String) { val t = get(sid) ?: return; if (t.st == St.ERROR) put(TaskProgressCore.recovered(t, sid, now())) }
    fun onWaiting(sid: String, why: String) { if (sid.isNotBlank()) put(TaskProgressCore.waiting(get(sid), sid, why, now())) }
    fun usedAccount(sid: String, account: String) { val t = get(sid) ?: Task(sid); put(t.copy(account = account.take(60), updatedAt = now())) }

    // ---------------- the agent's tool ----------------

    fun definition() = AgentToolDefinition(
        name = TOOL_NAME,
        description = "Keep this conversation's progress card up to date (the user has poor eyesight and many tasks at once; " +
            "the home list shows a progress ring per conversation). At the START of any task with more than one step call " +
            "action=plan with the goal and 2-8 short steps (Chinese, same language as the user). Then call action=step when a step " +
            "is done (state=done), fails (state=failed, note = short reason), or needs the user (state=waiting, note = what you need: " +
            "an answer, a captcha, a code). Do not call it for one-line answers.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "plan | step", enumValues = listOf("plan", "step")),
            "goal" to AgentToolParam("string", "plan: the goal in one short line"),
            "steps" to AgentToolParam("string", "plan: the steps, one per line"),
            "index" to AgentToolParam("integer", "step: 1-based step number"),
            "title" to AgentToolParam("string", "step: the step title (when not using index, or to add a step)"),
            "state" to AgentToolParam("string", "step: doing | done | failed | waiting", enumValues = listOf("doing", "done", "failed", "waiting")),
            "note" to AgentToolParam("string", "step: short reason / what you need from the user"),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "goal", "steps", "index", "title", "state", "note"),
    )

    fun runTool(sid: String, argsJson: String): ToolExecutionResult = runCatching {
        val a = JSONObject(argsJson)
        if (sid.isBlank()) return@runCatching ToolExecutionResult("No conversation id; progress not recorded.", false)
        val t = when (a.optString("action")) {
            "plan" -> TaskProgressCore.plan(get(sid), sid, a.optString("goal"), a.optString("steps").split('\n', '|', '；').map { it.trim().trimStart('-', '•', '·', ' ').replace(Regex("^\\d+[.、)]\\s*"), "") }, now())
            "step" -> {
                val idx = if (a.has("index")) a.optInt("index", 0) - 1 else null
                val st = StepSt.of(a.optString("state", "done"))
                TaskProgressCore.step(get(sid) ?: Task(sid), idx, a.optString("title").ifBlank { null }, st, a.optString("note"), now())
            }
            else -> return@runCatching ToolExecutionResult("action must be plan or step", false)
        }
        put(t)
        ToolExecutionResult("ok: ${t.st.label} ${t.doneSteps}/${t.steps.size}", true)
    }.getOrElse { ToolExecutionResult("Error: ${it.message}", false) }

    /** Short prompt line (always on): asks for the plan at the start of multi-step work. */
    const val PROMPT = "Progress card: for any task with more than one step, first call task_progress(action=plan, goal, steps), then mark steps done / failed / waiting as you go (waiting = you need the user's answer, a captcha or a code)."

    // ---------------- smoke hooks ----------------

    /** 'progress seed': one conversation in every state (for screenshots); 'progress state' = JSON of all rows. */
    fun smoke(c: Context, arg: String, sids: List<String>): String {
        init(c)
        if (arg.trim() == "seed") {
            val n = now()
            // [Mines-38.9.5 i11] most telling states first: with fewer chats than states the newest gets 出错, then 等你…
            val states = listOf(
                Triple("检查各账号登录状态，失效时提醒", listOf("列出所有账号", "测试 API key", "检测网页登录", "汇总结果"), St.ERROR),
                Triple("领取：轻松获取免费云浏览器", listOf("打开注册页", "填邮箱", "等验证码", "登录控制台"), St.WAITING),
                Triple("relay 部署", listOf("连服务器", "装依赖", "启动服务"), St.STOPPED),
                Triple("领取：值得关注", listOf("打开页面", "建桌面", "核对额度"), St.RUNNING),
                Triple("查看推特分享链接", listOf("打开链接", "读内容", "确认代理恢复"), St.DONE),
            )
            sids.take(states.size).forEachIndexed { i, sid ->
                val (goal, steps, st) = states[i]
                var t = TaskProgressCore.plan(null, sid, goal, steps, n - 600_000L)
                when (st) {
                    St.DONE -> steps.forEach { s -> t = TaskProgressCore.step(t, null, s, StepSt.DONE, "", n - 300_000L) }
                    St.WAITING -> { t = TaskProgressCore.step(t, 0, null, StepSt.DONE, "", n - 400_000L); t = TaskProgressCore.step(t, 1, null, StepSt.DONE, "", n - 300_000L)
                        t = TaskProgressCore.step(t, 2, null, StepSt.WAITING, "等你点一下邮件里的验证码", n - 200_000L) }
                    St.RUNNING -> t = TaskProgressCore.step(t, 0, null, StepSt.DONE, "", n - 200_000L)
                    St.ERROR -> { t = TaskProgressCore.step(t, 0, null, StepSt.DONE, "", n - 500_000L); t = TaskProgressCore.step(t, 1, null, StepSt.DONE, "", n - 400_000L)
                        t = TaskProgressCore.retrying(t, sid, "HTTP 502 Bad Gateway", n - 300_000L); t = TaskProgressCore.retrying(t, sid, "HTTP 502 Bad Gateway", n - 250_000L)
                        t = TaskProgressCore.failed(t, sid, "HTTP 502 Bad Gateway", n - 120_000L).copy(account = "阿里云 · demo_user") }
                    St.STOPPED -> { t = TaskProgressCore.step(t, 0, null, StepSt.DONE, "", n - 500_000L); t = TaskProgressCore.turnEnded(t, sid, true, n - 100_000L) }
                }
                put(t)
            }
        }
        return org.json.JSONArray(_tasks.value.values.map { JSONObject(TaskProgressCore.toJson(it)) }).toString()
    }
}
