package com.openminis.app.plus

/**
 * [Mines-38.9.11 b] Pure part of [SecretRefs] (unit-tested in SecretRefsCoreTest). User 2026-10-18 10:58
 * 「只要是mines自己要的时候就让他弹输入块，保护一下。已经记录的直接调用」: the agent never sees or types a password; it gets
 * a placeholder {{secret:ID}} (and {{user:ID}} for the account name), the shell gets the value as an env var of that one
 * command, and anything the command prints is masked.
 */
object SecretRefsCore {
    private val REF = Regex("""\{\{\s*(secret|user)\s*:\s*([A-Za-z0-9_]{1,48})\s*\}\}""")
    // A token may be quoted by the caller. Remove only quotes surrounding the entire token,
    // then emit a shell expansion so single quotes cannot turn it into literal text.
    private val QUOTED_REF = Regex("""(['"])\{\{\s*(secret|user)\s*:\s*([A-Za-z0-9_]{1,48})\s*\}\}\1""")

    /** Stable placeholder id for a site + account: ssh_47_95_1_2_root, api_openai_com, … */
    fun idFor(kind: String, host: String, account: String): String {
        val raw = listOf(kind, host, account).filter { it.isNotBlank() }.joinToString("_")
        return raw.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48).ifBlank { "secret" }
    }

    fun envName(id: String) = "MINES_SECRET_" + id.uppercase()

    /** ids referenced as {{secret:…}} in [command] */
    fun secretIds(command: String): Set<String> = REF.findAll(command).filter { it.groupValues[1] == "secret" }.map { it.groupValues[2] }.toSet()
    fun userIds(command: String): Set<String> = REF.findAll(command).filter { it.groupValues[1] == "user" }.map { it.groupValues[2] }.toSet()

    /** Missing broker-backed values must stop the command before a literal placeholder can reach the shell. */
    fun missingSecretIds(command: String, availableEnvNames: Set<String>): Set<String> =
        secretIds(command).filterTo(linkedSetOf()) { envName(it) !in availableEnvNames }

    /** {{secret:id}} → "$MINES_SECRET_ID" (quoted, safe inside sh), {{user:id}} → the account name ([users]) shell-quoted. */
    private fun secretExpansion(id: String): String = "\"" + '$' + envName(id) + "\""

    fun rewrite(command: String, users: Map<String, String>): String {
        // First handle a quote pair whose entire contents are the reference. This preserves the
        // caller's intent while avoiding nested single quotes around a variable expansion.
        val unwrapped = QUOTED_REF.replace(command) { m ->
            val id = m.groupValues[3]
            if (m.groupValues[2] == "secret") secretExpansion(id) else shQuote(users[id] ?: "")
        }
        val refs = REF.findAll(unwrapped).toList()
        if (refs.isEmpty()) return unwrapped

        val out = StringBuilder(unwrapped.length + refs.size * 16)
        var cursor = 0
        var quote: Char? = null
        var escaped = false
        fun copyUntil(end: Int) {
            while (cursor < end) {
                val ch = unwrapped[cursor++]
                out.append(ch)
                if (escaped) {
                    escaped = false
                } else when (quote) {
                    '\'' -> if (ch == '\'') quote = null
                    '"' -> when (ch) {
                        '\\' -> escaped = true
                        '"' -> quote = null
                    }
                    else -> when (ch) {
                        '\\' -> escaped = true
                        '\'', '"' -> quote = ch
                    }
                }
            }
        }
        for (m in refs) {
            copyUntil(m.range.first)
            val id = m.groupValues[2]
            val replacement = if (m.groupValues[1] == "secret") secretExpansion(id) else shQuote(users[id] ?: "")
            when (quote) {
                '\'' -> {
                    // Close/reopen the surrounding single-quoted word so the generated fragment
                    // is evaluated as shell code, while the secret itself remains double-quoted.
                    out.append('\'').append(replacement).append('\'')
                }
                '"' -> {
                    // Stay inside the current double-quoted word for secrets. User text is
                    // shell-quoted in a concatenated segment to prevent expansion/injection.
                    if (m.groupValues[1] == "secret") out.append('$').append(envName(id))
                    else out.append('"').append(replacement).append('"')
                }
                else -> out.append(replacement)
            }
            cursor = m.range.last + 1
        }
        copyUntil(unwrapped.length)
        return out.toString()
    }

    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Every known secret value (≥ 4 chars) in [text] → ••••. Longest first so a value inside another is not half-masked. */
    fun scrub(text: String, values: Collection<String>): String {
        var out = text
        values.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { v -> out = out.replace(v, "••••") }
        return out
    }
}
