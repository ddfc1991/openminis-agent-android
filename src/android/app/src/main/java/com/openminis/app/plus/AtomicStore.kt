package com.openminis.app.plus

import android.content.Context
import java.io.File

/**
 * [Mines-38.9.11.2] Android side of [AtomicStoreCore]: the vault files live in `files/vault/` (app-private). Each store keeps
 * its SharedPreferences copy too (the format older versions read), so the prefs act as one more fallback.
 * When the file and both backups exist but none can be read, the user gets ONE notice per store (never again for it).
 */
object AtomicStore {
    private const val NOTICE_PREFS = "mines_atomic_notice"
    @Volatile var lastNotice: String? = null; private set

    fun dir(c: Context): File = File(c.applicationContext.filesDir, "vault")

    fun write(c: Context, name: String, text: String): Boolean = runCatching {
        AtomicStoreCore.write(dir(c), name, text.toByteArray(Charsets.UTF_8)); true
    }.getOrElse { android.util.Log.w("AtomicStore", "write $name failed: ${it.javaClass.simpleName}"); false }

    /** newest valid copy as text; null = none (absent, or all copies failed → [notice] by the caller when nothing else works) */
    fun read(c: Context, name: String, valid: (String) -> Boolean): Pair<String?, Boolean> {
        val r = runCatching { AtomicStoreCore.read(dir(c), name) { valid(String(it, Charsets.UTF_8)) } }
            .getOrElse { AtomicStoreCore.Read(null, "error", true) }
        if (r.ok && r.source != "main") android.util.Log.w("AtomicStore", "$name: main copy unreadable, loaded ${r.source}")
        return (r.bytes?.let { String(it, Charsets.UTF_8) }) to r.allFailed
    }

    /** one-time notice: [label] could not be read from the file or its 2 backups */
    fun notice(c: Context, label: String) {
        val p = c.applicationContext.getSharedPreferences(NOTICE_PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean(label, false)) return
        p.edit().putBoolean(label, true).apply()
        val msg = "${label}文件和两份备份都读不出来，这次按空的打开，请重新导入或保存。"
        lastNotice = msg
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(c.applicationContext, msg, android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}
