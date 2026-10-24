package com.openminis.app.plus

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.11.3] user: 「出错 · Invalid API key」 (商汤). A key that answered 401 / invalid is marked bad (by a SHA-256
 * fingerprint — the key itself is never stored here or logged) for [TTL_MS]; the key pool skips it and the turn moves to the
 * next key / provider at once. 「测试全部密钥」 on the provider screen re-checks and clears / sets the marks.
 */
object BadKeys {
    const val TTL_MS = 24 * 60 * 60_000L
    private const val PREFS = "mines_bad_keys"
    private val marks = ConcurrentHashMap<String, Long>()
    @Volatile private var ctx: Context? = null

    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        Thread({
            runCatching { c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (k, v) -> (v as? Long)?.let { marks[k] = it } } }
        }, "mines-badkeys").apply { isDaemon = true }.start()
    }

    fun fp(key: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

    fun isBad(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        val at = marks[fp(key)] ?: return false
        return System.currentTimeMillis() - at < TTL_MS
    }

    fun mark(key: String?) {
        if (key.isNullOrBlank()) return
        val f = fp(key); val now = System.currentTimeMillis(); marks[f] = now
        ctx?.let { c -> Thread({ runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(f, now).apply() } }).start() }
    }

    fun clear(key: String?) {
        if (key.isNullOrBlank()) return
        val f = fp(key); if (marks.remove(f) == null) return
        ctx?.let { c -> Thread({ runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(f).apply() } }).start() }
    }
}
