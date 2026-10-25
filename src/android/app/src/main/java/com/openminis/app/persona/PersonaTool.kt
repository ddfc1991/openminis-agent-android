package com.openminis.app.persona

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject

/**
 * [Mines-38.9.15 伪人核心] The agent's one door to acting AS the user: send a mail, look up who someone is to me,
 * record what we talked about. Every outbound text goes through [VoiceFilterCore] and every send through
 * [Persona.guard] — the model cannot reach the limits, only hit them.
 */
object PersonaTool {
    const val TOOL_NAME = "persona"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_NAME,
        description = "Act as the user themselves (伪人核心). actions: " +
            "\"who\" — what I know about a contact before I answer them (first person); " +
            "\"remember\" — note who they are / what we just talked about; " +
            "\"send_email\" — send a mail from the user's own mailbox, in the user's voice. " +
            "Every outgoing text is rewritten into the user's own style and assistant phrasing is removed in code. " +
            "Sending is refused unless the user allow-listed that account and (by default) confirmed this send; " +
            "a refusal is final — report it to the user, never work around it.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown on the tool block, in the user's language."),
            "action" to AgentToolParam("string", "who / remember / send_email", enumValues = listOf("who", "remember", "send_email")),
            "who" to AgentToolParam("string", "the contact: an e-mail address or a handle"),
            "topic" to AgentToolParam("string", "action=remember: what we talked about, a few words"),
            "relation" to AgentToolParam("string", "action=remember: who they are to me (同事 / 朋友 / 客户 …)"),
            "name" to AgentToolParam("string", "action=remember: their name"),
            "subject" to AgentToolParam("string", "action=send_email: the subject"),
            "body" to AgentToolParam("string", "action=send_email: the mail text, as I would write it"),
            "from" to AgentToolParam("string", "action=send_email: which of my mailboxes to send from; omit for the only one"),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "who", "topic", "relation", "name", "subject", "body", "from"),
    )

    suspend fun runTool(c: Context, sid: String, argsJson: String): ToolExecutionResult {
        Persona.init(c)
        val o = runCatching { JSONObject(argsJson) }.getOrNull() ?: return ToolExecutionResult("Error: bad arguments", false)
        return when (o.optString("action").trim().lowercase()) {
            "who" -> ToolExecutionResult(Persona.briefFor(o.optString("who")), true)
            "remember" -> {
                val who = o.optString("who").trim()
                if (who.isBlank()) return ToolExecutionResult("Error: remember requires 'who'", false)
                Persona.remember(c, who, o.optString("topic").takeIf { it.isNotBlank() },
                    o.optString("name").takeIf { it.isNotBlank() }, o.optString("relation").takeIf { it.isNotBlank() })
                ToolExecutionResult("记住了：" + Persona.briefFor(who), true)
            }
            "send_email" -> sendEmail(c, sid, o)
            else -> ToolExecutionResult("Error: unknown action", false)
        }
    }

    private suspend fun sendEmail(c: Context, sid: String, o: JSONObject): ToolExecutionResult {
        val to = o.optString("who").trim()
        val subject = o.optString("subject").trim()
        val body = o.optString("body")
        if (to.isBlank() || body.isBlank()) return ToolExecutionResult("Error: send_email requires 'who' and 'body'", false)
        val boxes = runCatching { PersonaMail.boxes() }.getOrDefault(emptyList())
        val from = o.optString("from").trim()
        val box = from.takeIf { it.isNotBlank() }?.let { f -> boxes.firstOrNull { it.user.equals(f, true) } } ?: boxes.firstOrNull()
        // the limits first: no mailbox, no allow-listed account, kill switch — all end here, before anything is sent
        val mailbox = box?.user ?: from
        val d = Persona.guard(c, mailbox.substringAfter('@', "").ifBlank { null }, LimitGuardCore.Act.SEND, "→ $to", handle = mailbox, requireAccount = true)   // site = the mail domain
        if (!d.allowed) return Persona.blocked(sid, d)
        if (box == null) return ToolExecutionResult("还没有可用来发信的邮箱（设置 → 邮箱 加一个授权码）", false)

        val (text, leaked) = VoiceFilter.rewrite(c, body, to)
        val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PersonaMail.send(box, to, subject.ifBlank { "（无主题）" }, text) }
        return if (err == null) {
            Persona.remember(c, to, subject.takeIf { it.isNotBlank() })
            Persona.record(box.user, "send", true, "sent", "→ $to")
            ToolExecutionResult("已用 ${box.user} 发给 $to。" + if (leaked) "（发出前去掉了几句助手口吻）" else "", true)
        } else {
            Persona.record(box.user, "send", false, "smtp", err)
            ToolExecutionResult("没发出去：$err", false)
        }
    }
}
