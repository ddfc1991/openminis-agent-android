package com.openminis.app.plus

/**
 * [Mines-38.9.11.3] user (partially blind, cannot read English UI text): every error a person sees is ONE short Chinese line;
 * the raw English stays behind 「复制详情」. Also: which models may do chat at all. Pure, unit-tested (ErrZhCoreTest).
 */
object ErrZhCore {
    private fun l(s: String?) = (s ?: "").lowercase()

    /** worth one more try on the other route: refused (403), cut connection, timeout */
    fun retryable(raw: String?): Boolean = l(raw).let { s ->
        Regex("\\b403\\b").containsMatchIn(s) || "eof" in s || "stream was reset" in s || "unexpected end of stream" in s ||
            "connection reset" in s || "timeout" in s || "timed out" in s || "broken pipe" in s || "socket closed" in s
    }

    /** a news / feed source: 「Reddit 拒绝访问，已跳过」「Hacker News 连接中断，已重试」 */
    fun source(name: String, raw: String?, retried: Boolean = false, recovered: Boolean = false): String {
        val s = l(raw); val n = friendly(name)
        if (recovered) return "$n 连接中断，已重试成功"
        val tail = if (retried) "，重试也不行，已跳过" else "，已跳过"
        return when {
            Regex("\\b(401|403)\\b").containsMatchIn(s) -> "$n 拒绝访问$tail"
            Regex("\\b404\\b").containsMatchIn(s) -> "$n 地址不存在$tail"
            Regex("\\b429\\b").containsMatchIn(s) || "too many" in s -> "$n 访问太频繁$tail"
            Regex("\\b5\\d\\d\\b").containsMatchIn(s) -> "$n 服务器出错$tail"
            "eof" in s || "reset" in s || "unexpected end" in s || "broken pipe" in s || "socket closed" in s -> "$n 连接中断$tail"
            "timeout" in s || "timed out" in s -> "$n 等太久没回应$tail"
            "unable to resolve" in s || "unknownhost" in s || "no address" in s -> "$n 网络不通（域名解析失败）$tail"
            "connect" in s && ("refused" in s || "failed" in s) -> "$n 网络不通$tail"
            "ssl" in s || "certificate" in s || "handshake" in s -> "$n 加密连接失败$tail"
            else -> "$n 暂时读不到$tail"
        }
    }

    /** source names as the user knows them */
    fun friendly(name: String): String = when {
        name.startsWith("r/") -> "Reddit（$name）"
        name.equals("hn", true) || name.contains("hacker news", true) -> "Hacker News"
        else -> name
    }

    /** the model refused because it is not a chat model (TTS / embedding / image) */
    fun notChat(raw: String?): Boolean = l(raw).let { s ->
        "cannot be used with chat" in s || "not a chat model" in s || "does not support chat" in s || "text-to-speech" in s ||
            "is not supported for chat" in s || "embedding model" in s || "not supported in v1/chat/completions" in s
    }

    /** a model failure for the user: one Chinese line (ProviderRouteCore.zhError covers keys / cut streams / 429 / timeouts) */
    fun model(id: String, raw: String?, switched: Boolean = true): String {
        val tail = if (switched) "，已换下一个" else ""
        if (notChat(raw)) return "模型 $id 不能用来聊天$tail"
        ProviderRouteCore.zhError(raw, "模型 $id", switched)?.let { return it }
        val s = l(raw)
        return when {
            Regex("\\b400\\b").containsMatchIn(s) -> "模型 $id 不接受这个请求（400）$tail"
            Regex("\\b404\\b").containsMatchIn(s) -> "模型 $id 不存在或已下线$tail"
            Regex("\\b5\\d\\d\\b").containsMatchIn(s) -> "模型 $id 的服务器出错$tail"
            else -> "模型 $id 没回应$tail"
        }
    }

    private val NON_CHAT = Regex("(?i)(^|[-_/.:])(tts|speech|whisper|transcri\\w*|asr|embed\\w*|bge|rerank\\w*|moderation|dall-e|gpt-image|imagen|flux|" +
        "stable-diffusion|sdxl|sd3|kolors|cogview|wanx|seedream|seededit|midjourney|image-gen|vision-embed|clip|musicgen|lyria|veo|sora|kling)([-_/.:]|\\d|$)")

    /** may this model id be used for chat / writing tasks (TTS, embedding, image, video, audio-only models may not) */
    fun chatCapable(id: String): Boolean = !NON_CHAT.containsMatchIn(id)

    /** a stored line from before 38.9.11.3 (「r/LocalLLaMA：HTTP 403」): its English part becomes the Chinese line */
    fun legacyNote(line: String): String {
        val name = line.substringBefore('：', ""); val raw = line.substringAfter('：', line)
        val hasEnglishOnly = raw.none { it in '\u4e00'..'\u9fff' } && raw.any { it.isLetter() }
        return if (name.isNotBlank() && hasEnglishOnly) source(name, raw) else line
    }
}
