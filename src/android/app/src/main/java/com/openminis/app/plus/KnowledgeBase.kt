package com.openminis.app.plus

import android.content.Context
import android.net.Uri
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
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
import java.util.UUID
import kotlin.math.ln

/**
 * [Mines-kb] 知识库 MVP: several local knowledge bases; import PDF / TXT /
 * Markdown / web pages; documents are split into overlapping chunks and
 * searched with BM25 on the phone (CJK as single characters + bigrams, Latin
 * as words). Nothing leaves the phone. The agent gets a `search_knowledge`
 * tool, switchable per chat from the chat's ⋯ menu.
 *
 * Layout: filesDir/knowledge/<kb-id>/meta.json + chunks.json.
 */
object KnowledgeBase {
    const val TOOL_NAME = "search_knowledge"
    private const val PREFS = "mines_knowledge"
    private const val CHUNK = 700
    private const val OVERLAP = 120

    data class Doc(val id: String, val title: String, val source: String, val addedAt: Long, val chunks: Int, val chars: Int)
    data class Kb(val id: String, val name: String, val docs: List<Doc>)
    data class Chunk(val doc: String, val i: Int, val text: String)
    data class Hit(val kb: Kb, val doc: Doc?, val chunk: Chunk, val score: Double)

    private val _bases = MutableStateFlow<List<Kb>>(emptyList())
    val bases: StateFlow<List<Kb>> = _bases.asStateFlow()
    @Volatile private var loaded = false

    /** Per-KB search index, rebuilt lazily after a change. */
    private class Index(val chunks: List<Chunk>, val tf: List<Map<String, Int>>, val len: IntArray, val df: Map<String, Int>, val avg: Double)
    private val indexes = HashMap<String, Index>()

    private fun root(ctx: Context) = File(ctx.applicationContext.filesDir, "knowledge").apply { mkdirs() }
    private fun dir(ctx: Context, id: String) = File(root(ctx), id)

    fun load(ctx: Context): List<Kb> {
        if (loaded) return _bases.value
        synchronized(this) {
            if (!loaded) {
                _bases.value = root(ctx).listFiles()?.filter { it.isDirectory }?.mapNotNull { readMeta(it) }?.sortedBy { it.name } ?: emptyList()
                loaded = true
            }
        }
        return _bases.value
    }

