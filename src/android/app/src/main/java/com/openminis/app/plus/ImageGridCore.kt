package com.openminis.app.plus

/**
 * [Mines-38.9.11.1 hotfix] user: images "like Hark", not the fanned card stack. Sent message = a neat grid of rounded
 * thumbnails: 1 → one larger picture, 2–4 → 2 columns, 5+ → 3 columns (at most 6 tiles, the last one says +N);
 * composer = a row of small rounded squares, each with its own ×. Pure geometry, unit-tested (ImageGridCoreTest).
 */
object ImageGridCore {
    const val MAX_TILES = 6
    const val GAP_DP = 4f
    const val COMPOSER_TILE_DP = 56f
    const val SINGLE_W_DP = 200f

    fun columns(n: Int): Int = when { n <= 1 -> 1; n <= 4 -> 2; else -> 3 }
    fun shown(n: Int): Int = n.coerceIn(0, MAX_TILES)
    /** "+N" on the last shown tile, 0 = none */
    fun more(n: Int): Int = (n - MAX_TILES).coerceAtLeast(0)
    fun rows(n: Int): Int { val s = shown(n); val c = columns(n); return if (s == 0) 0 else (s + c - 1) / c }
    /** square tile edge for 2+ images (dp) */
    fun tileDp(n: Int): Float = when (columns(n)) { 2 -> 104f; 3 -> 76f; else -> SINGLE_W_DP }
    /** single image: width fixed, height from its aspect (w / h) clamped to 3:4 … 4:3 */
    fun singleHeightDp(aspect: Float?): Float = SINGLE_W_DP / (aspect ?: 1f).coerceIn(0.75f, 1.3333f)
    /** grid outer size (dp) */
    fun gridWidthDp(n: Int): Float { val c = minOf(columns(n), shown(n)).coerceAtLeast(1); return if (n <= 1) SINGLE_W_DP else c * tileDp(n) + (c - 1) * GAP_DP }
    fun gridHeightDp(n: Int, singleAspect: Float? = null): Float = if (n <= 1) singleHeightDp(singleAspect) else rows(n) * tileDp(n) + (rows(n) - 1) * GAP_DP
}
