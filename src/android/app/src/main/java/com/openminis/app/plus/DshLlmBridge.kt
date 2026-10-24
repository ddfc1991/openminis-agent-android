package com.openminis.app.plus

import android.content.Context
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Model-provider dsh plugins (e.g. dsh-workbuddy-connect) inside Mines.
 *
 * The plugins run in the sandbox under minis-dsh's socket, which implements
 * dsh's `llm` service. `socket.mjs --gateway` serves every registered
 * provider route as an OpenAI-compatible endpoint on 127.0.0.1:8790
 * (`/p/<route>/v1`). This object keeps that gateway process alive while the
 * app runs and adds one Mines provider per route, so the plugin's models show
 * up as their own group in the model picker. Requests go Mines → gateway →
 * the plugin's own endpoint (for WorkBuddy: its loopback shim → WorkBuddy).
 */
object DshLlmBridge {
    private const val TAG = "DshLlmBridge"
    const val PORT = 8790
    private const val PREFS = "mines_dsh_llm"
    private val lock = Mutex()
    @Volatile private var process: Process? = null

    data class Route(val id: String, val name: String, val plugin: String, val models: Int, val proxied: Int)

    private fun home(context: Context) = File(RootfsManager.getInstance(context).rootfsDir, "root/.minis-dsh")

    /** True when an installed, working plugin registered at least one model route. */
    fun hasModelPlugins(context: Context): Boolean = runCatching {
        val st = JSONObject(File(home(context), "status.json").readText())
        st.keys().asSequence().any { k ->
            val o = st.optJSONObject(k)
            o != null && o.optBoolean("ok") && (o.optJSONArray("llm")?.length() ?: 0) > 0
        }
    }.getOrDefault(false)

    /** Routes as last written by the socket (`llm.json`). */
    fun routes(context: Context): List<Route> = runCatching {
        val idx = JSONObject(File(home(context), "llm.json").readText())
        val arr = idx.optJSONArray("providers") ?: return@runCatching emptyList()
        (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val ms = p.optJSONArray("models")
            var proxied = 0
            if (ms != null) for (j in 0 until ms.length()) if (ms.getJSONObject(j).optBoolean("proxied")) proxied++
            Route(p.optString("id"), p.optString("name"), p.optString("plugin"), ms?.length() ?: 0, proxied)
        }
    }.getOrDefault(emptyList())

    fun healthy(): Boolean = runCatching {
        val c = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
        c.connectTimeout = 400; c.readTimeout = 800
        c.inputStream.use { it.readBytes() }
        c.responseCode == 200
    }.getOrDefault(false)

    private fun fetchIndex(): JSONObject? = runCatching {
        val c = URL("http://127.0.0.1:$PORT/index").openConnection() as HttpURLConnection
        c.connectTimeout = 600; c.readTimeout = 8000
        JSONObject(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
    }.getOrNull()

    /** Stop the gateway (e.g. after a new sign-in, so the plugin re-reads it). */
    fun restart() {
        process?.let { runCatching { it.destroy() } }
        process = null
    }

    /**
     * Make sure the gateway runs (when any model plugin is installed) and that
     * each route with usable models has a Mines provider. Safe to call often.
     * Returns a short Chinese status line for the UI.
     */
    suspend fun ensure(context: Context, force: Boolean = false): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            // Wait for the sandbox (first launch boots it in the background).
            var waited = 0
            while (!PRootKernel.isBooted && waited < 60_000) { delay(500); waited += 500 }
            if (!PRootKernel.isBooted) return@withLock "沙箱还没启动"
            if (!force && !hasModelPlugins(app)) return@withLock "没有提供模型的插件"
            if (!healthy()) {
                start(app)
                var t = 0
                while (!healthy() && t < 30_000) { delay(500); t += 500 }
                if (!healthy()) return@withLock "插件模型服务没起来（看插件页的检查结果）"
            }
            val idx = fetchIndex() ?: return@withLock "读不到插件模型列表"
            sync(app, idx)
        }
    }

    private fun start(context: Context) {
        process?.let { if (it.isAlive) return; process = null }
        val cmd = "MINIS_DSH_HOME=/root/.minis-dsh MINIS_DSH_GATEWAY_PORT=$PORT exec node /usr/local/lib/minis-dsh/socket.mjs --gateway"
        val pb = ProcessBuilder(PRootKernel.buildProotCommand(cmd)).redirectErrorStream(true)
        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        for ((k, v) in PRootKernel.customEnvironment) env[k] = v
        val p = pb.start()
        process = p
        // Drain output so the pipe never fills; the socket logs to its own file.
        Thread({ runCatching { p.inputStream.use { s -> val b = ByteArray(4096); while (s.read(b) >= 0) {} } } }, "dsh-gateway-drain")
            .apply { isDaemon = true }.start()
        Log.i(TAG, "gateway started")
    }

    private fun sync(context: Context, idx: JSONObject): String {
        val repo = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull ?: return "供应商还没加载"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = idx.optJSONArray("providers") ?: return "没有插件模型"
        var added = 0; var ready = 0
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val route = p.optString("id")
            if (route.isBlank()) continue
            val name = p.optString("name").ifBlank { route }
            val ms = p.optJSONArray("models")
            var proxied = 0
            if (ms != null) for (j in 0 until ms.length()) if (ms.getJSONObject(j).optBoolean("proxied")) proxied++
            val known = prefs.getString("inst_$route", null)?.let { repo.instance(it) }
            if (known != null) {
                ready++
                if (prefs.getInt("count_$route", -1) != proxied) {
                    repo.triggerAsyncModelReconcile(known.id, forceRefresh = true)
                    prefs.edit().putInt("count_$route", proxied).apply()
                }
                continue
            }
            if (proxied == 0) continue
            val inst = ProviderInstance(
                id = UUID.randomUUID().toString(),
                label = "$name（插件）",
                providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey,
                customBaseURL = "http://127.0.0.1:$PORT/p/$route/v1",
                appendV1Suffix = true,
            )
            repo.addInstance(inst)
            repo.saveApiKey(inst.id, "minis-dsh")
            repo.triggerAsyncModelReconcile(inst.id, forceRefresh = true)
            prefs.edit().putString("inst_$route", inst.id).putInt("count_$route", proxied).apply()
            added++; ready++
        }
        return when {
            added > 0 -> "已把 $added 组插件模型加进模型列表"
            ready > 0 -> "插件模型已就绪（$ready 组）"
            else -> "插件已加载，但还没有可用模型（多半是还没登录）"
        }
    }

    /**
     * Phone sign-in for dsh-workbuddy-connect: copy what the user picked or
     * pasted into the sandbox and let `minis-dsh wb-login` store it as the
     * plugin's own credential. Restarts the gateway so it is read at once.
     */
    suspend fun workBuddyLogin(context: Context, content: String, international: Boolean): String = withContext(Dispatchers.IO) {
        val dir = home(context).apply { mkdirs() }
        val f = File(dir, "wb-import-${System.currentTimeMillis()}.json")
        f.writeText(content)
        val r = com.openminis.app.sandbox.ShellExecutor.execute(
            context.applicationContext,
            "minis-dsh wb-login /root/.minis-dsh/${f.name}${if (international) " --ai" else ""} && minis-dsh list",
            timeout = 180_000L,
        )
        f.delete()
        restart()
        val line = r.output.lines().firstOrNull { it.startsWith("已保存") || it.contains("看不懂") || it.contains("没有可用") }
            ?: r.output.lines().lastOrNull().orEmpty()
        if (r.exitCode == 0) "$line · ${ensure(context, force = true)}" else line
    }
}
