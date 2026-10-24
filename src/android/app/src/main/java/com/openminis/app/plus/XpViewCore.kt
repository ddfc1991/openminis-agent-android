package com.openminis.app.plus

import org.json.JSONObject

/**
 * [Mines-38.9.11 i32] 工作流记录 / 经验 as readable cards (user 2026-10-20 08:14 screenshots: titles were raw user messages,
 * `<scheduled_task id=…>` and `<system-reminder>[Minis runt…` envelopes; subtitles raw tool calls 「shell_execute: …」; single
 * verification codes 「012746」「Qd5xs3d」 stored as experiences). Pure, unit-tested in XpViewCoreTest:
 *  - [clean]: strips system / scheduled-task envelopes and any XML-ish tag, never returns an id or a tag;
 *  - [scheduled]: a scheduled-task run → its label (「定时任务：xxx」) and id for grouping;
 *  - [heuristicTitle] / [heuristicSummary]: short Chinese title + one line when the model has not summarized it yet;
 *  - [tool]: tool name → Chinese chip (浏览器 / 命令 / 读文件 / 看图 …) + icon name;
 *  - [isSecretish] / [scrub]: codes, keys and passwords never shown or kept; [trivial] experiences dropped;
 *  - [similar] / [mergeKey]: near-duplicate experiences merged;
 *  - [parseModelSummary]: the model's JSON answer → (title, summary), validated.
 */
object XpViewCore {
    private val sysBlock = Regex("(?is)<system-reminder>.*?(</system-reminder>|$)")
    private val anyTag = Regex("</?[A-Za-z_][A-Za-z0-9_\\-]*(\\s+[^<>]*)?/?>")
    private val bracketRuntime = Regex("(?i)\\[(minis|mines)[^\\]]{0,40}\\]")
    private val uuid = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")

    data class Sched(val id: String, val label: String)

