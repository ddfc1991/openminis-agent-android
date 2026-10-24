package com.openminis.app.plus

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * [Mines-voice-34] Voice acts as Mines (user, 2026-10-13: voice said it "can't do" things
 * Mines can do). The Live session gets the Mines persona and one function, `mines_task`,
 * that hands the spoken request to the real Mines agent (same tools, same model routing)
 * in a dedicated 「语音任务」 chat. The call answers at once ("好，我去办"), the agent runs in
 * the background, and its result is fed back into the call as a note so the voice reports it.
 */
object MinesVoiceBridge {
    private const val TAG = "MinesVoiceBridge"
    private const val PREFS = "mines_voice_bridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun persona(c: Context): String {
        val name = Avatars.name(PlusPrefs.state.value.petStyle).takeIf { it != "我的形象" }
        return "你就是 ${com.openminis.app.ui.brand.Brand.NAME}" + (name?.let { "（形象是 $it）" } ?: "") +
            "，住在用户手机里的私人管家，和文字聊天里的 Mines 是同一个人、有同样的本事：查资料、跑命令、改文件、写代码、" +
            "登录网站、管理服务器和账号、看消息汇总。用户视力不好，请用简短、口语化的中文回答，一次只说一件事。\n" +
            "凡是需要动手去做的请求（不只是聊天或常识问答），都调用 mines_task，把用户要做的事和关键细节写清楚交给 Mines 执行；" +
            "绝对不要说「我做不到」「我没有这个能力」。调用后先用一句话告诉用户「好，我去办」，结果回来后用一两句话汇报，" +
            "需要用户决定的地方再问。付款、转账、下单、发消息之类的事，先口头确认再交给 Mines。"
    }

    fun toolDeclaration(): JSONObject = JSONObject()
        .put("name", "mines_task")
        .put("description", "把一件需要动手的事交给 Mines 智能体去执行（它有命令行、文件、浏览器、账号、服务器等全部工具）。立刻返回“已开始”，完成后结果会作为备注发回通话。")
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("task", JSONObject().put("type", "STRING").put("description", "要做的事，写清目标和细节（用用户的原话）"))
                .put("confirmed", JSONObject().put("type", "BOOLEAN").put("description", "涉及付款/下单/发送时，用户已口头确认为 true")))
            .put("required", org.json.JSONArray().put("task")))

    /** Starts the task; returns the immediate tool result. [report] gets the final text later. */
    fun start(c: Context, task: String, report: (String) -> Unit): String {
        val app = c.applicationContext
        if (task.isBlank()) return "没听清要做什么，请再说一次"
        scope.launch {
            val out = runCatching { run(app, task) }.getOrElse { e ->
                Log.w(TAG, "task failed: ${e.message}")
                "没办成：" + (e.message ?: "出错了").take(200)
            }
            report(out)
        }
        return "已交给 Mines 开始执行，完成后会把结果告诉你。先简短告诉用户「好，我去办」。"
    }

    private suspend fun run(c: Context, task: String): String {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = p.getString("session", null)
        val alive = saved != null && runCatching {
            (c as? com.openminis.app.MinisApp)?.chatRepository?.getSession(saved) != null
        }.getOrDefault(true)
        val sid: String = if (alive && saved != null) saved else {
            val n = com.openminis.app.debug.HeadlessChatRunner.ensureSession(c)
            p.edit().putString("session", n).apply()
            n
        }
        val r = com.openminis.app.debug.HeadlessChatRunner.prompt(
            c, sid, "（来自语音通话）$task", wait = true, timeoutMs = 10 * 60_000L,
        )
        val text = r.responseText?.trim().orEmpty()
        return when {
            r.timedOut -> "还在做，已经超过 10 分钟，结果会留在「语音任务」对话里。"
            text.isNotEmpty() -> text.take(1500)
            else -> "做完了（${r.status}），详情在对话里。"
        }
    }
}
