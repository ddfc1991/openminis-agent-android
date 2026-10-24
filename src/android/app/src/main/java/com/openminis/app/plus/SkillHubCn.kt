package com.openminis.app.plus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * [Mines-38.9.4] user 2026-10-17 「dsh的插件没换成skillhub？」: 市场 reads Tencent SkillHub (skillhub.cn, hosted in China,
 * public no-auth API at api.skillhub.cn) — ~12 k DSH plugins and skills, fast on a CN network. Semantics follow
 * Tencent/skillhub dsh-plugin (src/plugin-market.ts, src/api.ts, src/install.ts):
 *  - plugins: GET /api/v1/plugins?q&scope=verified|all&sort=stars|updated&category&page&page_size → {items,total,page,pageSize};
 *    only installability == "verified" is one-tap; install = GET /api/v1/plugins/{owner}/{name}/install-plan → a pinned
 *    `github:owner/repo#sha` source (checked against the chosen plugin, --force refused) → `minis-dsh add <source>`;
 *  - skills: GET /api/skills?keyword&category&sortBy=downloads|score&order=desc&page&pageSize → {code:0,data:{skills,total}};
 *    install = GET /api/v1/download?slug=…&source=dsh (zip, common top dir stripped, unsafe paths refused) into Mines' skills
 *    dir and $DSH_HOME/skills; signature (when published) = /api/v1/open/skills/{slug}/versions/{v}/signature, its
 *    content_hash is compared with the SHA-256 of the downloaded zip.
 * Catalog pages are cached 1 h (memory + cacheDir), nothing blocks the UI (callers run on IO).
 */
object SkillHubCn {
    const val API = "https://api.skillhub.cn"
    const val WEB = "https://skillhub.cn"
    const val PAGE = 24
    private const val TTL = 60 * 60 * 1000L

    val PLUGIN_CATEGORIES = linkedMapOf(
        "model-inference" to "模型推理", "web-tools" to "联网工具", "memory" to "记忆", "agent-workflow" to "工作流",
        "client" to "客户端", "admin-security" to "安全管理", "fun-dressup" to "趣味换装",
    )
    val SKILL_CATEGORIES = linkedMapOf(
        "office-efficiency" to "办公效率", "content-creation" to "内容创作", "dev-programming" to "开发编程",
        "data-analysis" to "数据分析", "design-media" to "设计多媒体", "ai-agent" to "AI Agent", "knowledge-management" to "知识管理",
        "business-ops" to "商业运营", "education" to "教育学习", "professional" to "行业专业", "it-ops-security" to "IT 运维与安全",
        "life-service" to "生活服务",
    )

    data class Plugin(
        val owner: String, val name: String, val fullName: String, val description: String, val stars: Int,
        val category: String, val verified: Boolean, val repo: String, val avatar: String,
        /** [Mines-38.9.7] GitHub topics + SkillHub installability (verified / unsupported …) for the row's reason line */
        val topics: List<String> = emptyList(), val installability: String = "",
    ) {
        val kind: MarketGitCore.Kind get() = MarketGitCore.classify(name, description, topics, category, if (verified) "verified" else installability)
        val categoryLabel: String get() = PLUGIN_CATEGORIES[category] ?: ""
    }

    data class Skill(
        val slug: String, val name: String, val description: String, val category: String, val version: String,
        val downloads: Long, val stars: Int, val icon: String, val publisher: String, val publisherVerified: Boolean,
        /** [Mines-38.9.4 h4] namespace handle (@handle/slug): several publishers share a slug (3× "weather"), and the
         *  download endpoint without it serves another publisher's package — version 1.0.1 of "weather" was a 404. */
        val namespace: String = "",
    ) {
        val categoryLabel: String get() = SKILL_CATEGORIES[category] ?: category
    }

    data class Page<T>(val items: List<T>, val total: Int, val fromCache: Boolean = false, /** [38.9.7] rows dropped as not runnable on the phone */ val hidden: Int = 0,
        /** [Mines-38.9.8] plugins that only work on a computer (shown under 「只能电脑用」 when 手机能用 is off) */ val desktop: List<T> = emptyList())

    // ── parsing (pure, unit-tested with fixtures saved from the live API) ──

    private val OWNER = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
    private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val SLUG = Regex("^[a-z0-9][a-z0-9_-]{0,127}$", RegexOption.IGNORE_CASE)
    private val PIN = Regex("^github:([A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100})#([0-9a-f]{7,40})$", RegexOption.IGNORE_CASE)

    private fun safeHttps(u: String): String = u.trim().takeIf { it.startsWith("https://", true) && it.length <= 500 && it.none { c -> c.isWhitespace() || c in "<>\"'`" } } ?: ""

