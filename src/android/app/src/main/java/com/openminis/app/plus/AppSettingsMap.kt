package com.openminis.app.plus

import android.content.Context
import org.json.JSONObject

/**
 * [Mines-38.9] Mines knows its own settings (user 2026-10-16: asked about 「服务器登录」 it ran `ls /var/minis` and searched
 * the knowledge base, while it lives in 设置 › 网络 › 电脑与服务器 = 工作位置 page). The map is generated from the real
 * settings code (r389/gen_settings_map.py → assets/mines/settings_map.tsv: path, route, page title, what is on the page,
 * subtitle) plus live names (servers, mailboxes, enabled agents). A compact index goes into the system prompt; the
 * `app_settings` tool searches it and can open the page for the user.
 */
object AppSettingsMap {
    const val TOOL_NAME = "app_settings"

    data class Row(val path: String, val route: String, val page: String, val contents: String, val sub: String)

    @Volatile private var rows: List<Row>? = null

    fun rows(c: Context): List<Row> = rows ?: runCatching {
        c.assets.open("mines/settings_map.tsv").bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { l -> l.split('\t').let { f -> Row(f.getOrElse(0) { "" }, f.getOrElse(1) { "" }, f.getOrElse(2) { "" }, f.getOrElse(3) { "" }, f.getOrElse(4) { "" }) } }
    }.getOrDefault(emptyList()).also { rows = it }

    /** Live things that sit on those pages (names only, never secrets). */
    private fun live(c: Context): Map<String, String> = buildMap {
        runCatching {
            PlusPrefs.init(c)
            val sv = PlusPrefs.state.value.servers.map { it.name }.filter { it.isNotBlank() }
            if (sv.isNotEmpty()) put(com.openminis.app.ui.plus.PlusRoutes.WORK, "已加的服务器：" + sv.joinToString("、") + "（点进去是服务器详情：地址、登录/授权、测试连接、终端）")
        }
        runCatching {
            val mb = MailInbox.accounts.value.map { it.address }
            if (mb.isNotEmpty()) put(com.openminis.app.ui.plus.PlusRoutes.DIGEST, "邮箱：" + mb.joinToString("、"))
        }
        runCatching {
            val on = AppAgents.enabledNames(c)
            if (on.isNotEmpty()) put(com.openminis.app.ui.plus.PlusRoutes.AGENTS, "已启用：" + on.joinToString("、"))
        }
    }

    /** Compact index for the system prompt (one line per entry, ~1.2 KB). */
    fun promptBlock(c: Context): String {
        val r = rows(c)
        val sb = StringBuilder("Mines app settings (this app's own pages; to find or open one call app_settings — never search the file system or knowledge base for app settings):\n")
        r.filter { it.route.isNotBlank() && !it.route.startsWith("settings#") }.forEach { x ->
            sb.append("- ").append(x.path.removePrefix("设置 › ")).append(if (x.page.isNotBlank() && !x.path.endsWith(x.page)) "（页面：${x.page}）" else "").append('\n')
        }
        live(c)[com.openminis.app.ui.plus.PlusRoutes.WORK]?.let { sb.append("- 电脑与服务器 ").append(it.substringBefore('（')).append('\n') }
        return sb.toString().trimEnd()
    }

    fun definition() = com.openminis.app.data.model.AgentToolDefinition(
        name = TOOL_NAME,
        description = "Find where something is in Mines' own settings (e.g. 服务器登录, 代理, 声纹, 支付卡, 主题色) and optionally open that page for the user. " +
            "Returns the menu path, the page title and what is on it. Use this instead of shell / file / knowledge-base searches for anything about this app's settings.",
        parameters = mapOf(
            "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
            "query" to com.openminis.app.data.model.AgentToolParam("string", "What the user is looking for, in their words"),
            "open" to com.openminis.app.data.model.AgentToolParam("boolean", "true = also open the best matching page on screen"),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "open"),
    )

    /** Ranked rows for a query (char bigrams over path + page + contents + subtitle + live names). */
    fun search(c: Context, q: String, k: Int = 4): List<Pair<Row, String>> {
        val lv = live(c)
        val docs = rows(c).map { it to (lv[it.route] ?: "") }
        val qt = com.openminis.app.agent.ExperienceCore.tokens(q).distinct()
        if (qt.isEmpty()) return emptyList()
        return docs.map { (r, l) ->
            val head = com.openminis.app.agent.ExperienceCore.tokens(r.path + " " + r.page).toSet()
            val body = com.openminis.app.agent.ExperienceCore.tokens(r.contents + " " + r.sub + " " + l).toSet()
            Triple(r, l, qt.sumOf { (if (it in head) 3.0 else 0.0) + (if (it in body) 1.0 else 0.0) })
        }.filter { it.third > 0 }.sortedByDescending { it.third }.take(k).map { it.first to it.second }
    }

    fun runTool(c: Context, argsJson: String): com.openminis.app.tools.ToolExecutionResult = runCatching {
        val a = JSONObject(argsJson)
        val q = a.optString("query")
        val hits = search(c, q)
        if (hits.isEmpty()) return@runCatching com.openminis.app.tools.ToolExecutionResult("No settings entry matches「$q」. Top-level 设置 groups: " +
            rows(c).map { it.path.split(" › ").getOrElse(1) { "" } }.distinct().joinToString(" / "), true)
        val sb = StringBuilder()
        hits.forEachIndexed { i, (r, l) ->
            sb.append(i + 1).append(". ").append(r.path)
            if (r.page.isNotBlank()) sb.append("  → 页面「").append(r.page).append("」")
            sb.append('\n')
            if (r.sub.isNotBlank()) sb.append("   说明：").append(r.sub).append('\n')
            if (r.contents.isNotBlank()) sb.append("   页面上有：").append(r.contents.take(220)).append('\n')
            if (l.isNotBlank()) sb.append("   ").append(l).append('\n')
        }
        val best = hits.first().first
        if (a.optBoolean("open", false) && best.route.isNotBlank()) {
            val route = if (best.route.startsWith("settings#")) "settings" else best.route
            android.os.Handler(android.os.Looper.getMainLooper()).post { com.openminis.app.ui.plus.PlusNav.go(route) }
            sb.append("已在屏幕上打开：").append(best.path).append('\n')
        }
        com.openminis.app.tools.ToolExecutionResult(sb.toString().trimEnd(), true)
    }.getOrElse { com.openminis.app.tools.ToolExecutionResult("Error: ${it.message}", false) }
}
