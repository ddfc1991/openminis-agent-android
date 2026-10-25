package com.openminis.app.persona

import android.content.Context
import kotlinx.coroutines.launch
import com.openminis.app.plus.SignInCore.Group as SG
import com.openminis.app.ui.chat.openBrowserSheetForUrl
import org.json.JSONObject

/**
 * [Mines-38.9.15 伪人核心] adb self-test hooks (smoke only, via GroupSelfTest 'i3915 …'):
 *  open    — show 应用 → 伪人核心
 *  state   — flag, kill switch, accounts, last audit lines, + the chat-home tasks
 *  direct  — call the persona tool's send_email the way the agent does (no allow-listed account) on a scratch sid
 *  agentkill — what an agent call that tries to flip the kill switch gets (it has no token: refused)
 *  pickseed / pickrun <tag> [linked] / pickstate — account picker: two fake cloudflare.com accounts in different sign-in
 *            groups (smoke-only list, no Keychain write); pickrun runs the real SignIn.run three times with a login that
 *            always fails (no network); pickstate = the card, the answer, the link, the outcomes, the task state
 *  slider <url> / sliderval — open 聊天's browser sheet on the fake slider page; read the slider value from the page
 * Nothing here allow-lists an account or loosens a limit.
 */
object PersonaSmoke {
    private const val CF = "cloudflare.com"
    private val outcomes = java.util.concurrent.ConcurrentHashMap<String, MutableList<JSONObject>>()
    @Volatile private var sliderVm: com.openminis.app.ui.chat.ChatViewModel? = null
    private fun SW(a: String, g: com.openminis.app.plus.SignInCore.Group) = com.openminis.app.plus.SignInCore.Way(a, g)
    suspend fun handle(c: Context, rest: String): String {
        Persona.init(c)
        val sub = rest.substringBefore(' ').trim()
        return when (sub) {
            "open" -> {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.openminis.app.ui.plus.PlusNav.navigate?.invoke(com.openminis.app.ui.plus.PersonaRoutes.PAGE)
                }
                "ok"
            }
            "state" -> JSONObject(Persona.stateJson()).put("tasks", runCatching { JSONObject(com.openminis.app.plus.ChatHome.stateJson()).optJSONArray("tasks") }.getOrNull()).toString()
            "direct" -> {
                val sid = "smoke3915-direct"
                val r = PersonaTool.runTool(c, sid, JSONObject().put("tool_title", "发邮件").put("action", "send_email")
                    .put("who", "boss@example.com").put("from", "smoke@163.com").put("subject", "周报").put("body", "作为AI助手，我可以帮你：周报见附件。").toString())
                val tp = com.openminis.app.plus.TaskProgress.get(sid)
                JSONObject().put("ok", r.success).put("out", r.output.take(300)).put("progress", tp?.st?.id ?: "").put("reason", tp?.reason ?: "").toString()
            }
            "agentkill" -> JSONObject().put("changed", Persona.userSetKill(c, "agent", true)).put("killed", Persona.limits.value.killed).toString()
            "pickseed" -> {
                val S = com.openminis.app.plus.SignIn; S.init(c)
                S.smokeWays[CF] = listOf(SW("smoke.a@gmail.com", SG.GOOGLE), SW("smoke.b@163.com", SG.EMAIL_PW))
                S.smokeReset(c, CF); outcomes.clear()
                JSONObject().put("ways", S.waysFor(c, CF).size).put("link", S.link(CF)?.account ?: "").toString()
            }
            "pickrun" -> {
                val args = rest.substringAfter(' ', "").trim().split(' ')
                val tag = args.firstOrNull().orEmpty().ifBlank { "a" }
                val S = com.openminis.app.plus.SignIn; S.init(c)
                if ("linked" in args) S.setLink(c, CF, com.openminis.app.plus.SignInCore.Link(SG.GOOGLE, "smoke.a@gmail.com", byUser = true))
                val sid = "smoke3915-pick-$tag"
                outcomes.remove(tag)
                val list = java.util.Collections.synchronizedList(ArrayList<JSONObject>()); outcomes[tag] = list
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                    repeat(3) {
                        val o = S.run(c, sid, CF, null, "Cloudflare DNS") { way -> false to "登录没成功（smoke：${way?.group?.zh ?: "-"}）" }
                        list += when (o) {
                            is com.openminis.app.plus.SignIn.Outcome.Tried -> JSONObject().put("k", "tried").put("ok", o.ok).put("attempt", o.attempt)
                                .put("group", o.way?.group?.id ?: "").put("acct", o.way?.account ?: "")
                            is com.openminis.app.plus.SignIn.Outcome.Handoff -> JSONObject().put("k", "handoff").put("line", o.line.take(120))
                            is com.openminis.app.plus.SignIn.Outcome.NoPick -> JSONObject().put("k", "nopick").put("line", o.line.take(120))
                        }
                        if (o !is com.openminis.app.plus.SignIn.Outcome.Tried) return@launch
                    }
                }
                JSONObject().put("started", sid).toString()
            }
            "pickstate" -> {
                val tag = rest.substringAfter(' ', "").trim().ifBlank { "a" }
                val S = com.openminis.app.plus.SignIn
                val P = com.openminis.app.plus.AccountPick
                val tp = com.openminis.app.plus.TaskProgress.get("smoke3915-pick-$tag")
                JSONObject()
                    .put("card", P.pending.value?.let { r -> JSONObject().put("site", r.site).put("n", r.ways.size)
                        .put("ways", org.json.JSONArray(r.ways.map { "${it.group.id}:${it.account}" })) } ?: JSONObject.NULL)
                    .put("shownId", P.lastShown?.id?.toString() ?: "")
                    .put("answer", P.lastAnswer?.let { "${it.way.group.id}:${it.way.account}:${it.remember}" } ?: "")
                    .put("link", S.link(CF)?.let { "${it.group.id}:${it.account}:${it.byUser}" } ?: "")
                    .put("outcomes", org.json.JSONArray(outcomes[tag]?.toList().orEmpty()))
                    .put("progress", tp?.st?.id ?: "").put("reason", tp?.reason ?: "").toString()
            }
            "slider" -> {
                val url = rest.substringAfter(' ', "").trim()
                val H = com.openminis.app.plus.ChatHome; H.init(c)
                val sid = H.ensureMain(c) ?: return "no main"
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.openminis.app.ui.plus.PlusNav.navigate?.invoke(com.openminis.app.ui.navigation.Routes.chat(sid))
                }
                var vm: com.openminis.app.ui.chat.ChatViewModel? = null
                repeat(20) { if (vm == null) { vm = com.openminis.app.ui.chat.ChatViewModelStore.existing(sid); if (vm == null) kotlinx.coroutines.delay(300) } }
                val v = vm ?: return "no vm"
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { v.openBrowserSheetForUrl(url) }
                sliderVm = v
                JSONObject().put("sid", sid).put("open", v.showBrowserSheet.value).toString()
            }
            "sliderval" -> {
                val v = sliderVm ?: return JSONObject().put("err", "no sheet").toString()
                val inp = com.openminis.app.browser.BrowserActionInput.parse(JSONObject().put("action", "execute_js")
                    .put("script", "String(window.__sv===undefined?-1:window.__sv)+'|'+String(window.__moves||0)").toString())!!
                val r = kotlinx.coroutines.withTimeoutOrNull(15_000) { v.browserTabPool.execute(inp) }
                JSONObject().put("raw", r?.text?.take(200) ?: "timeout").put("open", v.showBrowserSheet.value).toString()
            }
            else -> "i3915 open | state | direct | agentkill | pickseed | pickrun <tag> [linked] | pickstate <tag> | slider <url> | sliderval"
        }
    }
}
