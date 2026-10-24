package com.openminis.app.plus

import org.json.JSONObject
import kotlin.random.Random

/**
 * [Mines-38.9.13 mines_browser] Pure part of Mines' own browser (GeckoView, Firefox engine): which calls go to it, the
 * per-task private context id, the proxy prefs, and the human-like pacing (typing gaps, click pauses, scroll steps).
 * Android glue is [MinesBrowser]. Unit-tested in MinesBrowserCoreTest.
 */
object MinesBrowserCore {
    const val FLAG = "mines_browser"
    const val PREFS = "mines_experiments"
    const val ENGINE_GECKO = "gecko"
    const val ENGINE_WEBVIEW = "webview"
    /** a task's private session is closed and wiped after this long without a browser action */
    const val IDLE_MS = 10 * 60_000L

    /** browser_use actions the gecko backend runs; everything else answers [unsupported] */
    val SUPPORTED = setOf(
        "navigate", "get_text", "get_readable", "get_page_info", "find_elements", "click", "type", "scroll", "hover",
        "execute_js", "screenshot", "wait_for_dom_stable", "wait_for_otp", "wait_for_human", "close_tab",
    )

    fun unsupported(action: String) =
        "Error: '$action' is not available in Mines' own browser yet. Use engine \"webview\" for it."

    /** the `engine` argument of a browser_use call: gecko / webview / null (not given or unknown) */
    fun engineOf(argsJson: String): String? =
        runCatching { JSONObject(argsJson).optString("engine").trim().lowercase() }.getOrNull()
            ?.takeIf { it == ENGINE_GECKO || it == ENGINE_WEBVIEW }

    /**
     * Should this call go to the gecko backend? Flag off → never. An explicit engine wins. Without one, an owner that already
     * has a gecko session stays on it (follow-up click/type/get_text after a gecko navigate), except list_tabs / new_tab,
     * which belong to the WebView pool.
     */
    fun useGecko(flagOn: Boolean, engine: String?, ownerHasGecko: Boolean, action: String): Boolean = when {
        !flagOn -> false
        engine == ENGINE_WEBVIEW -> false
        engine == ENGINE_GECKO -> true
        action == "list_tabs" || action == "new_tab" -> false
        else -> ownerHasGecko
    }

