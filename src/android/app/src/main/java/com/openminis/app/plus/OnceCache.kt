package com.openminis.app.plus

import java.util.concurrent.ConcurrentHashMap

/**
 * [38.9.11.3.1] Build-once cache: one build per key, a per-key lock (never one lock for all keys), a hit never takes a
 * lock, and a failed build is not cached (the next call tries again). Used by EncryptedPrefsFactory so a credential
 * check reads an already-open store instead of re-opening it through the Android Keystore every time.
 */
class OnceCache<V : Any> {
    private val values = ConcurrentHashMap<String, V>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun peek(key: String): V? = values[key]

    fun get(key: String, build: () -> V): V {
        values[key]?.let { return it }
        val lock = locks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            values[key]?.let { return it }
            val v = build()
            values[key] = v
            return v
        }
    }
}
