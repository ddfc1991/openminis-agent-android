package com.openminis.app.plus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.sandbox.PRootKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * [Mines-jobs-25] Background sandbox jobs (plugin / MCP installs, removals,
 * checks). Nothing blocks the screen any more: a job runs here, shows a
 * progress notification and a slim pill in the app, and ends with a short
 * popup — "已安装 X" on success, the reason plus 重试 on failure
 * ([com.openminis.app.ui.plus.MinesJobsHost]).
 *
 * Unlike ShellExecutor, the time limit is enforced by killing the PRoot
 * process: ShellExecutor's blocking read of the output pipe can't be
 * cancelled, so a sandbox process that never closed the pipe (a plugin's
 * leftover child) kept 「检查插件 进行中」 up forever.
 */
object MinesJobs {
    private const val TAG = "MinesJobs"
    private const val CHANNEL = "mines_jobs"
    private const val NOTIF_RUNNING = 5510

    data class Job(
        val id: Long,
        val title: String,
        val command: String,
        val successText: String,
        val running: Boolean = true,
        val exit: Int? = null,
        val lines: List<String> = emptyList(),
        val reason: String = "",
        val startedAt: Long = System.currentTimeMillis(),
        val finishedAt: Long = 0L,
        val timeoutMs: Long = 900_000L,
        /** [Mines-net-26] Housekeeping job: no pill, no popup, no notification. */
        val silent: Boolean = false,
    ) {
        val ok: Boolean get() = exit == 0
        val lastLine: String get() = lines.lastOrNull().orEmpty()
    }

    private val ids = AtomicLong(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()
    private val _finished = MutableSharedFlow<Job>(extraBufferCapacity = 16)
    /** One event per finished job (the in-app popup and the desk pet listen). */
    val finished: SharedFlow<Job> = _finished.asSharedFlow()
    private val callbacks = HashMap<Long, (Int) -> Unit>()
    @Volatile var appInForeground: Boolean = true

    val running: List<Job> get() = _jobs.value.filter { it.running && !it.silent }

    /** "安装 X" → "已安装 X" etc. */
    fun successFor(title: String): String = when {
        title.startsWith("安装 ") -> "已安装 " + title.removePrefix("安装 ")
        title.startsWith("卸载 ") -> "已卸载 " + title.removePrefix("卸载 ")
        title.startsWith("添加 ") -> "已添加 " + title.removePrefix("添加 ")
        title.startsWith("移除 ") -> "已移除 " + title.removePrefix("移除 ")
        title.startsWith("启用 ") -> "已启用 " + title.removePrefix("启用 ")
        title.startsWith("停用 ") -> "已停用 " + title.removePrefix("停用 ")
        title.startsWith("更新 ") -> "已更新 " + title.removePrefix("更新 ")
        title.startsWith("检查") -> "检查完成"
        else -> "$title 完成"
    }

    /** Same command already running → its id (no second copy). */
    fun run(
        context: Context, title: String, command: String,
        successText: String = successFor(title), timeoutMs: Long = 900_000L,
        silent: Boolean = false,
        onDone: (Int) -> Unit = {},
    ): Long {
        _jobs.value.firstOrNull { it.running && it.command == command }?.let { return it.id }
        val app = context.applicationContext
        val job = Job(ids.getAndIncrement(), title, command, successText, timeoutMs = timeoutMs, silent = silent)
        synchronized(callbacks) { callbacks[job.id] = onDone }
        _jobs.value = (_jobs.value.filter { it.running || System.currentTimeMillis() - it.finishedAt < 120_000 } + job)
        notifyRunning(app)
        scope.launch { execute(app, job) }
        return job.id
    }

    fun retry(context: Context, job: Job) {
        val cb = synchronized(callbacks) { callbacks[job.id] } ?: {}
        run(context, job.title, job.command, job.successText, job.timeoutMs, job.silent, cb)
    }

    private fun update(id: Long, block: (Job) -> Job) {
        _jobs.value = _jobs.value.map { if (it.id == id) block(it) else it }
    }

    private suspend fun execute(app: Context, job: Job) {
        var waited = 0
        while (!PRootKernel.isBooted && waited < 60_000) { delay(500); waited += 500 }
        val code: Int = if (!PRootKernel.isBooted) {
            update(job.id) { it.copy(lines = it.lines + "沙箱还没启动好") }
            -1
        } else runCatching { runProot(app, job) }.getOrElse { e ->
            update(job.id) { it.copy(lines = it.lines + "出错：${e.message}") }
            -1
        }
        val now = System.currentTimeMillis()
        update(job.id) { j -> j.copy(running = false, exit = code, finishedAt = now, reason = if (code == 0) "" else reasonOf(j, code)) }
        val done = _jobs.value.first { it.id == job.id }
        val cb = synchronized(callbacks) { callbacks[job.id] }
        withContext(Dispatchers.Main) { runCatching { cb?.invoke(code) } }
        _finished.tryEmit(done)
        notifyRunning(app)
        if (!appInForeground && !done.silent) notifyFinished(app, done)
    }

    /** [Mines-report-31] Run a sandbox command synchronously (no job row); exit code + output. Blocking. */
    fun runSync(context: Context, command: String, timeoutMs: Long = 30_000L): Pair<Int, String> {
        val app = context.applicationContext
        var waited = 0
        while (!PRootKernel.isBooted && waited < 15_000) { Thread.sleep(500); waited += 500 }
        if (!PRootKernel.isBooted) return -1 to "沙箱还没启动"
        val pb = ProcessBuilder(PRootKernel.buildProotCommand(command)).redirectErrorStream(true)
        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(app).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        for ((k, v) in PRootKernel.customEnvironment) env[k] = v
        val p = pb.start()
        runCatching { p.outputStream.close() }
        val sb = StringBuilder()
        val reader = Thread({ runCatching { p.inputStream.bufferedReader().forEachLine { if (sb.length < 20_000) sb.append(it).append('\n') } } }, "mines-sync").apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) { runCatching { p.destroyForcibly() } }
        reader.join(1500)
        return (if (finished) p.exitValue() else 124) to sb.toString().trim()
    }

