package com.openminis.app.browser

import android.content.Context
import androidx.webkit.ProfileStore
import org.json.JSONObject

/**
 * [Mines-38.9.11.2] Android side of [ConvProfilesCore]: session id → profile name (persisted, so a draft's profile follows
 * it when it becomes a real conversation), account bindings, deletion with the conversation. Profile calls on the main thread.
 * Without MULTI_PROFILE support nothing changes (conversations share the default profile, as before) — [active] says so.
 */
object ConvProfiles {
    private const val PREFS = "mines_conv_profiles"
    @Volatile private var ctx: Context? = null
    private val map = LinkedHashMap<String, String>()        // sessionId → profile
    private val binds = LinkedHashMap<String, String>()      // "sid|site" → account

    fun active(): Boolean = Incognito.supported()

    @Synchronized fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching { JSONObject(p.getString("map", "{}")!!).let { o -> o.keys().forEach { k -> map[k] = o.getString(k) } } }
        runCatching { JSONObject(p.getString("binds", "{}")!!).let { o -> o.keys().forEach { k -> binds[k] = o.getString(k) } } }
    }

    @Synchronized private fun save() {
        val p = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        p.edit().putString("map", JSONObject(map as Map<*, *>).toString()).putString("binds", JSONObject(binds as Map<*, *>).toString()).apply()
    }

    /** the profile a conversation's tab uses (null = default profile: not a conversation, or no profile support) */
    @Synchronized fun profileFor(c: Context, sessionId: String?): String? {
        if (!ConvProfilesCore.isConversation(sessionId) || !active()) return null
        init(c)
        val name = map.getOrPut(sessionId!!) { ConvProfilesCore.profileName(sessionId) }
        save()
        runCatching { ProfileStore.getInstance().getOrCreateProfile(name) }
        runCatching { c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("created", true).apply() }
        return name
    }

    private fun everCreated(c: Context) = runCatching { c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("created", false) }.getOrDefault(false)

    fun profiles(): Map<String, String> = synchronized(this) { map.toMap() }
    fun bindings(): Map<String, String> = synchronized(this) { binds.toMap() }

    /** draft → real conversation: same profile, same bindings */
    @Synchronized fun rename(from: String, to: String) {
        if (from == to) return
        map.remove(from)?.let { map[to] = it }
        val nb = ConvProfilesCore.renameSession(binds, from, to); binds.clear(); binds.putAll(nb)
        save()
    }

    @Synchronized fun bind(sessionId: String?, site: String, account: String) {
        if (!ConvProfilesCore.isConversation(sessionId) || site.isBlank()) return
        binds[ConvProfilesCore.bindKey(sessionId!!, site)] = account; save()
    }

    fun mayAutoFill(sessionId: String?, site: String, account: String) = ConvProfilesCore.mayAutoFill(bindings(), sessionId, site, account)

    /** the conversation is gone: its profile (cookies, storage, cache) and its bindings go too */
    fun forget(c: Context, sessionId: String) {
        init(c)
        val name = synchronized(this) {
            val n = map.remove(sessionId)
            val nb = ConvProfilesCore.dropSession(binds, sessionId); binds.clear(); binds.putAll(nb); save(); n
        } ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { deleteProfile(name, 0) }
    }

    private fun deleteProfile(name: String, attempt: Int) {
        if (!active()) return
        val ok = runCatching {
            val st = ProfileStore.getInstance()
            st.getProfile(name)?.let { p -> p.cookieManager.removeAllCookies(null); p.webStorage.deleteAllData() }
            st.deleteProfile(name); true
        }.getOrDefault(false)
        if (!ok && attempt < 3) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ deleteProfile(name, attempt + 1) }, 1500L)
    }

    /** a conversation deleted where no Context is at hand (ChatRepository) */
    fun forget(sessionId: String) { ctx?.let { forget(it, sessionId) } }

    /** on launch (main thread, idle): conversation profiles no conversation maps to any more */
    fun sweepOrphans(c: Context): Int {
        // [38.9.11.3] user: 「卡、闪退」. ProfileStore.getInstance() (and even the MULTI_PROFILE feature check) starts Chromium on
        // the MAIN thread — 38.9.11.2 did that 6 s after every launch, right when the user opens a chat. Now only when a
        // conversation profile was ever created (the agent browser was really opened in a conversation).
        init(c)
        if (!everCreated(c) && profiles().isEmpty()) return 0
        if (!active()) return 0
        return runCatching {
            val st = ProfileStore.getInstance()
            val o = ConvProfilesCore.orphans(st.allProfileNames, profiles().values)
            o.forEach { n -> runCatching { st.deleteProfile(n) } }
            o.size
        }.getOrDefault(0)
    }
}
