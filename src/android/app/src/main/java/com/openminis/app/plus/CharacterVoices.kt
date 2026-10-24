package com.openminis.app.plus

import android.content.Context
import org.json.JSONObject

/**
 * [Mines-38.1] 「不同的 key 调用不同的人声」 (user 16:07): every AI character (Kimi, Claude, Gemini,
 * 小鲸/DeepSeek …) has its OWN voice: source (Gemini 自带 / OpenRouter / 免费声音 / 克隆) + voice id.
 * Switching the character switches the voice. Defaults never collide between characters:
 * Gemini key → a distinct Gemini voice each; else OpenRouter key → its TTS; else a distinct free
 * Edge voice each (Claude 晓晓 温暖, Kimi 晓伊 活泼, 小鲸/DeepSeek 云希 …).
 *
 * The call engine still reads the global voice settings (ExternalTts / gemini extras / VoiceClone);
 * [apply] writes the character's choice into them right before a call starts or when it is picked,
 * and [saveFromGlobals] stores whatever the picker set for that character.
 */
object CharacterVoices {
    const val SRC_GEMINI = ExternalTts.ENGINE_GEMINI
    const val SRC_OR = ExternalTts.ENGINE_OPENROUTER
    const val SRC_EDGE = ExternalTts.ENGINE_EDGE
    const val SRC_CLONE = "clone"

    data class Choice(val source: String, val voice: String)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("mines_character_voices", Context.MODE_PRIVATE)

    private val geminiDefault = mapOf("claude" to "Kore", "kimi" to "Leda", "whale" to "Puck", "grok" to "Fenrir",
        "gpt" to "Orus", "gemini" to "Zephyr", "wenxin" to "Aoede", "custom" to "Charon")
    private val edgeDefault = mapOf("claude" to "zh-CN-XiaoxiaoNeural", "kimi" to "zh-CN-XiaoyiNeural", "whale" to "zh-CN-YunxiNeural",
        "grok" to "zh-CN-YunjianNeural", "gpt" to "zh-CN-YunyangNeural", "gemini" to "zh-TW-HsiaoChenNeural",
        "wenxin" to "zh-CN-YunxiaNeural", "custom" to "zh-CN-liaoning-XiaobeiNeural")

    fun hasGemini(): Boolean = runCatching { GeminiRoute.apiKey() != null }.getOrDefault(false)

    fun defaultFor(c: Context, character: String): Choice = when {
        hasGemini() -> Choice(SRC_GEMINI, geminiDefault[character] ?: "Kore")
        ExternalTts.openRouterKey(c) != null -> Choice(SRC_OR, ExternalTts.settings(c).orVoice)
        else -> Choice(SRC_EDGE, edgeDefault[character] ?: ExternalTts.DEFAULT_EDGE_VOICE)
    }

    fun get(c: Context, character: String): Choice {
        val raw = prefs(c).getString(character, null) ?: return defaultFor(c, character)
        return runCatching { JSONObject(raw).let { Choice(it.getString("s"), it.optString("v")) } }.getOrElse { defaultFor(c, character) }
    }

    fun set(c: Context, character: String, ch: Choice) {
        prefs(c).edit().putString(character, JSONObject().put("s", ch.source).put("v", ch.voice).toString()).apply()
    }

    fun current(): String = runCatching { PlusPrefs.state.value.petStyle }.getOrDefault("claude").ifBlank { "claude" }

    /** Writes the character's voice into the settings the call engine reads. */
    fun apply(c: Context, character: String = current()) {
        val ch = get(c, character)
        val tts = ExternalTts.settings(c)
        when (ch.source) {
            SRC_CLONE -> { VoiceClone.setEnabled(c, true); ExternalTts.save(c, tts.copy(engine = SRC_GEMINI)) }
            SRC_GEMINI -> {
                VoiceClone.setEnabled(c, false); ExternalTts.save(c, tts.copy(engine = SRC_GEMINI))
                if (ch.voice.isNotBlank()) { PlusVault.setExtra("gemini", "voice", ch.voice); PlusVault.setExtra("gemini", "voice_user", "1") }
            }
            SRC_OR -> { VoiceClone.setEnabled(c, false); ExternalTts.save(c, tts.copy(engine = SRC_OR, orVoice = ch.voice.ifBlank { tts.orVoice })) }
            else -> { VoiceClone.setEnabled(c, false); ExternalTts.save(c, tts.copy(engine = SRC_EDGE, edgeVoice = ch.voice.ifBlank { ExternalTts.DEFAULT_EDGE_VOICE })) }
        }
    }

    /** Reads what the picker just set in the global settings and stores it for [character]. */
    fun saveFromGlobals(c: Context, character: String) {
        val tts = ExternalTts.settings(c)
        val ch = when {
            VoiceClone.settings(c).enabled -> Choice(SRC_CLONE, "")
            tts.engine == SRC_OR -> Choice(SRC_OR, tts.orVoice)
            tts.engine == SRC_EDGE -> Choice(SRC_EDGE, tts.edgeVoice)
            else -> Choice(SRC_GEMINI, PlusVault.extra("gemini", "voice").takeIf { PlusVault.extra("gemini", "voice_user") == "1" } ?: GeminiLive.DEFAULT_VOICE)
        }
        set(c, character, ch)
    }

    /** 「免费声音 · 晓伊 女声 活泼」 */
    fun label(c: Context, ch: Choice): String = when (ch.source) {
        SRC_CLONE -> "我的克隆声音"
        SRC_OR -> "OpenRouter · " + ch.voice.substringBefore(':')
        SRC_EDGE -> "免费声音 · " + (ExternalTts.edgeVoices[ch.voice] ?: ch.voice).replace(" · ", " ")
        else -> "Gemini 自带 · " + (GeminiLive.voiceLabels[ch.voice]?.replace(" · ", " ") ?: ch.voice)
    }
}
