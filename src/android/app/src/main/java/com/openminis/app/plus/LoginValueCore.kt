package com.openminis.app.plus

/**
 * [Mines-38.9.6 i13] Pure helpers for values read from a sign-in page and for re-written cookies (unit-tested in
 * LoginValueCoreTest). Bug (user 2026-10-18, Memoh 账号详情 showed 账号 「\u0001\u0001」 + 正常): WebView.evaluateJavascript
 * returns a JSON-encoded string; WebLoginScreen only trimmed the quotes, so the "\u0001" separators stayed as 6-char
 * escape text, the split never happened and the whole escaped blob was saved as the account name.
 * Nothing here logs; passwords pass through untouched except for the separator split.
 */
object LoginValueCore {
    /** evaluateJavascript result → the JS string: decodes "\"…\"" with \uXXXX / \n / \" escapes; null / undefined → "". */
    fun decodeJs(r: String?): String {
        val s = r?.trim() ?: return ""
        if (s == "null" || s == "undefined" || s.isEmpty()) return ""
        if (!(s.length >= 2 && s.startsWith("\"") && s.endsWith("\""))) return s
        val b = StringBuilder(s.length)
        var i = 1
        val end = s.length - 1
        while (i < end) {
            val ch = s[i]
            if (ch != '\\' || i + 1 >= end) { b.append(ch); i++; continue }
            when (val n = s[i + 1]) {
                'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                '/' -> b.append('/'); '\\' -> b.append('\\'); '"' -> b.append('"'); '\'' -> b.append('\'')
                'u' -> {
                    val hex = if (i + 6 <= end) s.substring(i + 2, i + 6) else ""
                    val code = hex.toIntOrNull(16)
                    if (code != null && hex.length == 4) { b.append(code.toChar()); i += 6; continue } else b.append("\\u")
                }
                else -> { b.append('\\'); b.append(n) }
            }
            i += 2
        }
        return b.toString()
    }

    private val ESC = Regex("""\\u[0-9a-fA-F]{4}|\\x[0-9a-fA-F]{2}""")

    /** A saved / shown account value: no control chars, no left-over escape text; "" when nothing real is left. */
    fun cleanAccount(v: String?): String {
        val s = (v ?: "").replace(ESC, "").filterNot { it.isISOControl() || it == '\uFFFD' }.trim()
        return if (s.isEmpty() || s.all { it == '\\' || it.isWhitespace() }) "" else s
    }

    /** True for a stored account name that came from the separator bug (or is otherwise junk). */
    fun badAccount(v: String?): Boolean = !v.isNullOrEmpty() && cleanAccount(v) != v.trim()

    data class Parts(val user: String, val token: String, val pass: String) {
        override fun toString() = "Parts(user=$user, token=${if (token.isEmpty()) "-" else "…"}, pass=${if (pass.isEmpty()) "-" else "***"})"
    }

    /** "user \u0001 token \u0001 password" (real separators, or escape text from an older build) → parts. */
    fun parseAccount(raw: String?): Parts {
        val s = raw ?: ""
        val parts = if (s.contains('\u0001')) s.split('\u0001') else s.split("\\u0001")
        val user = cleanAccount(parts.getOrNull(0))
        val token = parts.getOrNull(1).orEmpty().trim().takeIf { t -> t.isNotEmpty() && t.none { it.isISOControl() } }.orEmpty()
        val pass = parts.getOrNull(2).orEmpty().takeIf { p -> p.none { it.isISOControl() } }.orEmpty()
        return Parts(user, token, pass)
    }

    /**
     * Set-Cookie line for putting a cookie back. Cookie prefixes (RFC 6265bis): `__Host-` must be host-only (NO Domain),
     * Path=/ and Secure — the old writer added "Domain=.root", Chromium rejected it and the hosted Memoh session vanished
     * after every restart / swap; `__Secure-` needs Secure. [root] blank or an IP → host-only.
     */
    fun cookieLine(name: String, value: String, root: String, https: Boolean, maxAgeS: Long, sameSiteNone: Boolean = false): String {
        val host = name.startsWith("__Host-")
        val secure = https || host || name.startsWith("__Secure-")
        val domain = if (!host && root.isNotBlank() && !root.all { it.isDigit() || it == '.' } && !root.contains(':') && root != "localhost") "; Domain=.$root" else ""
        return "$name=$value; Path=/; Max-Age=$maxAgeS$domain" + (if (secure) "; Secure" else "") + (if (secure && sameSiteNone) "; SameSite=None" else "")
    }

    /** Lines that delete [name] in every scope it may have been written with (a __Host- delete must be Secure, host-only). */
    fun deleteLines(name: String, root: String, isIp: Boolean): List<String> {
        val sec = if (name.startsWith("__Host-") || name.startsWith("__Secure-")) "; Secure" else ""
        val out = mutableListOf("$name=; Max-Age=0; Path=/$sec")
        if (!isIp && !name.startsWith("__Host-") && root.isNotBlank()) { out += "$name=; Max-Age=0; Path=/; Domain=.$root$sec"; out += "$name=; Max-Age=0; Path=/; Domain=$root$sec" }
        return out
    }
}
