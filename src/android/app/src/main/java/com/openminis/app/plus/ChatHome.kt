package com.openminis.app.plus

import android.content.Context
import com.openminis.app.plus.ChatHomeCore.AutoTask
import com.openminis.app.plus.ChatHomeCore.St
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [Mines-38.9.14 chat_home] Android side of [ChatHomeCore]: flag (设置 → 实验功能 「聊天主页」, default on), the one main
 * conversation, the router hook in ChatViewModel.sendMessage, background task runs (HeadlessChatRunner, own conversation
 * each, start gate = heap < 88 % + 2 auto tasks + one agent slot kept for the user), progress from [TaskProgress], and
 * the notices written into 聊天 (assistant rows, no model turn) + a system notification when a task waits / fails / ends.
 */
object ChatHome {
    private const val FILE = "mines_chat_home"
    private const val CHANNEL = "mines_auto_tasks"
    @Volatile private var app: Context? = null
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on.asStateFlow()
    private val _tasks = MutableStateFlow<List<AutoTask>>(emptyList())
    val tasks: StateFlow<List<AutoTask>> = _tasks.asStateFlow()
    /** the message being routed right now (聊天 shows 「正在安排…」) */
    private val _routing = MutableStateFlow<String?>(null)
    val routing: StateFlow<String?> = _routing.asStateFlow()
    @Volatile var mainSid: String = ""
        private set
    /** [38.9.14.3] each task's record (state changes with time + line), kept beside the tasks */
    private val _logs = MutableStateFlow<Map<String, List<TaskRecordCore.Ev>>>(emptyMap())
    val logs: StateFlow<Map<String, List<TaskRecordCore.Ev>>> = _logs.asStateFlow()
    /** the last state each task was recorded at (diffed on every save) */
    private val seen = HashMap<String, AutoTask>()
    /** [38.9.14.3] task id → its own conversation (「打开」) + when that conversation last got the record */
    private val talks = HashMap<String, Pair<String, Long>>()
    /** launch opened 聊天 once in this process (back / 历史 then show the old list without bouncing) */
    @Volatile var redirected = false
    /** [38.9.14.2] a crash / hang breaker tripped on this launch: 聊天主页 安全模式 (queued tasks wait [SAFE_DELAY_MS]) */
    @Volatile var safeLaunch = false
    private const val SAFE_DELAY_MS = 20_000L
    @Volatile private var initAt = 0L
    /** last router decision (smoke / 自检) */
    @Volatile var lastDecision: ChatHomeCore.Decision? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inRunner: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lock = Mutex()
    private val pumpLock = Mutex()
    private val retryPending = AtomicBoolean(false)

    val loaded: Boolean get() = app != null
    fun enabled(): Boolean = loaded && _on.value
    fun isMain(sid: String?): Boolean = enabled() && !sid.isNullOrBlank() && sid == mainSid
    fun taskOf(sid: String?): AutoTask? = if (sid.isNullOrBlank()) null else _tasks.value.firstOrNull { it.sid == sid }
    fun task(id: String): AutoTask? = _tasks.value.firstOrNull { it.id == id }

    fun init(c: Context) {
        if (app != null) return
        synchronized(this) {
            if (app != null) return
            val p = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            _on.value = p.getBoolean("on", true)
            mainSid = p.getString("main", "").orEmpty()
            // a run cut by a restart is queued again (its conversation keeps what it did)
            _tasks.value = ChatHomeCore.fromJson(p.getString("tasks", "[]")).map { if (it.st == St.RUNNING) it.copy(st = St.QUEUED) else it }
            _logs.value = TaskRecordCore.logOf(p.getString("logs", "{}"))
            _tasks.value.forEach { seen[it.id] = it }
            runCatching { val o = JSONObject(p.getString("talks", "{}") ?: "{}"); o.keys().forEach { k -> o.optJSONObject(k)?.let { talks[k] = it.optString("s") to it.optLong("t") } } }
            app = c.applicationContext
            initAt = android.os.SystemClock.uptimeMillis()
        }
        observe(); pump()
    }

    private fun prefs() = app?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun save() {
        recordChanges()
        prefs()?.edit()?.putString("tasks", ChatHomeCore.toJson(_tasks.value.takeLast(200)))?.putString("logs", TaskRecordCore.logJson(_logs.value))?.apply()
    }

