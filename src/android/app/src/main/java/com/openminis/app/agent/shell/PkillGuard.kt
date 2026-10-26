package com.openminis.app.agent.shell

/**
 * [38.9.15.1] user 14:53 「很慢很慢什么问题」: the agent ran `pkill -f genspark`; `-f` matches the whole command line, and the
 * command line of the agent's own `/bin/sh -c "… pkill -f genspark …"` (and of proot tracing it) contains that word, so the
 * command killed itself — no output, the model re-ran, re-planned, wrote base64 workarounds…
 *
 * Fix in the shell tool, not in the prompt:
 *  - `pkill -f X` / `pgrep -f X` → the pattern's first letter is bracketed (`[g]enspark`): the regex still matches the
 *    target process, but no longer the command line that carries the pattern text itself.
 *  - commands that can only kill the agent's own shell / proot / everything (`pkill proot`, `killall sh`, `kill -9 -1`,
 *    `pkill -f proot`, `pkill -u $(whoami)` …) are refused with one plain line, so the model picks a real pid instead.
 * Pure (unit-tested in PkillGuardTest).
 */
object PkillGuard {
    data class Result(val command: String, val refused: String? = null, val patched: Boolean = false)

    private val SELF_NAMES = setOf("proot", "sh", "bash", "ash", "busybox", "setsid", "minis", "mines", "com.openminis.app", "app_process", "java")

    private val refuseRes = listOf(
        Regex("""(^|[;&|(`\s])kill\s+(-\d+|-KILL|-9|-TERM|-s\s+\w+)?\s*-1(\s|$|;)"""),
        Regex("""(^|[;&|(`\s])(pkill|killall)\s+(-\S+\s+)*(-u|--user)\s"""),
    )

    // pkill/pgrep [opts] -f [opts] PATTERN — PATTERN is a bare word or a quoted string without quotes inside
    private val fRe = Regex("""(^|[;&|(`\s])(pkill|pgrep)((?:\s+-[A-Za-z0-9]+(?:\s+\d+)?)*?\s+-[A-Za-z]*f[A-Za-z]*(?:\s+-[A-Za-z0-9]+(?:\s+\d+)?)*)\s+('([^']+)'|"([^"${'$'}`\\]+)"|([^\s;&|'"`)]+))""")
    private val nameRe = Regex("""(^|[;&|(`\s])(pkill|killall)((?:\s+-\S+)*)\s+([A-Za-z0-9._-]+)""")

    fun check(command: String): Result {
        refuseRes.firstOrNull { it.containsMatchIn(command) }?.let {
            return Result(command, refused = "已拦下：这条命令会把执行它的 shell 自己（以及 Mines 的沙箱进程）一起杀掉。请先用 ps / pgrep 找到目标进程号，再 kill <进程号>。")
        }
        for (m in nameRe.findAll(command)) {
            val opts = m.groupValues[3]
            if (Regex("""-\S*f""").containsMatchIn(opts)) continue   // -f handled below
            if (m.groupValues[4].lowercase() in SELF_NAMES) return Result(command,
                refused = "已拦下：`${m.groupValues[2]} ${m.groupValues[4]}` 会杀掉执行命令的 shell / 沙箱本身。请用 pgrep -f <目标程序> 找进程号，再 kill <进程号>。")
        }
        for (m in fRe.findAll(command)) {
            val word = m.groupValues[5].ifEmpty { m.groupValues[6] }.ifEmpty { m.groupValues[7] }.trim().lowercase()
            if (word in SELF_NAMES || word.startsWith("proot ")) return Result(command,
                refused = "已拦下：`${m.groupValues[2]} -f $word` 会匹配到执行命令的 shell / proot 自己。请换成目标程序独有的名字。")
        }
        var patched = false
        val out = fRe.replace(command) { m ->
            val word = m.groupValues[5].ifEmpty { m.groupValues[6] }.ifEmpty { m.groupValues[7] }.trim()
            val b = bracket(word) ?: return@replace m.value
            patched = true
            m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + " '" + b.replace("'", "'\\''") + "'"
        }
        return Result(out, patched = patched)
    }

    /** `genspark` → `[g]enspark`; null when it already has a bracket class or no letter/digit to bracket */
    fun bracket(p: String): String? {
        if (p.isEmpty() || p.contains('[')) return null
        val i = p.indexOfFirst { it.isLetterOrDigit() }
        if (i < 0) return null
        // a preceding backslash would make the bracket literal
        if (i > 0 && p[i - 1] == '\\') return null
        return p.substring(0, i) + "[" + p[i] + "]" + p.substring(i + 1)
    }
}
