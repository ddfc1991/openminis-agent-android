package com.openminis.app.plus

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.data.db.ChatSessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Tidies the conversation list without touching the database:
 *  - auto-archive: a chat idle for N days (default 7) leaves the main list;
 *    it shows under the 「已归档」 chip and comes back as soon as it is
 *    opened (or with 「全部恢复」). Pinned chats are never archived.
 *  - topics: chats are grouped by keywords in their title and last message
 *    (服务器 / 交易 / Mines 开发 / 编程 / 生活), shown as filter chips. Local
 *    and instant; no model call, nothing leaves the phone.
 * State is a small SharedPreferences file, so it survives updates and costs
 * no migration.
 */
object ChatOrganizer {
    const val FILTER_ALL = "all"
    const val FILTER_ARCHIVED = "archived"
    private const val FILE = "mines_chat_tidy"

    data class Topic(val name: String, val keywords: List<String>)

    val topics = listOf(
        Topic("服务器", listOf("服务器", "vps", "ssh", "阿里云", "ecs", "linux", "docker", "nginx", "宝塔", "面板", "域名", "dns", "cloudflare", "端口", "部署", "梦云", "wispbyte")),
        Topic("交易", listOf("交易", "okx", "币安", "binance", "比特币", "btc", "eth", "合约", "量化", "行情", "仓位", "k线", "策略", "hermes", "现货", "止损")),
        Topic("Mines 开发", listOf("mines", "minis", "apk", "插件", "构建", "build", "actions", "github", "补丁", "kotlin", "安卓", "android", "dsh")),
        Topic("编程", listOf("代码", "python", "javascript", "bug", "报错", "脚本", "api", "函数", "编译", "sql")),
        Topic("生活", listOf("天气", "吃", "餐厅", "旅行", "机票", "酒店", "购物", "电影", "健康", "快递", "外卖")),
    )

