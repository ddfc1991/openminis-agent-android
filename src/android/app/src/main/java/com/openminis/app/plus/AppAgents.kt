package com.openminis.app.plus

import android.content.Context
import com.openminis.app.plus.group.AiClient
import com.openminis.app.plus.group.GroupPrefs
import com.openminis.app.plus.group.Partner
import com.openminis.app.plus.group.Partners
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [Mines-38.9] 「应用代理」 (user 2026-10-16: cut Mines' own token use a lot): sign-up-based AI services the user already
 * has — Hark / Manus web (WebBridge, the user's signed-in session), 扣子 (login → PAT, free quota), Memoh (own server),
 * Muse 中继 (own relay), Meta Muse (US only) — reuse the 38.8 free-route adapters ([Partners] / [AiClient]). Each one is
 * OFF until the user taps 启用 (their terms of use apply; nothing is enabled by itself). When one is on, the agent gets
 * the `app_agent` tool: hand a self-contained task to one of them and read the answer back. 「auto」 asks a cheap cloud
 * model (fast / mini / flash in the user's own providers) whether to delegate and to whom; if none is configured a
 * keyword rule decides. Never delegated: paying, sending messages for the user, deleting data (the router refuses).
 */
object AppAgents {
    const val TOOL_NAME = "app_agent"

    data class Def(val id: String, val name: String, val host: String, val good: String, val quota: String, val keywords: List<String>)

    val defs = listOf(
        Def("web:hark", "Hark", "hark.com", "订票、购物、网页上办事", "网页版不显示额度", listOf("订", "买", "预约", "网页", "办", "book", "order")),
        Def("web:manus", "Manus", "manus.im", "长任务、写报告、跑代码", "按 Manus 积分（网页上看剩余）", listOf("报告", "调研", "整理", "代码", "脚本", "对比", "research", "report")),
        Def("coze", "扣子", "coze.cn", "你自己的智能体、插件", "扣子免费额度（以扣子后台为准）", listOf("智能体", "bot", "插件", "工作流")),
        Def("memoh", "Memoh", "memoh.ai", "你自己部署的机器人", "自建：看你的服务器", listOf("机器人", "memoh")),
        Def("nanomuse", "Muse 中继", "", "快问快答、翻译", "自建中继：看你的服务器", listOf("翻译", "解释", "什么是", "translate")),
        Def("web:muse", "Meta Muse", "meta.ai", "英文资料、图片", "额度未知", listOf("英文", "图片", "image")),
    )

    private const val PREFS = "mines_app_agents"
    data class LogEntry(val agent: String, val task: String, val at: Long, val ms: Long, val ok: Boolean, val outChars: Int)
    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log
    @Volatile private var app: Context? = null

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext; GroupPrefs.init(c)
        _log.value = runCatching {
            val a = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let { LogEntry(it.optString("a"), it.optString("t"), it.optLong("at"), it.optLong("ms"), it.optBoolean("ok"), it.optInt("n")) } }
        }.getOrDefault(emptyList())
    }

    fun enabled(id: String) = GroupPrefs.get("agent_on_$id") == "1"
    fun setEnabled(id: String, on: Boolean) = GroupPrefs.put("agent_on_$id", if (on) "1" else null)
    /** Read without a context (tool registration); false until GroupPrefs was initialised by the app. */
    fun anyEnabled(): Boolean = runCatching { all().any { enabled(it.id) && isMine(it) } }.getOrDefault(false)
    fun enabledNames(c: Context): List<String> { init(c); return mine().filter { enabled(it.id) }.map { it.name } }

    // ---------------- [Mines-38.9.1] only the services THIS user has ----------------
    // User 2026-10-16 「这样我就其他人都有了」: the 38.9 page listed a built-in six (Hark, Manus, 扣子, Memoh, Muse 中继, Meta
    // Muse) for everyone. Now [defs] is only the 「添加」 catalog behind +; the page lists what the user added or what Mines
    // sees they already have (a signed-in web session / 扣子 token / Memoh login / relay key), plus their own custom
    // OpenAI-compatible endpoints. A newly added service is OFF until the user switches it on.

    private fun mineIds(): Set<String> = GroupPrefs.get("agents_mine")?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    private fun hiddenIds(): Set<String> = GroupPrefs.get("agents_hidden")?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    fun add(id: String) { GroupPrefs.put("agents_mine", (mineIds() + id).joinToString(",")); GroupPrefs.put("agents_hidden", (hiddenIds() - id).joinToString(",").ifBlank { null }); setEnabled(id, false) }
    fun remove(id: String) { GroupPrefs.put("agents_mine", (mineIds() - id).joinToString(",").ifBlank { null }); GroupPrefs.put("agents_hidden", (hiddenIds() + id).joinToString(",")); setEnabled(id, false) }

    /** The user's own OpenAI-compatible endpoints (自定义接口) as agents. */
    fun customs(): List<Def> = runCatching { GroupPrefs.customs().map { Def(it.id, it.name, runCatching { java.net.URI(it.base).host.orEmpty() }.getOrDefault(""), "你自己接的服务", "看你的服务", emptyList()) } }.getOrDefault(emptyList())
    fun all(): List<Def> = defs + customs()

    /** The user has it: added by hand, a custom endpoint, or Mines sees a working sign-in / key (and the user did not remove it). */
    fun isMine(d: Def): Boolean {
        if (d.id in hiddenIds()) return false
        if (d.id in mineIds() || customs().any { it.id == d.id }) return true
        return runCatching { loginOf(d) == Login.OK }.getOrDefault(false)
    }
    fun mine(): List<Def> = all().filter { isMine(it) }
    fun catalog(): List<Def> = defs.filter { !isMine(it) }

    /** One-time (38.9.1): switches the 38.9 page let users turn on for services they never signed in to go back OFF. */
    fun migrate() {
        if (GroupPrefs.get("agents_v3891") == "1") return
        defs.forEach { d -> if (enabled(d.id) && runCatching { loginOf(d) != Login.OK }.getOrDefault(true)) setEnabled(d.id, false) }
        GroupPrefs.put("agents_v3891", "1")
    }

    // ---------------- 测试一下: a real tiny round trip, never silent ----------------

    data class Test(val running: Boolean, val ok: Boolean = false, val text: String = "", val ms: Long = 0, val reason: String = "")

    /** 「回复 OK」 through the same adapter the tool uses, ≤ 45 s; the error says WHY (未登录 / 网络 / 页面变了 / 额度). */
    suspend fun test(c: Context, d: Def): Test {
        init(c)
        if (Partners.list.value.isEmpty()) runCatching { Partners.refresh(c) }
        // [Mines-38.9.7] Memoh = OpenAI-compatible endpoint with a key
        if (d.id == "memoh" && MemohKey.configured()) {
            val t0 = System.currentTimeMillis()
            val r = runCatching { kotlinx.coroutines.withTimeout(45_000L) { MemohKey.reply("你在帮 Mines 做连通测试。", "只回复 OK 两个字母，不要别的。", 20) } }
            val ms = System.currentTimeMillis() - t0
            val res = r.fold({ Test(false, it.isNotBlank(), it.take(200).ifBlank { "（回复是空的）" }, ms, if (it.isBlank()) "回复是空的" else "") }, { e -> Test(false, false, "", ms, reasonOf(e)) })
            addLog(c, LogEntry(d.name, "连通测试", System.currentTimeMillis(), ms, res.ok, res.text.length))
            return res
        }
        val p = partnerFor(d)
        if (p == null || !p.ready) return Test(false, false, "", 0, "${d.name} 还没登录，点这里去登录")
        val t0 = System.currentTimeMillis()
        val r = runCatching {
            kotlinx.coroutines.withTimeout(45_000L) {
                AiClient.reply(p, "你在帮 Mines 做连通测试。", listOf(AiClient.Turn("user", "只回复 OK 两个字母，不要别的。")), maxTokens = 20)
            }
        }
        val ms = System.currentTimeMillis() - t0
        val res = r.fold({ Test(false, it.text.isNotBlank(), it.text.take(200).ifBlank { "（回复是空的）" }, ms, if (it.text.isBlank()) "回复是空的" else "") },
            { e -> Test(false, false, "", ms, reasonOf(e)) })
        addLog(c, LogEntry(d.name, "连通测试", System.currentTimeMillis(), ms, res.ok, res.text.length))
        return res
    }

    fun reasonOf(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName)
        val low = m.lowercase()
        return when {
            e is kotlinx.coroutines.TimeoutCancellationException -> "等了 45 秒没有回复（网页没反应或服务太慢）"
            m.contains("还没登录") || m.contains("要你先做一步") -> m   // already one plain sentence (WebBridge)
            low.contains("登录") || low.contains("login") || low.contains("401") || low.contains("鉴权") -> "未登录或登录过期：$m"
            low.contains("selector") || low.contains("输入框") || low.contains("找不到") || low.contains("页面") -> "页面变了，找不到输入框 / 回复（选择器失效）：$m"
            low.contains("额度") || low.contains("余额") || low.contains("quota") || low.contains("402") || low.contains("积分") -> "额度不够：$m"
            low.contains("连不上") || low.contains("超时") || low.contains("拒绝") || low.contains("解析") || low.contains("断开") -> "网络：$m"
            else -> m
        }
    }

    /** The partner that serves a def: the exact member, or the first listed bot for 扣子 / Memoh. */
    fun partnerFor(d: Def): Partner? {
        val l = Partners.list.value
        return l.firstOrNull { it.id == d.id } ?: (if (d.id.startsWith("web:")) l.firstOrNull { it.id == d.id + "#1" } else null) ?: if (d.id == "coze" || d.id == "memoh") l.firstOrNull { it.id.startsWith(d.id + ":") && it.ready } ?: l.firstOrNull { it.id.startsWith(d.id + ":") } else null
    }

    enum class Login(val label: String) { OK("已登录"), NEED("需要登录"), US("需要美国网络"), OFF("连不上") }
    fun loginOf(d: Def): Login {
        if (d.id == "memoh") return if (MemohKey.configured()) Login.OK else Login.NEED   // [Mines-38.9.7] key, not web login
        if (d.id == "web:muse" && !com.openminis.app.plus.group.PresetRegistry.museOn()) return Login.US
        val p = partnerFor(d) ?: return Login.NEED
        return when (p.state) { Partner.State.READY -> Login.OK; Partner.State.UNREACHABLE -> Login.OFF; else -> Login.NEED }
    }
    fun quotaOf(d: Def): String = partnerFor(d)?.balance?.let { "余额：$it" }
        ?: (if (d.id == "memoh") MemohKeyCore.where(MemohKey.base()) ?: "填你服务器上的地址和 key" else null) ?: d.quota

    // ---------------- routing ----------------

    private val never = Regex("(付款|支付|转账|下单|购买|买下|充值|发给|发送|发消息|回复他|回复她|删除|删掉|清空|pay|transfer|checkout|send|delete)", RegexOption.IGNORE_CASE)

    /** Rule fallback: id of the best enabled+signed-in agent for [task], or null = Mines does it itself. */
    fun ruleRoute(task: String, ready: List<Def>): Def? {
        if (never.containsMatchIn(task) || ready.isEmpty()) return null
        val long = task.length >= 40
        val scored = ready.map { d -> d to d.keywords.count { task.contains(it, true) } }.sortedByDescending { it.second }
        val top = scored.first()
        return if (top.second > 0 || long) top.first else null
    }

    /** Cheap cloud model decides (one short call); falls back to [ruleRoute]. Returns (def or null, how it was decided). */
    suspend fun route(c: Context, task: String): Pair<Def?, String> {
        val ready = mine().filter { enabled(it.id) && loginOf(it) == Login.OK }
        if (never.containsMatchIn(task)) return null to "付款 / 发消息 / 删除 不交出去"
        if (ready.isEmpty()) return null to "没有已启用且已登录的代理"
        val cheap = runCatching { cheapAsk(c,
            "Decide who should do this task. Options: SELF (Mines itself, for short chat, phone control, files, anything needing the user's device) or one of: " +
                ready.joinToString("; ") { "${it.id} = ${it.name} (good at ${it.good})" } +
                ". Reply with ONE id only, nothing else.\nTask: " + task.take(500)) }.getOrNull()
        if (cheap != null) {
            val pick = ready.firstOrNull { cheap.second.contains(it.id, true) }
            return pick to "路由模型 ${cheap.first}"
        }
        return ruleRoute(task, ready) to "规则"
    }

    /** One short call to the cheapest configured cloud model (flash / mini / lite / haiku / turbo first). (model name, reply) */
    private suspend fun cheapAsk(c: Context, prompt: String): Pair<String, String>? {
        val a = c.applicationContext as? com.openminis.app.MinisApp ?: return null
        val repo = a.providerRepository
        val cheapRe = Regex("(?i)(flash|mini|lite|haiku|turbo|nano|small|air|8b|7b)")
        val entries = repo.allVisibleEntries().filter { ErrZhCore.chatCapable(it.baseModel.id) }.sortedByDescending { cheapRe.containsMatchIn(it.baseModel.id) }.take(2)
        for (e in entries) {
            val inst = repo.instance(e.providerInstanceId)?.takeIf { it.isEnabled } ?: continue
            val key = repo.usableApiKey(inst) ?: continue
            val r = runCatching {
                kotlinx.coroutines.withTimeout(15_000L) {
                    com.openminis.app.provider.ProviderFactory.create(inst, key, e.baseModel, c, overrides = e.overrides)
                        .sendMessage(messages = listOf(com.openminis.app.data.model.LLMMessage(role = com.openminis.app.data.model.LLMMessage.Role.USER, content = prompt)),
                            systemPrompt = "You are a router. Answer with one id.", maxTokens = 20).text
                }
            }.getOrNull()
            if (!r.isNullOrBlank()) return e.baseModel.id to r.trim()
        }
        return null
    }

    // ---------------- delegation ----------------

    data class Result(val ok: Boolean, val agent: String, val text: String, val ms: Long, val how: String)

    suspend fun delegate(c: Context, agentId: String, task: String): Result {
        init(c)
        if (Partners.list.value.isEmpty()) runCatching { Partners.refresh(c) }
        var how = "指定"
        val d: Def? = if (agentId.isBlank() || agentId == "auto") route(c, task).let { how = it.second; it.first } else all().firstOrNull { it.id == agentId || it.name == agentId }
        if (d == null) return Result(false, "", if (agentId == "auto" || agentId.isBlank()) "不交出去（$how）：Mines 自己做" else "没有这个代理：$agentId", 0, how)
        if (!enabled(d.id)) return Result(false, d.name, "${d.name} 没启用（设置 › 应用代理 里点启用）", 0, how)
        if (never.containsMatchIn(task)) return Result(false, d.name, "付款、替用户发消息、删除数据的事不交给别的服务", 0, how)
        if (d.id == "memoh" && MemohKey.configured()) {
            val t0 = System.currentTimeMillis()
            val r = runCatching { MemohKey.reply("你在帮 Mines 完成一件事。直接把结果写出来，中文，简洁；做不到就说做不到和原因。", task.take(6000)) }
            val ms = System.currentTimeMillis() - t0
            val res = r.fold({ Result(it.isNotBlank(), d.name, it.ifBlank { "（没有回复）" }, ms, how) }, { Result(false, d.name, "没成功：${it.message}", ms, how) })
            addLog(c, LogEntry(d.name, task.take(60), System.currentTimeMillis(), ms, res.ok, res.text.length))
            return res
        }
        val p = partnerFor(d)?.takeIf { it.ready } ?: return Result(false, d.name, "${d.name} 还没登录（设置 › 应用代理 › ${d.name}）", 0, how)
        val t0 = System.currentTimeMillis()
        val r = runCatching {
            AiClient.reply(p, "你在帮 Mines 完成一件事。直接把结果写出来，中文，简洁；做不到就说做不到和原因。",
                listOf(AiClient.Turn("user", task.take(6000))), maxTokens = 2000)
        }
        val ms = System.currentTimeMillis() - t0
        val res = r.fold({ Result(it.text.isNotBlank(), d.name, it.text.ifBlank { "（没有回复）" }, ms, how) },
            { val msg = it.message ?: it.javaClass.simpleName; Result(false, d.name, if (msg.contains(d.name)) msg else "没成功：$msg", ms, how) })
        addLog(c, LogEntry(d.name, task.take(60), System.currentTimeMillis(), ms, res.ok, res.text.length))
        return res
    }

    private fun addLog(c: Context, e: LogEntry) {
        val l = (listOf(e) + _log.value).take(50); _log.value = l
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("a", it.agent).put("t", it.task).put("at", it.at).put("ms", it.ms).put("ok", it.ok).put("n", it.outChars)) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("log", a.toString()).apply()
    }

    /** Today's count + rough tokens Mines did not spend (reply chars ≈ tokens × 1.6 for Chinese, × 3 for the work around it). */
    fun today(): Pair<Int, Int> {
        val day = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val l = _log.value.filter { it.at >= day && it.ok }
        return l.size to l.sumOf { (it.outChars / 1.6 * 3).toInt() }
    }

    fun promptLine(c: Context): String? {
        init(c)
        val on = mine().filter { enabled(it.id) }
        if (on.isEmpty()) return null
        return "App agents the user enabled (use app_agent to hand off long research / reports / web errands and save tokens; never for paying, sending messages or deleting): " +
            on.joinToString("; ") { "${it.id} ${it.name}: ${it.good}" }
    }

    fun definition() = com.openminis.app.data.model.AgentToolDefinition(
        name = TOOL_NAME,
        description = "Hand a self-contained task to one of the user's own AI services (enabled in 设置 › 应用代理) and get its answer back, so Mines spends fewer tokens. " +
            "Good for long research, reports, comparisons, web errands. agent=\"auto\" lets a cheap router decide (it may answer SELF = do it yourself). " +
            "Never use it for paying, sending messages for the user, or deleting data. Write the task so it needs no earlier context.",
        parameters = mapOf(
            "tool_title" to com.openminis.app.data.model.AgentToolParam("string", "A concise 5-10 word summary shown to the user. Use the same language as the user."),
            "agent" to com.openminis.app.data.model.AgentToolParam("string", "auto, or one id", listOf("auto") + runCatching { mine().map { it.id } }.getOrDefault(emptyList())),
            "task" to com.openminis.app.data.model.AgentToolParam("string", "The full task, self-contained"),
        ),
        required = listOf("tool_title", "task"),
        propertyOrdering = listOf("tool_title", "agent", "task"),
    )

    suspend fun runTool(c: Context, argsJson: String): com.openminis.app.tools.ToolExecutionResult = runCatching {
        val a = JSONObject(argsJson)
        val r = delegate(c, a.optString("agent", "auto"), a.optString("task"))
        val head = if (r.agent.isBlank()) "未交出去（${r.how}）" else "交给 ${r.agent}（${r.how}，${r.ms / 1000} 秒）"
        com.openminis.app.tools.ToolExecutionResult("$head\n${r.text.take(8000)}", true)
    }.getOrElse { com.openminis.app.tools.ToolExecutionResult("Error: ${it.message}", false) }
}
