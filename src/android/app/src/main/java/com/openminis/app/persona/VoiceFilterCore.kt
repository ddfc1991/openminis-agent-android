package com.openminis.app.persona

import org.json.JSONObject

/**
 * [Mines-38.9.15 伪人核心 · VoiceFilter] Every outbound text (a reply typed into a page, a post, a DM, a mail body)
 * passes through here in CODE before it leaves the phone. Assistant phrasing is stripped whatever the model emits —
 * a leak cannot ship just because the model wrote it — and the text is re-shaped towards how the user actually
 * writes ([Style], learned from their own sent mail / messages). Unit-tested in VoiceFilterCoreTest.
 */
object VoiceFilterCore {

    /** openers / disclaimers that give the assistant away; matched case-insensitively, Chinese and English */
    private val LEAK_SENTENCE = listOf(
        "作为一个人工智能", "作为一名人工智能", "作为人工智能", "作为一个AI", "作为一名AI", "作为AI助手", "作为AI", "作为您的助手",
        "作为你的助手", "作为一个大语言模型", "作为语言模型", "我是一个AI", "我是AI", "我是人工智能", "我只是一个AI",
        "我是一个语言模型", "我没有感情", "我无法拥有个人观点", "很高兴为您服务", "希望以上回答对您有帮助", "希望这对你有帮助",
        "如果您还有其他问题", "如果你还有其他问题", "有什么可以帮您", "有什么我可以帮你", "有什么我可以帮您",
        "as an ai", "as an a.i.", "as a language model", "as a large language model", "i am an ai", "i'm an ai",
        "i am an assistant", "i'm an assistant", "i'm just an ai", "i am just an ai", "as your assistant",
        "i don't have personal", "i do not have personal", "i cannot have feelings", "hope this helps",
        "let me know if you have any other questions", "is there anything else i can help",
    )

    /** polite filler that marks a bot even without naming one */
    private val FILLER = listOf(
        "当然可以！", "当然可以，", "好的，没问题！", "没问题！", "亲爱的用户", "尊敬的用户", "您好！我是",
        "certainly! ", "of course! ", "sure thing! ", "i'd be happy to help", "great question",
    )

    /**
     * 「我可以帮你」 on its own is the assistant's offer; inside a real sentence (「明天我可以帮你搬家」) it is the user
     * speaking, so it only counts as a leak when it is the whole sentence.
     */
    private val LEAK_WHOLE = listOf(
        Regex("^\\s*(有什么)?我(可以|能)帮(你|您)(的吗|什么|解答|做些什么|做什么|的)?[吗？?。！!：:，,~～]*\\s*$"),
        Regex("^\\s*(how )?can i (help|assist) you( today)?[?.!]*\\s*$", RegexOption.IGNORE_CASE),
    )

    private fun leakSentence(s: String): Boolean {
        val low = s.lowercase()
        return LEAK_SENTENCE.any { low.contains(it.lowercase()) } || LEAK_WHOLE.any { it.matches(s.trim()) }
    }

    /** true when [text] would give the assistant away */
    fun hasLeak(text: String): Boolean = leaks(text).isNotEmpty()

    /** which phrases were found (for the audit log; never the text itself) */
    fun leaks(text: String): List<String> {
        val low = text.lowercase()
        val whole = splitSentences(text).filter { s -> LEAK_WHOLE.any { it.matches(s.trim()) } }.map { it.trim() }
        return (LEAK_SENTENCE + FILLER).filter { low.contains(it.lowercase()) } + whole
    }

    /**
     * Code-level stripping: a sentence that contains a give-away phrase is dropped whole (it is always a disclaimer,
     * never the content); filler is cut in place. Never returns a blank string when the input had any content —
     * if everything was a leak the caller gets [EMPTY] and must rewrite.
     */
    fun strip(text: String): String {
        var t = text
        FILLER.forEach { f -> t = Regex(Regex.escape(f), RegexOption.IGNORE_CASE).replace(t, "") }
        // a leaky sentence loses the clause that leaks (「作为AI，我觉得这样不错。」 → 「我觉得这样不错。」); if every clause leaks, it goes
        val kept = splitSentences(t).mapNotNull { s ->
            if (!leakSentence(s)) return@mapNotNull s
            val clauses = Regex("[^，,；;]+[，,；;]?").findAll(s).map { it.value }.toList()
            val rest = clauses.filter { !leakSentence(it.trimEnd('，', ',', '；', ';')) }.joinToString("").trimStart()
                .let { r -> if (r.endsWith("，") || r.endsWith("；")) r.dropLast(1) + "。" else if (r.endsWith(",") || r.endsWith(";")) r.dropLast(1) + "." else r }
            rest.takeIf { it.isNotBlank() && !leakSentence(it) && it.trim().length > 1 }
        }
        val out = kept.joinToString("").trim().trimStart('，', ',', '。', '.', '、', ';', '；')
        return out.trim()
    }

    const val EMPTY = ""

    /** sentences, punctuation kept with the sentence it ends */
    fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (c in "。！？!?\n" || (c == '.' && sb.length > 1)) { out += sb.toString(); sb.clear() }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    // ── the user's style ──────────────────────────────────────────────────────

