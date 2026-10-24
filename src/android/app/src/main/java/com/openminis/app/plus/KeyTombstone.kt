package com.openminis.app.plus

/**
 * [Mines-38.9.1] user 2026-10-16 「grok 有一个坏的 key 去除不了」「8 个删到 2 个就不行了」. Root cause: 账号连接 adopts every
 * 模型组 key it does not hold yet ([ProviderKeys.adopt], via = "model") each time the page opens, so deleting an account
 * whose key also sits in 模型组 only lasted until the next open — it came straight back (the two left were the ones in
 * 模型组). Now a deleted key leaves a tombstone (a short SHA-256 of the key, never the key) and adoption skips it; typing
 * or pasting the same key again on purpose clears the tombstone. Pure part, unit-tested.
 */
object KeyTombstone {
    fun mark(key: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray())
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    fun parse(raw: String?): Set<String> = raw?.split(',')?.map { it.trim() }?.filter { it.length == 24 }?.toSet() ?: emptySet()
    fun add(raw: String?, key: String): String = (parse(raw) + mark(key)).toList().takeLast(200).joinToString(",")
    fun clear(raw: String?, key: String): String = (parse(raw) - mark(key)).joinToString(",")

    /** Keys from 模型组 that 账号连接 should adopt: not held already, not deleted by the user. */
    fun toAdopt(found: List<String>, held: Collection<String>, tombs: Set<String>): List<String> =
        found.map { it.trim() }.filter { it.isNotBlank() && it !in held && mark(it) !in tombs }.distinct()
}
