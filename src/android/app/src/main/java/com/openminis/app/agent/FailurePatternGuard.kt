package com.openminis.app.agent

/**
 * [Mines-loop-34] Same failing tool + same error, again and again → stop.
 *
 * User report (2026-10-13, 商汤 sensenova-6.8-flash, 135 steps): asked to register a
 * website account, the agent kept running shell_execute to start a remote/headless
 * browser over CDP ("session creation 400", "concurrency limit",
 * "browserType.connectOverCDP" …) for many minutes. Arguments differed each time,
 * so the repeat guard (same tool + same args) never fired.
 *
 * Here the key is the tool name + a normalized error signature (first error-looking
 * line, digits / hex / ids / urls / paths blanked). The [limit]th time the same key is
 * seen in one run, [onResult] returns a one-line plain-Chinese reason and the caller stops
 * the run and offers the next step. Pure Kotlin (JVM self-test).
 */
class FailurePatternGuard(private val limit: Int = 3) {
    private val counts = HashMap<String, Int>()
    var lastTool: String = ""
        private set
    var lastSignature: String = ""
        private set

    /** [Mines-38.9] patterns that reached their 2nd repeat in the last [onResult] (tool, signature) — consult the 经验库 */
    val consult = ArrayList<Pair<String, String>>()
    /** [Mines-38.9] every distinct failure of this run (tool, signature), for the post-run record */
    val seen = LinkedHashSet<Pair<String, String>>()

    /** Returns a user-facing reason when this failure pattern hit [limit], else null. */
    fun onResult(tool: String, isError: Boolean, content: String): String? {
        val sig = signature(content, isError, tool) ?: return null
        val key = "$tool|$sig"
        val n = (counts[key] ?: 0) + 1
        counts[key] = n
        seen += tool to sig
        if (n == 2 && limit > 2) consult += tool to sig
        if (n >= limit) {
            lastTool = tool; lastSignature = sig
            return "「${toolLabel(tool)}」已经连续 $n 次因为同样的问题失败（${sig.take(60)}），我先停下了。"
        }
        return null
    }

    /** One plain-language next step for the stop card. */
    fun nextStep(): String = when {
        lastSignature.contains("cdp", true) || lastSignature.contains("browser", true) || lastSignature.contains("playwright", true) ||
            lastSignature.contains("puppeteer", true) || lastSignature.contains("session", true) && lastTool == "shell_execute" ->
            "网站注册、登录这类事改用内置浏览器（browser_use）来做，要我这样继续吗？"
        lastSignature.contains("limit", true) || lastSignature.contains("429") || lastSignature.contains("quota", true) ->
            "对方限流了，可以等几分钟再试，或者换个办法。要我换个办法继续吗？"
        else -> "要我换个办法继续，还是先停在这里？"
    }

    companion object {
        private val errWords = Regex(
            "(?i)(error|exception|failed|failure|traceback|denied|refused|timed? ?out|not found|unauthori[sz]ed|forbidden|" +
                "limit|invalid|cannot|can't|unable|错误|失败|拒绝|超时|找不到|无法|限制)")

        fun toolLabel(t: String): String = when (t) {
            "shell_execute" -> "命令行"
            "browser_use" -> "浏览器"
            "file_read" -> "读文件"
            "file_write", "file_edit" -> "写文件"
            else -> t
        }

        /**
         * [Mines-loop-34] For a tool that reported success, only a line that STARTS like an error
         * report counts ("Error: …", "fatal: …", "Traceback …", "xxxException: …", "失败：…").
         * Smoke run 21: memory_get returned the persona text, whose bullet "遇到失败先自己换办法重试"
         * contains 失败 — three identical successful reads were stopped as a "failure loop".
         */
        private val errHeader = Regex(
            "(?i)^(\\[?(error|err|fatal|exception|traceback|failed|failure|denied|refused|timeout|timed out|forbidden|unauthori[sz]ed|" +
                "错误|失败|拒绝|超时|无法)\\]?[\\s:：!,，(]|[\\w.$]*(error|exception)\\b\\s*[:：(]|curl: \\(\\d+\\)|\\w+: (command )?not found)")

        /** Tools that read stored text: their successful output is content, never a failure report. */
        private fun isReader(tool: String): Boolean {
            val t = tool.lowercase()
            return t.startsWith("memory") || t.contains("read") || t.contains("knowledge") || t.contains("search") ||
                t.startsWith("get_") || t.startsWith("list") || t == "file_list" || t == "skill_view"
        }

        /** Normalized signature of the first error-looking line, or null when [content] doesn't look like a failure. */
        fun signature(content: String, isError: Boolean, tool: String = ""): String? {
            if (!isError && isReader(tool)) return null
            val head = content.take(4000)
            val line = (if (isError) head.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && errWords.containsMatchIn(it) }
                else head.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && errHeader.containsMatchIn(it) })
                ?: if (isError) head.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } else null
            line ?: return null
            return line.lowercase()
                .replace(Regex("https?://\\S+"), "<url>")
                .replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "<id>")
                .replace(Regex("\\b[0-9a-f]{12,}\\b"), "<hex>")
                .replace(Regex("(/[\\w.\\-]+){2,}"), "<path>")
                // short ids that mix letters and digits (session 4f1a2b, req a1b2c3d) must not make each error unique
                .replace(Regex("\\b(?=[0-9a-z]*\\d)(?=[0-9a-z]*[a-z])[0-9a-z]{4,}\\b"), "<id>")
                .replace(Regex("\\d+"), "#")
                .replace(Regex("\\s+"), " ")
                .take(140)
        }
    }
}