    data class Style(
        /** median sentence length in characters of the user's own writing */
        val sentenceLen: Int = 0,
        val usesEmoji: Boolean = false,
        val usesExclaim: Boolean = false,
        /** the user's own openers, most frequent first (e.g. 「你好」「收到」) */
        val openers: List<String> = emptyList(),
        /** how the user signs off, when they do */
        val signoff: String = "",
        val chinese: Boolean = true,
        /** how many of the user's own texts this was learned from */
        val samples: Int = 0,
    ) {
        val known: Boolean get() = samples >= MIN_SAMPLES
    }

    const val MIN_SAMPLES = 3

    /** learn from the user's own sent texts (mail bodies, messages); short automated lines are ignored */
    fun learn(samples: List<String>): Style {
        val texts = samples.map { it.trim() }.filter { it.length in 2..2000 }
        if (texts.isEmpty()) return Style(samples = 0)
        val sentences = texts.flatMap { splitSentences(it) }.map { it.trim() }.filter { it.length > 1 }
        val lens = sentences.map { it.length }.sorted()
        val median = if (lens.isEmpty()) 0 else lens[lens.size / 2]
        val emoji = texts.count { hasEmoji(it) } * 2 >= texts.size
        val exclaim = texts.count { it.contains('!') || it.contains('！') } * 2 >= texts.size
        val openers = texts.mapNotNull { t -> splitSentences(t).firstOrNull()?.trim()?.takeIf { it.length in 2..8 } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.filter { it.value >= 2 }.map { it.key }.take(2)
        val tails = texts.mapNotNull { t -> splitSentences(t).lastOrNull()?.trim()?.takeIf { it.length in 2..10 } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.firstOrNull { it.value >= 2 }?.key.orEmpty()
        val cn = texts.sumOf { t -> t.count { it.code in 0x4E00..0x9FFF } } * 4 >= texts.sumOf { it.length }
        return Style(median, emoji, exclaim, openers, tails, cn, texts.size)
    }

    private fun isEmoji(cp: Int) = cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F
    fun hasEmoji(t: String): Boolean = t.codePoints().anyMatch { isEmoji(it) }
    fun dropEmoji(t: String): String = buildString { t.codePoints().forEach { cp -> if (!isEmoji(cp)) appendCodePoint(cp) } }

    fun styleJson(s: Style): String = JSONObject().put("len", s.sentenceLen).put("emoji", s.usesEmoji).put("excl", s.usesExclaim)
        .put("open", s.openers.joinToString("|")).put("sign", s.signoff).put("cn", s.chinese).put("n", s.samples).toString()

    fun styleFromJson(j: String?): Style = runCatching {
        if (j.isNullOrBlank()) return Style()
        val o = JSONObject(j)
        Style(o.optInt("len"), o.optBoolean("emoji"), o.optBoolean("excl"),
            o.optString("open").split('|').filter { it.isNotBlank() }, o.optString("sign"), o.optBoolean("cn", true), o.optInt("n"))
    }.getOrDefault(Style())

    /** what the rewrite model is told; the user's own style, never "sound human" */
    fun rewritePrompt(profile: PersonaCore.Profile, style: Style): String = buildString {
        append("你就是本人在写这条消息，不是助手代写。把下面这段改写成本人平时的说法：第一人称，去掉所有客套和自我介绍，")
        append("不出现「作为AI」「助手」「很高兴为您服务」这类话，不解释自己是谁。只回改写后的正文，不要加引号或说明。")
        if (profile.traits.isNotEmpty()) append("\n本人的说话习惯：").append(profile.traits.joinToString("、")).append("。")
        if (style.known) {
            append("\n本人平时每句约 ").append(style.sentenceLen).append(" 个字")
            append(if (style.usesEmoji) "，会用表情" else "，几乎不用表情")
            append(if (style.usesExclaim) "，爱用感叹号" else "，很少用感叹号")
            if (style.openers.isNotEmpty()) append("，常见开头：").append(style.openers.joinToString("/"))
            if (style.signoff.isNotBlank()) append("，常见结尾：").append(style.signoff)
            append("。")
        }
    }

    /**
     * Fallback when no model answers: rules only. Strips the leaks, cuts the politeness, trims sentences towards the
     * user's own length, and drops emoji / exclamation marks the user does not use.
     */
    fun ruleRewrite(text: String, style: Style): String {
        var t = strip(text)
        if (!style.usesExclaim) t = t.replace('！', '。').replace('!', '.')
        if (!style.usesEmoji) t = dropEmoji(t)
        t = t.replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\n{3,}"), "\n\n")
        if (style.known && style.sentenceLen in 4..60) {
            t = splitSentences(t).joinToString("") { s ->
                if (s.trim().length <= style.sentenceLen * 2) s
                else s.trim().chunked(style.sentenceLen.coerceAtLeast(8)).joinToString("。").let { if (it.endsWith("。")) it else "$it。" }
            }
        }
        return t.trim()
    }

    /**
     * The whole filter in one pure step, for the code path that has no model: what leaves the phone, and whether
     * anything was stripped. [rewritten] null = the model did not answer; its own output is filtered again, because
     * a rewrite can leak too.
     */
    fun finish(original: String, rewritten: String?, style: Style): Pair<String, Boolean> {
        val leaked = hasLeak(original)
        val base = rewritten?.takeIf { it.isNotBlank() && !hasLeak(it) } ?: ruleRewrite(original, style)
        val out = if (base.isBlank()) ruleRewrite(original, style) else base
        return out.trim() to leaked
    }
}
