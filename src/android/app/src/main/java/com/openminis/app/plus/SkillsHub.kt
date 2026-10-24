package com.openminis.app.plus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * [Mines-38.9.1] user 2026-10-17: 技能市场 reads Hermes Agent's Skills Hub (https://hermes-agent.nousresearch.com/docs/skills),
 * the same index that page loads: docs/api/skills.json (~100 k skills, one 60 MB JSON array, gzip ~11 MB, rebuilt every
 * ~2 days) + skills-meta.json. The array is STREAM-parsed (JsonReader) straight into a local SQLite FTS4 table and then
 * paged with LIMIT/OFFSET — never held in memory. The download is conditional (If-None-Match ETag), so an unchanged index
 * costs one 304. Install = the skill folder (SKILL.md + files) into the same minis-global/skills path the GitHub market uses.
 * The GitHub-repo market stays as the fallback when the hub cannot be reached and nothing is cached.
 */
object SkillsHub {
    const val INDEX = "https://nousresearch.github.io/hermes-agent/docs/api/skills.json"
    const val META = "https://nousresearch.github.io/hermes-agent/docs/api/skills-meta.json"
    /** test hook (smoke): a small index on the runner */
    @Volatile var indexOverride: String? = null
    private const val PREFS = "mines_skills_hub"
    const val PAGE = 40

    data class Skill(val id: Long, val name: String, val desc: String, val category: String, val source: String,
                     val ident: String, val url: String, val author: String)