    /** scheduled-task envelope → (task id, label); null for an ordinary message */
    fun scheduled(raw: String): Sched? {
        val m = Regex("(?s)<scheduled_task\\b([^>]*)>?").find(raw) ?: return null
        val attrs = m.groupValues[1]
        fun attr(k: String) = Regex("\\b$k=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1).orEmpty()
        val id = attr("id")
        val label = attr("label").ifBlank { attr("name") }.ifBlank {
            clean(raw.substringAfter('>', "")).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(16)
        }
        return Sched(id.ifBlank { label }, label.ifBlank { "未命名" })
    }

    /** user-visible text of a stored task: no envelopes, no tags, no ids, single spaces */
    fun clean(raw: String): String {
        var s = raw.replace(sysBlock, " ")
        // a scheduled-task envelope keeps only its body
        s = s.replace(Regex("(?s)<scheduled_task\\b[^>]*>?"), " ").replace("</scheduled_task>", " ")
        s = s.replace(anyTag, " ").replace(bracketRuntime, " ").replace(uuid, " ")
        // a truncated tag at the very end (`<scheduled_task id="5992…`) or a dangling attribute run
        s = s.replace(Regex("<[A-Za-z_][^>]*$"), " ").replace(Regex("\\b(id|label|next|prefilled|inserted)=\"[^\"]*\"?"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private val codeOnly = Regex("^[A-Za-z0-9,._\\-]{4,10}$")
    /** a verification code / key / password-looking token (never a title, never kept) */
    fun isSecretish(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (Regex("(?i)(sk-[a-z0-9_\\-]{8,}|ghp_[a-z0-9]{10,}|github_pat_|AIza[0-9a-z_\\-]{20,}|eyJ[a-z0-9_\\-]{15,}\\.)").containsMatchIn(t)) return true
        if (codeOnly.matches(t) && t.any { it.isDigit() } && t.none { it.code in 0x4E00..0x9FFF }) return true
        return false
    }

    /** masks codes after 验证码 / code words and key-like tokens inside a line */
    fun scrub(s: String): String = s
        .replace(Regex("(?i)(验证码|校验码|动态码|code|otp|密码|password|passwd|token|密钥)(\\s*[:：=]?\\s*)([A-Za-z0-9,._\\-]{4,})")) { m -> m.groupValues[1] + m.groupValues[2] + "***" }
        .replace(Regex("(?i)\\b(sk-[a-z0-9_\\-]{8,}|ghp_[a-z0-9]{10,}|github_pat_[a-z0-9_]{10,}|AIza[0-9a-z_\\-]{20,}|eyJ[a-z0-9_\\-]{15,}\\.[a-z0-9_\\-.]+)"), "***")

    /** an experience not worth a card: empty / a code / a few characters with no task in them */
    fun trivial(pattern: String, steps: String): Boolean {
        val p = clean(pattern)
        if (p.isBlank()) return steps.isBlank()
        if (isSecretish(p)) return true
        val zh = p.count { it.code in 0x4E00..0x9FFF }
        val latin = Regex("[A-Za-z]{3,}").findAll(p).count()
        return zh < 2 && latin == 0
    }

    data class Tool(val zh: String, val icon: String)

    fun tool(name: String): Tool {
        val n = name.lowercase()
        return when {
            n.contains("browser") || n.contains("web_view") || n.contains("agentbrowse") -> Tool("浏览器", "globe")
            n.contains("shell") || n.contains("exec") || n.contains("command") || n.contains("terminal") || n.contains("ssh") -> Tool("命令", "terminal")
            n.contains("read_image") || n.contains("image") || n.contains("screenshot") || n.contains("vision") -> Tool("看图", "image")
            n.contains("file_read") || n.contains("read_file") || n == "read" || n.contains("cat") -> Tool("读文件", "file-text")
            n.contains("file_write") || n.contains("write") || n.contains("edit") -> Tool("写文件", "pencil")
            n.contains("search") -> Tool("搜索", "search")
            n.contains("fetch") || n.contains("http") || n.contains("url") -> Tool("网页", "external-link")
            n.contains("a11y") || n.contains("android") || n.contains("tap") || n.contains("screen") -> Tool("手机操作", "smartphone")
            n.contains("mail") -> Tool("邮件", "mail")
            n.contains("memory") || n.contains("remember") -> Tool("记忆", "brain")
            n.contains("skill") -> Tool("技能", "sparkles")
            n.contains("model") || n.contains("agent") || n.contains("delegate") -> Tool("交给别的 AI", "bot")
            n.contains("account") || n.contains("login") -> Tool("账号", "key-round")
            else -> Tool("工具", "wrench")
        }
    }

    /** the distinct tool chips of a run, in first-use order (≤ [max]) */
    fun chips(tools: List<String>, max: Int = 4): List<Tool> = tools.map(::tool).distinct().take(max)

    /** one step for the timeline: Chinese tool word + the step's own words (tool prefix and secrets removed) */
    fun stepLine(tool: String, brief: String): String {
        val b = scrub(clean(brief.replace(Regex("^[a-z_]+:\\s*"), ""))).take(80)
        return if (b.isBlank()) tool(tool).zh else b
    }

    private fun firstSentence(s: String, max: Int): String {
        val t = s.split(Regex("[。！？!?\\n]|(?<=[，,])\\s")).map { it.trim(' ', '，', ',', '、') }.firstOrNull { it.length >= 2 } ?: s
        return if (t.length > max) t.take(max - 1) + "…" else t
    }

    /**
     * Title without a model: scheduled → 「定时任务：label」; else the cleaned first sentence (filler words dropped); when
     * that is empty, a code, or a bare question with no subject, the first meaningful step instead.
     */
    fun heuristicTitle(rawTask: String, steps: List<Pair<String, String>>): String {
        scheduled(rawTask)?.let { return "定时任务：" + it.label.take(14) }
        val c = clean(rawTask).replace(Regex("^((这样|那么|好的|好|嗯|那|你|帮我|麻烦|请|我说|现在)[，,\\s]*)+"), "")
        val fs = firstSentence(c, 18)
        val weak = fs.isBlank() || isSecretish(fs) || fs.count { it.code in 0x4E00..0x9FFF || it.isLetter() } < 3 ||
            Regex("^(能不能用|能用吗|哪里有|怎么样|行吗|好了吗|可以吗)[?？]?$").matches(fs) ||
            // a short follow-up question (「哪里有地区？」「能不能用？」) says nothing about the task without its chat
            (Regex("^[^。！!\\n]{1,8}[?？]").containsMatchIn(c) && fs.length <= 8)
        if (!weak) return fs
        val st = steps.map { (t, b) -> stepLine(t, b) }.firstOrNull { it.length >= 4 && !isSecretish(it) }
        return st?.let { firstSentence(it, 18) } ?: fs.ifBlank { "一次工作流" }.let { if (isSecretish(it)) "一次工作流" else it }
    }

    /** one line: what happened, from the steps (「用浏览器、命令 6 步，打开 WorkBuddy 并读界面」) */
    fun heuristicSummary(steps: List<Pair<String, String>>, ok: Boolean): String {
        if (steps.isEmpty()) return if (ok) "没有用工具，直接答复" else "没做成"
        val kinds = chips(steps.map { it.first }, 3).joinToString("、") { it.zh }
        val last = steps.lastOrNull { (t, b) -> stepLine(t, b).length >= 4 }?.let { (t, b) -> stepLine(t, b) }.orEmpty()
        return ("用" + kinds + " " + steps.size + " 步" + (if (last.isNotBlank()) "，最后：" + last.take(24) else "")).take(48)
    }

    // ---------------- near-duplicates ----------------

    fun tokens(s: String): Set<String> {
        val t = clean(s).lowercase()
        val out = HashSet<String>()
        Regex("[a-z0-9]{2,}").findAll(t).forEach { out += it.value }
        Regex("[\\u4e00-\\u9fff]+").findAll(t).forEach { m -> val w = m.value; if (w.length == 1) out += w else for (i in 0 until w.length - 1) out += w.substring(i, i + 2) }
        return out
    }

    fun similar(a: String, b: String, min: Double = 0.6): Boolean {
        val x = tokens(a); val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val inter = x.intersect(y).size.toDouble()
        return inter / (x.size + y.size - inter) >= min || (inter >= 3 && inter / minOf(x.size, y.size) >= 0.85)
    }

    /** groups of near-duplicates by title (each list = one merged card), newest first inside a group */
    fun <T> groupSimilar(items: List<T>, title: (T) -> String): List<List<T>> {
        val groups = ArrayList<MutableList<T>>()
        for (it in items) {
            val g = groups.firstOrNull { gr -> similar(title(gr.first()), title(it)) }
            if (g != null) g += it else groups += mutableListOf(it)
        }
        return groups
    }

    // ---------------- model summary ----------------

    /** prompt for the configured model: Chinese JSON {"title","summary"} for one workflow / experience */
    fun summaryPrompt(task: String, steps: List<Pair<String, String>>, result: String, ok: Boolean): String = buildString {
        append("给下面这次 AI 助手的工作写一张卡片。只输出 JSON：{\"title\":\"不超过14个字的中文标题，说清做了什么事，比如「登录 CodeBuddy 官网」\",\"summary\":\"一句不超过30字的中文，说怎么做的和结果\"}。")
        append("不要写验证码、密码、密钥、id、XML 标签。\n用户的话：").append(scrub(clean(task)).take(300))
        append("\n步骤：")
        steps.take(10).forEachIndexed { i, (t, b) -> append("\n").append(i + 1).append(". ").append(tool(t).zh).append("：").append(stepLine(t, b)) }
        append("\n结果：").append(if (ok) "成功" else "失败").append("。").append(scrub(clean(result)).take(200))
    }

    /** the model's answer → (title, summary); null when it is not usable (no JSON, too long, tags, codes, English-only) */
    fun parseModelSummary(answer: String): Pair<String, String>? {
        val j = Regex("(?s)\\{.*\\}").find(answer)?.value ?: return null
        val o = runCatching { JSONObject(j) }.getOrNull() ?: return null
        val t = clean(o.optString("title")).trim('「', '」', '"', ' ')
        val s = clean(o.optString("summary")).trim()
        if (t.length !in 2..20 || t.none { it.code in 0x4E00..0x9FFF }) return null
        if (isSecretish(t) || t.contains('<') || s.contains('<')) return null
        return t to scrub(s).take(60)
    }
}