    data class Settings(val autoArchive: Boolean = true, val days: Int = ArchiveCore.DEFAULT_DAYS, val topicChips: Boolean = true)   // [Mines-38.9.6] 3 days (was 7)

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _filter = MutableStateFlow(FILTER_ALL)
    val filter: StateFlow<String> = _filter.asStateFlow()
    /** id → when the user last restored/opened it (restarts the idle clock). */
    private val _restored = MutableStateFlow<Map<String, Long>>(emptyMap())
    val restored: StateFlow<Map<String, Long>> = _restored.asStateFlow()
    /** [Mines-38.9.6 i12] id → when the user archived it by hand. */
    private val _manual = MutableStateFlow<Map<String, Long>>(emptyMap())
    val manual: StateFlow<Map<String, Long>> = _manual.asStateFlow()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            prefs = p
            runCatching { TaskProjects.init(context) }   // [Mines-38.9.13 task_projects]
            _settings.value = Settings(
                autoArchive = p.getBoolean("auto", true),
                // [Mines-38.9.6] new key: everyone starts on the 3-day rule (user 2026-10-18); a value set after this is kept
                days = p.getInt("days3", ArchiveCore.DEFAULT_DAYS).coerceIn(1, 365),
                topicChips = p.getBoolean("chips", true),
            )
            _restored.value = runCatching {
                val o = JSONObject(p.getString("restored", "{}") ?: "{}")
                o.keys().asSequence().associateWith { o.getLong(it) }
            }.getOrDefault(emptyMap())
            _manual.value = runCatching {
                val o = JSONObject(p.getString("manual", "{}") ?: "{}")
                o.keys().asSequence().associateWith { o.getLong(it) }
            }.getOrDefault(emptyMap())
        }
    }

    fun update(context: Context, block: (Settings) -> Settings) {
        init(context)
        val n = block(_settings.value).let { it.copy(days = it.days.coerceIn(1, 365)) }
        _settings.value = n
        prefs?.edit()?.putBoolean("auto", n.autoArchive)?.putInt("days3", n.days)?.putBoolean("chips", n.topicChips)?.apply()
        if (!n.topicChips && _filter.value != FILTER_ARCHIVED) _filter.value = FILTER_ALL
    }

    fun setFilter(f: String) { _filter.value = f }

    private fun saveRestored() {
        prefs?.edit()?.putString("restored", JSONObject(_restored.value as Map<*, *>).toString())?.apply()
    }

    /** Opening a chat (or 恢复 / 取消归档 / 撤销) puts it back in the main list. */
    fun restore(context: Context, id: String) {
        init(context)
        _restored.value = _restored.value + (id to System.currentTimeMillis())
        saveRestored()
        if (_manual.value.containsKey(id)) { _manual.value = _manual.value - id; saveManual() }
    }

    private fun saveManual() {
        prefs?.edit()?.putString("manual", JSONObject(_manual.value as Map<*, *>).toString())?.apply()
    }

    /** [Mines-38.9.6 i12] one-tap archive (progress card, row long-press, chat menu). */
    fun archive(context: Context, id: String) {
        init(context)
        _manual.value = _manual.value + (id to System.currentTimeMillis())
        saveManual()
        ArchiveUndo.offer(id)
    }

    fun isManual(id: String): Boolean = _manual.value.containsKey(id)

    /** the chat is 进行中 / 等你 (never auto-archived) */
    private fun busy(id: String): Boolean = TaskProgress.get(id)?.st.let { it == TaskProgressCore.St.RUNNING || it == TaskProgressCore.St.WAITING }

    fun restoreAll(context: Context, ids: Collection<String>) {
        init(context)
        val now = System.currentTimeMillis()
        _restored.value = _restored.value + ids.associateWith { now }
        saveRestored()
        if (ids.any { _manual.value.containsKey(it) }) { _manual.value = _manual.value - ids.toSet(); saveManual() }
        _filter.value = FILTER_ALL
    }

    fun isArchived(s: ChatSessionEntity, now: Long = System.currentTimeMillis()): Boolean {
        val st = _settings.value
        // [Mines-38.9.13 task_projects] projects never auto-archive; small tasks after at most 2 days
        val proj = TaskProjects.isProject(s.id)
        val days = if (TaskProjects.enabled()) TaskProjectsCore.archiveDays(proj, st.days) ?: st.days else st.days
        return ArchiveCore.isArchived(ArchiveCore.Input(
            lastActivity = s.updatedAt, pinned = s.pinnedAt != null || proj, draft = s.modelId.isEmpty(), busy = busy(s.id),
            manualAt = _manual.value[s.id], restoredAt = _restored.value[s.id] ?: 0L,
        ), now, st.autoArchive, days)
    }

    fun topicOf(s: ChatSessionEntity): String? {
        val text = ((s.title ?: "") + " " + (s.lastMessage ?: "").take(400)).lowercase()
        if (text.isBlank()) return null
        var best: String? = null; var bestHits = 0
        for (t in topics) {
            val hits = t.keywords.count { text.contains(it) }
            if (hits > bestHits) { best = t.name; bestHits = hits }
        }
        return best
    }

    /** Topic chips worth showing: topics with at least one live chat. */
    fun chipCounts(all: List<ChatSessionEntity>): List<Pair<String, Int>> {
        val live = all.filter { !isArchived(it) }
        return topics.map { t -> t.name to live.count { topicOf(it) == t.name } }.filter { it.second > 0 }
    }

    fun archivedCount(all: List<ChatSessionEntity>): Int = all.count { isArchived(it) }

    /** The list the home screen shows for the current filter. */
    fun apply(all: List<ChatSessionEntity>, filter: String): List<ChatSessionEntity> = when (filter) {
        FILTER_ARCHIVED -> all.filter { isArchived(it) }
        FILTER_ALL -> all.filter { !isArchived(it) }
        else -> all.filter { !isArchived(it) && topicOf(it) == filter }
    }
}

/** [Mines-38.9.6 i12] 「已归档 · 撤销」 for ~4 s after a one-tap archive (shown by ArchiveUndoHost at the activity root). */
object ArchiveUndo {
    data class Offer(val id: String, val at: Long)
    private val _offer = MutableStateFlow<Offer?>(null)
    val offer: StateFlow<Offer?> = _offer.asStateFlow()
    fun offer(id: String) { _offer.value = Offer(id, System.currentTimeMillis()) }
    fun clear(o: Offer) { if (_offer.value == o) _offer.value = null }
}
