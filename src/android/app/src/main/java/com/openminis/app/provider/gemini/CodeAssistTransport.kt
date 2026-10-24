package com.openminis.app.provider.gemini

import org.json.JSONObject

/**
 * [Mines-38.9.11 b] How [GeminiProvider] reaches Gemini when the instance signs in with Google accounts (Gemini CLI /
 * Code Assist) instead of an API key. The transport owns the account pool: it picks an account, wraps [request] in the
 * Code Assist envelope, rotates to the next account on quota / login errors and switches to the US-exit route when Google
 * refuses the region. It returns a SUCCESSFUL response (caller reads + closes it) or throws an LLMError with a plain
 * Chinese reason. Answers come back wrapped as {response:{…}}; the provider unwraps them.
 */
interface CodeAssistTransport {
    /** [stream] true → :streamGenerateContent?alt=sse, false → :generateContent. */
    suspend fun execute(modelId: String, request: JSONObject, stream: Boolean): okhttp3.Response

    companion object {
        /** The normal Gemini answer inside a Code Assist envelope (the object itself when not wrapped). */
        fun unwrap(o: JSONObject): JSONObject = o.optJSONObject("response") ?: o
    }
}
