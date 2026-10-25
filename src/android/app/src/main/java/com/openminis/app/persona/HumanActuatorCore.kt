package com.openminis.app.persona

import kotlin.random.Random

/**
 * [Mines-38.9.15 伪人核心 · HumanActuator] The one choke point for every tap, keystroke and scroll the agent makes in
 * either browser engine: a person's pace, not a script's. Variable cadence, the occasional typo that is corrected
 * with a backspace, a pause to read before acting, and uneven scrolling.
 *
 * This is pacing only. Captchas and human checks are still handed to the user (HumanVerify / wait_for_human); there
 * is no captcha solving and no bot-detection evasion here. Unit-tested in HumanActuatorCoreTest (timing bounds).
 */
object HumanActuatorCore {

    /** one keystroke: the gap before it, the character, and whether it is a slip to be corrected */
    data class Key(val delayMs: Long, val ch: Char, val typo: Boolean = false)

    const val MIN_GAP_MS = 45L
    const val MAX_GAP_MS = 240L
    /** a thinking pause after a space / punctuation */
    const val THINK_MIN_MS = 300L
    const val THINK_MAX_MS = 950L
    /** the whole field never takes longer than this, however long the text */
    const val MAX_FIELD_MS = 14_000L
    /** roughly one slip per this many characters, only in texts long enough for one */
    const val TYPO_EVERY = 60
    const val TYPO_MIN_LEN = 25

    private val NEIGHBOURS = mapOf(
        'a' to "sq", 'e' to "wr", 'i' to "ou", 'o' to "ip", 'n' to "bm", 't' to "ry", 's' to "ad", 'r' to "et",
        'l' to "ko", 'c' to "xv", 'd' to "sf", 'm' to "n", 'u' to "yi", 'h' to "gj",
    )

    /**
     * The keystroke plan for [text]. Each entry is the gap BEFORE its character. A typo entry is followed by a
     * backspace entry (ch = [BACKSPACE]) and then the right character, the way a person notices and fixes it.
     */
    fun plan(text: String, rnd: Random = Random.Default, maxTotalMs: Long = MAX_FIELD_MS): List<Key> {
        val out = ArrayList<Key>(text.length + 4)
        val typos = if (text.length >= TYPO_MIN_LEN) text.length / TYPO_EVERY else 0
        val typoAt = HashSet<Int>()
        repeat(typos) { typoAt += 4 + rnd.nextInt((text.length - 6).coerceAtLeast(1)) }
        for ((i, c) in text.withIndex()) {
            var d = MIN_GAP_MS + rnd.nextLong(MAX_GAP_MS - MIN_GAP_MS + 1)
            val prev = if (i > 0) text[i - 1] else ' '
            if (i > 0 && ((prev == ' ' && rnd.nextInt(5) == 0) || prev in ".,;:!?，。；：！？@")) d += THINK_MIN_MS + rnd.nextLong(THINK_MAX_MS - THINK_MIN_MS + 1)
            if (i in typoAt) {
                val wrong = NEIGHBOURS[c.lowercaseChar()]?.randomOrNull(rnd)
                if (wrong != null) {
                    out += Key(d, wrong, typo = true)
                    out += Key(180L + rnd.nextLong(320), BACKSPACE)
                    out += Key(90L + rnd.nextLong(140), c)
                    continue
                }
            }
            out += Key(d, c)
        }
        val total = out.sumOf { it.delayMs }
        if (total > maxTotalMs && total > 0) {
            val f = maxTotalMs.toDouble() / total
            return out.map { it.copy(delayMs = (it.delayMs * f).toLong().coerceAtLeast(8L)) }
        }
        return out
    }

    const val BACKSPACE = '\b'

    /** total time the plan takes, for the caller's timeout */
    fun planMs(keys: List<Key>): Long = keys.sumOf { it.delayMs }

    /** how long a person looks at a page before touching it: longer for more text, bounded */
    fun readDwellMs(textLength: Int, rnd: Random = Random.Default): Long =
        (600L + textLength / 12L + rnd.nextLong(900)).coerceIn(600L, 6_000L)

    /** pause between scrolling something into view and pressing it */
    fun clickPauseMs(rnd: Random = Random.Default): Long = 250L + rnd.nextLong(451)

    /** finger down → up */
    fun pressMs(rnd: Random = Random.Default): Long = 60L + rnd.nextLong(81)

    /** a few pixels off the exact centre, never outside a small element */
    fun jitter(sizePx: Float, rnd: Random = Random.Default): Float {
        val r = (sizePx / 6f).coerceIn(0f, 6f)
        return if (r <= 0f) 0f else (rnd.nextFloat() * 2f - 1f) * r
    }

    /** a scroll split into 3–6 uneven steps that add up to [amount], with a gap after each */
    data class Scroll(val px: Int, val gapMs: Long)

    fun scrollPlan(amount: Int, rnd: Random = Random.Default): List<Scroll> {
        if (amount == 0) return listOf(Scroll(0, 0))
        val n = 3 + rnd.nextInt(4)
        val w = List(n) { 0.6 + rnd.nextDouble() }
        val s = w.sum()
        val px = w.map { (amount * it / s).toInt() }.toMutableList()
        px[px.lastIndex] += amount - px.sum()
        return px.map { Scroll(it, 120L + rnd.nextLong(380)) }
    }

    /** the per-account action budget left today */
    fun remaining(cap: Int, usedToday: Int): Int = (cap - usedToday).coerceAtLeast(0)
}
