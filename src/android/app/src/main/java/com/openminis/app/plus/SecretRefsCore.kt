package com.openminis.app.plus

/**
 * [Mines-38.9.11 b] Pure part of [SecretRefs] (unit-tested in SecretRefsCoreTest). User 2026-10-18 10:58
 * 「只要是mines自己要的时候就让他弹输入块，保护一下。已经记录的直接调用」: the agent never sees or types a password; it gets
 * a placeholder {{secret:ID}} (and {{user:ID}} for the account name), the shell gets the value as an env var of that one
 * command, and anything the command prints is masked.
 */
object SecretRefsCore {
    private val REF = Regex("""\{\{\s*(secret|user)\s*:\s*([A-Za-z0-9_]{1,48})\s*\}\}""")

    /** Stable placeholder id for a site + account: ssh_47_95_1_2_root, api_openai_com, … */
    fun idFor(kind: String, host: String, account: String): String {
        val raw = listOf(kind, host, account).filter { it.isNotBlank() }.joinToString("_")
        return raw.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(48).ifBlank { "secret" }
    }

    fun envName(id: String) = "MINES_SECRET_" + id.uppercase()

    /** ids referenced as {{secret:…}} in [command] */
    fun secretIds(command: String): Set<String> = REF.findAll(command).filter { it.groupValues[1] == "secret" }.map { it.groupValues[2] }.toSet()
    fun userIds(command: String): Set<String> = REF.findAll(command).filter { it.groupValues[1] == "user" }.map { it.groupValues[2] }.toSet()

    /** {{secret:id}} → "$MINES_SECRET_ID" (quoted, safe inside sh), {{user:id}} → the account name ([users]) shell-quoted. */
    fun rewrite(command: String, users: Map<String, String>): String = REF.replace(command) { m ->
        val id = m.groupValues[2]
        if (m.groupValues[1] == "secret") "\"\$${envName(id)}\"" else shQuote(users[id] ?: "")
    }

    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Every known secret value (≥ 4 chars) in [text] → ••••. Longest first so a value inside another is not half-masked. */
    fun scrub(text: String, values: Collection<String>): String {
        var out = text
        values.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { v -> out = out.replace(v, "••••") }
        return out
    }
}