    /** [38.9.14.3] every state change becomes a record line (建立 / 开始 / 等你 / 出错 / 重新开始 / 完成) */
    private fun recordChanges() = synchronized(seen) {
        val now = System.currentTimeMillis()
        val keep = _tasks.value.takeLast(200)
        var m = _logs.value
        for (t in keep) {
            val e = TaskRecordCore.diff(seen[t.id], t, now)
            if (e != null) m = m + (t.id to TaskRecordCore.append(m[t.id].orEmpty(), e))
            seen[t.id] = t
        }
        val ids = keep.map { it.id }.toSet()
        _logs.value = m.filterKeys { it in ids }
    }

    private fun saveTalks() {
        val o = JSONObject(); synchronized(talks) { talks.forEach { (k, v) -> o.put(k, JSONObject().put("s", v.first).put("t", v.second)) } }
        prefs()?.edit()?.putString("talks", o.toString())?.apply()
    }

    private fun fmt(at: Long): String = if (at <= 0) "—" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))

    /**
     * [38.9.14.3] the task's own NEW conversation (user 2026-10-26 06:38 「我点进去它应该是以这个对话开的新的对话页」): made once,
     * row 1 = the message the task came from, row 2 = its full record (state, result / error, steps, retries, timeline).
     * Opened again later it gets one 「任务更新」 row with what happened since. Null when the task or a model is missing.
     */
    suspend fun openTalk(c: Context, id: String, foldRetries: Int = 0): String? = withContext(Dispatchers.IO) {
        init(c)
        val t = task(id) ?: return@withContext null
        val a = c.applicationContext as? com.openminis.app.MinisApp ?: return@withContext null
        val now = System.currentTimeMillis()
        val log = _logs.value[id].orEmpty()
        val ex = synchronized(talks) { talks[id] }
        if (ex != null && runCatching { a.chatRepository.getSession(ex.first) }.getOrNull() != null) {
            TaskRecordCore.update(log, ex.second, ::fmt)?.let { appendTo(ex.first, listOf("assistant" to it)) }
            synchronized(talks) { talks[id] = ex.first to now }; saveTalks()
            return@withContext ex.first
        }
        val main = runCatching { a.chatRepository.getSession(mainSid) }.getOrNull()
        val run = if (t.sid.isNotBlank()) runCatching { a.chatRepository.getSession(t.sid) }.getOrNull() else null
        val model = main?.modelId ?: run?.modelId
            ?: a.providerRepository.allVisibleEntries().firstOrNull { ErrZhCore.chatCapable(it.baseModel.id) }?.baseModel?.id ?: "unknown"
        val s = a.chatRepository.createSession(modelId = model, title = t.name + " · 任务对话")
        runCatching { a.chatRepository.dao.updateSource(s.id, "tasktalk") }
        val bind = main?.modelBinding
        if (main != null && !bind.isNullOrBlank()) runCatching { a.chatRepository.updateSessionBinding(s.id, bind, main.modelId) }
        else runCatching { com.openminis.app.debug.HeadlessChatRunner.applyModelOverride(c, s.id, null, null) }
        val steps = TaskProgress.get(t.sid)?.steps.orEmpty().map { it.title to (it.st == TaskProgressCore.StepSt.DONE) }
        appendTo(s.id, TaskRecordCore.seed(t, log, steps, TaskRecordCore.retries(log, foldRetries), ::fmt), reload = false)
        synchronized(talks) { talks[id] = s.id to now }; saveTalks()
        s.id
    }

    fun openTalkAsync(c: Context, id: String, foldRetries: Int = 0, then: (String) -> Unit) {
        scope.launch {
            val sid = runCatching { openTalk(c, id, foldRetries) }.getOrNull()
            if (sid != null) withContext(Dispatchers.Main) { then(sid) }
        }
    }

    /** smoke / 自检: the task's own conversation, if made */
    fun talkOf(id: String): String? = synchronized(talks) { talks[id]?.first }
    fun setOn(c: Context, v: Boolean) { init(c); _on.value = v; prefs()?.edit()?.putBoolean("on", v)?.apply() }

    /** [38.9.14.1] smoke only: put a finished task in the list (its notices are written by the caller) */
    fun seedForTest(t: AutoTask) { _tasks.value = _tasks.value.filter { it.id != t.id } + t; save() }
    /** [38.9.14.3] smoke only: give a seeded task a record (the 出错 → 等你 → 出错 → 完成 of the user's screenshot) */
    fun seedLogForTest(id: String, evs: List<TaskRecordCore.Ev>) { _logs.value = _logs.value + (id to evs); save() }

    private fun update(id: String, f: (AutoTask) -> AutoTask) {
        synchronized(this) { _tasks.value = _tasks.value.map { if (it.id == id) f(it) else it } }
        save()
    }

    /** [38.9.14.2] the main conversation if it already exists (no provider needed, never creates one) */
    suspend fun existingMain(c: Context): String? = withContext(Dispatchers.IO) {
        init(c)
        val a = c.applicationContext as? com.openminis.app.MinisApp ?: return@withContext null
        val cur = mainSid
        if (cur.isNotBlank() && runCatching { a.chatRepository.getSession(cur) }.getOrNull() != null) cur else null
    }

    /** the main conversation (created once, titled 「Mines」, on the default model group) */
    suspend fun ensureMain(c: Context): String? = withContext(Dispatchers.IO) {
        init(c)
        val a = c.applicationContext as? com.openminis.app.MinisApp ?: return@withContext null
        val cur = mainSid
        if (cur.isNotBlank() && a.chatRepository.getSession(cur) != null) return@withContext cur
        val model = a.providerRepository.allVisibleEntries().firstOrNull { ErrZhCore.chatCapable(it.baseModel.id) }?.baseModel?.id ?: return@withContext null
        val s = a.chatRepository.createSession(modelId = model, title = "Mines")
        runCatching { a.chatRepository.dao.updateSource(s.id, "main") }
        runCatching { com.openminis.app.debug.HeadlessChatRunner.applyModelOverride(c, s.id, null, null) }
        mainSid = s.id
        prefs()?.edit()?.putString("main", s.id)?.apply()
        s.id
    }

    // ── router (ChatViewModel.sendMessage) ──

    /**
     * true = 聊天 took the message: it is routed off the main thread, then either sent normally ([send])
     * or turned into a background task. Attachments, slash commands and every other conversation go the old way.
     */
    fun intercept(sid: String, text: String, hasAttachments: Boolean, send: (String) -> Unit): Boolean {
        if (!isMain(sid) || hasAttachments || text.isBlank() || text.trimStart().startsWith("/")) return false
        if (ChatHomeCore.obviousQuick(text)) { lastDecision = ChatHomeCore.Decision(ChatHomeCore.Route.QUICK); return false }
        _routing.value = text
        scope.launch {
            val d = try { ChatHomeCore.decide(text) { s, p -> ask(s, p) } } finally { _routing.value = null }
            lastDecision = d
            if (d.route == ChatHomeCore.Route.QUICK) withContext(Dispatchers.Main) { send(text) }
            else runCatching { startTask(text, d.name) }.onFailure {
                com.openminis.app.logging.AppLogger.warning("ChatHome", "startTask failed: ${it.message}")
                withContext(Dispatchers.Main) { send(text) }   // never lose the message
            }
        }
        return true
    }

    /** the user's own model, cheapest first (same choice as 消息 titles), 12 s each, at most two */
    private suspend fun ask(system: String, prompt: String): String? {
        val c = app ?: return null
        val a = c as? com.openminis.app.MinisApp ?: return null
        val repo = a.providerRepository
        val cheapRe = Regex("(?i)(flash|mini|lite|haiku|turbo|nano|small|air|8b|7b)")
        val entries = repo.allVisibleEntries().filter { e ->
            ErrZhCore.chatCapable(e.baseModel.id) && repo.instance(e.providerInstanceId)?.isEnabled == true
        }.sortedByDescending { cheapRe.containsMatchIn(it.baseModel.id) }.take(2)
        for (e in entries) {
            val inst = repo.instance(e.providerInstanceId) ?: continue
            val key = repo.usableApiKey(inst) ?: (if (repo.hasAnyCredential(inst)) "" else null) ?: continue
            val r = runCatching {
                kotlinx.coroutines.withTimeout(12_000L) {
                    com.openminis.app.provider.ProviderFactory.create(inst, key, e.baseModel, c, overrides = e.overrides)
                        .sendMessage(messages = listOf(com.openminis.app.data.model.LLMMessage(role = com.openminis.app.data.model.LLMMessage.Role.USER, content = prompt)),
                            systemPrompt = system, maxTokens = 200).text
                }
            }.getOrNull()
            if (!r.isNullOrBlank()) return r
        }
        return null
    }

    // ── tasks ──

    private suspend fun startTask(text: String, name0: String) {
        val c = app ?: return
        val a = c as com.openminis.app.MinisApp
        val main = a.chatRepository.getSession(mainSid)
        val now = System.currentTimeMillis()
        val s = a.chatRepository.createSession(modelId = main?.modelId ?: "unknown", title = name0)
        runCatching { a.chatRepository.dao.updateSource(s.id, "autotask") }
        val bind = main?.modelBinding
        if (main != null && !bind.isNullOrBlank()) runCatching { a.chatRepository.updateSessionBinding(s.id, bind, main.modelId) }
        else runCatching { com.openminis.app.debug.HeadlessChatRunner.applyModelOverride(c, s.id, null, null) }
        // 任务项目: a registration is a project (「Zeabur 注册」, 第 2 次 under the same project)
        var name = name0
        if (TaskProjects.enabled() && TaskProjectsCore.registerIntent(text)) {
            ChatHomeCore.siteWord(text)?.let { site ->
                TaskProjects.confirm(c, s.id, site, TaskProjectsCore.verbOf(text), "", "", TaskProjects.today())
                name = TaskProjectsCore.titleFor(TaskProjects.projects.value, s.id) ?: name
            }
        }
        if (name != name0) runCatching { a.chatRepository.dao.updateSessionTitle(s.id, name, now) }
        val t = AutoTask("t$now", s.id, name, text.trim(), St.QUEUED, createdAt = now, updatedAt = now)
        synchronized(this) { _tasks.value = _tasks.value + t }
        save()
        appendToMain(listOf("user" to text, "assistant" to ChatHomeCore.tagText(t.id, ChatHomeCore.Kind.CREATED, ChatHomeCore.createdLine(name))))
        pump()
    }

    private fun heapCritical(): Boolean {
        val r = Runtime.getRuntime()
        return MemGuardCore.level(r.totalMemory() - r.freeMemory(), r.maxMemory()) == MemGuardCore.Level.CRITICAL
    }

    /** start queued tasks while the gate allows; otherwise look again in 15 s */
    fun pump() {
        if (!loaded) return
        val wait = if (safeLaunch) SAFE_DELAY_MS - (android.os.SystemClock.uptimeMillis() - initAt) else 0L
        if (wait > 0) {
            if (retryPending.compareAndSet(false, true)) scope.launch { delay(wait); retryPending.set(false); pump() }
            return
        }
        scope.launch {
            pumpLock.withLock {
                while (true) {
                    val next = ChatHomeCore.nextQueued(_tasks.value) ?: break
                    val autoRunning = _tasks.value.count { it.id in inRunner }
                    val all = com.openminis.app.service.SessionConcurrencyManager.runningSessions.value.size
                    if (!ChatHomeCore.canStart(autoRunning, all, com.openminis.app.service.SessionConcurrencyManager.MAX_CONCURRENT, heapCritical())) {
                        if (retryPending.compareAndSet(false, true)) scope.launch { delay(15_000); retryPending.set(false); pump() }
                        break
                    }
                    inRunner += next.id
                    update(next.id) { ChatHomeCore.started(it, System.currentTimeMillis()) }
                    scope.launch { run(next) }
                }
            }
        }
    }

    private suspend fun run(t: AutoTask) {
        val c = app ?: return
        val res = runCatching {
            com.openminis.app.debug.HeadlessChatRunner.prompt(c, t.sid, t.goal, wait = true, timeoutMs = 45 * 60_000L)
        }
        inRunner.remove(t.id)
        val now = System.currentTimeMillis()
        res.onSuccess { pr ->
            when {
                pr.status == "Rejected" || pr.status == "Error" -> update(t.id) { ChatHomeCore.runFailed(it, ChatHomeCore.rejectZh(pr.responseText), now) }
                pr.timedOut -> update(t.id) { if (it.st == St.WAITING) it else ChatHomeCore.runFailed(it, "45 分钟还没做完，点开看看", now) }
                else -> update(t.id) { ChatHomeCore.runEnded(ChatHomeCore.onProgress(it, TaskProgress.get(t.sid), false, now), pr.responseText, now) }
            }
        }.onFailure { e -> update(t.id) { ChatHomeCore.runFailed(it, e.message ?: e.javaClass.simpleName, now) } }
        postNotices()
        pump()
    }

    /** the task conversations' progress (agent loop running or not) → chip states, notices */
    private fun observe() {
        scope.launch {
            combine(TaskProgress.tasks, com.openminis.app.service.SessionConcurrencyManager.runningSessions) { p, r -> p to r }.collect { (p, running) ->
                val now = System.currentTimeMillis()
                var changed = false
                synchronized(this@ChatHome) {
                    _tasks.value = _tasks.value.map { t ->
                        val live = t.sid in running || t.id in inRunner
                        var n = ChatHomeCore.onProgress(t, p[t.sid], live, now)
                        // the user went on inside a finished / waiting task: running again, its next end is told again
                        if (live && n.st == St.RUNNING && t.st != St.RUNNING && t.st != St.QUEUED) n = ChatHomeCore.resumed(n, now)
                        if (n != t) changed = true
                        n
                    }
                }
                if (changed) { save(); postNotices() }
            }
        }
    }

    /** one notice per 等你 / 出错 / 完成 into 聊天 (+ notification); a run still in the runner reports only 等你 */
    private fun postNotices() {
        scope.launch {
            lock.withLock {
                for (t in _tasks.value) {
                    if (!ChatHomeCore.needsNotice(t) || (t.id in inRunner && t.st != St.WAITING)) continue
                    val (kind, body) = ChatHomeCore.noticeOf(t) ?: continue
                    update(t.id) { it.copy(told = t.st.id) }
                    runCatching { appendToMain(listOf("assistant" to ChatHomeCore.tagText(t.id, kind, body))) }
                    runCatching { notify(t, body) }
                }
            }
        }
    }

    /** rows written straight into the main conversation (no model turn), then the open chat reloads */
    suspend fun appendToMain(rows: List<Pair<String, String>>) {
        appendTo(mainSid.ifBlank { return }, rows)
    }

    /** rows written straight into conversation [sid] (no model turn) */
    suspend fun appendTo(sid: String, rows: List<Pair<String, String>>, reload: Boolean = true) {
        val c = app as? com.openminis.app.MinisApp ?: return
        withContext(Dispatchers.IO) {
            rows.forEach { (role, text) ->
                c.chatRepository.appendMessage(sid, role, JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString())
            }
        }
        if (reload) withContext(Dispatchers.Main) { com.openminis.app.ui.chat.ChatViewModelStore.existing(sid)?.minesReload() }
    }

    private fun notify(t: AutoTask, body: String) {
        val c = app ?: return
        val nm = androidx.core.app.NotificationManagerCompat.from(c)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val sys = c.getSystemService(android.app.NotificationManager::class.java)
            if (sys?.getNotificationChannel(CHANNEL) == null)
                sys?.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "任务提醒", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("minis://session/${t.sid}"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = android.app.PendingIntent.getActivity(c, t.sid.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(com.openminis.app.R.drawable.ic_launcher_monochrome)
            .setContentTitle("${t.name} · ${t.st.label}")
            .setContentText(body).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(t.sid.hashCode(), n) } catch (_: SecurityException) {}
    }

    /** smoke / 自检: flag, main conversation, tasks, last decision */
    fun stateJson(): String = JSONObject().put("on", _on.value).put("main", mainSid).put("routing", _routing.value ?: "")
        .put("last", lastDecision?.let { "${it.route.name}:${it.name}:${if (it.byModel) "model" else "rule"}" } ?: "")
        .put("tasks", JSONArray().apply {
            _tasks.value.forEach { t -> put(JSONObject().put("id", t.id).put("sid", t.sid).put("name", t.name).put("st", t.st.id)
                .put("step", t.step).put("total", t.total).put("line", t.line).put("told", t.told)
                .put("log", _logs.value[t.id].orEmpty().size).put("talk", talkOf(t.id) ?: "")) }
        }).toString()
}
