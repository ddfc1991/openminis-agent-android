package com.openminis.app.sandbox.offload

import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.8 v5d] Fast phone screen reading, fastest tier first (user-approved 2026-10-16):
 *
 *   1 回放 replay   a saved step path that already worked for this app + task is replayed directly; every step is
 *                   checked against the live screen first (same resource-id / label near the saved place) and the
 *                   replay stops at the first mismatch → the normal path below takes over.
 *   2 无障碍树 tree  the accessibility tree (or Shizuku uiautomator): compact element list, no screenshot. Default.
 *   3 OCR            on-device ML Kit text recognition on a screenshot, only when the tree has (almost) no text
 *                   (games, canvas, image-only WebViews). OCR lines become tappable elements.
 *   4 云端视觉 cloud  last resort: `ui screenshot` + read_image to the user's cloud vision model group. Never a local LLM.
 *
 * This file is the pure part (no Android types) so the parsing / ranking / matching is unit-tested on the JVM.
 */
object ScreenTiers {
    const val TIER_REPLAY = "1·回放"
    const val TIER_TREE = "2·无障碍树"
    const val TIER_OCR = "3·OCR"
    const val TIER_CLOUD = "4·云端视觉"

    /** Screen-agnostic element: what tiers 2 and 3 produce and tier 1 matches against. */
    data class Item(
        val label: String, val desc: String = "", val rid: String = "", val role: String = "",
        val l: Int, val t: Int, val r: Int, val b: Int,
        val clickable: Boolean = false, val source: String = "tree",
    ) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
        fun area() = (r - l).toLong() * (b - t).toLong()
    }

    // ── tier 3 trigger ──────────────────────────────────────────────────

    /** A label that carries no words a person would read (a bare #resource-id or a widget class name). */
    fun isGenericLabel(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        if (t.startsWith("#")) return true
        if (Regex("^[A-Z][A-Za-z]*(View|Layout|Button|Image|Text|Group|Frame|Canvas|Surface)$").matches(t)) return true
        return false
    }

    /**
     * True when the tree is too thin to act on, so OCR should be tried: fewer than [minText] elements with a
     * readable label (games / canvas / video / image-only WebViews typically expose 0–2).
     */
    fun needsOcr(items: List<Item>, minText: Int = 3): Boolean = items.count { !isGenericLabel(it.label) || it.desc.isNotBlank() } < minText

    /** OCR lines that do not repeat what the tree already says at the same place (overlap > 50 % with the same text). */
    fun mergeOcr(tree: List<Item>, ocr: List<Item>): List<Item> {
        val out = ArrayList<Item>()
        for (o in ocr) {
            val txt = norm(o.label)
            if (txt.isEmpty()) continue
            val dup = tree.any { t -> overlapRatio(t, o) > 0.5f && (norm(t.label).contains(txt) || txt.contains(norm(t.label)) && norm(t.label).isNotEmpty()) }
            if (!dup && out.none { norm(it.label) == txt && overlapRatio(it, o) > 0.5f }) out += o.copy(source = "ocr", role = o.role.ifEmpty { "文字" })
        }
        return out
    }

    fun overlapRatio(a: Item, b: Item): Float {
        val w = minOf(a.r, b.r) - maxOf(a.l, b.l); val h = minOf(a.b, b.b) - maxOf(a.t, b.t)
        if (w <= 0 || h <= 0) return 0f
        val small = minOf(a.area(), b.area()).coerceAtLeast(1)
        return (w.toLong() * h.toLong()).toFloat() / small
    }

    // ── [Mines-38.9] tier 3 · cloud OCR ────────────────────────────────

    const val TIER_OCR_CLOUD = "3·OCR·云端"

    fun hasHan(s: String): Boolean = s.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    /**
     * Use the cloud OCR model instead of / after ML Kit (Latin only): ML Kit read fewer than [minLines] lines, the screen
     * shows Chinese (tree labels or an OCR line contain Han characters), the phone is set to Chinese and ML Kit's lines
     * are short fragments, or most of what ML Kit read is garbage (no letter/digit runs of 2+).
     */
    fun wantsCloudOcr(tree: List<Item>, latin: List<Item>, zhLocale: Boolean, minLines: Int = 3): Boolean {
        if (latin.size < minLines) return true
        if (tree.any { hasHan(it.label) || hasHan(it.desc) } || latin.any { hasHan(it.label) }) return true
        val avg = latin.sumOf { it.label.length }.toDouble() / latin.size
        if (zhLocale && avg < 4.0) return true
        val good = latin.count { Regex("[A-Za-z0-9]{2,}").containsMatchIn(it.label) }
        return good * 2 < latin.size
    }

    /**
     * Parse the cloud model's OCR answer: a JSON array of {"t": text, "b": [l, t, r, b]} (coordinates in the sent image,
     * multiplied by [scale] back to screen pixels). Lines without a usable box keep zero bounds (shown, not tappable);
     * a plain-text answer becomes one line per text line.
     */
    fun parseCloudOcr(raw: String, scale: Float = 1f): List<Item> {
        val a = raw.indexOf('['); val z = raw.lastIndexOf(']')
        val out = ArrayList<Item>()
        if (a >= 0 && z > a) runCatching {
            val arr = JSONArray(raw.substring(a, z + 1))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = (o.optString("t").ifBlank { o.optString("text") }).trim()
                if (t.isEmpty()) continue
                val b = o.optJSONArray("b") ?: o.optJSONArray("box") ?: o.optJSONArray("bbox")
                val ok = b != null && b.length() >= 4
                fun c(k: Int) = if (ok) (b!!.optDouble(k, 0.0) * scale).toInt() else 0
                var l = c(0); var tp = c(1); var r = c(2); var bt = c(3)
                if (ok && (r <= l || bt <= tp)) { r = l + r; bt = tp + bt }   // [x, y, w, h] answers
                out += Item(t.take(60), l = l, t = tp, r = r, b = bt, clickable = false, source = "ocr-cloud", role = if (ok && r > l && bt > tp) "文字" else "文字(无位置)")
            }
        }
        if (out.isEmpty()) raw.lines().map { it.trim().trim('-', '*', ' ') }.filter { it.isNotEmpty() && !it.startsWith("```") }.take(80)
            .forEach { out += Item(it.take(60), l = 0, t = 0, r = 0, b = 0, source = "ocr-cloud", role = "文字(无位置)") }
        return out
    }

    // ── text ranking (tap "文本") ────────────────────────────────────────

    fun norm(s: String): String = s.lowercase().replace(Regex("[\\s\\u00A0·•:：,，.。!！?？\"'“”‘’()（）\\[\\]【】]"), "")

    /**
     * Score how well [label] (or its content-desc) answers [query]: 100 exact, 80 starts with, 60 contains,
     * 40 the query contains the label (≥ 2 chars), 20 all query characters in order; 0 no match. Clickable +5.
     */
    fun score(query: String, it: Item): Int {
        val q = norm(query); if (q.isEmpty()) return 0
        fun one(s0: String): Int {
            val s = norm(s0); if (s.isEmpty()) return 0
            return when {
                s == q -> 100
                s.startsWith(q) -> 80
                s.contains(q) -> 60
                s.length >= 2 && q.contains(s) -> 40
                inOrder(q, s) -> 20
                else -> 0
            }
        }
        val base = maxOf(one(it.label), one(it.desc), if (it.rid.isNotEmpty()) one(it.rid.substringAfter('/')) - 10 else 0)
        return if (base > 0 && it.clickable) base + 5 else base
    }

    private fun inOrder(q: String, s: String): Boolean {
        if (q.length < 2) return false
        var i = 0
        for (c in s) if (i < q.length && c == q[i]) i++
        return i == q.length
    }

    /** Best match for [query], ties broken by smaller (more specific) element then top-left order; null when nothing scores. */
    fun best(query: String, items: List<Item>): Item? =
        items.map { it to score(query, it) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Item, Int>> { it.second }.thenBy { it.first.area() }.thenBy { it.first.t }.thenBy { it.first.l })
            .firstOrNull()?.first

    // ── tier 1: saved step paths ─────────────────────────────────────────

    /** One recorded step: verb (tap / type / key / scroll / wait / open …), its target as seen when it worked, and text. */
    data class Step(val verb: String, val label: String = "", val rid: String = "", val cx: Int = -1, val cy: Int = -1, val arg: String = "")

    data class Path(val pkg: String, val activity: String, val task: String, val steps: List<Step>, val savedAt: Long = 0L, val uses: Int = 0)

    fun key(pkg: String, task: String) = pkg + "|" + norm(task)

    fun toJson(p: Path): JSONObject = JSONObject().put("pkg", p.pkg).put("activity", p.activity).put("task", p.task)
        .put("savedAt", p.savedAt).put("uses", p.uses)
        .put("steps", JSONArray().apply {
            p.steps.forEach { s -> put(JSONObject().put("verb", s.verb).put("label", s.label).put("rid", s.rid).put("cx", s.cx).put("cy", s.cy).put("arg", s.arg)) }
        })

    fun fromJson(o: JSONObject): Path? = runCatching {
        val arr = o.getJSONArray("steps")
        Path(o.getString("pkg"), o.optString("activity"), o.getString("task"),
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { s -> Step(s.getString("verb"), s.optString("label"), s.optString("rid"), s.optInt("cx", -1), s.optInt("cy", -1), s.optString("arg")) } },
            o.optLong("savedAt"), o.optInt("uses"))
    }.getOrNull()

    /**
     * The live element a saved tap step means, or null (mismatch → stop replaying): same resource-id first, else the
     * best text match; either must sit within [maxShift] px of where it was (layouts move a little, not across the screen).
     */
    fun matchStep(s: Step, items: List<Item>, maxShift: Int = 260): Item? {
        fun near(it: Item) = s.cx < 0 || (kotlin.math.abs(it.cx - s.cx) <= maxShift && kotlin.math.abs(it.cy - s.cy) <= maxShift)
        if (s.rid.isNotEmpty()) {
            val byId = items.filter { it.rid == s.rid && near(it) }
            if (byId.size == 1) return byId[0]
            if (byId.size > 1 && s.label.isNotEmpty()) byId.firstOrNull { norm(it.label) == norm(s.label) }?.let { return it }
            if (byId.isNotEmpty() && s.label.isEmpty()) return byId.minByOrNull { kotlin.math.abs(it.cx - s.cx) + kotlin.math.abs(it.cy - s.cy) }
        }
        if (s.label.isEmpty()) return null
        val cands = items.filter { near(it) && score(s.label, it) >= 80 }
        return best(s.label, cands)
    }

    /** Steps that are safe to store and replay: never a step that pays, orders or sends (same words the voice guard uses). */
    private val sensitive = Regex("支付|付款|转账|确认付款|立即支付|下单|提交订单|购买|发送|send|pay|transfer|checkout", RegexOption.IGNORE_CASE)
    fun replayable(steps: List<Step>): Boolean = steps.isNotEmpty() && steps.none { sensitive.containsMatchIn(it.label) || sensitive.containsMatchIn(it.arg) }

    // ── output (Mobile-MCP-style list_elements_on_screen) ───────────────

    fun elementsJson(items: List<Item>, tier: String, pkg: String): JSONObject = JSONObject()
        .put("tier", tier).put("package", pkg).put("count", items.size)
        .put("elements", JSONArray().apply {
            items.forEachIndexed { i, e ->
                put(JSONObject().put("n", i + 1).put("text", e.label).put("desc", e.desc).put("id", e.rid).put("role", e.role)
                    .put("clickable", e.clickable).put("source", e.source)
                    .put("center", JSONObject().put("x", e.cx).put("y", e.cy))
                    .put("bounds", JSONArray().put(e.l).put(e.t).put(e.r).put(e.b)))
            }
        })
}
