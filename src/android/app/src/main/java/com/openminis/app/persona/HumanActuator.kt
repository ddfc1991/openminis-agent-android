package com.openminis.app.persona

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9.15 伪人核心] The single choke point every browser action of both engines goes through
 * (ChatViewModel → [com.openminis.app.plus.MinesBrowser.route] → WebView pool or Gecko):
 *
 *  1. the limits layer decides ([Persona.guard]) — a refused action never reaches the page;
 *  2. the text about to be typed passes [VoiceFilter] in code;
 *  3. the action is paced like a person ([HumanActuatorCore]) and held outside the user's active hours.
 *
 * Captchas and human checks are untouched: they still go to the user through HumanVerify / wait_for_human.
 */
object HumanActuator {

    data class Gate(val refusal: String?, val args: String, val dwellMs: Long)

    /**
     * Called before a browser_use call runs. [refusal] non-null = answer the model with it and do nothing else;
     * [args] may carry a voice-filtered `text`.
     */
    fun gate(c: Context, argsJson: String, pageUrl: String?, owner: String? = null): Gate {
        Persona.init(c)
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return Gate(null, argsJson, 0L)
        val action = o.optString("action").trim().lowercase()
        val url = o.optString("url").takeIf { it.isNotBlank() } ?: pageUrl
        // [38.9.15] sign-in loop guard (any flag): re-opening a site's sign-in page counts against the same ≤ 2 budget
        // as accounts login; past it the step goes to 等你处理 instead of looping (user's CF screenshot: 64 steps)
        if (action == "navigate" && owner != null) loginLoop(c, owner, o.optString("url"), pageUrl)?.let { return Gate(it, argsJson, 0L) }
        if (!Persona.on.value) return Gate(null, argsJson, 0L)
        val text = o.optString("text")
        val target = listOf(text, o.optString("selector")).joinToString(" ")
        val act = LimitGuardCore.actOf("browser_use", action, target)
        val d = Persona.guard(c, url, act, note = action + " " + target.take(40))
        if (!d.allowed) {
            runCatching { PersonaNotify.refresh() }
            return Gate(LimitGuardCore.refusal(d), argsJson, 0L)
        }
        // the account's own persistent browser profile (stable cookies / language), allow-listed accounts only
        if (action == "navigate") PersonaCore.accountFor(Persona.profile.value, url)?.takeIf { it.allowed }?.let { bind(owner, it.profile) }
        var args = argsJson
        if (action == "type" && text.isNotBlank()) {
            val (clean, stripped) = VoiceFilter.filterNow(text)
            if (stripped && clean.isNotBlank() && clean != text) args = runCatching { o.put("text", clean).toString() }.getOrDefault(argsJson)
        }
        val dwell = if (action in PACED) HumanActuatorCore.readDwellMs(text.length + 200) else 0L
        return Gate(null, args, dwell)
    }

    private fun loginLoop(c: Context, owner: String, target: String, last: String?): String? {
        val host = com.openminis.app.plus.Keychain.hostOf(target).ifBlank { return null }
        val lastHost = com.openminis.app.plus.Keychain.hostOf(last)
        val authNow = com.openminis.app.plus.HandoffCore.isAuthUrl(target)
        val wasAuth = last != null && com.openminis.app.plus.Keychain.root(lastHost) == com.openminis.app.plus.Keychain.root(host) && com.openminis.app.plus.HandoffCore.isAuthUrl(last)
        if (!authNow && !wasAuth) return null
        com.openminis.app.plus.SignIn.init(c)
        val line = com.openminis.app.plus.SignIn.browserAuthHit(owner, host) ?: return null
        com.openminis.app.plus.SignIn.notifyUser(c, owner, host, line)
        return line
    }

    private val PACED = setOf("click", "type", "scroll", "hover", "navigate")

    /** the reading pause before the action (the page is already on screen; a person looks before touching) */
    suspend fun dwell(ms: Long) { if (ms > 0) delay(ms.coerceAtMost(6_000L)) }

