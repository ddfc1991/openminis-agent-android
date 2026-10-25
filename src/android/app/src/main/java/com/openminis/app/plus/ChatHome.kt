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
    /** launch opened 聊天 once in this process (back / 历史 then show the old list without bouncing) */
    @Volatile var redirected = false
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
            _tasks.value = ChatHomeCore.fromJson(p.getString("tasks", "[]")).map {
                // [Mines-38.9.15 register_auto] a 一键注册 task has no conversation: it is never queued for the chat runner
                if (isExternal(it.sid)) { if (it.st == St.RUNNING || it.st == St.QUEUED || it.st == St.WAITING) it.copy(st = St.ERROR, line = "Mines 重启，注册中断了", told = St.ERROR.id) else it }
                else if (it.st == St.RUNNING) it.copy(st = St.QUEUED) else it }
            app = c.applicationContext
        }
        observe(); pump()
    }

    private fun prefs() = app?.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun save() { prefs()?.edit()?.putString("tasks", ChatHomeCore.toJson(_tasks.value.takeLast(200)))?.apply() }
    fun setOn(c: Context, v: Boolean) { init(c); _on.value = v; prefs()?.edit()?.putBoolean("on", v)?.apply() }

    private fun update(id: String, f: (AutoTask) -> AutoTask) {
        synchronized(this) { _tasks.value = _tasks.value.map { if (it.id == id) f(it) else it } }
        save()
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

    // ── [Mines-38.9.15 register_auto] tasks run by Mines itself (no conversation): one row, notices like any task ──

    fun isExternal(sid: String?): Boolean = sid?.startsWith(RegisterAuto.SID_PREFIX) == true

    /** create or update a task that [RegisterAuto] (not the chat runner) drives; 等你 / 完成 post their notice once */
    fun putExternal(sid: String, id: String, name: String, goal: String, st: St, step: Int, total: Int, line: String, createdAt: Long) {
        if (app == null) return
        val now = System.currentTimeMillis()
        var created = false
        synchronized(this) {
            val cur = _tasks.value.firstOrNull { it.id == id }
            if (cur == null) {
                created = true
                _tasks.value = _tasks.value + AutoTask(id, sid, name, goal, st, step, total, line, createdAt, now)
            } else _tasks.value = _tasks.value.map {
                if (it.id != id) it else it.copy(st = st, step = step, total = total, line = line, updatedAt = now, told = if (st != it.st) "" else it.told)
            }
        }
        save()
        if (created && mainSid.isNotBlank()) scope.launch { runCatching { appendToMain(listOf("assistant" to ChatHomeCore.tagText(id, ChatHomeCore.Kind.CREATED, ChatHomeCore.createdLine(name)))) } }
        if (st == St.WAITING || st == St.DONE || st == St.ERROR) postNotices()
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
        scope.launch {
            pumpLock.withLock {
                while (true) {
                    val next = ChatHomeCore.nextQueued(_tasks.value.filterNot { isExternal(it.sid) }) ?: break
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
                        if (isExternal(t.sid)) return@map t   // [38.9.15 register_auto] its state comes from RegisterAuto only
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
        val c = app as? com.openminis.app.MinisApp ?: return
        val sid = mainSid.ifBlank { return }
        withContext(Dispatchers.IO) {
            rows.forEach { (role, text) ->
                c.chatRepository.appendMessage(sid, role, JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString())
            }
        }
        withContext(Dispatchers.Main) { com.openminis.app.ui.chat.ChatViewModelStore.existing(sid)?.minesReload() }
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
        val intent = if (isExternal(t.sid)) (c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return)
                .putExtra("mines_route", com.openminis.app.ui.plus.TaskRoutes.chat(t.sid)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            else android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("minis://session/${t.sid}"))
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
                .put("step", t.step).put("total", t.total).put("line", t.line).put("told", t.told)) }
        }).toString()
}
