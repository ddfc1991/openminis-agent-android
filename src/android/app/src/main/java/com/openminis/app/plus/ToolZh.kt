package com.openminis.app.plus

/**
 * [Mines-38.9.11 i32] user 2026-10-20 08:54 (real phone chat): tool pills read 「Task Progress」「Execute Shell…」「file_write」.
 * Chinese names for tool pills / the floating tool bar when the model gave no title of its own (or gave back the tool's
 * English humanized name). A real model title (「打补丁并校验 JS 语法」) is kept as written. Pure, tested in ToolZhTest.
 */
object ToolZh {
    private val names = mapOf(
        "shell_execute" to "执行命令", "file_read" to "读文件", "file_write" to "写文件", "file_edit" to "改文件",
        "browser_use" to "浏览网页", "read_image" to "看图", "memory_write" to "写记忆", "memory_get" to "读记忆", "memory" to "查记忆",
        "web_search" to "搜索网页", "web_fetch" to "读网页", "task_progress" to "任务进度", "todo_write" to "待办",
        "subagent_task" to "子任务", "delegate_task" to "交给别的 AI", "agent_status" to "子任务状态", "accounts" to "账号",
        "model_use" to "调用模型", "skill_use" to "用技能", "android_a11y" to "手机操作", "screen_tap" to "点屏幕",
        "mail_send" to "发邮件", "mail_read" to "读邮件", "experience" to "经验", "ask_user" to "问你",
    )

    fun humanized(toolName: String): String = toolName.split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private val englishFallbacks = setOf("Execute Shell", "Read File", "Write File", "Edit File", "Browse Web", "Read Image",
        "Write Memory", "Read Memory", "Search Web", "Task Progress")

    /** Chinese name of a tool (known ones), else null */
    fun name(toolName: String?): String? = toolName?.let { names[it] }

    /** what the pill shows: the model's own title, unless it is empty / the raw tool id / the English humanized fallback */
    fun title(title: String?, toolName: String?): String {
        val t = title?.trim().orEmpty()
        val n = toolName.orEmpty()
        val generic = t.isEmpty() || t == n || t.equals(humanized(n), ignoreCase = true) || t in englishFallbacks ||
            t.trimEnd('.', '…', ' ') in englishFallbacks
        if (!generic) return t
        return name(n) ?: englishFallbacks.firstOrNull { it.equals(t.trimEnd('.', '…', ' '), true) }?.let { fb ->
            names.entries.firstOrNull { humanized(it.key) == fb || fallbackOf(it.key) == fb }?.value
        } ?: t.ifEmpty { humanized(n).ifEmpty { "工具" } }
    }

    private fun fallbackOf(k: String) = when (k) {
        "shell_execute" -> "Execute Shell"; "file_read" -> "Read File"; "file_write" -> "Write File"; "file_edit" -> "Edit File"
        "browser_use" -> "Browse Web"; "read_image" -> "Read Image"; "memory_write" -> "Write Memory"; "memory_get" -> "Read Memory"
        "web_search" -> "Search Web"; else -> humanized(k)
    }
}