    private fun readMeta(d: File): Kb? = runCatching {
        val j = JSONObject(File(d, "meta.json").readText())
        val a = j.optJSONArray("docs") ?: JSONArray()
        Kb(d.name, j.optString("name", d.name), (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Doc(it.getString("id"), it.optString("title"), it.optString("source"), it.optLong("at"), it.optInt("chunks"), it.optInt("chars")) }
        })
    }.getOrNull()

    private fun writeMeta(ctx: Context, kb: Kb) {
        val d = dir(ctx, kb.id).apply { mkdirs() }
        val docs = JSONArray().apply {
            kb.docs.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("source", it.source).put("at", it.addedAt).put("chunks", it.chunks).put("chars", it.chars)) }
        }
        File(d, "meta.json").writeText(JSONObject().put("name", kb.name).put("docs", docs).toString())
        _bases.value = (_bases.value.filter { it.id != kb.id } + kb).sortedBy { it.name }
    }

    private fun readChunks(ctx: Context, id: String): MutableList<Chunk> = runCatching {
        val a = JSONArray(File(dir(ctx, id), "chunks.json").readText())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Chunk(it.getString("d"), it.optInt("i"), it.getString("t")) } }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun writeChunks(ctx: Context, id: String, chunks: List<Chunk>) {
        val a = JSONArray().apply { chunks.forEach { put(JSONObject().put("d", it.doc).put("i", it.i).put("t", it.text)) } }
        File(dir(ctx, id).apply { mkdirs() }, "chunks.json").writeText(a.toString())
        synchronized(indexes) { indexes.remove(id) }
    }

    fun create(ctx: Context, name: String): Kb {
        load(ctx)
        val kb = Kb(UUID.randomUUID().toString().take(8), name.trim().ifBlank { "知识库" }, emptyList())
        writeMeta(ctx, kb)
        return kb
    }

    /** [Mines-kb-33] A knowledge base with a fixed id (created when missing), e.g. 「对话整理」. */
    fun ensure(ctx: Context, id: String, name: String): Kb {
        load(ctx)
        return _bases.value.firstOrNull { it.id == id } ?: Kb(id, name, emptyList()).also { writeMeta(ctx, it) }
    }

    /** [Mines-kb-33] Replace every document that came from [source] with new text (one doc). */
    @Synchronized
    fun replaceSource(ctx: Context, kbId: String, title: String, source: String, text: String): Doc {
        load(ctx).firstOrNull { it.id == kbId }?.docs?.filter { it.source == source }?.forEach { removeDoc(ctx, kbId, it.id) }
        return addText(ctx, kbId, title, source, text)
    }

    fun rename(ctx: Context, id: String, name: String) {
        val kb = load(ctx).firstOrNull { it.id == id } ?: return
        writeMeta(ctx, kb.copy(name = name.trim().ifBlank { kb.name }))
    }

    fun delete(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
        synchronized(indexes) { indexes.remove(id) }
        _bases.value = _bases.value.filter { it.id != id }
    }

    fun removeDoc(ctx: Context, kbId: String, docId: String) {
        val kb = load(ctx).firstOrNull { it.id == kbId } ?: return
        writeChunks(ctx, kbId, readChunks(ctx, kbId).filter { it.doc != docId })
        writeMeta(ctx, kb.copy(docs = kb.docs.filter { it.id != docId }))
    }

    // ── import ─────────────────────────────────────────────────────────

    /** Adds plain text as one document. Returns the new doc. */
    fun addText(ctx: Context, kbId: String, title: String, source: String, text: String): Doc {
        val kb = load(ctx).firstOrNull { it.id == kbId } ?: error("知识库不存在")
        val clean = text.replace("\r\n", "\n").replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
        require(clean.isNotBlank()) { "没有读到文字内容" }
        val docId = UUID.randomUUID().toString().take(8)
        val parts = chunk(clean)
        val all = readChunks(ctx, kbId)
        parts.forEachIndexed { i, t -> all += Chunk(docId, i, t) }
        writeChunks(ctx, kbId, all)
        val doc = Doc(docId, title.ifBlank { "未命名" }, source, System.currentTimeMillis(), parts.size, clean.length)
        writeMeta(ctx, kb.copy(docs = kb.docs + doc))
        return doc
    }

    /** Import a picked file (PDF, TXT, Markdown, or other text). */
    suspend fun importUri(ctx: Context, kbId: String, uri: Uri): Doc = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "文件"
        val mime = cr.getType(uri) ?: ""
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("读不到这个文件")
        val text = if (mime == "application/pdf" || name.lowercase().endsWith(".pdf")) pdfText(ctx, bytes)
        else decodeText(bytes)
        addText(ctx, kbId, name, "文件", text)
    }

    /** Import a web page (HTML is reduced to readable text). */
    suspend fun importUrl(ctx: Context, kbId: String, url: String): Doc = withContext(Dispatchers.IO) {
        val c = URL(url.trim()).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) Mines")
        val bytes = c.inputStream.use { it.readBytes() }
        val ct = c.contentType ?: ""
        if (ct.contains("pdf")) return@withContext addText(ctx, kbId, url, url, pdfText(ctx, bytes))
        val raw = decodeText(bytes)
        if (!ct.contains("html") && !raw.trimStart().startsWith("<")) return@withContext addText(ctx, kbId, url.substringAfterLast('/').ifBlank { url }, url, raw)
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(raw)?.groupValues?.get(1)?.let { htmlToText(it).trim() }?.ifBlank { null } ?: url
        addText(ctx, kbId, title, url, htmlToText(raw))
    }

    private fun decodeText(bytes: ByteArray): String {
        val utf = String(bytes, Charsets.UTF_8)
        // Many Chinese .txt files are GBK; fall back when UTF-8 decoding is lossy.
        return if (utf.count { it == '\uFFFD' } > 8) runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf) else utf
    }

    private fun htmlToText(html: String): String {
        val s = html.replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|h[1-6]|tr|section|article)>"), "\n")
        @Suppress("DEPRECATION")
        return android.text.Html.fromHtml(s).toString().replace('\u00A0', ' ').replace('\uFFFC', ' ')
    }

    /** PDF → text with poppler's pdftotext inside the sandbox (installed once). */
    private suspend fun pdfText(ctx: Context, bytes: ByteArray): String {
        val rootfs = com.openminis.app.sandbox.RootfsManager.getInstance(ctx).rootfsDir
        val tag = UUID.randomUUID().toString().take(8)
        val inF = File(rootfs, "tmp/mines-kb-$tag.pdf").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        val outF = File(rootfs, "tmp/mines-kb-$tag.txt")
        try {
            var waited = 0
            while (!com.openminis.app.sandbox.PRootKernel.isBooted && waited < 60_000) { kotlinx.coroutines.delay(500); waited += 500 }
            val r = com.openminis.app.sandbox.ShellExecutor.execute(
                ctx.applicationContext,
                "command -v pdftotext >/dev/null 2>&1 || apk add --no-cache poppler-utils >/dev/null 2>&1; " +
                    "pdftotext -enc UTF-8 /tmp/mines-kb-$tag.pdf /tmp/mines-kb-$tag.txt",
                timeout = 300_000L,
            )
            if (!outF.exists()) error("PDF 读取失败：" + r.output.takeLast(200))
            return outF.readText()
        } finally {
            inF.delete(); outF.delete()
        }
    }

    /** Paragraph-aware chunks of ~[CHUNK] chars with [OVERLAP] overlap. */
    private fun chunk(text: String): List<String> {
        val out = ArrayList<String>()
        val paras = text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out += cur.toString().trim(); cur.clear() }
        for (p in paras) {
            if (p.length > CHUNK) {
                flush()
                var i = 0
                while (i < p.length) { out += p.substring(i, minOf(p.length, i + CHUNK)); i += CHUNK - OVERLAP }
                continue
            }
            if (cur.length + p.length + 2 > CHUNK) {
                val tail = cur.takeLast(OVERLAP).toString()
                flush(); cur.append(tail).append("\n")
            }
            cur.append(p).append("\n\n")
        }
        flush()
        return out
    }

    // ── search ─────────────────────────────────────────────────────────

    private fun isCjk(c: Char) = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0x3040..0x30FF || c.code in 0xAC00..0xD7AF

    fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        var prevCjk: Char? = null
        fun endWord() { if (word.length > 1 || (word.isNotEmpty() && word[0].isDigit())) out += word.toString(); word.clear() }
        for (ch in s.lowercase()) {
            when {
                isCjk(ch) -> {
                    endWord()
                    out += ch.toString()
                    prevCjk?.let { out += "$it$ch" }
                    prevCjk = ch
                }
                ch.isLetterOrDigit() -> { prevCjk = null; word.append(ch) }
                else -> { prevCjk = null; endWord() }
            }
        }
        endWord()
        return out
    }

    private fun index(ctx: Context, id: String): Index = synchronized(indexes) {
        indexes[id] ?: run {
            val chunks = readChunks(ctx, id)
            val tf = chunks.map { c -> tokens(c.text).groupingBy { it }.eachCount() }
            val len = IntArray(chunks.size) { i -> tf[i].values.sum() }
            val df = HashMap<String, Int>()
            tf.forEach { m -> m.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
            Index(chunks, tf, len, df, if (len.isEmpty()) 1.0 else len.average().coerceAtLeast(1.0)).also { indexes[id] = it }
        }
    }

    /** BM25 over the chosen knowledge bases (all when [kbIds] is null). */
    fun search(ctx: Context, query: String, kbIds: Collection<String>? = null, topK: Int = 5): List<Hit> {
        val q = tokens(query).distinct()
        if (q.isEmpty()) return emptyList()
        val k1 = 1.2; val b = 0.75
        val hits = ArrayList<Hit>()
        for (kb in load(ctx).filter { kbIds == null || it.id in kbIds }) {
            val ix = index(ctx, kb.id)
            val n = ix.chunks.size
            if (n == 0) continue
            val docs = kb.docs.associateBy { it.id }
            for (i in 0 until n) {
                var score = 0.0
                val tf = ix.tf[i]
                for (t in q) {
                    val f = tf[t] ?: continue
                    val df = ix.df[t] ?: continue
                    val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
                    score += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * ix.len[i] / ix.avg))
                }
                if (score > 0) hits += Hit(kb, docs[ix.chunks[i].doc], ix.chunks[i], score)
            }
        }
        return hits.sortedByDescending { it.score }.take(topK.coerceIn(1, 20))
    }

    // ── per-chat switch + agent tool ───────────────────────────────────

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Default for chats the user hasn't switched: on. */
    fun defaultOn(ctx: Context) = prefs(ctx).getBoolean("default_on", true)
    fun setDefaultOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("default_on", on).apply()

    fun isOnFor(ctx: Context, sessionId: String): Boolean {
        val p = prefs(ctx)
        return if (p.contains("chat_$sessionId")) p.getBoolean("chat_$sessionId", true) else defaultOn(ctx)
    }

    fun setOnFor(ctx: Context, sessionId: String, on: Boolean) = prefs(ctx).edit().putBoolean("chat_$sessionId", on).apply()

    fun hasContent(ctx: Context) = load(ctx).any { it.docs.isNotEmpty() }

    /** The tool definition for this chat, or null when off / nothing imported. */
    fun toolDefinition(ctx: Context, sessionId: String): AgentToolDefinition? {
        if (!runCatching { hasContent(ctx) && isOnFor(ctx, sessionId) }.getOrDefault(false)) return null
        val names = load(ctx).filter { it.docs.isNotEmpty() }.joinToString("、") { "「${it.name}」(${it.docs.size} 篇)" }
        return AgentToolDefinition(
            name = TOOL_NAME,
            description = "Search the user's local knowledge bases (documents they imported into Mines: PDFs, notes, web pages, plus 对话整理: summaries of their past chats with facts, decisions, links and files). " +
                "Available: $names. Use it whenever the question may be answered by the user's own documents, before answering from general knowledge, " +
                "and cite the document titles you used. Keyword search (BM25): pass the key terms, in the language of the documents.",
            parameters = mapOf(
                "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
                "query" to AgentToolParam("string", "Search terms"),
                "knowledge_base" to AgentToolParam("string", "Optional: name of one knowledge base to search; omit to search all"),
                "top_k" to AgentToolParam("integer", "Number of passages to return (default 5, max 20)"),
            ),
            required = listOf("tool_title", "query"),
            propertyOrdering = listOf("tool_title", "query", "knowledge_base", "top_k"),
        )
    }

    fun runTool(ctx: Context, argsJson: String): ToolExecutionResult = runCatching { runToolInner(ctx, argsJson) }
        .getOrElse { ToolExecutionResult("Error: ${it.message}", false) }

    private fun runToolInner(ctx: Context, argsJson: String): ToolExecutionResult {
        val a = JSONObject(argsJson)
        val query = a.optString("query").trim()
        if (query.isEmpty()) return ToolExecutionResult("Error: query is required", false)
        val kbName = a.optString("knowledge_base").trim()
        val ids = if (kbName.isNotEmpty()) load(ctx).filter { it.name.equals(kbName, true) || it.name.contains(kbName, true) }.map { it.id }.ifEmpty { null } else null
        val hits = search(ctx, query, ids, a.optInt("top_k", 5))
        val title = a.optString("tool_title").ifBlank { "搜索知识库：$query" }
        if (hits.isEmpty()) return ToolExecutionResult("No passages matched \"$query\" in the knowledge bases. Try other key terms.", true, toolTitle = title)
        val sb = StringBuilder()
        hits.forEachIndexed { i, h ->
            sb.append("[${i + 1}] 「${h.kb.name}」· ${h.doc?.title ?: "?"} · 第 ${h.chunk.i + 1} 段 (score ${"%.2f".format(h.score)})")
            h.doc?.source?.takeIf { it.startsWith("http") }?.let { sb.append(" · ").append(it) }
            sb.append('\n').append(h.chunk.text.take(1200)).append("\n\n")
        }
        return ToolExecutionResult(sb.toString().trim(), true, toolTitle = title)
    }
}
