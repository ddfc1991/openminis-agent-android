package com.openminis.app.sandbox

/**
 * [Mines-38.9.15.1] Long scripts / heredocs into proot, intact.
 *
 * The fresh-process path hands the command to the guest as ONE argv string (`/bin/sh -c "$1"`). Linux caps a single argv
 * string at MAX_ARG_STRLEN = 128 KiB (and the whole argv + env at ~2 MiB), so a big pasted script or a long heredoc failed
 * with E2BIG ("Argument list too long") or never started — the model then rewrote it as base64 / split chunks, in a loop
 * (user screenshot 2026-10-26 14:52: 「heredoc 被工具搞坏了。写到文件再跑」). Above [LIMIT_BYTES] the exact command text is
 * written to a file under the rootfs and the guest sources it (`. file`), so quotes, `$`, backslashes, tabs, CRLF-free
 * newlines and heredoc bodies arrive byte-for-byte and `exit N` / cwd behave as they would inline.
 */
object LongCommand {
    /** well under 128 KiB so the bash wrapper (base64 ≈ ×1.34) and env never push one argv string over the cap */
    const val LIMIT_BYTES = 48 * 1024

    fun needsFile(command: String): Boolean =
        command.length > LIMIT_BYTES / 4 && command.toByteArray(Charsets.UTF_8).size > LIMIT_BYTES || command.indexOf('\u0000') >= 0

    /** the short command that runs the saved script at guest path [guestPath] in the same shell (`.` = source) */
    fun runner(guestPath: String): String = ". '" + guestPath.replace("'", "'\\''") + "'"

    /** what goes in the file: the command unchanged, with a final newline so a heredoc terminator on the last line counts */
    fun fileText(command: String): String = command.replace("\u0000", "") + if (command.endsWith("\n")) "" else "\n"
}
