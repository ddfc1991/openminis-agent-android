package com.openminis.app.plus

/**
 * [Mines-38.9.15.1] Final replies, tidied for reading (user 2026-10-26 14:51 「还有它输出很😂」): gratuitous emoji and stray Markdown
 * marks (a lone 「**」 the renderer shows literally, an empty 「#」 line) are dropped from a FINISHED reply at display time.
 * Code — fenced blocks and `inline` spans — is never touched, nor are tables, links, list markers or the check / cross /
 * warning marks (✓ ✔ ✗ ✘ ⚠) used as status. The stored message is unchanged (copy / 复制为 Markdown keep the original).
 */
object ReplyTidy {
    private val KEEP = setOf(0x2713, 0x2714, 0x2717, 0x2718, 0x26A0, 0x2022, 0x00A9, 0x00AE, 0x2122)
    private val FENCE = Regex("(?m)^\\s*(```|~~~)")

    fun isEmoji(cp: Int): Boolean = cp !in KEEP && (
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x1F1E6..0x1F1FF ||
            cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 || cp in 0xE0020..0xE007F || cp in 0x2300..0x23FF && cp !in 0x2318..0x231B)

    fun tidy(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var inFence = false
        val lines = text.split('\n')
        lines.forEachIndexed { i, line ->
            val fence = FENCE.containsMatchIn(line)
            val kept = if (inFence || fence) line else tidyLine(line)
            if (fence) inFence = !inFence
            // a line that held only emoji / a stray mark disappears instead of leaving a gap
            val drop = !inFence && !fence && kept.isBlank() && line.isNotBlank()
            if (!drop) { out.append(kept); if (i < lines.lastIndex) out.append('\n') }
        }
        return out.toString().replace(Regex("\n{4,}"), "\n\n\n").trimEnd('\n').let { if (text.endsWith("\n")) "$it\n" else it }
    }

    /** one prose line: emoji out (outside `code`), unmatched ** out, an empty heading / quote mark out */
    internal fun tidyLine(line: String): String {
        val parts = line.split('`')
        if (parts.size % 2 == 0) return dropEmoji(line)   // unbalanced backticks: leave the marks, still drop emoji
        val sb = StringBuilder()
        parts.forEachIndexed { k, p -> if (k > 0) sb.append('`'); sb.append(if (k % 2 == 1) p else dropEmoji(p)) }
        var s = sb.toString()
        val outside = parts.filterIndexed { k, _ -> k % 2 == 0 }.joinToString("")
        if (Regex("\\*\\*").findAll(outside).count() % 2 == 1) s = s.replaceStrayBold()
        if (Regex("^\\s*(#{1,6}|>|\\*\\*|\\*|__)\\s*$").matches(s)) s = ""
        return s
    }

    /** removes the LAST unmatched ** (the usual case: a bold mark the model never closed / closed on another line) */
    private fun String.replaceStrayBold(): String {
        val idx = lastIndexOf("**"); if (idx < 0) return this
        return (substring(0, idx) + substring(idx + 2)).replace(Regex("[ \\t]{2,}"), " ").trimEnd()
    }

    private fun dropEmoji(s: String): String {
        if (s.none { it.isSurrogate() || it.code in 0x2300..0x2BFF || it.code == 0xFE0F || it.code == 0x200D || it.code == 0x20E3 }) return s
        val sb = StringBuilder(s.length)
        var i = 0
        var removed = false
        while (i < s.length) {
            val cp = s.codePointAt(i); val n = Character.charCount(cp)
            if (isEmoji(cp)) { removed = true; i += n; if (i < s.length && s[i] == ' ' && (sb.isEmpty() || sb.last() == ' ' || sb.last() in "：:（(-*>#")) i++; continue }
            sb.appendCodePoint(cp); i += n
        }
        if (!removed) return s
        val lead = s.takeWhile { it == ' ' || it == '\t' }
        return lead + sb.toString().substring(lead.length.coerceAtMost(sb.length)).replace(Regex("(?<=\\S) {2,}"), " ").trimEnd()
    }
}
