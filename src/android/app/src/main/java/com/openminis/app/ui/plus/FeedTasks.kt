package com.openminis.app.ui.plus

import android.content.Context
import android.widget.Toast
import com.openminis.app.plus.Digest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [Mines-feed-35.3] 消息 action buttons that really do the work (user 2026-10-14:
 * 「帮我处理，帮我领，都不起作用点了没反应」).
 *
 * Before: 帮我处理 / 帮我领 / 开始吧 / 讨论 handed the card to ShareReceiverActivity (ACTION_SEND).
 * MainActivity is singleTask, so the share only bumped ShareCoordinator.bufferVersion; the
 * buffer is drained by ChatScreen or by AppNavigation's fallback, and that fallback only
 * navigates when the current route is the session list. On the 消息 page (route plus_digest)
 * nothing consumed it — the tap did nothing visible, and the text later showed up as a draft in
 * whatever chat the user opened within 5 minutes (never sent).
 *
 * Now: a new chat is created, the task text is submitted to the real Mines agent
 * (HeadlessChatRunner, the same ChatViewModel the chat screen binds to), and the chat opens with
 * the run in progress. The button shows 「处理中…」 while this happens; a failure is shown on the
 * card and as a toast with the plain reason.
 */
object FeedTasks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    /** item ids whose task is being started right now */
    val running: StateFlow<Set<String>> = _running.asStateFlow()
    private val _failed = MutableStateFlow<Map<String, String>>(emptyMap())
    /** item id → plain reason of the last failed start (shown on the card) */
    val failed: StateFlow<Map<String, String>> = _failed.asStateFlow()

    /** last started chat (self-test / smoke) */
    @Volatile var lastSession: String? = null; private set
    @Volatile var lastPrompt: String? = null; private set

    fun toast(c: Context, s: String, long: Boolean = false) {
        runCatching { Toast.makeText(c.applicationContext, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }

    fun clearError(id: String) { _failed.value = _failed.value - id }

    /** Task text for one card: what to do, the card's context, and the safety rules. */
    fun promptFor(m: Digest.Item, steps: List<Pair<String, String>>, mode: String): String {
        val (fh, fs) = com.openminis.app.plus.DigestWriter.fallback(m)
        val head = m.headline ?: fh
        val body = (m.brief ?: fs).ifBlank { m.summary }
        val sb = StringBuilder()
        when (mode) {
            "claim" -> sb.append("请帮我领取这条消息里的福利 / 免费额度，直接动手做：打开官方页面，按步骤领取；拿到 API 密钥就加密存进 Mines 的账号连接并测试一次。" +
                "不要付钱，不要选任何付费套餐；遇到短信验证码、实名或人机验证就停下来，用一句话告诉我要做哪一步。做完用简短中文告诉我结果。\n")
            "discuss" -> sb.append("我们来聊聊这条消息。先用两三句大白话说清楚它是什么、对我有什么用、值不值得做，然后等我提问。\n")
            "look" -> sb.append("请帮我看看这条要处理的消息：先弄清楚发生了什么，能直接修好的就直接修好；需要我决定、付款或输入验证码的地方先停下来问我。做完用简短中文告诉我结果。\n")
            else -> sb.append("请帮我处理这条消息，直接动手做，做完用简短中文告诉我结果；需要我做决定、付款或发消息给别人的地方先停下来问我。\n")
        }
        if (steps.isNotEmpty() && mode != "discuss") {
            sb.append("要做的事：\n")
            steps.forEach { (t, s) -> sb.append("- ").append(t).append(if (s.isNotBlank()) "：$s" else "").append('\n') }
        }
        sb.append("\n消息：").append(head).append('\n')
        if (body.isNotBlank() && body != head) sb.append("内容：").append(body.take(600)).append('\n')
        sb.append("来源：").append(m.source).append(if (m.sender.isNotBlank() && m.sender != m.source) " · " + m.sender else "").append('\n')
        m.url?.let { sb.append("链接：").append(it).append('\n') }
        return sb.toString().trim()
    }

    /**
     * Starts [prompt] as a new Mines chat titled [title] and opens it. [id] = the card (for 「处理中…」
     * and the error line). Returns at once; feedback is a toast + the card state.
     */
    fun startAgent(c: Context, id: String, title: String, prompt: String) {
        val app = c.applicationContext
        if (id in _running.value) { toast(app, "正在处理这条，请稍等"); return }
        _failed.value = _failed.value - id
        _running.value = _running.value + id
        toast(app, "好，正在交给 Mines 处理…")
        scope.launch {
            try {
                val sid = withContext(Dispatchers.IO) {
                    val a = app as? com.openminis.app.MinisApp ?: error("Mines 还没启动好，请再点一次")
                    val model = a.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                        ?: throw NoModel()
                    a.chatRepository.createSession(modelId = model, title = title.take(40)).id
                }
                lastSession = sid; lastPrompt = prompt
                val r = com.openminis.app.debug.HeadlessChatRunner.prompt(app, sid, prompt, wait = false, timeoutMs = 0L)
                when (r.status) {
                    "Running", "Queued" -> {
                        openChat(sid)
                        toast(app, "已开始处理，可以在对话里看进度")
                    }
                    else -> fail(app, id, reason(r.responseText ?: r.status))
                }
            } catch (e: NoModel) {
                fail(app, id, "还没有能用的模型。请先在 设置 → 模型 里添加一个模型供应商")
            } catch (e: Throwable) {
                android.util.Log.w("FeedTasks", "start failed", e)
                fail(app, id, (e.message ?: e.javaClass.simpleName).take(120))
            } finally {
                _running.value = _running.value - id
            }
        }
    }

    private class NoModel : Exception()

    private fun fail(c: Context, id: String, why: String) {
        _failed.value = _failed.value + (id to why)
        toast(c, "没能开始：$why", long = true)
    }

    private fun reason(code: String): String = when (code) {
        "no_provider_resolved_in_5s", "no_provider" -> "还没有能用的模型。请先在 设置 → 模型 里添加一个模型供应商"
        "context_near_capacity" -> "对话太长了，请新开一个对话再试"
        "session_compacting", "awaiting_user_compact_decision" -> "对话正在整理，稍后再点一次"
        "enqueue_declined" -> "Mines 正忙，排队已满，稍后再点一次"
        "empty_prompt" -> "这条消息没有内容"
        else -> code.take(120)
    }

    /** Opens the chat; retried for a moment while a sheet / transition is closing. */
    fun openChat(sid: String) = go("chat/$sid")

    /** PlusNav.go, retried for ~1.5 s while the current screen settles (sheet closing, transition). */
    fun go(route: String) {
        scope.launch {
            delay(60)
            PlusNav.go(route)
        }
    }
}
