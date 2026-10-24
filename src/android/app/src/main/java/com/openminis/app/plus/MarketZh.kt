package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * [Mines-38.9.8] user 01:21: 市场 plugin / skill descriptions show in English → shown in Chinese. One batched call per page
 * to the user's own model (cheapest configured cloud model first; Memoh when set), cached per item + description hash
 * (SkillHub's plugin list has no version field, so a changed description = a new version). Original text whenever the
 * call fails; the detail dialog has a small 「原文」 toggle. Nothing is sent when every description already reads Chinese.
 */
object MarketZh {
    /** bumps when new translations land (rows re-read [zh]) */
    val tick = MutableStateFlow(0)
    @Volatile var lastError: String = ""
    @Volatile var lastModel: String = ""
    private val mem = HashMap<String, String>()
    @Volatile private var loaded = false
    private val lock = Mutex()
    private val inFlight = HashSet<String>()

    fun key(kind: String, id: String, text: String): String = "$kind:$id#${text.trim().hashCode().toUInt().toString(16)}"
    /** needs translating: has Latin words and (almost) no CJK */
    fun needs(text: String): Boolean {
        val t = text.trim(); if (t.length < 8) return false
        val cjk = t.count { it.code in 0x4E00..0x9FFF }; val latin = t.count { it in 'a'..'z' || it in 'A'..'Z' }
        return latin >= 8 && cjk * 4 < latin / 2
    }

    private fun file(c: Context) = File(c.filesDir, "market_zh.json")
    private fun load(c: Context) {
        if (loaded) return
        synchronized(mem) {
            if (loaded) return
            runCatching { val o = JSONObject(file(c).readText()); o.keys().forEach { k -> mem[k] = o.optString(k) } }
            loaded = true
        }
    }
    private fun save(c: Context) = runCatching {
        val o = JSONObject(); synchronized(mem) { mem.entries.toList().takeLast(3000).forEach { (k, v) -> o.put(k, v) } }
        file(c).writeText(o.toString())
    }

    /** cached Chinese text, or null (not translated yet / failed) */
    fun zh(c: Context, kind: String, id: String, text: String): String? {
        if (!needs(text)) return null
        load(c); return synchronized(mem) { mem[key(kind, id, text)] }?.takeIf { it.isNotBlank() }
    }

    /** Translates what is missing (≤ 25 per call). Safe to call on every page load; returns how many were added. */
    suspend fun ensure(c: Context, kind: String, items: List<Pair<String, String>>): Int = withContext(Dispatchers.IO) {
        load(c)
        val todo = items.filter { (id, t) -> needs(t) && synchronized(mem) { mem[key(kind, id, t)] } == null }
            .distinctBy { key(kind, it.first, it.second) }
            .filter { synchronized(inFlight) { inFlight.add(key(kind, it.first, it.second)) } }
            .take(25)
        if (todo.isEmpty()) return@withContext 0
        try {
            lock.withLock {
                val prompt = buildString {
                    append("把下面每一行软件插件简介翻译成简洁自然的中文（保留产品名、命令名和代码标识符原样）。")
                    append("只输出译文，每行一条，行首保留原来的编号「N. 」，不要解释。\n\n")
                    todo.forEachIndexed { i, (_, t) -> append(i + 1).append(". ").append(t.replace('\n', ' ').take(400)).append('\n') }
                }
                val reply = ask(c, prompt) ?: return@withLock 0
                val lines = reply.lines().mapNotNull { l -> Regex("^\\s*(\\d+)[.、)]\\s*(.+)$").find(l)?.let { it.groupValues[1].toInt() to it.groupValues[2].trim() } }.toMap()
                var n = 0
                todo.forEachIndexed { i, (id, t) ->
                    val z = lines[i + 1]?.takeIf { it.isNotBlank() && it.any { ch -> ch.code in 0x4E00..0x9FFF } }
                    if (z != null) { synchronized(mem) { mem[key(kind, id, t)] = z.take(600) }; n++ }
                }
                if (n > 0) { save(c); tick.value++ }
                n
            }
        } finally { synchronized(inFlight) { todo.forEach { inFlight.remove(key(kind, it.first, it.second)) } } }
    }

    /** The user's own model: cheapest enabled cloud entry with a key (not the local 免登录 gateway), else Memoh. */
    private suspend fun ask(c: Context, prompt: String): String? {
        val a = c.applicationContext as? com.openminis.app.MinisApp
        if (a != null) {
            val repo = a.providerRepository
            val cheapRe = Regex("(?i)(flash|mini|lite|haiku|turbo|nano|small|air|8b|7b)")
            val entries = repo.allVisibleEntries().filter { e -> if (!ErrZhCore.chatCapable(e.baseModel.id)) return@filter false   // [38.9.11.3] no TTS / embedding / image models
                val inst = repo.instance(e.providerInstanceId)
                inst != null && inst.isEnabled && !(inst.customBaseURL ?: "").contains("127.0.0.1")
            }.sortedByDescending { cheapRe.containsMatchIn(it.baseModel.id) }.take(3)
            for (e in entries) {
                val inst = repo.instance(e.providerInstanceId) ?: continue
                val key = repo.usableApiKey(inst) ?: continue
                val r = runCatching {
                    kotlinx.coroutines.withTimeout(40_000L) {
                        com.openminis.app.provider.ProviderFactory.create(inst, key, e.baseModel, c, overrides = e.overrides)
                            .sendMessage(messages = listOf(com.openminis.app.data.model.LLMMessage(role = com.openminis.app.data.model.LLMMessage.Role.USER, content = prompt)),
                                systemPrompt = "你是翻译。只输出译文。", maxTokens = 2000).text
                    }
                }.onFailure { lastError = "${e.baseModel.id}：${it.message ?: it.javaClass.simpleName}".take(200) }.getOrNull()
                if (!r.isNullOrBlank()) { lastModel = e.baseModel.id; lastError = ""; return r }
            }
        }
        if (MemohKey.configured()) {
            val r = runCatching { MemohKey.reply("你是翻译。只输出译文。", prompt) }.onFailure { lastError = "Memoh：${it.message}".take(200) }.getOrNull()
            if (!r.isNullOrBlank()) { lastModel = "Memoh"; lastError = ""; return r }
        }
        if (lastError.isBlank()) lastError = "没有可用的模型（模型组里加一个带 key 的模型，或在 应用代理 里填 Memoh）"
        return null
    }
}
