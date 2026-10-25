package com.openminis.app.plus

import com.openminis.app.plus.ChatHomeCore.AutoTask
import com.openminis.app.plus.ChatHomeCore.St
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.10 cloud_brain] Pure rules for 「云端执行」: the app ↔ mines-brain protocol (events with a resume cursor),
 * how a cloud event moves a [AutoTask], the rows written into the task conversation, the reply parsing for 同意/拒绝,
 * and the reconnect back-off. Unit-tested in CloudBrainCoreTest. Android side: [CloudBrain].
 *
 * User 2026-10-25: long tasks died with "Software caused connection abort" whenever the phone froze the app or the VPN
 * reconnected. The work now runs on the user's AWS box (mines-brain, 127.0.0.1:8790 there, reached through the app's
 * existing SSH session with a local port forward — no new public port); the phone only shows progress, answers asks,
 * approves 限制层 steps. A dropped link loses nothing: events are read again from the last cursor.
 */
object CloudBrainCore {
    const val REMOTE_PORT = 8790
    const val TOKEN_CMD = "cat ~/.mines-brain/token 2>/dev/null"

    data class Event(val id: Long, val task: String, val type: String, val data: JSONObject)
    data class Page(val events: List<Event>, val cursor: Long, val more: Boolean)

    fun parsePage(body: String, after: Long): Page {
        val o = JSONObject(body)
        val a = o.optJSONArray("events") ?: JSONArray()
        val ev = (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { e -> Event(e.optLong("id"), e.optString("task"), e.optString("type"), e.optJSONObject("data") ?: JSONObject()) }
        }.filter { it.id > after }.sortedBy { it.id }
        return Page(ev, maxOf(after, o.optLong("cursor", after), ev.lastOrNull()?.id ?: after), o.optBoolean("more"))
    }

    /** a token read over SSH: mb_ + 43 url-safe chars; anything else (a shell banner, an error) is not a token */
    fun cleanToken(out: String?): String? = out?.lineSequence()?.map { it.trim() }?.firstOrNull { Regex("""^mb_[A-Za-z0-9_\-]{20,}$""").matches(it) }

    /** the pending ask of a waiting cloud task, as the phone shows it */
    data class Ask(val kind: String, val callId: String, val line: String, val act: String = "", val account: String = "", val url: String = "") {
        /** 限制层 asks get 同意 / 拒绝; questions / handoffs get a typed answer */
        val isApproval: Boolean get() = kind == "approve"
    }

    fun askOf(e: Event): Ask? = if (e.type != "ask") null else Ask(
        e.data.optString("kind", "ask"), e.data.optString("call_id"), e.data.optString("line").ifBlank { "需要你处理" },
        e.data.optString("act"), e.data.optString("account"), e.data.optString("url"))

    /** what the cloud event does to the task chip / 任务 page */
    fun apply(t: AutoTask, e: Event, now: Long): AutoTask {
        val d = e.data
        val step = d.optInt("step", t.step).coerceAtLeast(t.step)
        return when (e.type) {
            "status" -> when (d.optString("st")) {
                "running" -> t.copy(st = St.RUNNING, told = if (t.st == St.RUNNING) t.told else "", updatedAt = now)
                "done" -> t.copy(st = St.DONE, line = d.optString("line").ifBlank { "已完成" }.take(200), updatedAt = now)
                "error" -> t.copy(st = St.ERROR, line = d.optString("line").ifBlank { "没完成" }.take(200), updatedAt = now)
                "cancelled" -> t.copy(st = St.ERROR, line = "已取消", updatedAt = now)
                else -> t
            }
            "ask" -> t.copy(st = St.WAITING, line = askLine(askOf(e)!!).take(200), step = step, updatedAt = now)
            "say", "tool", "tool_done" -> if (t.st == St.QUEUED || t.st == St.RUNNING) t.copy(st = St.RUNNING, step = step, updatedAt = now) else t.copy(step = step)
            "decision", "user_msg" -> if (t.st == St.WAITING) t.copy(st = St.RUNNING, told = "", updatedAt = now) else t
            "retry" -> t.copy(st = if (t.st == St.QUEUED) St.RUNNING else t.st, updatedAt = now)
            else -> t
        }
    }

    fun askLine(a: Ask): String = when (a.kind) {
        "approve" -> a.line.trimEnd('：', ':') + "（回复「同意」或「拒绝」）"
        "handoff" -> "需要你亲手处理：" + a.line + (if (a.url.isNotBlank()) "  ${a.url}" else "") + "（处理完回复「好了」）"
        "phone" -> "需要手机上的东西：" + a.line
        else -> a.line
    }

