package com.openminis.app.browser

/** [Mines-38.9.9] pure UA clean-up (unit-tested): the system WebView UA without the "; wv" / "Version/x.y" WebView markers. */
object WebUaCore {
    fun chromeLike(ua: String?): String? {
        if (ua.isNullOrBlank()) return ua
        return ua.replace("; wv)", ")").replace(Regex("""\s?Version/\d+(\.\d+)*"""), "").replace(Regex("\\s{2,}"), " ").trim()
    }
}