    /** the keystroke plan for the Gecko bridge: [[delayMs, "c"], …]; "\b" is a correcting backspace */
    fun typePlanJson(text: String): String = JSONArray().apply {
        HumanActuatorCore.plan(text).forEach { k -> put(JSONArray().put(k.delayMs).put(k.ch.toString())) }
    }.toString()

    fun planMs(text: String): Long = HumanActuatorCore.planMs(HumanActuatorCore.plan(text))

    private val bound = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** the persistent profile a conversation's browser uses for the account it is working on (null = its own) */
    fun profileFor(owner: String?): String? = if (Persona.enabled()) bound[owner ?: "chat"] else null
    private fun bind(owner: String?, profile: String) { bound[owner ?: "chat"] = profile }

    private val lastUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun lastUrl(owner: String?): String? = lastUrls[owner ?: "chat"]
    fun noteUrl(owner: String?, url: String?) { if (!url.isNullOrBlank()) lastUrls[owner ?: "chat"] = url }

    /**
     * A `type` call becomes one human-paced script that runs the same way in both engines (execute_js): keystroke by
     * keystroke with the planned gaps and the occasional corrected slip. Secrets ({{secret:…}}) and password fields
     * keep the engine's own fill (null here), so a real value never sits in a script.
     */
    fun typingArgs(argsJson: String): String? {
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
        if (o.optString("action") != "type") return null
        val text = o.optString("text")
        if (text.isBlank() || text.contains("{{") || text.length > 2_000) return null
        val sel = o.optString("selector").takeIf { it.isNotBlank() }
        val q = { v: String -> JSONObject.quote(v) }
        val script = "var sel=" + (sel?.let(q) ?: "null") + ", plan=" + typePlanJson(text) + ";\n" + TYPE_JS
        val out = JSONObject(o.toString()).put("action", "execute_js").put("script", script)
        out.remove("text")
        return out.toString()
    }

    private const val TYPE_JS = """
var el = sel ? document.querySelector(sel) : document.activeElement;
if (!el) return JSON.stringify({error: 'Element not found: ' + sel});
if (el.type === 'password') return JSON.stringify({error: 'password field: use type with the saved secret'});
el.focus();
var isField = el.tagName === 'INPUT' || el.tagName === 'TEXTAREA';
function setv(v){ var p = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype; var d = Object.getOwnPropertyDescriptor(p, 'value'); if (d && d.set) d.set.call(el, v); else el.value = v; }
if (isField) { setv(''); el.dispatchEvent(new Event('input', {bubbles: true})); } else if (el.isContentEditable) { el.textContent = ''; }
for (var i = 0; i < plan.length; i++) {
  await new Promise(function(r){ setTimeout(r, plan[i][0]); });
  var ch = plan[i][1];
  if (ch === '\b') {
    el.dispatchEvent(new KeyboardEvent('keydown', {key: 'Backspace', bubbles: true}));
    var ok = false; try { ok = document.execCommand('delete', false); } catch (e) { ok = false; }
    if (!ok) { if (isField) setv(el.value.slice(0, -1)); else el.textContent = (el.textContent || '').slice(0, -1);
      el.dispatchEvent(new InputEvent('input', {inputType: 'deleteContentBackward', bubbles: true})); }
    el.dispatchEvent(new KeyboardEvent('keyup', {key: 'Backspace', bubbles: true}));
    continue;
  }
  el.dispatchEvent(new KeyboardEvent('keydown', {key: ch, bubbles: true}));
  var done = false; try { done = document.execCommand('insertText', false, ch); } catch (e) { done = false; }
  if (!done) { if (isField) setv(el.value + ch); else el.textContent = (el.textContent || '') + ch;
    el.dispatchEvent(new InputEvent('input', {data: ch, inputType: 'insertText', bubbles: true})); }
  el.dispatchEvent(new KeyboardEvent('keyup', {key: ch, bubbles: true}));
}
el.dispatchEvent(new Event('change', {bubbles: true}));
return JSON.stringify({typed: true, tag: el.tagName, length: (el.value != null ? el.value.length : (el.textContent || '').length), paced: 'human'});
"""
}