    /** Runs one sandbox command; the time limit kills PRoot (and with it every process inside). */
    private fun runProot(app: Context, job: Job): Int {
        val pb = ProcessBuilder(PRootKernel.buildProotCommand(job.command)).redirectErrorStream(true)
        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(app).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        for ((k, v) in PRootKernel.customEnvironment) env[k] = v
        env["CI"] = "true"; env["npm_config_yes"] = "true"
        val p = pb.start()
        runCatching { p.outputStream.close() }
        val reader = Thread({
            runCatching {
                InputStreamReader(p.inputStream, StandardCharsets.UTF_8).buffered().useLines { seq ->
                    seq.forEach { raw ->
                        val clean = raw.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "").replace("\r", "").trimEnd()
                        if (clean.isNotBlank()) update(job.id) { it.copy(lines = (it.lines + clean).takeLast(150)) }
                    }
                }
            }
        }, "mines-job-${job.id}").apply { isDaemon = true; start() }
        val finished = p.waitFor(job.timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            Log.w(TAG, "job ${job.id} timed out: ${job.command}")
            update(job.id) { it.copy(lines = it.lines + "超过 ${job.timeoutMs / 60_000} 分钟没完成，已停止") }
            runCatching { p.destroyForcibly() }
            runCatching { p.waitFor(3, TimeUnit.SECONDS) }
        }
        reader.join(2000)
        runCatching { p.inputStream.close() }
        return if (finished) p.exitValue() else 124
    }

    /** A short, human reason for a failed job, picked from its output. */
    private fun reasonOf(job: Job, code: Int): String {
        val text = job.lines.joinToString("\n")
        return when {
            code == 124 -> "超时了"
            MarketGitCore.reason(text) != null -> MarketGitCore.reason(text)!!   // [Mines-38.9.7] GitHub ssh → https
            text.contains("MINES_DEVKIT_FAIL") || text.contains("mines-guard") ->
                job.lines.lastOrNull { it.startsWith("✗") }?.take(90) ?: "下载卡住或连不上（已自动换源重试过），运行「网络体检」看原因"
            Regex("ETIMEDOUT|ECONNRESET|EAI_AGAIN|ENOTFOUND|network|socket hang up", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                "网络连不上（检查代理或换个 npm 镜像）"
            Regex("E404|404 Not Found|is not in this registry", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "找不到这个包"
            Regex("ENOSPC|No space left", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "手机存储空间不够"
            Regex("EACCES|Permission denied", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "没有权限"
            else -> job.lines.lastOrNull { it.contains("✗") || it.contains("ERR", true) || it.contains("错") || it.contains("失败") }
                ?.take(80) ?: "出错了（退出码 $code）"
        }
    }

    private fun channel(app: Context): NotificationManager {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "后台任务（安装 / 检查）", NotificationManager.IMPORTANCE_LOW))
        }
        return nm
    }

    private fun openApp(app: Context): PendingIntent = PendingIntent.getActivity(
        app, 41, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notifyRunning(app: Context) = runCatching {
        val nm = channel(app)
        val run = running
        if (run.isEmpty()) { nm.cancel(NOTIF_RUNNING); return@runCatching }
        val n = android.app.Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (run.size == 1) run[0].title + "…" else "${run.size} 个任务在后台进行")
            .setContentText("在后台进行，完成后会告诉你")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(app))
            .build()
        nm.notify(NOTIF_RUNNING, n)
    }

    private fun notifyFinished(app: Context, job: Job) = runCatching {
        val nm = channel(app)
        val n = android.app.Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (job.ok) job.successText else job.title + " 没成功")
            .setContentText(if (job.ok) "完成" else job.reason + "（打开 Mines 可重试）")
            .setAutoCancel(true)
            .setContentIntent(openApp(app))
            .build()
        nm.notify((NOTIF_RUNNING + 1 + (job.id % 50)).toInt(), n)
    }
}
