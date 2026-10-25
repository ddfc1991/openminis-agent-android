package com.openminis.app.persona

import android.content.Context
import org.json.JSONObject

/**
 * [Mines-38.9.15 伪人核心] adb self-test hooks (smoke only, via GroupSelfTest 'i3915 …'):
 *  open    — show 应用 → 伪人核心
 *  state   — flag, kill switch, accounts, last audit lines, + the chat-home tasks
 *  direct  — call the persona tool's send_email the way the agent does (no allow-listed account) on a scratch sid
 *  agentkill — what an agent call that tries to flip the kill switch gets (it has no token: refused)
 * Nothing here allow-lists an account or loosens a limit.
 */
object PersonaSmoke {
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
            else -> "i3915 open | state | direct | agentkill"
        }
    }
}