    /** Mines' Chinese filter → hub categories + words matched in name / description / tags */
    data class Cat(val label: String, val cats: List<String>, val words: List<String>)
    val CATS = listOf(
        Cat("搜索", listOf("research"), listOf("search", "research", "搜索", "检索")),
        Cat("文件", emptyList(), listOf("pdf", "docx", "excel", "xlsx", "file", "document", "markdown", "文件", "文档")),
        Cat("代码", listOf("software-development", "github", "devops", "mcp", "mlops"), listOf("code", "coding", "programming", "git", "代码", "编程")),
        Cat("浏览器", emptyList(), listOf("browser", "playwright", "puppeteer", "scrape", "scraping", "crawl", "浏览器", "网页")),
        Cat("图像", listOf("vision-ai", "creative"), listOf("image", "photo", "design", "diagram", "图片", "图像", "设计")),
        Cat("音频", emptyList(), listOf("audio", "speech", "tts", "voice", "music", "podcast", "语音", "音频")),
        Cat("视频", listOf("media"), listOf("video", "youtube", "ffmpeg", "视频")),
        Cat("生产力", listOf("productivity", "communication"), listOf("notes", "calendar", "email", "todo", "notion", "效率", "日程")),
        Cat("商业金融", listOf("blockchain", "real-estate"), listOf("finance", "trading", "stock", "crypto", "business", "marketing", "金融", "交易")),
        Cat("数据安全", listOf("security", "data-science"), listOf("security", "pentest", "vulnerability", "privacy", "data", "安全", "数据")),
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dbFile(c: Context) = File(c.filesDir, "skills_hub.db")
    @Volatile private var db: SQLiteDatabase? = null

    @Synchronized fun open(c: Context): SQLiteDatabase {
        db?.let { if (it.isOpen) return it }
        val d = SQLiteDatabase.openOrCreateDatabase(dbFile(c), null)
        d.execSQL("CREATE TABLE IF NOT EXISTS sk(id INTEGER PRIMARY KEY, name TEXT, description TEXT, tags TEXT, category TEXT, source TEXT, ident TEXT, url TEXT, author TEXT, rank INTEGER)")
        d.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS skf USING fts4(content='sk', name, description, tags, category, source)")
        db = d; return d
    }

    fun count(c: Context): Int = runCatching { open(c).rawQuery("SELECT COUNT(*) FROM sk", null).use { if (it.moveToFirst()) it.getInt(0) else 0 } }.getOrDefault(0)
    fun lastSync(c: Context): Long = prefs(c).getLong("at", 0L)
    fun meta(c: Context): String? = prefs(c).getString("meta", null)

    /** big registries first in the no-query list (curated sources are what people look for) */
    private fun rankOf(src: String): Int = when (src) {
        "built-in" -> 0; "optional" -> 1; "Anthropic" -> 2; "OpenAI" -> 3; "HuggingFace" -> 4; "NVIDIA" -> 5
        "gstack" -> 6; "GitHub" -> 7; "browse.sh" -> 8; "skills.sh" -> 9; "ClawHub" -> 10; else -> 11
    }

    /**
     * Refresh the local index. Returns a plain status line. Unchanged index (304) or a fresh one < 12 h old → no download.
     * [progress] gets the number of rows imported so far.
     */
    fun sync(c: Context, force: Boolean = false, progress: (Int) -> Unit = {}): String {
        val p = prefs(c)
        if (!force && count(c) > 0 && System.currentTimeMillis() - lastSync(c) < 12 * 3600_000L) return "已是最新"
        runCatching { (URL(META).openConnection() as HttpURLConnection).run { connectTimeout = 10000; readTimeout = 15000
            if (responseCode == 200) p.edit().putString("meta", inputStream.bufferedReader().readText().take(2000)).apply() } }
        val url = indexOverride ?: INDEX
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 45000
            if (!force && count(c) > 0) p.getString("etag", null)?.let { setRequestProperty("If-None-Match", it) }
        }
        val code = conn.responseCode
        if (code == 304) { p.edit().putLong("at", System.currentTimeMillis()).apply(); conn.disconnect(); return "已是最新（${count(c)} 个技能）" }
        if (code !in 200..299) { conn.disconnect(); throw java.io.IOException("Skills Hub 返回 HTTP $code") }
        val d = open(c)
        var n = 0
        d.beginTransaction()
        try {
            d.execSQL("DROP TABLE IF EXISTS skf"); d.execSQL("DELETE FROM sk")
            d.execSQL("CREATE VIRTUAL TABLE skf USING fts4(content='sk', name, description, tags, category, source)")
            val ins = d.compileStatement("INSERT INTO sk(name,description,tags,category,source,ident,url,author,rank) VALUES(?,?,?,?,?,?,?,?,?)")
            JsonReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var name = ""; var desc = ""; var tags = ""; var cat = ""; var src = ""; var ident = ""; var u = ""; var author = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        val k = r.nextName()
                        if (r.peek() == JsonToken.NULL) { r.nextNull(); continue }
                        when (k) {
                            "name" -> name = str(r); "description" -> desc = str(r).take(400)
                            "category" -> cat = str(r); "source" -> src = str(r)
                            "installIdentifier" -> ident = str(r); "sourceUrl" -> u = str(r); "author" -> author = str(r).take(80)
                            "tags" -> if (r.peek() == JsonToken.BEGIN_ARRAY) { val l = ArrayList<String>(); r.beginArray()
                                while (r.hasNext()) { if (r.peek() == JsonToken.STRING) l += r.nextString() else r.skipValue() }; r.endArray(); tags = l.joinToString(" ") } else r.skipValue()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (name.isBlank() || ident.isBlank()) continue
                    ins.clearBindings()
                    ins.bindString(1, name); ins.bindString(2, desc); ins.bindString(3, tags); ins.bindString(4, cat); ins.bindString(5, src)
                    ins.bindString(6, ident); ins.bindString(7, u); ins.bindString(8, author); ins.bindLong(9, rankOf(src).toLong())
                    ins.executeInsert(); n++
                    if (n % 2000 == 0) progress(n)
                }
                r.endArray()
            }
            d.execSQL("INSERT INTO skf(skf) VALUES('rebuild')")
            d.setTransactionSuccessful()
        } finally { d.endTransaction(); conn.disconnect() }
        p.edit().putString("etag", conn.getHeaderField("ETag")).putLong("at", System.currentTimeMillis()).apply()
        progress(n)
        return "已更新：$n 个技能"
    }

    /** a string field that is sometimes a list / object / number in the index (e.g. author: ["a", "b"]) */
    private fun str(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.BEGIN_ARRAY -> { val l = ArrayList<String>(); r.beginArray(); while (r.hasNext()) { if (r.peek() == JsonToken.STRING) l += r.nextString() else r.skipValue() }; r.endArray(); l.joinToString(", ") }
        else -> { r.skipValue(); "" }
    }

    private fun ascii(w: String) = w.all { it.code < 128 }
    private fun ftsTerm(w: String) = w.replace(Regex("[^A-Za-z0-9_-]"), " ").trim().split(Regex("[\\s-]+")).filter { it.isNotBlank() }
        .joinToString(" ") { "$it*" }

    /** One page. [query] free text (all words must match), [cat] one of [CATS] labels or null, [source] or null. */
    fun page(c: Context, query: String, cat: String?, source: String?, offset: Int, limit: Int = PAGE): List<Skill> {
        val d = open(c)
        val where = ArrayList<String>(); val args = ArrayList<String>()
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        // FTS4's tokenizer does not split Chinese: Latin words go through the index, Chinese ones through LIKE
        words.filter { ascii(it) }.map { ftsTerm(it) }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { l ->
            where += "sk.id IN (SELECT docid FROM skf WHERE skf MATCH ?)"; args += l.joinToString(" ") }
        words.filter { !ascii(it) }.forEach { w -> where += "(sk.name LIKE ? OR sk.description LIKE ? OR sk.tags LIKE ?)"; repeat(3) { args += "%$w%" } }
        CATS.firstOrNull { it.label == cat }?.let { k ->
            val parts = ArrayList<String>()
            if (k.cats.isNotEmpty()) parts += "sk.category IN (" + k.cats.joinToString(",") { "'$it'" } + ")"
            val latin = k.words.filter { ascii(it) }.map { ftsTerm(it) }.filter { it.isNotBlank() }
            if (latin.isNotEmpty()) { parts += "sk.id IN (SELECT docid FROM skf WHERE skf MATCH ?)"; args += latin.joinToString(" OR ") }
            k.words.filter { !ascii(it) }.forEach { w -> parts += "sk.description LIKE ?"; args += "%$w%" }
            where += "(" + parts.joinToString(" OR ") + ")"
        }
        if (!source.isNullOrBlank()) { where += "sk.source = ?"; args += source }
        val sql = "SELECT id,name,description,category,source,ident,url,author FROM sk" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY rank, id LIMIT $limit OFFSET $offset"
        return d.rawQuery(sql, args.toTypedArray()).use { cur ->
            val out = ArrayList<Skill>()
            while (cur.moveToNext()) out += Skill(cur.getLong(0), cur.getString(1), cur.getString(2), cur.getString(3), cur.getString(4),
                cur.getString(5), cur.getString(6), cur.getString(7))
            out
        }
    }

    fun sources(c: Context): List<Pair<String, Int>> = runCatching {
        open(c).rawQuery("SELECT source, COUNT(*) FROM sk GROUP BY source ORDER BY MIN(rank)", null).use { cur ->
            val l = ArrayList<Pair<String, Int>>(); while (cur.moveToNext()) l += cur.getString(0) to cur.getInt(1); l }
    }.getOrDefault(emptyList())

    // ---------------- install ----------------

    fun skillsDir(c: Context) = File(c.filesDir, "minis-global/skills")
    fun dirName(s: Skill) = s.ident.substringAfterLast('/').ifBlank { s.name }.replace(Regex("[^A-Za-z0-9._-]"), "-").take(64).ifBlank { "skill-${s.id}" }

    fun installedSource(c: Context, s: Skill): String? {
        val d = File(skillsDir(c), dirName(s)); if (!d.isDirectory) return null
        return File(d, ".mines-source").takeIf { it.isFile }?.readText()?.trim() ?: "local"
    }

    /** (owner, repo, branch, path) of the skill folder on GitHub, or null when the source is not a GitHub folder */
    internal fun githubDir(s: Skill): List<String>? {
        Regex("^https://github\\.com/([^/]+)/([^/]+)/(?:tree|blob)/([^/]+)/(.+?)(?:/SKILL\\.md)?/?$").find(s.url)?.let { m ->
            return listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) }
        val id = s.ident
        if (s.source == "built-in" && id.startsWith("NousResearch/hermes-agent/")) return listOf("NousResearch", "hermes-agent", "main", id.removePrefix("NousResearch/hermes-agent/"))
        if (s.source == "optional" && id.startsWith("official/")) return listOf("NousResearch", "hermes-agent", "main", "optional-skills/" + id.removePrefix("official/"))
        return null
    }

    private fun get(url: String, timeout: Int = 20000): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 12000; readTimeout = timeout; setRequestProperty("User-Agent", "Mines"); setRequestProperty("Accept", "application/vnd.github+json, */*")
    }

    private fun enc(p: String) = p.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** Download the skill folder into minis-global/skills/<name>. Returns a plain result line. */
    fun install(c: Context, s: Skill): String {
        val name = dirName(s)
        val src0 = installedSource(c, s)
        if (src0 == "local") return "已经有一个同名的本地技能「$name」，没有覆盖"
        val tmp = File(c.cacheDir, "hub-dl-$name").apply { deleteRecursively(); mkdirs() }
        try {
            val gh = githubDir(s)
            when {
                gh != null -> { var files = 0
                    fun walk(path: String, depth: Int) {
                        if (depth > 3 || files > 120) return
                        val conn = get("https://api.github.com/repos/${gh[0]}/${gh[1]}/contents/${enc(path)}?ref=${gh[2]}")
                        if (conn.responseCode !in 200..299) throw java.io.IOException("GitHub 返回 HTTP ${conn.responseCode}")
                        val arr = org.json.JSONArray(conn.inputStream.bufferedReader().readText())
                        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                            val rel = o.optString("path").removePrefix(gh[3]).trimStart('/')
                            if (rel.contains("..")) continue
                            if (o.optString("type") == "dir") walk(o.optString("path"), depth + 1)
                            else if (o.optString("type") == "file" && o.optLong("size") < 2_000_000) {
                                val dl = o.optString("download_url").ifBlank { null } ?: continue
                                val f = File(tmp, rel).apply { parentFile?.mkdirs() }
                                get(dl).inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }; files++
                            }
                        }
                    }
                    walk(gh[3], 0)
                }
                s.source == "ClawHub" -> {
                    val slug = s.ident.removePrefix("clawhub/")
                    val conn = get("https://clawhub.ai/api/v1/download?slug=" + URLEncoder.encode(slug, "UTF-8"), 30000)
                    if (conn.responseCode !in 200..299) throw java.io.IOException("ClawHub 返回 HTTP ${conn.responseCode}")
                    java.util.zip.ZipInputStream(conn.inputStream).use { z ->
                        var e = z.nextEntry
                        while (e != null) {
                            val rel = e.name.trimStart('/')
                            if (!e.isDirectory && !rel.contains("..")) File(tmp, rel).apply { parentFile?.mkdirs() }.outputStream().use { z.copyTo(it) }
                            e = z.nextEntry
                        }
                    }
                }
                s.source == "skills.sh" -> {
                    // skills-sh/<owner>/<repo>/<skill>: the folder whose SKILL.md is named <skill> in that GitHub repo
                    val p = s.ident.removePrefix("skills-sh/").split('/')
                    if (p.size < 3) throw java.io.IOException("看不懂这个 skills.sh 地址")
                    val tree = org.json.JSONObject(get("https://api.github.com/repos/${p[0]}/${p[1]}/git/trees/HEAD?recursive=1").inputStream.bufferedReader().readText()).optJSONArray("tree")
                        ?: throw java.io.IOException("读不到仓库 ${p[0]}/${p[1]}")
                    val dirs = (0 until tree.length()).map { tree.getJSONObject(it).optString("path") }.filter { it.endsWith("SKILL.md") }.map { it.removeSuffix("SKILL.md").trimEnd('/') }
                    val dir = dirs.firstOrNull { it.substringAfterLast('/') == p[2] } ?: dirs.firstOrNull() ?: throw java.io.IOException("仓库里没有 SKILL.md")
                    val one = Skill(s.id, s.name, s.desc, s.category, "GitHub", s.ident, "https://github.com/${p[0]}/${p[1]}/tree/HEAD/$dir", s.author)
                    tmp.deleteRecursively(); return install(c, one)
                }
                else -> return "「${s.source}」的条目不是 SKILL.md 技能，没法装"
            }
            if (!File(tmp, "SKILL.md").isFile) { return "下载失败（没拿到 SKILL.md）" }
            File(tmp, ".mines-source").writeText("hub:${s.source}:${s.ident}\n")
            val target = File(skillsDir(c), name)
            target.deleteRecursively(); target.parentFile?.mkdirs()
            if (!tmp.renameTo(target)) { tmp.copyRecursively(target, overwrite = true) }
            (c.applicationContext as? com.openminis.app.MinisApp)?.skillRepository?.requestReload("mines-hub", force = true)
            return if (src0 == null) "已安装「$name」" else "已更新「$name」"
        } finally { tmp.deleteRecursively() }
    }

    fun uninstall(c: Context, s: Skill): String {
        if (installedSource(c, s) == "local") return "这是本地技能，请在 设置 → 技能 里删"
        File(skillsDir(c), dirName(s)).deleteRecursively()
        (c.applicationContext as? com.openminis.app.MinisApp)?.skillRepository?.requestReload("mines-hub", force = true)
        return "已卸载「${dirName(s)}」"
    }
}
