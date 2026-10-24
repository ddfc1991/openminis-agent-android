package com.openminis.app.plus.group

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-group-37] One round of a group chat: the user speaks, then every
 * member (or only the @-mentioned ones) answers, one after another (each sees
 * the earlier answers) or all at once. Everyone sees the shared history, with
 * other speakers' lines labelled 【名字】. One member failing shows its reason
 * on its own bubble and never stops the others. 「停止」 cancels the round.
 */
object GroupRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    const val HOST_NAME = "Mines"

    fun shortName(name: String) = name.substringBefore('（').substringBefore(" (").trim()

    /** Member ids @-mentioned in [text] (host = [GroupStore.HOST]). Empty = everyone answers. */
    fun mentions(text: String, members: List<Partner>): List<String> {
        if (!text.contains('@') && !text.contains('＠')) return emptyList()
        val t = text.replace('＠', '@').lowercase()
        val out = ArrayList<String>()
        for (p in members) {
            // [Mines-38.5] 「@智谱」 matches 「智谱 GLM」: full name, short name, name without spaces, its first word, and the id
            val sn = shortName(p.spec.name)
            val names = listOf(p.spec.name, sn, sn.replace(" ", ""), sn.substringBefore(' '), p.spec.id.substringAfter(':'))
                .map { it.lowercase() }.filter { it.length >= 2 }.distinct()
            if (names.any { t.contains("@$it") }) out += p.id
        }
        if (t.contains("@mines") || t.contains("@主持")) out += GroupStore.HOST
        return out.distinct()
    }

    private fun system(me: String, model: String, others: List<String>, host: Boolean, direct: Boolean = false): String = if (direct)
        "你在和用户单聊，你的名字是「$me」。用中文直接回答用户最新的话，简洁清楚。" else buildString {
        append("你在一个多 AI 群聊里，你的名字是「").append(me).append("」")
        if (model.isNotBlank()) append("（模型 ").append(model).append("）")
        append("。群里还有：").append((others + "群主（用户）").joinToString("、")).append("。")
        append("别人的发言会以【名字】开头。直接用中文回答群主最新的话，简洁（一般不超过 200 字，除非群主要长内容），")
        append("可以补充、引用或反驳其他成员的观点，不要重复别人已经说过的，不要在开头写自己的名字。")
        if (host) append("你是主持人 Mines：按顺序组织大家发言，梳理分歧、给出结论和下一步。")
    }

    private fun turnsFor(gid: String, me: String): List<AiClient.Turn> =
        GroupStore.messages(gid).value.filter { it.status == "ok" }.takeLast(24).map { m ->
            when (m.who) {
                me -> AiClient.Turn("assistant", m.text)
                "user" -> AiClient.Turn("user", "【群主】" + m.text)
                else -> AiClient.Turn("user", "【" + m.name + "】" + m.text)
            }
        }

    /** 「停止」: cancels the running round and everything queued behind it. */
    fun stop(gid: String) {
        jobs.remove(gid)?.cancel(CancellationException("stopped"))
        synchronized(chains) { chains.remove(gid)?.forEach { it.cancel(CancellationException("stopped")) } }
        _running.update { it - gid }
    }
    private val chains = HashMap<String, MutableList<Job>>()

    /**
     * [Mines-38.5] Rounds are QUEUED, never cancel each other. build-37 started every send / 重试 / 总结 with stop(), so a
     * second message (or 总结) while members were still answering killed the round and left 「已停止」 bubbles in the middle
     * of normal replies (user screenshot 2026-10-15: 智谱 「已停止」). Now a new round waits for the one before it; only the
     * user's 「停止」 cancels, and its leftovers are marked quietly (no text, drawn as a small grey note).
     */
    private fun launchRound(gid: String, block: suspend () -> Unit) {
        val prev = jobs[gid]
        val job = scope.launch {
            prev?.join()
            _running.update { it + gid }
            try { block() } finally {
                if (jobs[gid] === coroutineContext[Job]) _running.update { it - gid }
                GroupStore.messages(gid).value.filter { it.status == "pending" }.forEach {
                    GroupStore.replace(gid, it.copy(status = "stopped", text = ""))
                }
            }
        }
        jobs[gid] = job
        synchronized(chains) { chains.getOrPut(gid) { mutableListOf() }.apply { removeAll { !it.isActive }; add(job) } }
        job.invokeOnCompletion { if (jobs[gid] === job) { jobs.remove(gid); _running.update { it - gid } } }
    }

    fun send(context: Context, gid: String, text: String) {
        val g = GroupStore.group(gid) ?: return
        GroupStore.add(gid, GroupMsg(GroupStore.newId(), "user", "我", "", text.trim()))
        launchRound(gid) {
            if (Partners.list.value.isEmpty()) Partners.refresh(context)
            val members = g.members.mapNotNull { Partners.find(it) }
            val asked = mentions(text, members)
            if (asked.isNotEmpty()) {
                // @someone → only they answer (the host too only when @Mines)
                answer(gid, g, asked, members)
            } else {
                val n0 = GroupStore.messages(gid).value.size
                answer(gid, g, members.map { it.id }, members)
                // the host closes the round when two or more members actually answered
                val okCount = GroupStore.messages(gid).value.drop(n0).count { it.status == "ok" && it.who != GroupStore.HOST }
                if (g.host && okCount >= 2) answer(gid, g, listOf(GroupStore.HOST), members, wrap = true)
            }
        }
    }

    /** Retry one failed member with the current history. */
    fun retry(context: Context, gid: String, memberId: String) {
        val g = GroupStore.group(gid) ?: return
        launchRound(gid) {
            if (Partners.list.value.isEmpty()) Partners.refresh(context)
            answer(gid, g, listOf(memberId), g.members.mapNotNull { Partners.find(it) })
        }
    }

    fun summarize(context: Context, gid: String) {
        val g = GroupStore.group(gid) ?: return
        launchRound(gid) {
            if (Partners.list.value.isEmpty()) Partners.refresh(context)
            answer(gid, g, listOf(GroupStore.HOST), g.members.mapNotNull { Partners.find(it) }, summary = true)
        }
    }

    const val SUMMARY_PROMPT = "【群主】请你作为主持人，给上面的讨论写一份结构化总结，严格用下面的小标题，每项 1–4 条短句：\n" +
        "## 结论\n## 共识\n## 分歧\n## 各成员要点（每位一行：名字：要点）\n## 下一步\n不要客套，不要重复原文。"
    const val WRAP_PROMPT = "【群主】请你作为主持人，用 3–5 行收一下这一轮：大家的共识、最大的分歧、你的建议；如果有人没说清楚，点名请他下一轮补充。"

    private suspend fun answer(gid: String, g: AiGroup, who: List<String>, members: List<Partner>, summary: Boolean = false, wrap: Boolean = false) {
        val names = members.map { shortName(it.spec.name) } + (if (g.host) listOf(HOST_NAME) else emptyList())
        suspend fun one(id: String) {
            val isHost = id == GroupStore.HOST
            val p0 = if (isHost) Partners.host() else members.firstOrNull { it.id == id }
            val name = if (isHost) HOST_NAME else p0?.spec?.name ?: id
            // [Mines-38.5] a member that is not connected is skipped with one quiet line, not a red error bubble
            if (!isHost && (p0 == null || !p0.ready)) {
                GroupStore.add(gid, GroupMsg(GroupStore.newId(), id, name, p0?.model ?: "", "", "skip",
                    detail = if (p0?.state == Partner.State.NEED_LOGIN) "需登录" else p0?.reason ?: "没连接或已被移除"))
                return
            }
            val placeholder = GroupMsg(GroupStore.newId(), if (isHost) GroupStore.HOST else id, name, p0?.model ?: "", "", "pending",
                kind = if (summary) "summary" else if (wrap) "wrap" else "")
            GroupStore.add(gid, placeholder)
            val t0 = System.currentTimeMillis()
            val done = try {
                if (p0 == null) throw MemberError("主持需要至少一个能用的模型", null)
                // the host falls back to the next ready API member when its first choice fails
                val candidates = if (isHost) Partners.hostCandidates().take(3).ifEmpty { listOf(p0) } else listOf(p0)
                var last: MemberError? = null
                var result: GroupMsg? = null
                for (p in candidates) {
                    try {
                        val me = if (isHost) HOST_NAME else p.spec.name
                        var turns = turnsFor(gid, if (isHost) GroupStore.HOST else id)
                        if (summary) turns = turns + AiClient.Turn("user", SUMMARY_PROMPT)
                        else if (wrap) turns = turns + AiClient.Turn("user", WRAP_PROMPT)
                        val direct = g.members.size == 1 && !g.host
                        val sys = system(me, p.model, names.filter { it != shortName(me) }, isHost, direct)
                        if (direct) turns = turns.map { if (it.role == "user") AiClient.Turn("user", it.text.removePrefix("【群主】")) else it }
                        val r = AiClient.reply(p, sys, turns, if (summary) 1200 else if (wrap) 400 else 700)
                        result = placeholder.copy(text = r.text, status = "ok", model = r.model.ifBlank { p.model } + if (isHost) " · ${shortName(p.spec.name)}" else "",
                            inTok = r.inTok, outTok = r.outTok, ms = System.currentTimeMillis() - t0, detail = r.note ?: "")
                        break
                    } catch (e: MemberError) { last = e }
                }
                result ?: throw (last ?: MemberError("没有回复", null))
            } catch (e: CancellationException) {
                GroupStore.replace(gid, placeholder.copy(status = "stopped", text = ""))
                throw e
            } catch (e: MemberError) {
                placeholder.copy(status = "error", text = e.message ?: "没回上", detail = e.detail ?: "", ms = System.currentTimeMillis() - t0)
            } catch (e: Exception) {
                placeholder.copy(status = "error", text = "出了点问题，可以重试", detail = "${e.javaClass.simpleName}: ${e.message?.take(200) ?: ""}", ms = System.currentTimeMillis() - t0)
            }
            GroupStore.replace(gid, done)
        }
        // 代打字 members drive the phone UI: always one at a time, after the API members.
        val (phone, rest) = who.partition { PhoneApps.isPhone(it) }
        // [Mines-38.6] slow agents (Manus tasks, web-chat apps, Mines' own agent loop) start at once in the background and
        // never hold up the quick members; each shows 「正在思考…」 with its own time until it answers or times out.
        val slowProtos = setOf(Proto.MANUS, Proto.WEBAPP, Proto.MINES, Proto.ADAPTER)  // [Mines-38.8] ADAPTER = Memoh …
        val (slow, api) = rest.partition { id -> id != GroupStore.HOST && members.firstOrNull { it.id == id }?.let { it.ready && it.spec.proto in slowProtos } == true }
        coroutineScope {
            val bg = slow.map { id -> async { one(id) } }
            if (g.sequential) {
                for (id in api) one(id)
            } else {
                // parallel mode: at most 3 requests at once (11 members at once tripped 429 limits)
                val gate = kotlinx.coroutines.sync.Semaphore(3)
                api.map { id -> async { gate.acquire(); try { one(id) } finally { gate.release() } } }.awaitAll()
            }
            bg.awaitAll()
        }
        for (id in phone) one(id)
    }

    /** Tokens used in a group (shown under the title). */
    fun tokens(msgs: List<GroupMsg>): Int = msgs.sumOf { it.inTok + it.outTok }
}
