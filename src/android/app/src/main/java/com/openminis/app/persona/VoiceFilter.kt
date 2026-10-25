package com.openminis.app.persona

import android.content.Context

/**
 * [Mines-38.9.15 伪人核心 · VoiceFilter] Android side: the LLM rewrite into the user's style (their cheapest working
 * model, 15 s), falling back to [VoiceFilterCore.ruleRewrite]. Whatever comes back is filtered again in code, so a
 * leak cannot ship even when the rewrite model writes one.
 */
object VoiceFilter {

    /** (text that may leave the phone, whether the original leaked) */
    suspend fun rewrite(c: Context, text: String, to: String? = null): Pair<String, Boolean> {
        Persona.init(c)
        val style = Persona.style.value
        val system = VoiceFilterCore.rewritePrompt(Persona.profile.value, style) +
            (to?.let { "\n" + Persona.briefFor(it) } ?: "")
        val model = runCatching { ask(c, system, text) }.getOrNull()
        val (out, leaked) = VoiceFilterCore.finish(text, model, style)
        if (leaked) Persona.record("", "voice", true, "stripped", VoiceFilterCore.leaks(text).take(3).joinToString("/"))
        return out to leaked
    }

    /** code-only pass for paths that cannot wait for a model (typing into a page): strip + rule shape */
    fun filterNow(text: String): Pair<String, Boolean> {
        val leaked = VoiceFilterCore.hasLeak(text)
        if (!leaked) return text to false
        val out = VoiceFilterCore.ruleRewrite(text, Persona.style.value)
        Persona.record("", "voice", true, "stripped", VoiceFilterCore.leaks(text).take(3).joinToString("/"))
        return out to true
    }

    private suspend fun ask(c: Context, system: String, prompt: String): String? {
        val a = c.applicationContext as? com.openminis.app.MinisApp ?: return null
        val repo = a.providerRepository
        val cheapRe = Regex("(?i)(flash|mini|lite|haiku|turbo|nano|small|air|8b|7b)")
        val entries = repo.allVisibleEntries().filter { e ->
            com.openminis.app.plus.ErrZhCore.chatCapable(e.baseModel.id) && repo.instance(e.providerInstanceId)?.isEnabled == true
        }.sortedByDescending { cheapRe.containsMatchIn(it.baseModel.id) }.take(2)
        for (e in entries) {
            val inst = repo.instance(e.providerInstanceId) ?: continue
            val key = repo.usableApiKey(inst) ?: (if (repo.hasAnyCredential(inst)) "" else null) ?: continue
            val r = runCatching {
                kotlinx.coroutines.withTimeout(15_000L) {
                    com.openminis.app.provider.ProviderFactory.create(inst, key, e.baseModel, c, overrides = e.overrides)
                        .sendMessage(messages = listOf(com.openminis.app.data.model.LLMMessage(role = com.openminis.app.data.model.LLMMessage.Role.USER, content = prompt)),
                            systemPrompt = system, maxTokens = 800).text
                }
            }.getOrNull()
            if (!r.isNullOrBlank()) return r.trim().trim('"', '“', '”', '「', '」')
        }
        return null
    }
}
