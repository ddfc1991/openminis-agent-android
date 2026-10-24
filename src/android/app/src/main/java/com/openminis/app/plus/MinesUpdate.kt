package com.openminis.app.plus

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.data.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update for Mines builds published on GitHub
 * (ddfc1991/openminis-agent-android, releases `build-<n>` with `mines-<n>.apk`).
 *
 * Every build has the same package, signing key and versionCode, so Android
 * installs a newer build in place — and an older one too, which is what makes
 * rollback possible: before installing, the APK the phone is running now is
 * copied aside, and 「回到上一版」 installs that copy.
 */
object MinesUpdate {
    const val OWNER = "ddfc1991"
    const val REPO = "openminis-agent-android"
    private const val PREFS = "mines_update"
    val currentBuild: Int get() = BuildConfig.MINES_BUILD

    data class Release(
        val build: Int,
        val tag: String,
        val apkUrl: String,
        val apkSize: Long,
        val pageUrl: String,
        val publishedAt: String,
        val notes: String,
    )

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(val latest: Int) : State()
        data class Available(val release: Release) : State()
        data class Downloading(val release: Release, val progress: Float) : State()
        data class Ready(val release: Release, val apk: File) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Mines-build-$currentBuild")
        val code = c.responseCode
        if (code == 403) throw IllegalStateException("GitHub 暂时限流了，过一会再试")
        if (code !in 200..299) throw IllegalStateException("GitHub 返回 $code")
        return c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private fun parse(arr: JSONArray): List<Release> = (0 until arr.length()).mapNotNull { i ->
        val r = arr.getJSONObject(i)
        if (r.optBoolean("draft")) return@mapNotNull null
        val tag = r.optString("tag_name")
        val n = Regex("^build-(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val assets = r.optJSONArray("assets") ?: return@mapNotNull null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").startsWith("mines-") && it.optString("name").endsWith(".apk") }
            ?: return@mapNotNull null
        Release(n, tag, apk.optString("browser_download_url"), apk.optLong("size"), r.optString("html_url"),
            r.optString("published_at"), r.optString("body"))
    }

    /** The change list for a build: the message of the commit its tag points at. */
    private fun changelog(tag: String): String = runCatching {
        val c = JSONObject(get("https://api.github.com/repos/$OWNER/$REPO/commits/$tag"))
        c.optJSONObject("commit")?.optString("message").orEmpty()
    }.getOrDefault("")

    suspend fun check(context: Context, quiet: Boolean = false): State = withContext(Dispatchers.IO) {
        if (!quiet) _state.value = State.Checking
        val result = runCatching {
            val all = parse(JSONArray(get("https://api.github.com/repos/$OWNER/$REPO/releases?per_page=20")))
            val latest = all.maxByOrNull { it.build } ?: return@runCatching State.Failed("还没有发布的版本")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("checked", System.currentTimeMillis()).apply()
            if (currentBuild > 0 && latest.build <= currentBuild) State.UpToDate(latest.build)
            else State.Available(latest.copy(notes = changelog(latest.tag).ifBlank { latest.notes }))
        }.getOrElse { State.Failed(it.message ?: "检查失败") }
        if (!(quiet && result is State.Failed)) _state.value = result
        result
    }

    /** At most once a day, silently (for the home card). */
    suspend fun checkDaily(context: Context) {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("checked", 0L)
        if (System.currentTimeMillis() - last > 20 * 3600_000L || _state.value is State.Idle) check(context, quiet = true)
    }

    fun dismissedBuild(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("dismissed", 0)
    fun dismiss(context: Context, build: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("dismissed", build).apply()
    }

    private fun updatesDir(context: Context) = File(context.filesDir, "updates").apply { mkdirs() }

    /** The copy of the build that was running before the last update, if any. */
    fun rollbackApk(context: Context): Pair<Int, File>? {
        val f = updatesDir(context).listFiles()?.filter { it.name.startsWith("rollback-build-") && it.name.endsWith(".apk") }
            ?.maxByOrNull { it.lastModified() } ?: return null
        val n = Regex("rollback-build-(\\d+)\\.apk").find(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (n == currentBuild) null else n to f
    }

    /** Keep the APK we are running now, so the user can go back to it. */
    private fun saveRollback(context: Context) {
        val dir = updatesDir(context)
        val target = File(dir, "rollback-build-$currentBuild.apk")
        if (target.exists() && target.length() > 0) return
        dir.listFiles()?.filter { it.name.startsWith("rollback-build-") }?.forEach { it.delete() }
        File(context.applicationInfo.sourceDir).copyTo(target, overwrite = true)
    }

    suspend fun download(context: Context, release: Release) = withContext(Dispatchers.IO) {
        _state.value = State.Downloading(release, 0f)
        runCatching { saveRollback(context) }
        when (val r = UpdateChecker.download(context, release.apkUrl, versionName = null) { p ->
            _state.value = State.Downloading(release, p)
        }) {
            is UpdateChecker.DownloadResult.Success -> {
                val dest = File(updatesDir(context), "mines-${release.build}.apk")
                if (r.file != dest) { r.file.copyTo(dest, overwrite = true); r.file.delete() }
                _state.value = State.Ready(release, dest)
            }
            is UpdateChecker.DownloadResult.Error -> _state.value = State.Failed("下载失败：${r.message}")
        }
    }

    /** Launches the system installer; asks for the install permission first when needed. */
    fun install(context: Context, apk: File): Boolean {
        if (!UpdateChecker.canInstall(context)) {
            UpdateChecker.openInstallPermissionSettings(context)
            return false
        }
        return UpdateChecker.installApk(context, apk)
    }
}
