package com.openminis.app.plus

/** [Mines-38.9.12.1] pure rules for [ProcessGuard] (unit-tested). */
object ProcessGuardCore {
    /** main = the process named exactly like the package (an unknown name counts as main: never skip init by mistake) */
    fun isMain(processName: String?, packageName: String): Boolean =
        processName.isNullOrBlank() || processName == packageName

    /** WebView data-dir suffix for a secondary process: "com.x:acra" → "acra"; only [A-Za-z0-9_], never empty */
    fun dataSuffix(processName: String?, packageName: String): String {
        val raw = processName.orEmpty().removePrefix(packageName).trimStart(':', '.')
        val clean = raw.filter { it.isLetterOrDigit() || it == '_' }
        return clean.ifEmpty { "proc" }
    }
}
