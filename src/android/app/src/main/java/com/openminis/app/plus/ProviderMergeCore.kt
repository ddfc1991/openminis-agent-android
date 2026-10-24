package com.openminis.app.plus

/**
 * [Mines-38.9.11.2] 合并重复供应商 (user 自检 2026-10-21: 商汤 ×5 token.sensenova.cn, AMD ×14, xkiro ×2, Cloudflare AI #2 +
 * newapi-cf on one host, 爱思 / 爱思国际 apihub.agnes-ai.com). Pure, unit-tested (ProviderMergeCoreTest).
 * Providers whose base URL has the same host form one group; the keeper is the one with the most models (then the oldest).
 * Merging = the keeper gets every member's key as a rotating key pool (its own key first, duplicates dropped, empty 1-model
 * shells still give their key) and the union of the models; the other members go. Nothing merges without the user's tap.
 */
object ProviderMergeCore {
    data class P(val id: String, val label: String, val baseUrl: String?, val keys: List<String>, val modelIds: List<String>, val createdAt: Long)
    data class Group(val host: String, val keeper: String, val members: List<String>, val labels: List<String>,
                     val keys: List<String>, val models: List<String>, val pathsDiffer: Boolean)

    fun host(url: String?): String = runCatching {
        val u = url?.trim().orEmpty(); if (u.isEmpty()) return ""
        java.net.URI(if (u.contains("://")) u else "https://$u").host.orEmpty().lowercase().removePrefix("www.")
    }.getOrDefault("")

    private fun path(url: String?): String = runCatching {
        val u = url?.trim().orEmpty(); java.net.URI(if (u.contains("://")) u else "https://$u").path.orEmpty().trimEnd('/').removeSuffix("/v1")
    }.getOrDefault("")

    fun plan(list: List<P>): List<Group> = list.filter { host(it.baseUrl).isNotEmpty() }.groupBy { host(it.baseUrl) }
        .filter { it.value.size >= 2 }
        .map { (h, ps) ->
            val keeper = ps.sortedWith(compareByDescending<P> { it.modelIds.size }.thenBy { it.createdAt }).first()
            val ordered = listOf(keeper) + ps.filter { it.id != keeper.id }.sortedBy { it.createdAt }
            Group(h, keeper.id, ordered.map { it.id }, ordered.map { it.label },
                ordered.flatMap { it.keys }.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                ordered.flatMap { it.modelIds }.distinct(), ps.map { path(it.baseUrl) }.distinct().size > 1)
        }.sortedByDescending { it.members.size }

    /** entries of removed members → where they go: same model at the keeper = that entry, else moved under the keeper */
    fun remap(keeperId: String, keeperModels: Set<String>, removed: List<Pair<String, String>> /* entryId to modelId */,
              entryKey: (String, String) -> String): Map<String, String> =
        removed.associate { (eid, mid) -> eid to entryKey(keeperId, mid) }

    /** one line per group for the preview */
    fun line(g: Group): String = "${g.labels.first()} 等 ${g.members.size} 个（${g.host}）→ 合成 1 个：${g.keys.size} 把 key 轮换，${g.models.size} 个模型" +
        if (g.pathsDiffer) "（注意：地址路径不完全一样，合并后用「${g.labels.first()}」的地址）" else ""

    /** round-robin pick from a key pool */
    /** [38.9.11.3] round-robin over the keys not marked invalid (all invalid → the whole pool, so a fixed key still gets tried) */
    fun pickGood(pool: List<String>, counter: Long, isBad: (String) -> Boolean): String? {
        val good = pool.filter { !isBad(it) }
        return pick(good.ifEmpty { pool }, counter)
    }
    /** keys of the pool other than [current] that are not marked invalid */
    fun othersGood(pool: List<String>, current: String?, isBad: (String) -> Boolean): Int = pool.count { it != current && !isBad(it) }

    fun pick(pool: List<String>, counter: Long): String? = if (pool.isEmpty()) null else pool[(counter % pool.size).toInt().let { if (it < 0) it + pool.size else it }]
}