    /** private-browsing context id for one task (GeckoSessionSettings.contextId), safe characters only */
    fun contextId(owner: String?, serial: Int): String {
        val o = (owner ?: "chat").filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40).ifEmpty { "chat" }
        return "mines-task-$o-$serial"
    }

    /**
     * Gecko prefs for the proxy: the app's passing local proxy port (split proxy / mihomo / the user's Clash), or the closed
     * port 127.0.0.1:1 under 严格代理 when none passes (never direct), or direct when neither. WebRTC is always off so the
     * real IP cannot leak through it. Values are String / Int / Boolean.
     */
    fun proxyPrefs(port: Int?, strict: Boolean): LinkedHashMap<String, Any> {
        val m = LinkedHashMap<String, Any>()
        val p = port?.takeIf { it in 1..65535 } ?: if (strict) 1 else null
        if (p == null) {
            m["network.proxy.type"] = 0
        } else {
            m["network.proxy.type"] = 1
            m["network.proxy.http"] = "127.0.0.1"; m["network.proxy.http_port"] = p
            m["network.proxy.ssl"] = "127.0.0.1"; m["network.proxy.ssl_port"] = p
            m["network.proxy.share_proxy_settings"] = true
            m["network.proxy.no_proxies_on"] = "localhost,127.0.0.1"
        }
        m["media.peerconnection.enabled"] = false
        return m
    }

    fun proxyLabel(port: Int?, strict: Boolean): String = when {
        port != null && port in 1..65535 -> "代理 127.0.0.1:$port"
        strict -> "严格代理：没有可用代理，已拦截（不直连）"
        else -> "直连"
    }

    // ── human-like pacing ─────────────────────────────────────────────────────

    /**
     * Gap before each character (ms): 45–220 ms, a longer "thinking" pause (350–900 ms) after a space now and then and
     * after punctuation. Long texts are sped up so a whole field never takes more than ~[maxTotalMs].
     */
    fun typingDelays(text: String, rnd: Random = Random.Default, maxTotalMs: Long = 12_000L): List<Long> {
        val out = ArrayList<Long>(text.length)
        for ((i, c) in text.withIndex()) {
            var d = 45L + rnd.nextLong(176)
            val prev = if (i > 0) text[i - 1] else ' '
            if (i > 0 && (prev == ' ' && rnd.nextInt(5) == 0 || prev in ".,;:!?，。；：！？@")) d += 350L + rnd.nextLong(551)
            out += d
        }
        val total = out.sum()
        if (total > maxTotalMs && total > 0) {
            val f = maxTotalMs.toDouble() / total
            for (i in out.indices) out[i] = (out[i] * f).toLong().coerceAtLeast(8L)
        }
        return out
    }

    /** pause after scrolling an element into view, before touching it */
    fun clickPauseMs(rnd: Random = Random.Default): Long = 250L + rnd.nextLong(451)

    /** finger down → up */
    fun pressMs(rnd: Random = Random.Default): Long = 60L + rnd.nextLong(81)

    /** a few pixels off the exact centre, never outside a small element */
    fun jitter(sizePx: Float, rnd: Random = Random.Default): Float {
        val r = (sizePx / 6f).coerceIn(0f, 6f)
        return if (r <= 0f) 0f else (rnd.nextFloat() * 2f - 1f) * r
    }

    /** split a scroll into 3–6 uneven steps that add up to [amount] (sign kept) */
    fun scrollSteps(amount: Int, rnd: Random = Random.Default): List<Int> {
        if (amount == 0) return listOf(0)
        val n = 3 + rnd.nextInt(4)
        val w = List(n) { 0.6 + rnd.nextDouble() }
        val s = w.sum()
        val steps = w.map { (amount * it / s).toInt() }.toMutableList()
        steps[steps.lastIndex] += amount - steps.sum()
        return steps
    }

    // ── control API guard for later skills ───────────────────────────────────

    /**
     * Actions a future skill (social accounts, mail clean-up) must not take without the user's yes: anything that posts,
     * sends or messages other people, or deletes for good. Reading, sorting, archiving and drafting are fine.
     */
    private val CONFIRM_VERBS = setOf("post", "publish", "send", "message", "reply", "comment", "share", "invite", "delete_forever", "pay")
    fun needsUserConfirm(kind: String): Boolean = kind.trim().lowercase() in CONFIRM_VERBS

    // ── bridge replies ───────────────────────────────────────────────────────

    data class Reply(val id: Int, val ok: Boolean, val value: String)

    /** a reply from the content script: {id, ok, value}; null when it is not one */
    fun parseReply(msg: Any?): Reply? {
        val o = when (msg) { is JSONObject -> msg; is String -> runCatching { JSONObject(msg) }.getOrNull(); else -> null } ?: return null
        if (!o.has("id")) return null
        return Reply(o.optInt("id"), o.optBoolean("ok", false), if (o.isNull("value")) "null" else o.opt("value").toString())
    }

    /** wrap agent JS (execute_js) like the WebView backend: async body, `await` and `return` allowed, objects as JSON */
    fun wrapUserScript(script: String): String =
        "(async function(){ var __v = await (async function(){ $script })(); " +
            "return (__v === undefined || __v === null) ? String(__v) : (typeof __v === 'object' ? JSON.stringify(__v) : String(__v)); })()"

    /** one line for the system prompt (self-knowledge) */
    const val PROMPT =
        "Mines has its own browser (Firefox engine, built into the app): browser_use with engine \"gecko\". There Mines acts " +
            "as the human — it types and clicks at a person's pace, every task runs in its own private session that is wiped when " +
            "the task ends, traffic goes through the user's proxy, and Mines handles SMS / e-mail codes itself (wait_for_otp) and " +
            "simple checks. Only payments and approvals on the phone go to the user (wait_for_human shows them the live page). Why: Google and Apple block " +
            "sign-in inside embedded WebViews, and a clean private session never touches the user's saved logins. The normal " +
            "in-app browser (engine \"webview\", the default) keeps the user's saved logins. Posting or messaging other people " +
            "always needs the user's yes first."
}