    /** one line in the task conversation per event worth reading (null = not shown) */
    fun row(e: Event): String? {
        val d = e.data
        return when (e.type) {
            "created" -> "☁ 已交给云端执行（手机黑屏、断网、退出都不影响）"
            "say" -> d.optString("text").trim().takeIf { it.isNotEmpty() }?.let { "☁ 第 ${d.optInt("step")} 步 · $it" }
            "tool" -> "☁ " + d.optString("line").ifBlank { d.optString("tool") }
            "ask" -> "☁ " + askLine(askOf(e)!!)
            "retry", "note" -> d.optString("line").takeIf { it.isNotBlank() }?.let { "☁ $it" }
            "decision" -> "☁ " + d.optString("line")
            "status" -> when (d.optString("st")) {
                "done" -> "☁ 完成：" + d.optString("line")
                "error" -> "☁ 没完成：" + d.optString("line")
                "cancelled" -> "☁ 已取消"
                else -> null
            }
            else -> null
        }?.take(900)
    }

    enum class Verb { ALLOW, DENY, CANCEL, TEXT }

    private val YES = Regex("""^\s*(同意|允许|可以|好的?|行|ok|okay|yes|批准|确认)\s*[。.!！]?\s*$""", RegexOption.IGNORE_CASE)
    private val NO = Regex("""^\s*(拒绝|不行|不要|不同意|no)\s*[。.!！]?\s*$""", RegexOption.IGNORE_CASE)
    private val STOP = Regex("""^\s*(取消|停止|停下|别做了|不做了|stop|cancel)(任务)?\s*[。.!！]?\s*$""", RegexOption.IGNORE_CASE)

    /** what a message typed into a cloud task's conversation means */
    fun verbOf(text: String, waitingApproval: Boolean): Verb = when {
        STOP.matches(text) -> Verb.CANCEL
        waitingApproval && YES.matches(text) -> Verb.ALLOW
        waitingApproval && NO.matches(text) -> Verb.DENY
        else -> Verb.TEXT
    }

    /** reconnect back-off: 2, 4, 8 … 60 s */
    fun backoffMs(fails: Int): Long = (2_000L shl (fails - 1).coerceIn(0, 5)).coerceAtMost(60_000L)

    /** the submit key: one per phone task, so a submit retried after a dropped link never makes a second cloud task */
    fun clientRef(taskId: String): String = "mines-$taskId"

    /** the providers the phone sends to the brain: OpenAI-compatible chat endpoints only, deduped, main model first */
    data class Prov(val name: String, val base: String, val key: String, val model: String, val ua: String = "")

    fun providersJson(list: List<Prov>, max: Int = 6): String = JSONObject().put("providers", JSONArray().apply {
        list.distinctBy { it.base.trimEnd('/') + "|" + it.model }.take(max).forEach {
            put(JSONObject().put("name", it.name).put("base", it.base).put("key", it.key).put("model", it.model).put("ua", it.ua))
        }
    }).toString()

    fun submitJson(goal: String, name: String, ref: String): String =
        JSONObject().put("goal", goal).put("name", name).put("client_ref", ref).toString()

    /** default base for provider types that have no custom base URL (null = this type is not sent to the cloud) */
    fun defaultBase(type: String): String? = when (type) {
        "openAI" -> "https://api.openai.com/v1"
        "openRouter" -> "https://openrouter.ai/api/v1"
        "xAI" -> "https://api.x.ai/v1"
        else -> null
    }

    private val PHONE_ONLY = Regex("""(?i)微信|QQ|短信|相册|照片|截图|截屏|通知栏|手机上|本机|这台手机|打开.{0,8}(App|应用)|闹钟|日历|联系人|打电话|拨打|蓝牙|无障碍|录音|拍照|WiFi|wi-fi|手机设置""")

    /** needs the phone itself (its apps, files, sensors, its logged-in apps): stays on the phone */
    fun phoneOnly(text: String): Boolean = PHONE_ONLY.containsMatchIn(text)

    /** state shown under 设置 → 实验功能 → 云端执行 */
    fun statusLine(on: Boolean, server: String?, models: Int, err: String?): String = when {
        !on -> "已关闭：任务在手机上运行"
        server == null && err != null -> "没找到云端服务：$err"
        server == null -> "正在查找云端服务…"
        models == 0 -> "已连接 $server，还没有可同步的模型"
        else -> "已连接 $server · $models 个模型 · 任务在云端运行，手机断网也继续"
    }
}
