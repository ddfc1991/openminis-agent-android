package com.openminis.app.crash

import com.openminis.app.browser.BrowserLogMask

/**
 * [Mines-38.9.11.3] crash / ANR / native reports never carry a secret: everything written to filesDir/logs/crash-*,
 * exitinfo-* and what 「复制崩溃日志」 copies goes through [redact]. Pure, unit-tested (CrashRedactCoreTest).
 *  - BrowserLogMask (password= / token= / Bearer / Cookie / user:pass@ / JSON "password":"…" / codes next to 验证码);
 *  - key-shaped tokens (sk-…, ghp_…, github_pat_…, glpat-…, AKIA…, AIza…, xox?-…, JWT eyJ….….…);
 *  - ENV-style NAME_KEY=… / NAME_TOKEN: … / PASSWORD=… lines;
 *  - mainland phone numbers and the local part of e-mail addresses.
 */
object CrashRedactCore {
    const val MASK = "***"
    private val tokens = Regex(
        """\b(?:sk-(?:ant-|proj-)?[A-Za-z0-9_\-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_\-]{16,}|""" +
            """AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_\-]{30,}|xox[abprs]-[A-Za-z0-9\-]{10,}|eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{6,})""")
    private val env = Regex("""(?m)\b([A-Za-z0-9_]*(?:API_?KEY|TOKEN|SECRET|PASSWORD|PASSWD|PASS|PRIVATE_KEY)[A-Za-z0-9_]*)(\s*[=:]\s*)(\S+)""", RegexOption.IGNORE_CASE)
    private val phone = Regex("""(?<![0-9])(1[3-9][0-9])[0-9]{4}([0-9]{4})(?![0-9])""")
    private val email = Regex("""\b([A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]*@([A-Za-z0-9.\-]+\.[A-Za-z]{2,})\b""")
    private val pem = Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""")
    // Preserve ordinary platform/source paths in diagnostics, but hide sensitive path components.
    // URL schemes are excluded by the boundary so https:// links are not rewritten as filesystem paths.
    private val absolutePath = Regex("""(?<![A-Za-z0-9._~:/-])/(?:[A-Za-z0-9._~+@-]+/)*[A-Za-z0-9._~+@-]+""")
    private val sensitivePathPart = Regex(
        """^(?:\.env(?:\..*)?|\.netrc|\.npmrc|\.pypirc|authorized_keys|.*(?:secret|credential|token|password|passwd|private[-_]?key|keychain|keystore|id_(?:rsa|ed25519|ecdsa|dsa)).*|.*[-_]key|.*\.(?:pem|key|p12|pfx|jks|keystore))$""",
        RegexOption.IGNORE_CASE,
    )

    fun redact(s: String?): String {
        if (s.isNullOrEmpty()) return s.orEmpty()
        var r = pem.replace(s) { "-----PRIVATE KEY $MASK-----" }
        r = BrowserLogMask.mask(r)
        r = tokens.replace(r, MASK)
        r = env.replace(r) { match ->
            val value = match.groupValues[3]
            // A path assigned to a key-like variable is still useful diagnostic context.
            // Preserve it, then let path-aware masking hide only sensitive components.
            val safeValue = if (value.startsWith('/') && !value.startsWith("//")) redactPath(value) else MASK
            match.groupValues[1] + match.groupValues[2] + safeValue
        }
        r = phone.replace(r) { it.groupValues[1] + "****" + it.groupValues[2] }
        r = email.replace(r) { it.groupValues[1] + "***@" + it.groupValues[2] }
        r = absolutePath.replace(r) { match -> redactPath(match.value) }
        return r
    }

    private fun redactPath(path: String): String = path.split('/').joinToString("/") { part ->
        if (part.isNotEmpty() && sensitivePathPart.matches(part)) MASK else part
    }

    /** the text 「复制崩溃日志」 puts on the clipboard: redacted, newest report, capped (clipboard limit ~1 MB; keep it small) */
    fun forClipboard(report: String, max: Int = 60_000): String {
        val r = redact(report)
        return if (r.length <= max) r else r.take(max) + "\n…（已截断，完整日志在 设置 › 日志）"
    }

    /** the card's title from a report's header line */
    fun title(report: String): String = when {
        "Reason: ANR" in report -> "上次 Mines 卡住被系统关闭（ANR）"
        "Reason: CRASH_NATIVE" in report || "Native Crash" in report -> "上次 Mines 闪退（底层）"
        else -> "上次 Mines 闪退"
    }
}
