package com.openminis.app.agent

import org.json.JSONObject

/**
 * [Mines-38.9.6 i17] Tolerant tool-call arguments (user 2026-10-18: several models on the phone send
 * {"arguments": {"command": …}} — sometimes with a JSON string inside — the executor found no `command`, refused, and the
 * agent looped 6+ turns). Before dispatch: a JSON-string payload is decoded; an object whose ONLY key is
 * arguments / args / parameters / input (object or JSON-string value) is unwrapped, up to 2 levels. When a required
 * field is still missing the model gets one precise error naming it and showing the expected top-level shape.
 * Pure (JVM-testable).
 */
object ToolArgsCore {
    private val WRAPPERS = setOf("arguments", "args", "parameters", "input")

    /** fields that must be there for the call to run (tool_title is cosmetic and never enforced) */
    val REQUIRED: Map<String, List<String>> = mapOf(
        "shell_execute" to listOf("command"),
        "file_read" to listOf("path"),
        "file_write" to listOf("path", "content"),
        "file_edit" to listOf("path", "old_string", "new_string"),
        "read_image" to listOf("path"),
        "browser_use" to listOf("action"),
        "memory_write" to listOf("content"),
    )

    private val EXAMPLE: Map<String, String> = mapOf(
        "shell_execute" to """{"tool_title":"列出文件","command":"ls -la"}""",
        "file_read" to """{"tool_title":"读文件","path":"/var/minis/workspace/a.txt"}""",
        // [Mines-38.9.11 c] deliverables go to shared/<主题>/ (the workspace is deleted with the chat)
        "file_write" to """{"tool_title":"写文件","path":"/var/minis/shared/报告/a.md","content":"…"}""",
        "file_edit" to """{"tool_title":"改文件","path":"/var/minis/workspace/a.txt","old_string":"…","new_string":"…"}""",
        "read_image" to """{"tool_title":"看图","path":"/var/minis/attachments/uploads/a.jpg"}""",
        "browser_use" to """{"tool_title":"打开网页","action":"navigate","url":"https://example.com"}""",
        "memory_write" to """{"tool_title":"记下","content":"…"}""",
    )

    private fun asObject(v: Any?): JSONObject? = when (v) {
        is JSONObject -> v
        is String -> v.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        else -> null
    }

    /** Returns (normalized JSON text, how many wrapper levels were removed or -1 when nothing parsed). */
    fun normalize(raw: String?): Pair<String, Int> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "{}" to 0
        var o: JSONObject = asObject(text) ?: run {
            // a JSON string literal holding the object: "{\"command\":\"ls\"}"
            val s = runCatching { org.json.JSONTokener(text).nextValue() }.getOrNull()
            asObject(s) ?: return text to -1
        }
        var levels = if (text.startsWith("\"")) 1 else 0
        repeat(2) {
            if (o.length() != 1) return@repeat
            val k = o.keys().next()
            if (k !in WRAPPERS) return@repeat
            val inner = asObject(o.opt(k)) ?: return@repeat
            o = inner; levels++
        }
        return (if (levels == 0) text else o.toString()) to levels
    }

    fun missing(tool: String, argsJson: String): List<String> {
        val req = REQUIRED[tool] ?: return emptyList()
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return req
        return req.filter { !o.has(it) || o.isNull(it) }
    }

    fun error(tool: String, missing: List<String>, argsJson: String): String {
        val keys = runCatching { JSONObject(argsJson).keys().asSequence().toList() }.getOrDefault(emptyList())
        return "工具 $tool 的参数缺少 ${missing.joinToString("、") { "`$it`" }}（收到的顶层字段：${if (keys.isEmpty()) "无" else keys.joinToString(", ")}）。" +
            "参数必须是一个顶层 JSON 对象，字段直接放在最外层，不要包在 arguments/args/input 里，例如：${EXAMPLE[tool] ?: "{…}"}"
    }
}
