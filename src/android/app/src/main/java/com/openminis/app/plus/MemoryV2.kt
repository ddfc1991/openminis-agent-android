package com.openminis.app.plus

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [Mines-38.9.13 memory_v2] Android side of [MemoryV2Core]: the feature flag,
 * the SQLite adapter, the `memory` tool (ls / find / read), the budgeted prompt
 * fragment, lazy L0/L1 summaries and optional write-time embeddings.
 * Everything stays on the phone except the two optional API calls (summary,
 * embedding), which use keys the user already saved. Flag off → none of this runs.
 */
object MemoryV2 {
    private const val TAG = "MemoryV2"
    const val TOOL_NAME = "memory"
    private const val PREFS = "mines_experiments"
    const val KEY = "memory_v2"
    const val KEY_EMBED = "memory_v2.embed"
    private const val SUMMARY_DAILY_CAP = 20

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isOn(c: Context): Boolean = prefs(c).getBoolean(KEY, true)
    fun setOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY, on).apply()
    fun embedOn(c: Context): Boolean = prefs(c).getBoolean(KEY_EMBED, false)
    fun setEmbedOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_EMBED, on).apply()

    /** Same directory MinisApp gives MemoryRepository. */
    private fun memDir(c: Context) = File(c.applicationContext.filesDir, "minis-global/memory")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val summarising = AtomicBoolean(false)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    @Volatile private var ix: MemoryV2Core.Index? = null

    private class AndroidSql(val db: SQLiteDatabase) : MemoryV2Core.Sql {
        override fun exec(sql: String, args: Array<Any?>) = if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
        override fun rows(sql: String, args: Array<Any?>): List<Array<Any?>> =
            db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cur ->
                val out = mutableListOf<Array<Any?>>()
                while (cur.moveToNext()) out += Array(cur.columnCount) { i ->
                    when (cur.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> cur.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> cur.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> cur.getBlob(i)
                        Cursor.FIELD_TYPE_NULL -> null
                        else -> cur.getString(i)
                    }
                }
                out
            }
        override fun <T> tx(block: () -> T): T {
            db.beginTransaction()
            try { val r = block(); db.setTransactionSuccessful(); return r } finally { db.endTransaction() }
        }
    }

    /** All index access goes through this lock (tool calls, prompt builds, background jobs). */
    private fun <T> withIndex(c: Context, block: (MemoryV2Core.Index) -> T): T = synchronized(this) {
        val i = ix ?: open(c)
        val ok = runCatching { i.sync() }.onFailure { Log.w(TAG, "index broken, rebuilding: ${it.message}") }.isSuccess
        // The index is derived: a corrupt or foreign (e.g. restored from a device with FTS5) copy is dropped and rebuilt.
        block(if (ok) i else { db?.close(); SQLiteDatabase.deleteDatabase(dbFile(c)); open(c).also { it.sync() } })
    }

    @Volatile private var db: SQLiteDatabase? = null
    private fun dbFile(c: Context) = c.applicationContext.getDatabasePath("memory_v2.db").apply { parentFile?.mkdirs() }
    private fun open(c: Context): MemoryV2Core.Index {
        val d = SQLiteDatabase.openOrCreateDatabase(dbFile(c), null).also { db = it }
        return MemoryV2Core.Index(AndroidSql(d), memDir(c).apply { mkdirs() }).also { ix = it; Log.i(TAG, "index open, ${it.fts}") }
    }

    // ---------------------------------------------------------------- prompt

    const val TOOL_BULLET = "- memory: Browse long-term memory on demand — ls (directories 项目/账号/偏好/机器/技能/其他), find (search), read (full text). Check it at the start of new topics."

    /** Replaces GLOBAL.md + recent daily logs in the system prompt. Null = nothing stored. */
    fun promptFragment(c: Context): String? = runCatching {
        val g = File(memDir(c), MemoryV2Core.GLOBAL).takeIf { it.isFile }?.readText()
        val frag = withIndex(c) { it.promptFragment(g) }
        summariseLater(c)
        frag
    }.onFailure { Log.w(TAG, "promptFragment: ${it.message}") }.getOrNull()

    // ---------------------------------------------------------------- tool

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_NAME,
        description = "Long-term memory stored on this phone, organised as directories 项目 (projects), 账号 (accounts), 偏好 (preferences), 机器 (servers/devices), 技能 (how-tos), 其他, plus GLOBAL.md. " +
            "Pull only what you need: action=ls (path '' for directories with one-line summaries, or a directory for its entries), " +
            "action=find (query, optional dir — searches full text, Chinese or English), action=read (path from ls/find for the original text; a directory name for its overview; GLOBAL.md).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user (e.g. '查服务器记忆'). Use the same language as the user."),
            "action" to AgentToolParam("string", "ls, find or read", enumValues = listOf("ls", "find", "read")),
            "path" to AgentToolParam("string", "For ls/read: '' (root), a directory like '机器', an entry path like '机器/2026-10-24.md#a1b2c3d4e5', or 'GLOBAL.md'."),
            "query" to AgentToolParam("string", "For find: key words."),
            "dir" to AgentToolParam("string", "For find: optional directory to search in.", enumValues = MemoryV2Core.DIRS),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "path", "query", "dir"),
    )

    /** Extra `dir` parameter for memory_write when v2 is on. */
    val WRITE_DIR_PARAM = AgentToolParam("string", "Optional memory directory for this entry: 项目 / 账号 / 偏好 / 机器 / 技能 / 其他. Omit to file it automatically.", enumValues = MemoryV2Core.DIRS)

    fun runTool(c: Context, argsJson: String): ToolExecutionResult = runCatching {
        val a = JSONObject(argsJson)
        val action = a.optString("action").trim()
        val title = a.optString("tool_title").ifBlank { "查记忆" }
        val dir = a.optString("dir").trim().takeIf { it in MemoryV2Core.DIRS }
        val out = when (action) {
            "ls" -> withIndex(c) { it.ls(a.optString("path")) }.also { summariseLater(c) }
            "read" -> withIndex(c) { it.read(a.optString("path")) }
            "find" -> {
                val q = a.optString("query").trim().ifEmpty { a.optString("path").trim() }
                if (q.isEmpty()) return ToolExecutionResult("Error: query is required for find", false, toolTitle = title)
                val qv = if (embedOn(c)) embed(q) else null   // one API call, outside the lock
                withIndex(c) { it.findText(q, dir, qv) }
            }
            else -> return ToolExecutionResult("Error: action must be ls, find or read", false, toolTitle = title)
        }
        ToolExecutionResult(out, true, toolTitle = title)
    }.getOrElse { ToolExecutionResult("Error: ${it.message}", false) }

    /** After a successful memory_write: record the agent's directory, embed (if switched on) — write time only. */
    fun onWrite(c: Context, argsJson: String) {
        val a = runCatching { JSONObject(argsJson) }.getOrNull() ?: return
        val content = a.optString("content").trim().takeIf { it.isNotEmpty() } ?: return
        val dir = a.optString("dir").trim()
        scope.launch {
            runCatching { if (dir in MemoryV2Core.DIRS) withIndex(c) { it.setDir(content, dir) } }
            if (embedOn(c)) runCatching { embed(content)?.let { (m, v) -> withIndex(c) { it.putVec(content, m, v) } } }
        }
    }

    // ---------------------------------------------------------------- model calls (optional)

    /** Cheapest chat key the user saved (same order as DigestWriter). */
    private fun chatEndpoint(): Triple<String, String, String>? {
        PlusVault.defaultAccount("deepseek")?.let { PlusVault.secret(it) }?.let { return Triple("https://api.deepseek.com/chat/completions", it, "deepseek-chat") }
        PlusVault.defaultAccount("zhipu")?.let { PlusVault.secret(it) }?.let { return Triple("https://open.bigmodel.cn/api/paas/v4/chat/completions", it, "glm-4-flash") }
        PlusVault.defaultAccount("siliconflow")?.let { PlusVault.secret(it) }?.let { return Triple("https://api.siliconflow.cn/v1/chat/completions", it, "Qwen/Qwen2.5-7B-Instruct") }
        return null
    }

    /** Embedding API the user already has a key for; null = embeddings off. */
    private fun embedEndpoint(): Triple<String, String, String>? {
        PlusVault.defaultAccount("siliconflow")?.let { PlusVault.secret(it) }?.let { return Triple("https://api.siliconflow.cn/v1/embeddings", it, "BAAI/bge-m3") }
        PlusVault.defaultAccount("zhipu")?.let { PlusVault.secret(it) }?.let { return Triple("https://open.bigmodel.cn/api/paas/v4/embeddings", it, "embedding-3") }
        PlusVault.defaultAccount("openai")?.let { PlusVault.secret(it) }?.let { return Triple("https://api.openai.com/v1/embeddings", it, "text-embedding-3-small") }
        return null
    }

    fun hasEmbeddingModel(): Boolean = runCatching { embedEndpoint() != null }.getOrDefault(false)

    private fun post(url: String, key: String, body: JSONObject): JSONObject? = runCatching {
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
        }
    }.getOrNull()

    private fun embed(text: String): Pair<String, FloatArray>? {
        val (url, key, model) = embedEndpoint() ?: return null
        val o = post(url, key, JSONObject().put("model", model).put("input", text.take(2000))) ?: return null
        val arr = o.optJSONArray("data")?.optJSONObject(0)?.optJSONArray("embedding") ?: return null
        return model to FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
    }

    /** Lazily (re)write L0/L1 for directories whose content changed; fallback text is used meanwhile. */
    private fun summariseLater(c: Context) {
        if (!summarising.compareAndSet(false, true)) return
        scope.launch {
            try {
                val p = prefs(c)
                val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                var used = if (p.getString("memory_v2.sum_day", "") == day) p.getInt("memory_v2.sum_n", 0) else 0
                val (url, key, model) = chatEndpoint() ?: return@launch
                for (d in withIndex(c) { it.staleDirs() }.take(3)) {
                    if (used >= SUMMARY_DAILY_CAP) break
                    val (sig, prompt) = withIndex(c) { i -> val its = i.items(d); i.sig(its) to MemoryV2Core.summaryPrompt(d, its.take(40).map { it to (i.body(it) ?: it.title) }) }
                    used++; p.edit().putString("memory_v2.sum_day", day).putInt("memory_v2.sum_n", used).apply()
                    val o = post(url, key, JSONObject().put("model", model).put("temperature", 0.2).put("max_tokens", 900)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))) ?: continue
                    val text = runCatching { o.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content") }.getOrNull() ?: continue
                    MemoryV2Core.parseSummary(text)?.let { (l0, l1) -> withIndex(c) { it.saveSummary(d, sig, l0, l1) } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "summarise: ${e.message}")
            } finally { summarising.set(false) }
        }
    }
}