    fun parsePlugins(body: String): Page<Plugin> {
        val o = JSONObject(body)
        val arr = o.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<Plugin>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optBoolean("banned", false)) continue
            val owner = r.optString("owner").trim(); val name = r.optString("name").trim()
            if (!OWNER.matches(owner) || !NAME.matches(name)) continue
            val repo = r.optString("repositoryUrl").takeIf { it.startsWith("https://github.com/", true) }.orEmpty()
            out += Plugin(owner, name, r.optString("fullName", "$owner/$name").take(200), r.optString("description").take(500).trim(),
                r.optInt("stars"), r.optString("categoryKey").takeIf { it in PLUGIN_CATEGORIES }.orEmpty(),
                r.optString("installability") == "verified", repo, safeHttps(r.optString("avatarUrl")),
                r.optJSONArray("topics")?.let { t -> (0 until t.length()).map { t.optString(it) }.filter { it.isNotBlank() }.take(30) } ?: emptyList(),
                r.optString("installability"))
        }
        // [Mines-38.9.7] user 21:27 「做面板那种跑不了的，你不要放进来呀」: only what installs and runs in the phone sandbox
        val runnable = out.filter { it.kind.canTry }
        // [Mines-38.9.8] user 01:22: phone-usable first, computer-only ones grouped under 「只能电脑用」 (prompt / doc packs too:
        // they can be turned into a phone skill there)
        val desktop = out.filter { !it.kind.canTry && (it.kind.desktopOnly || it.kind.convertible) }
        return Page(runnable, o.optInt("total", out.size), hidden = out.size - runnable.size, desktop = desktop)
    }

    fun parseSkills(body: String): Page<Skill> {
        val o = JSONObject(body)
        if (o.optInt("code", -1) != 0) throw IllegalStateException(o.optString("message").ifBlank { "SkillHub 返回错误" })
        val d = o.optJSONObject("data") ?: throw IllegalStateException("SkillHub 返回错误")
        val arr = d.optJSONArray("skills") ?: JSONArray()
        val out = ArrayList<Skill>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val slug = r.optString("slug").ifBlank { r.optJSONObject("namespace")?.optString("publicSlug").orEmpty() }.trim()
            if (!SLUG.matches(slug)) continue
            val zh = r.optString("description_zh").takeIf { it != "null" }.orEmpty().trim()
            val pub = r.optJSONObject("publisher")
            out += Skill(slug.lowercase(), r.optString("name").ifBlank { slug }.trim(), zh.ifBlank { r.optString("description").takeIf { it != "null" }.orEmpty().trim() },
                r.optString("category"), r.optString("version").takeIf { it != "null" }.orEmpty(), r.optLong("downloads"), r.optInt("stars"),
                safeHttps(r.optString("iconUrl")), pub?.optString("certifiedName")?.takeIf { it.isNotBlank() && it != "null" } ?: pub?.optString("name")?.takeIf { it != "null" }.orEmpty(),
                pub?.optBoolean("verified", false) == true,
                r.optJSONObject("namespace")?.optString("handle")?.trim()?.takeIf { it != "null" && SLUG.matches(it) }.orEmpty())
        }
        return Page(out, d.optInt("total", out.size))
    }

    /** install-plan → the pinned `github:owner/repo#sha` source, refusing anything that does not match the chosen plugin. */
    fun resolveInstallSource(planBody: String, owner: String, name: String): String {
        val plan = JSONObject(planBody)
        val command = plan.optString("command")
        if (Regex("(^|\\s)(--force|-f)(\\s|$)").containsMatchIn(command)) throw IllegalStateException("拒绝带 --force 的安装计划")
        val profile = plan.optString("profile", "web").ifBlank { "web" }
        if (profile != "web") throw IllegalStateException("安装计划不是 web profile")
        var source = plan.optString("source").trim()
        if (source.isEmpty()) source = Regex("^dsh plugin --profile web add (\\S+)$").find(command.trim())?.groupValues?.get(1)
            ?: throw IllegalStateException("安装计划没有可用的来源")
        val m = PIN.find(source) ?: throw IllegalStateException("安装计划来源不是固定版本的 GitHub 仓库")
        if (!m.groupValues[1].equals("$owner/$name", ignoreCase = true)) throw IllegalStateException("安装计划的仓库和所选插件不一致")
        val head = plan.optJSONObject("plugin")?.optString("headSha").orEmpty().lowercase()
        val sha = m.groupValues[2].lowercase()
        if (head.isNotBlank() && !head.startsWith(sha) && !sha.startsWith(head)) throw IllegalStateException("安装计划的版本和来源不一致")
        return source
    }

    /**
     * signature JSON → the MD5 of the package zip the platform signed (payload.package_md5), null when unsigned / absent.
     * (content_hash is over the unpacked files with an unpublished scheme; package_md5 is checked against the exact zip bytes.)
     */
    fun parseSignatureMd5(body: String): String? = runCatching {
        val o = JSONObject(body)
        if (!o.optBoolean("signed", false)) return@runCatching null
        JSONObject(o.optString("payload")).optString("package_md5").lowercase().takeIf { it.matches(Regex("^[0-9a-f]{32}$")) }
    }.getOrNull()

    /** zip entries with the common top directory removed; null for an unsafe path. */
    fun normalizeEntry(path: String, prefix: String): String? {
        val p = path.replace('\\', '/').let { if (prefix.isNotEmpty() && it.startsWith(prefix)) it.substring(prefix.length) else it }
        if (p.isEmpty() || p.endsWith("/")) return null
        if (p.startsWith("/") || p.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return p
    }

    fun commonTopDir(names: List<String>): String {
        val files = names.filter { !it.endsWith("/") }
        if (files.isEmpty()) return ""
        val first = files.first().substringBefore('/', "")
        if (first.isEmpty() || files.any { !it.startsWith("$first/") }) return ""
        return "$first/"
    }

    fun pluginsUrl(q: String, category: String?, sort: String, page: Int, verifiedOnly: Boolean): String = buildString {
        append("$API/api/v1/plugins?")
        if (q.isNotBlank()) append("q=").append(URLEncoder.encode(q.trim(), "UTF-8")).append('&')
        append("scope=").append(if (verifiedOnly) "verified" else "all")
        append("&sort=").append(if (sort == "updated") "updated" else "stars")
        if (category != null && category in PLUGIN_CATEGORIES) append("&category=").append(category)
        append("&page=").append(page.coerceAtLeast(1)).append("&page_size=").append(PAGE)
    }

    fun skillsUrl(q: String, category: String?, page: Int): String = buildString {
        append("$API/api/skills?")
        if (q.isNotBlank()) append("keyword=").append(URLEncoder.encode(q.trim(), "UTF-8")).append('&')
        if (category != null && category in SKILL_CATEGORIES) append("category=").append(category).append('&')
        append("sortBy=").append(if (q.isBlank()) "downloads" else "score").append("&order=desc")
        append("&page=").append(page.coerceAtLeast(1)).append("&pageSize=").append(PAGE)
    }

    // ── network + 1 h cache ──

    private val mem = ConcurrentHashMap<String, Pair<Long, String>>()

    private fun open(url: String, timeout: Int): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeout; readTimeout = timeout
        setRequestProperty("User-Agent", "Mines-SkillHub/1.0")
        setRequestProperty("Accept", "application/json")
    }

    fun get(url: String, timeout: Int = 12_000): String {
        val c = open(url, timeout)
        val code = c.responseCode
        if (code == 429) throw IllegalStateException("SkillHub 限流了，过一会再试")
        if (code !in 200..299) throw IllegalStateException("SkillHub HTTP $code")
        return c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private fun cacheFile(ctx: Context?, url: String): File? = ctx?.let { File(it.cacheDir, "skillhub").apply { mkdirs() }.resolve(Integer.toHexString(url.hashCode()) + ".json") }

    /** Fresh copy within 1 h, else network; network failure falls back to any cached copy (marked fromCache). */
    private fun cached(ctx: Context?, url: String, force: Boolean): Pair<String, Boolean> {
        val now = System.currentTimeMillis()
        if (!force) {
            mem[url]?.let { if (now - it.first < TTL) return it.second to false }
            cacheFile(ctx, url)?.takeIf { it.isFile && now - it.lastModified() < TTL }?.let { f -> val s = f.readText(); mem[url] = f.lastModified() to s; return s to false }
        }
        return try {
            val s = get(url)
            mem[url] = now to s
            runCatching { cacheFile(ctx, url)?.writeText(s) }
            s to false
        } catch (e: Exception) {
            val old = mem[url]?.second ?: cacheFile(ctx, url)?.takeIf { it.isFile }?.readText() ?: throw e
            old to true
        }
    }

    fun plugins(ctx: Context?, q: String, category: String?, sort: String, page: Int, verifiedOnly: Boolean = false, force: Boolean = false): Page<Plugin> {
        val (s, stale) = cached(ctx, pluginsUrl(q, category, sort, page, verifiedOnly), force)
        return parsePlugins(s).copy(fromCache = stale)
    }

    fun skills(ctx: Context?, q: String, category: String?, page: Int, force: Boolean = false): Page<Skill> {
        val (s, stale) = cached(ctx, skillsUrl(q, category, page), force)
        return parseSkills(s).copy(fromCache = stale)
    }

    // ── install ──

    /** Resolves the pinned source of a verified plugin; the caller runs `minis-dsh add <source>`. */
    fun installSource(p: Plugin): String {
        if (!OWNER.matches(p.owner) || !NAME.matches(p.name)) throw IllegalStateException("插件名不合法")
        val plan = get("$API/api/v1/plugins/${URLEncoder.encode(p.owner, "UTF-8")}/${URLEncoder.encode(p.name, "UTF-8")}/install-plan", 15_000)
        return resolveInstallSource(plan, p.owner, p.name)
    }

    /** Is a market plugin among the installed DSH packages? (plugins.json package names + package.json github specs) */
    fun isInstalled(p: Plugin, installedPkgs: Set<String>, specs: Map<String, String>): Boolean {
        val repo = "${p.owner}/${p.name}".lowercase()
        if (specs.values.any { v -> v.lowercase().removePrefix("github:").substringBefore('#').removeSuffix(".git").let { it == repo || it.endsWith("github.com/$repo") } }) return true
        return installedPkgs.any { it.equals(p.name, true) || it.substringAfterLast('/').equals(p.name, true) }
    }

    fun skillsDirs(ctx: Context): List<File> = listOf(
        File(ctx.filesDir, "minis-global/skills"),
        File(com.openminis.app.sandbox.RootfsManager.getInstance(ctx).rootfsDir, "root/.dsh/skills"),
    )

    fun installedSkill(ctx: Context, slug: String): String? {
        val d = File(skillsDirs(ctx)[0], slug)
        if (!d.isDirectory) return null
        return File(d, ".mines-source").takeIf { it.isFile }?.readText()?.trim() ?: "local"
    }

    /** [Mines-38.9.5 i] this row's state: installed from THIS publisher, another publisher's same-slug skill, local, none. */
    fun skillState(ctx: Context, s: Skill): SkillInstallKey.State = SkillInstallKey.state(installedSkill(ctx, s.slug), s.namespace, s.slug)

    /** Download the zip, verify the signature hash when published, unpack into both skills dirs. Returns a one-line result.
     *  [replace] = the user tapped 替换 on a row whose slug is held by another publisher's skill (never silently). */
    fun installSkill(ctx: Context, s: Skill, replace: Boolean = false): String {
        if (!SLUG.matches(s.slug)) throw IllegalStateException("技能名不合法")
        val prev = installedSkill(ctx, s.slug)
        if (prev == "local") return "已经有一个同名的本地技能「${s.slug}」，没有覆盖"
        val st = SkillInstallKey.state(prev, s.namespace, s.slug)
        if (st == SkillInstallKey.State.OTHER && !replace) return "已装了另一位作者的同名技能「${s.slug}」，没有覆盖（要换成这个请点「替换」）"
        val c = open("$API/api/v1/download?slug=${URLEncoder.encode(s.slug, "UTF-8")}" + (if (s.namespace.isNotBlank()) "&namespace=" + URLEncoder.encode(s.namespace, "UTF-8") else "") + (if (s.version.isNotBlank()) "&version=" + URLEncoder.encode(s.version, "UTF-8") else "") + "&source=dsh", 30_000)
        c.setRequestProperty("Accept", "application/zip, application/octet-stream")
        if (c.responseCode !in 200..299) throw IllegalStateException("下载失败（HTTP ${c.responseCode}）")
        val bytes = c.inputStream.use { it.readBytes() }
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) throw IllegalStateException("SkillHub 给的不是压缩包")
        if (bytes.size > 30 * 1024 * 1024) throw IllegalStateException("技能包太大（超过 30 MB）")
        var signed = "未签名"
        if (s.version.isNotBlank()) runCatching { get("$API/api/v1/open/skills/${s.slug}/versions/${URLEncoder.encode(s.version, "UTF-8")}/signature" + (if (s.namespace.isNotBlank()) "?namespace=" + URLEncoder.encode(s.namespace, "UTF-8") else ""), 10_000) }.getOrNull()
            ?.let { parseSignatureMd5(it) }?.let { want ->
                val got = java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
                if (got != want) throw IllegalStateException("签名校验没通过（包被改过或下载不完整），没有安装")
                signed = "平台签名校验通过"
            }
        // unpack
        val entries = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) { if (!e.isDirectory) entries += e.name to z.readBytes(); e = z.nextEntry }
        }
        val prefix = commonTopDir(entries.map { it.first })
        val files = entries.mapNotNull { (n, b) -> normalizeEntry(n, prefix)?.let { it to b } }
        if (files.none { it.first == "SKILL.md" }) throw IllegalStateException("技能包里没有 SKILL.md")
        for (root in skillsDirs(ctx)) runCatching {
            root.mkdirs()
            val tmp = File(root, ".tmp-${s.slug}").apply { deleteRecursively(); mkdirs() }
            for ((rel, b) in files) File(tmp, rel).apply { parentFile?.mkdirs() }.writeBytes(b)
            File(tmp, ".mines-source").writeText(SkillInstallKey.source(s.namespace, s.slug, s.version) + "\n")   // [Mines-38.9.5 i] + publisher
            val target = File(root, s.slug)
            target.deleteRecursively()
            if (!tmp.renameTo(target)) { tmp.copyRecursively(target, overwrite = true); tmp.deleteRecursively() }
        }
        (ctx.applicationContext as? com.openminis.app.MinisApp)?.skillRepository?.requestReload("skillhub", force = true)
        return (if (prev == null) "已安装「${s.name}」" else if (st == SkillInstallKey.State.OTHER) "已替换为「${s.name}」" else "已更新「${s.name}」") + " · $signed"
    }

    fun uninstallSkill(ctx: Context, slug: String): String {
        if (installedSkill(ctx, slug) == "local") return "这是本地技能，请在 设置 → 技能 里删"
        skillsDirs(ctx).forEach { File(it, slug).deleteRecursively() }
        (ctx.applicationContext as? com.openminis.app.MinisApp)?.skillRepository?.requestReload("skillhub", force = true)
        return "已卸载「$slug」"
    }

    /**
     * [Mines-38.9.8] 「转成手机技能」: README (+ up to 4 prompt / doc markdown files) of a plugin repo → SKILL.md in both
     * skills dirs. Refused for plugins that need desktop control. Returns a one-line result.
     */
    fun convertToSkill(ctx: Context, p: Plugin): String {
        MarketGitCore.platformOnly(p.name, p.description, p.topics)?.let { return "转不了：" + MarketGitCore.desktopLine(it).replace("这个插件", "它") + "（要操控电脑，不是提示词或说明）" }
        if (!OWNER.matches(p.owner) || !NAME.matches(p.name)) throw IllegalStateException("仓库名不合法")
        val raw = "https://raw.githubusercontent.com/${p.owner}/${p.name}/HEAD/"
        val docs = ArrayList<Pair<String, String>>()
        for (f in listOf("README_zh.md", "README.zh-CN.md", "README.md", "readme.md")) {
            val t = runCatching { get(raw + f, 15_000) }.getOrNull()
            if (!t.isNullOrBlank() && !t.trimStart().startsWith("404")) { docs += f to t; if (f.startsWith("README.") || f == "readme.md") break }
        }
        // prompt / doc files at the top level or in prompts/ docs/ (GitHub contents API, no token: 60 / h is plenty here)
        for (dir in listOf("", "prompts", "docs", "skills")) runCatching {
            val arr = JSONArray(get("https://api.github.com/repos/${p.owner}/${p.name}/contents/$dir", 12_000))
            for (i in 0 until arr.length()) {
                if (docs.size >= 6) break
                val o = arr.getJSONObject(i); val n = o.optString("name"); val path = o.optString("path")
                if (o.optString("type") != "file" || !Regex("(?i).*\\.(md|txt)$").matches(n) || n.lowercase().startsWith("readme") || n.lowercase() in setOf("license.md", "changelog.md", "contributing.md")) continue
                if (o.optInt("size") > 200_000) continue
                runCatching { get(raw + path, 12_000) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { docs += path to it }
            }
        }
        val md = MarketGitCore.skillMd(p.owner, p.name, p.description, p.repo, docs) ?: return "转不了：仓库里没有能用的说明 / 提示词文件"
        val slug = MarketGitCore.skillSlug(p.name)
        if (installedSkill(ctx, slug) == "local") return "已经有一个同名的本地技能「$slug」，没有覆盖"
        for (root in skillsDirs(ctx)) runCatching {
            val d = File(root, slug).apply { deleteRecursively(); mkdirs() }
            File(d, "SKILL.md").writeText(md)
            File(d, ".mines-source").writeText("github:${p.owner}/${p.name}#converted\n")
        }
        (ctx.applicationContext as? com.openminis.app.MinisApp)?.skillRepository?.requestReload("skillhub", force = true)
        return "已转成手机技能「$slug」（用了 ${docs.size} 个说明文件）"
    }
}
