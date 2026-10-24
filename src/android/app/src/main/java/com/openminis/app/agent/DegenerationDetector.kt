package com.openminis.app.agent

/**
 * [Mines-degen-34] Streaming degeneration detector.
 *
 * User report (2026-10-13, AMD-01 · Gemma 4 31B, 思考 极高, 71 % context): Deep
 * Thinking turned into tens of thousands of garbage characters
 * ("LLLL_나L_yLL//////…", 23K + 7K blocks) while the user's queued message
 * waited. Feed every streamed delta in; once ~1.5K characters are buffered the
 * tail window is checked (cheaply, every [checkEvery] characters) for:
 *  - a long run of one character (≥ 200, e.g. "//////…");
 *  - an exact short loop: the last 600 chars repeating with a period ≤ 300 at least 6 times;
 *  - very low character diversity (≤ 12 distinct characters in 1.5K);
 *  - n-gram collapse: < 15 % distinct 10-grams;
 *  - symbol soup: > 60 % of the window is neither letters, digits, CJK nor whitespace,
 *    or mixed-script noise (Hangul next to Latin junk in a non-Korean stream).
 * Ordinary prose, Chinese text, code, JSON and markdown tables stay well clear of these.
 * Pure Kotlin (no Android) so it runs in the JVM self-test too.
 */
class DegenerationDetector(
    private val window: Int = 1500,
    private val checkEvery: Int = 200,
) {
    private val tail = StringBuilder()
    private var sinceCheck = 0
    var reason: String? = null
        private set
    var total = 0L
        private set

    /** Feeds a delta; returns a reason once the stream has degenerated, else null. */
    fun feed(delta: String): String? {
        if (reason != null) return reason
        if (delta.isEmpty()) return null
        total += delta.length
        tail.append(delta)
        if (tail.length > window * 2) tail.delete(0, tail.length - window)
        sinceCheck += delta.length
        if (sinceCheck < checkEvery || tail.length < MIN_LEN) return null
        sinceCheck = 0
        val w = if (tail.length > window) tail.substring(tail.length - window) else tail.toString()
        reason = analyze(w)
        return reason
    }

    fun reset() { tail.setLength(0); sinceCheck = 0; reason = null; total = 0 }

    companion object {
        const val MIN_LEN = 800

        /** Null when [text] looks like normal output, else a short Chinese reason. */
        fun analyze(text: String): String? {
            if (text.length < MIN_LEN) return null
            // 1) one character repeated
            var run = 1; var best = 1
            for (i in 1 until text.length) {
                if (text[i] == text[i - 1] && text[i] != '\n') { run++; if (run > best) best = run } else run = 1
            }
            if (best >= 200) return "同一个字符连续重复 $best 次"
            // 2) exact short loop at the end
            val seg = text.takeLast(600)
            for (p in 1..300) {
                if (seg.length < p * 6) break
                var ok = true
                for (i in p until seg.length) if (seg[i] != seg[i - p]) { ok = false; break }
                if (ok) return "同一段内容（${p} 字）反复重复"
            }
            // 3) diversity
            val distinct = text.toSet().size
            if (distinct <= 12) return "只剩 $distinct 种字符在重复"
            // 4) n-gram collapse
            val n = 10
            val grams = HashSet<String>()
            var count = 0
            var i = 0
            while (i + n <= text.length) { grams.add(text.substring(i, i + n)); count++; i += 1 }
            if (count > 0 && grams.size.toDouble() / count < 0.15) return "内容在循环（重复片段过多）"
            // 5) symbol soup / mixed-script noise
            var sym = 0; var hangul = 0; var latin = 0; var cjk = 0; var nonSpace = 0
            for (ch in text) {
                if (ch.isWhitespace()) continue
                nonSpace++
                when {
                    ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF' || ch in '\u3130'..'\u318F' -> hangul++
                    ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> cjk++
                    ch.isLetterOrDigit() -> latin++
                    else -> sym++
                }
            }
            if (nonSpace > 0 && sym.toDouble() / nonSpace > 0.60) return "大部分是乱码符号"
            if (nonSpace > 0 && hangul in 1..(nonSpace / 4) && hangul >= 8 && cjk < nonSpace / 10 &&
                sym.toDouble() / nonSpace > 0.30) return "混杂的乱码字符"
            return null
        }
    }
}

/** [Mines-degen-34] Thrown from the stream collector to abort a degenerated generation. */
class DegenerateOutputException(val why: String, val thinkingCap: Boolean = false) :
    Exception(if (thinkingCap) "思考太长（$why），已打断" else "模型输出变成了乱码（$why），已打断")

/** [Mines-degen-34] Thrown from the stream collector when the user's queued message should cut in now. */
class QueuedInterruptException : Exception("用户发来新消息，先处理它")
