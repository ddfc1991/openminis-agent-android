package com.openminis.app.plus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openminis.app.browser.BrowserHandoffCore
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.browser.BrowserUseManager
import com.openminis.app.plus.HandoffCore.Handoff
import com.openminis.app.plus.HandoffCore.Kind
import com.openminis.app.plus.HandoffCore.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * [Mines-38.9.13 handoff_v2] Android side of [HandoffCore]: one record per browser step handed to the user, per
 * conversation + tab (replaces the single global HumanVerify.pending / LoginCard wait for the agent's browser).
 *
 *  - the browser action that hits a captcha / sign-in returns at once with 「[交接中 handoff_id=…]」; the agent waits with
 *    browser wait_for_handoff (≤ 55 s slices, the tab is exempt from the 75 s wedge rule while a handoff is open);
 *  - the handoff ends on events (page finished / URL change / login card answered / browser sheet closed / 「继续接管」 /
 *    back from Chrome) or a 500 ms fallback probe, and the waiting agent gets the result at once; when its turn had
 *    already ended, a new turn is started in that conversation with 「[交接完成] …」;
 *  - watchdog: 30 s reminder, 2 min 「还在等你」 card, 5 min pause (task shown as 等待用户) — 「继续接管」 still resumes.
 * Values the user types never pass through here (the login card fills the page directly).
 */
object HandoffBroker {
    private const val TAG = "HandoffBroker"
    private const val CHANNEL = "mines_handoff"
    private const val NOTIF_BASE = 47100

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _list = MutableStateFlow<List<Handoff>>(emptyList())
    /** open + paused handoffs of every conversation (finished ones leave the list) */
    val list: StateFlow<List<Handoff>> = _list.asStateFlow()

    private val pools = ConcurrentHashMap<String, WeakReference<BrowserTabPool>>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<String>>()
    /** finished text not yet given to the agent: handoff id → (session id, text) */
    private val results = ConcurrentHashMap<String, Pair<String, String>>()
    private val kicks = ConcurrentHashMap<String, Channel<Unit>>()
    private val oauthAsked = ConcurrentHashMap<String, Long>()
    private val chromeOpenedAt = ConcurrentHashMap<String, Long>()
    /** a note for the agent's next result (the click that hit Google / Apple) — manager identity → text */
    private val actionNotes = ConcurrentHashMap<Int, String>()
    private data class AuthPage(val pool: WeakReference<BrowserTabPool>, val tabId: Int, val url: String, val pw: Boolean, val cookies: Set<String>, val at: Long)
    private val lastAuthPage = ConcurrentHashMap<String, AuthPage>()

    @Volatile private var app: Context? = null
    /** set by the task-naming feature: session id → (project name, attempt N for 「第N次」) */
    @Volatile var projectNameOf: ((String) -> Pair<String, Int>?)? = null
    /** a handoff whose conversation screen must open on its tab once that conversation is loaded (notification tap) */
    val pendingFocus = MutableStateFlow<String?>(null)

    fun init(c: Context) { if (app == null) app = c.applicationContext; HandoffFlags.init(c) }

    fun resumeOn(): Boolean = HandoffFlags.on(HandoffFlags.RESUME)

    fun get(id: String): Handoff? = _list.value.firstOrNull { it.id == id }

    fun openFor(sid: String?): Handoff? = _list.value.filter { it.sessionId == sid && it.open }.maxByOrNull { it.createdAt }

    private fun forTab(sid: String?, tabId: Int): Handoff? = _list.value.firstOrNull { it.sessionId == sid && it.tabId == tabId && it.open }

    @Synchronized private fun update(id: String, f: (Handoff) -> Handoff?): Handoff? {
        val cur = _list.value.firstOrNull { it.id == id } ?: return null
        val n = f(cur) ?: return null
        _list.value = _list.value.map { if (it.id == id) n else it }
        return n
    }

    @Synchronized private fun remove(id: String) { _list.value = _list.value.filterNot { it.id == id } }

    private fun now() = System.currentTimeMillis()

    // ── page probe (never marks a tab wedged) ─────────────────────────
    data class Probe(val url: String, val kind: String, val otpField: Boolean, val pw: Boolean, val words: Boolean)

    private const val PROBE_JS = """(function(){try{
var vis=function(e){var r=e.getBoundingClientRect();return r.width>4&&r.height>4&&getComputedStyle(e).visibility!=='hidden';};
var pw=[].slice.call(document.querySelectorAll('input[type=password]')).some(vis);
var t=((document.body&&document.body.innerText)||'').slice(0,6000);
var av=!!document.querySelector('img[alt*="avatar" i],img[class*="avatar" i],[class*="avatar" i] img');
return JSON.stringify({pw:pw,t:t.slice(0,6000),av:av});}catch(x){return '{}';}})()"""

    suspend fun probe(m: BrowserUseManager): Probe? {
        val url = m.currentURL.value
        val scan = HumanVerify.parseScan(m.handoffEval(HumanVerify.DETECT_JS) ?: return null)
        val raw = m.handoffEval(PROBE_JS) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val words = o.optBoolean("av") || HandoffCore.SIGNED_IN_WORDS.containsMatchIn(o.optString("t"))
        return Probe(url, scan.kind, scan.otpField, o.optBoolean("pw"), words)
    }

    /** cookie names of [url] in the jar the conversation's browser uses */
    fun cookieNames(sid: String?, url: String): Set<String> = runCatching {
        val name = sid?.let { com.openminis.app.browser.ConvProfiles.profiles()[it] }
        val cm = name?.let { androidx.webkit.ProfileStore.getInstance().getProfile(it)?.cookieManager } ?: android.webkit.CookieManager.getInstance()
        HandoffCore.cookieNames(cm.getCookie(url))
    }.getOrDefault(emptySet())

    private fun loggedInNow(h: Handoff, p: Probe): Boolean =
        HandoffCore.loggedIn(HandoffCore.signals(h.startPw, h.startAuthUrl, h.startCookies, p.pw, p.url, cookieNames(h.sessionId, p.url), p.words))

    // ── open ──────────────────────────────────────────────────────────
    /**
     * Records a handoff on [m]'s tab and tells the user (popup + notification). Main thread or not; never suspends.
     * Returns null when the tab has no conversation (home browser) — the caller keeps the 38.9.12.1 path.
     */
    fun open(m: BrowserUseManager, kind: Kind, detail: String = "", startPw: Boolean = false, notifyUser: Boolean = true): Handoff? {
        val pool = m.poolRef ?: return null
        val sid = pool.sessionKey ?: return null
        val tabId = m.tabId.takeIf { it >= 0 } ?: return null
        forTab(sid, tabId)?.takeIf { it.kind == kind }?.let { return it }
        val url = m.currentURL.value
        val host = Keychain.hostOf(url)
        val t = now()
        val h = Handoff(
            id = "h" + java.util.UUID.randomUUID().toString().take(6),
            sessionId = sid, tabId = tabId, url = url, host = host, kind = kind, detail = detail, createdAt = t,
            maxTabIdAtStart = pool.tabs.value.maxOfOrNull { it.id } ?: tabId,
            startPw = startPw, startAuthUrl = HandoffCore.isAuthUrl(url), startCookies = cookieNames(sid, url),
        )
        synchronized(this) { _list.value = _list.value + h }
        pools[h.id] = WeakReference(pool)
        Log.i(TAG, "open ${h.id} $kind tab=$tabId sid=${sid.take(8)}")
        scope.launch {
            val label = label(h)
            update(h.id) { it.copy(taskLabel = label) }
            if (notifyUser) get(h.id)?.let { notify(it) }
        }
        watch(h.id)
        return h
    }

    private suspend fun label(h: Handoff): String {
        val site = runCatching { Keychain.siteName(h.host) }.getOrNull()?.ifBlank { null } ?: HandoffLabel.siteWord(h.host)
        val proj = runCatching { projectNameOf?.invoke(h.sessionId) }.getOrNull()
        val title = runCatching { (app as? com.openminis.app.MinisApp)?.chatRepositoryOrNull?.getSession(h.sessionId)?.title }.getOrNull()
        return HandoffLabel.taskName(proj?.first, proj?.second ?: 1, title, site, h.url)
    }

    /** the task name once resolved (≤ 1 s), for the login card */
    suspend fun labelOf(id: String): String {
        repeat(10) { get(id)?.taskLabel?.takeIf { it.isNotBlank() }?.let { return it }; delay(100) }
        return get(id)?.taskLabel.orEmpty()
    }

    fun verb(h: Handoff): String = HandoffLabel.action(h.kind, h.detail, h.host)

    // ── watcher: watchdog + fallback probe + events ───────────────────
    private fun watch(id: String) {
        val ch = Channel<Unit>(Channel.CONFLATED)
        kicks[id] = ch
        scope.launch {
            try {
                while (true) {
                    withTimeoutOrNull(HandoffCore.POLL_MS) { ch.receive() }
                    val h = get(id) ?: break
                    if (!h.open) break
                    when (HandoffCore.watchdog(h, now())) {
                        HandoffCore.Watch.REMIND -> { update(id) { it.copy(stage = 1, showSeq = it.showSeq + 1) }?.let { notify(it) } }
                        HandoffCore.Watch.CARD -> update(id) { it.copy(stage = 2) }
                        HandoffCore.Watch.EXPIRE -> { expire(id); break }
                        HandoffCore.Watch.NONE -> {}
                    }
                    if (h.kind == Kind.CAPTCHA || h.kind == Kind.OTP || h.kind == Kind.LOGIN) check(id)
                }
            } finally { kicks.remove(id, ch) }
        }
    }

    private fun kick(id: String) { kicks[id]?.trySend(Unit) }

    private suspend fun check(id: String) {
        if (id in filling) return
        val h = get(id)?.takeIf { it.open } ?: return
        val m = manager(h, h.tabId) ?: return
        val p = probe(m) ?: return
        val done = when (h.kind) {
            Kind.CAPTCHA -> HandoffCore.captchaCleared(h.url, p.url, "cf_clearance" in h.startCookies,
                "cf_clearance" in cookieNames(h.sessionId, p.url), p.kind)
            Kind.OTP -> HandoffCore.otpCleared(h.url, p.url, p.otpField)
            Kind.LOGIN -> loggedInNow(h, p)
            else -> false
        }
        if (done) complete(id, verified = if (h.kind == Kind.LOGIN) true else null)
    }

    private fun pool(h: Handoff): BrowserTabPool? = pools[h.id]?.get()
    private fun manager(h: Handoff, tabId: Int): BrowserUseManager? = pool(h)?.tabs?.value?.firstOrNull { it.id == tabId }?.manager

    /** a page of [sid]'s browser finished loading / changed URL (user or agent) */
    fun onPage(m: BrowserUseManager) {
        if (_list.value.isEmpty()) return
        val sid = m.poolRef?.sessionKey ?: return
        _list.value.filter { it.sessionId == sid && it.open }.forEach { h ->
            update(h.id) { it.copy(progressAt = now(), idleWaits = 0) }
            kick(h.id)
        }
    }

    // ── finishing ─────────────────────────────────────────────────────
    /**
     * The user is done (or pressed 「继续接管」 when [manual]). Picks the tab the agent continues on (F3), flushes cookies,
     * and gives the agent the result — to its running wait, else as a new turn.
     */
    suspend fun complete(id: String, verified: Boolean? = null, manual: Boolean = false, extra: String = "") {
        val h = get(id) ?: return
        if (!HandoffCore.canMove(h.state, State.DONE)) return
        val pool = pool(h)
        var tab = h.tabId
        if (pool != null && HandoffFlags.on(HandoffFlags.TAB)) {
            val selected = pool.selectedTabId.value
            tab = if (manual) selected.takeIf { s -> pool.tabs.value.any { it.id == s } } ?: h.tabId else {
                val site = HandoffCore.site(h.host)
                val infos = pool.tabs.value.filter { it.id == h.tabId || it.id == selected || it.id > h.maxTabIdAtStart }.map { t ->
                    val url = t.manager.currentURL.value
                    val li = HandoffCore.site(url) == site && (h.kind != Kind.LOGIN || t.id == h.tabId && verified == true ||
                        probe(t.manager)?.let { loggedInNow(h, it) } == true)
                    HandoffCore.TabInfo(t.id, url, li || (h.kind != Kind.LOGIN && t.id == h.tabId))
                }
                HandoffCore.pickResumeTab(h.tabId, selected, h.maxTabIdAtStart, site, infos)
            }
            if (tab != h.tabId) pool.handoffAdopt(h.tabId, tab)
        }
        val url = pool?.tabs?.value?.firstOrNull { it.id == tab }?.manager?.currentURL?.value ?: h.url
        runCatching { LoginWeb.flush() }
        val v = verified ?: if (h.kind == Kind.LOGIN) manager(h, tab)?.let { m -> probe(m)?.let { loggedInNow(h, it) } } else null
        val done = update(id) { HandoffCore.move(it, State.DONE, now()) } ?: return
        Log.i(TAG, "done $id tab=$tab manual=$manual verified=$v")
        cancelNotif(done)
        remove(id)
        deliver(done, HandoffCore.doneText(done, tab, url, v, manual, extra), wake = true)
    }

    /** handoffs whose login card answer is being typed into the page (the watcher leaves them alone) */
    private val filling = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun launch(block: suspend CoroutineScope.() -> Unit) { scope.launch(block = block) }

    /** the login card's values are in the page: wait ≤ 5 s for the sign-in to land, then hand back with the fill note */
    suspend fun loginFilled(id: String, note: String) {
        filling += id
        try {
            var ok = false
            repeat(10) {
                if (ok) return@repeat
                delay(500)
                val h = get(id) ?: return
                val m = manager(h, h.tabId) ?: return@repeat
                ok = probe(m)?.let { loggedInNow(h, it) } == true
            }
            complete(id, verified = ok, extra = note)
        } finally { filling -= id }
    }

    /** 「放弃这一步」 / login card closed / 「稍后」 never cancels (it only hides the popup) */
    fun cancel(id: String, why: String = "") {
        val h = update(id) { HandoffCore.move(it, State.CANCELLED, now()) } ?: return
        cancelNotif(h)
        remove(id)
        if (h.kind == Kind.LOGIN && LoginCard.pending.value?.host == h.host) LoginCard.answer(null)
        deliver(h, HandoffCore.cancelledText(h, why), wake = false)
    }

    private fun expire(id: String) {
        val h = update(id) { HandoffCore.move(it, State.EXPIRED, now())?.copy(stage = 3) } ?: return
        cancelNotif(h)   // [Mines-38.9.15] the ongoing human-check notice must not outlive its handoff
        runCatching { TaskProgress.onWaiting(h.sessionId, "等你在 ${HandoffCore.site(h.host)} ${verb(h)}") }
        deliver(h, HandoffCore.expiredText(h), wake = false)
    }

    private fun deliver(h: Handoff, text: String, wake: Boolean) {
        val w = waiters.remove(h.id)
        if (w != null && w.complete(text)) return
        results[h.id] = h.sessionId to text
        if (wake && resumeOn()) wakeTurn(h.sessionId, h.id)
    }

    /** the agent's turn had ended: start a new one in that conversation (or after the running one ends) */
    private fun wakeTurn(sid: String, id: String) {
        scope.launch {
            repeat(1800) {
                if (!results.containsKey(id)) return@launch
                val vm = com.openminis.app.ui.chat.ChatViewModelStore.existing(sid) ?: return@launch
                if (!vm.isStreaming.value) {
                    val t = results.remove(id)?.second ?: return@launch
                    val out = vm.submitPrompt(HandoffCore.resumePrompt(t))
                    Log.i(TAG, "woke ${sid.take(8)} for $id → $out")
                    return@launch
                }
                delay(1000)
            }
        }
    }

    /** finished-handoff lines for the agent's next browser result in [sid] (when it did not wait) */
    fun drainNotes(sid: String?): String? {
        if (sid == null || results.isEmpty()) return null
        val mine = results.entries.filter { it.value.first == sid }
        if (mine.isEmpty()) return null
        return mine.mapNotNull { results.remove(it.key)?.second }.joinToString("\n").ifBlank { null }
    }

    // ── the agent side ────────────────────────────────────────────────
    /** browser wait_for_handoff: returns as soon as the handoff ends, else after one slice */
    suspend fun await(sid: String?, id: String?): String {
        val key = id?.trim()?.ifBlank { null }
        key?.let { k -> results[k]?.takeIf { it.first == sid }?.let { results.remove(k); return it.second } }
        val h = HandoffCore.pick(_list.value, sid, key)
            ?: return drainNotes(sid) ?: "[交接] 现在没有在等用户的交接，可以继续操作（先截图看当前页面）。"
        if (h.state == State.EXPIRED) return HandoffCore.expiredText(h)
        val d = CompletableDeferred<String>()
        waiters.put(h.id, d)?.complete(HandoffCore.stillWaitingText(h))
        kick(h.id)
        val r = try { withTimeoutOrNull(HandoffCore.WAIT_SLICE_MS) { d.await() } } finally { waiters.remove(h.id, d) }
        if (r != null) return r
        results.remove(h.id)?.let { return it.second }
        val n = update(h.id) { it.copy(idleWaits = it.idleWaits + 1) } ?: return HandoffCore.stillWaitingText(h)
        return if (n.idleWaits >= HandoffCore.MAX_IDLE_WAITS) HandoffCore.stopText(n) else HandoffCore.stillWaitingText(n)
    }

    /** the pool's 75 s guard must not call a tab "frozen" while its user is busy on it */
    fun exempt(sid: String?, tabId: Int): Boolean = sid != null && (forTab(sid, tabId) != null ||
        _list.value.any { it.sessionId == sid && it.open && waiters.containsKey(it.id) })

    /** user-control lock: a page-changing agent action on a tab the user is working on */
    fun busyFor(sid: String?, tabId: Int, action: String): String? {
        if (sid == null || !HandoffFlags.on(HandoffFlags.TAB)) return null
        val h = forTab(sid, tabId) ?: return null
        return if (HandoffCore.blocks(h, action)) HandoffCore.busyText(h) else null
    }

    fun takeActionNote(m: BrowserUseManager): String? = actionNotes.remove(System.identityHashCode(m))

    // ── Google / Apple (F8) ───────────────────────────────────────────
    /**
     * The agent's page tried to go to Google / Apple sign-in. Returns true when it was turned into a handoff (nothing
     * opened: the user starts Chrome from the popup), false to keep the old behaviour (open the Custom Tab).
     */
    fun oauthIntercept(m: BrowserUseManager, target: String): Boolean {
        if (!HandoffFlags.on(HandoffFlags.OAUTH) || !resumeOn()) return false
        val sid = m.poolRef?.sessionKey ?: return false
        val site = HandoffCore.site(m.currentURL.value).ifBlank { HandoffCore.site(target) }
        val key = "$sid|$site"
        val prior = oauthAsked[key]
        val existing = _list.value.firstOrNull { it.sessionId == sid && it.kind == Kind.OAUTH_EXTERNAL && it.open }
        val h = when {
            existing != null -> existing
            HandoffCore.shouldAskOauth(prior, now()) -> open(m, Kind.OAUTH_EXTERNAL, detail = target)?.also { oauthAsked[key] = now() }
            else -> null
        }
        actionNotes[System.identityHashCode(m)] = if (h != null) HandoffCore.pendingText(h, verb(h)) + " " + BrowserHandoffCore.CUSTOM_TAB_NOTE
            else "[登录] 刚才已经问过用户是否在 Chrome 里用 ${if (target.contains("apple.com")) "Apple" else "Google"} 登录，10 分钟内不再问。" +
                BrowserHandoffCore.CUSTOM_TAB_NOTE + " 不要再点这个按钮。"
        return true
    }

    /** popup 「在 Chrome 里打开」 (an Activity context) */
    fun openChrome(activity: Context, id: String) {
        val h = get(id) ?: return
        update(id) { HandoffCore.move(it, State.USER_ACTIVE, now()) ?: it }
        chromeOpenedAt[id] = now()
        com.openminis.app.browser.GoogleAuthRouter.openInCustomTab(activity, h.detail.ifBlank { h.url })
    }

    /** Mines is in front again: a Google / Apple handoff the user took to Chrome is over */
    fun onAppResumed() {
        _list.value.filter { it.kind == Kind.OAUTH_EXTERNAL && it.state == State.USER_ACTIVE }.forEach { h ->
            val at = chromeOpenedAt[h.id] ?: return@forEach
            if (now() - at < 1500L) return@forEach
            chromeOpenedAt.remove(h.id)
            scope.launch { complete(h.id, extra = HandoffCore.OAUTH_BACK_NOTE) }
        }
    }

    // ── user actions ──────────────────────────────────────────────────
    /** 「去那个页面」: that conversation's browser, on the recorded tab (a new tab only when it is gone) */
    fun goTo(id: String) {
        val h = get(id) ?: return
        val pool = pool(h)
        if (pool != null) {
            if (HandoffFlags.on(HandoffFlags.TAB) && pool.tabs.value.any { it.id == h.tabId }) pool.selectTab(h.tabId)
            else pool.selectOrCreateTabForURL(h.url)?.let { t -> update(id) { it.copy(tabId = t.id) } }
        }
        update(id) { if (it.state == State.WAITING) HandoffCore.move(it, State.USER_ACTIVE, now()) else it }
        showConversation(h.sessionId, id)
    }

    private fun showConversation(sid: String, id: String) {
        val vm = com.openminis.app.ui.chat.ChatViewModelStore.existing(sid)
        if (vm != null) vm._showBrowserSheet.value = true else pendingFocus.value = id
        val active = com.openminis.app.ui.chat.ChatViewModelStore.activeSessionId
        val here = active != null && active == com.openminis.app.ui.chat.ChatViewModelStore.resolvePersistedId(sid)
        if (!here) {
            val route = com.openminis.app.ui.navigation.Routes.chat(sid)
            com.openminis.app.ui.plus.PlusNav.navigate?.invoke(route) ?: run { com.openminis.app.ui.plus.PlusNav.pending = route }
        }
    }

    /** a notification deep link minis://session/<sid>?handoff=<id> was opened (MainActivity) */
    fun onDeepLink(uri: Uri?) {
        val id = runCatching { uri?.getQueryParameter("handoff") }.getOrNull()?.ifBlank { null } ?: return
        val h = get(id) ?: return
        val pool = pool(h)
        if (pool != null && pool.tabs.value.any { it.id == h.tabId }) pool.selectTab(h.tabId)
        val vm = com.openminis.app.ui.chat.ChatViewModelStore.existing(h.sessionId)
        if (vm != null) vm._showBrowserSheet.value = true else pendingFocus.value = id
    }

    /** HandoffHost: the conversation screen of a pending focus exists now → open its browser on the tab */
    fun applyPendingFocus(): Boolean {
        val id = pendingFocus.value ?: return true
        val h = get(id) ?: run { pendingFocus.value = null; return true }
        val vm = com.openminis.app.ui.chat.ChatViewModelStore.existing(h.sessionId) ?: return false
        pool(h)?.takeIf { p -> p.tabs.value.any { it.id == h.tabId } }?.selectTab(h.tabId)
        vm._showBrowserSheet.value = true
        pendingFocus.value = null
        return true
    }

    /** 「继续接管」: the selected tab becomes the agent's, skip auto-detection (F4 still reported) */
    fun takeOver(id: String) { scope.launch { complete(id, manual = true) } }

    /** the browser sheet of [sid] was closed (ChatViewModel.dismissBrowserSheet) */
    fun onSheetClosed(sid: String?) {
        if (sid == null || !resumeOn()) return
        val open = _list.value.filter { it.sessionId == sid && it.open }
        open.forEach { h ->
            kick(h.id)
            if (h.kind == Kind.LOGIN) scope.launch {
                val m = manager(h, h.tabId) ?: return@launch
                val p = probe(m) ?: return@launch
                if (loggedInNow(h, p)) complete(h.id, verified = true)
                else update(h.id) { it.copy(hint = "好像还没登录成功，请再看一下", showSeq = it.showSeq + 1) }
            }
        }
        if (open.isEmpty()) scope.launch { manualLoginDone(sid) }
    }

    /** the agent's page was a sign-in page (no card: passkey / 2FA / the agent asked in chat) */
    fun noteAuthPage(m: BrowserUseManager, pw: Boolean) {
        val pool = m.poolRef ?: return
        val sid = pool.sessionKey ?: return
        val url = m.currentURL.value
        lastAuthPage[sid] = AuthPage(WeakReference(pool), m.tabId, url, pw, cookieNames(sid, url), now())
    }

    /** the user signed in by hand in the sheet after the agent's turn ended on a sign-in page → wake the agent */
    private suspend fun manualLoginDone(sid: String) {
        val a = lastAuthPage[sid]?.takeIf { now() - it.at < 30 * 60_000L } ?: return
        val pool = a.pool.get() ?: return
        val vm = com.openminis.app.ui.chat.ChatViewModelStore.existing(sid) ?: return
        if (vm.isStreaming.value) return
        val sel = pool.selectedTabId.value
        val t = pool.tabs.value.firstOrNull { it.id == sel } ?: return
        if (HandoffCore.site(t.manager.currentURL.value) != HandoffCore.site(a.url)) return
        val p = probe(t.manager) ?: return
        val ok = HandoffCore.loggedIn(HandoffCore.signals(a.pw, HandoffCore.isAuthUrl(a.url), a.cookies, p.pw, p.url,
            cookieNames(sid, p.url), p.words))
        if (!ok) return
        lastAuthPage.remove(sid)
        val h = open(t.manager, Kind.LOGIN, notifyUser = false) ?: return
        update(h.id) { it.copy(tabId = a.tabId.takeIf { id -> pool.tabs.value.any { it.id == id } } ?: t.id, url = a.url) }
        complete(h.id, verified = true)
    }

    // ── notifications (F7) ────────────────────────────────────────────
    private fun notify(h: Handoff) {
        val c = app ?: return
        if (h.kind == Kind.LOGIN && (c as? com.openminis.app.MinisApp)?.isAppForeground() == true) return   // the card is on screen
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(c,
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val nm = c.getSystemService(NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "需要你操作一下", NotificationManager.IMPORTANCE_HIGH))
            val labelled = HandoffFlags.on(HandoffFlags.LABEL)
            val site = HandoffCore.site(h.host).ifBlank { "网页" }
            val title = if (labelled) HandoffLabel.title(h.taskLabel.ifBlank { HandoffLabel.siteWord(h.host) }, verb(h))
                else if (h.kind == Kind.LOGIN) "需要你登录 $site" else "需要你点一下验证"
            val body = if (labelled) HandoffLabel.body(h.host) else "$site · Mines 在等你，完成后会自动继续"
            val intent = if (labelled) Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/${h.sessionId}?handoff=${h.id}")).apply {
                setPackage(c.packageName); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } else c.packageManager.getLaunchIntentForPackage(c.packageName)
            val pi = intent?.let { PendingIntent.getActivity(c, NOTIF_BASE + (h.id.hashCode() and 0xfff), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title).setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(h.kind != Kind.CAPTCHA)
                // [Mines-38.9.15] a human check stays in the shade until the handoff ends (cancelNotif), not swiped away by a tap
                .setOngoing(h.kind == Kind.CAPTCHA).setCategory(NotificationCompat.CATEGORY_REMINDER)
                .apply { if (pi != null) { setContentIntent(pi); if (labelled) addAction(0, "去那个页面", pi) } }
                .build()
            nm.notify(NOTIF_BASE + (h.id.hashCode() and 0xfff), n)
            MinesCall.onNeedsAttention(c, title, body, if (labelled) h.sessionId else null)
        }
    }

    private fun cancelNotif(h: Handoff) {
        val c = app ?: return
        runCatching { androidx.core.app.NotificationManagerCompat.from(c).cancel(NOTIF_BASE + (h.id.hashCode() and 0xfff)) }
    }

    // ── system prompt (ChatViewModel) ─────────────────────────────────
    fun promptLine(c: Context, legacyGoogle: String): String {
        init(c)
        if (!HandoffFlags.on(HandoffFlags.MASTER)) return legacyGoogle
        val sb = StringBuilder()
        if (resumeOn()) sb.append("人机验证 / 安全登录框 / 验证码交给用户时，浏览器结果里会有 [交接中 handoff_id=…]：不要重试点击，调用 browser 的 " +
            "wait_for_handoff（text 填 handoff_id）等待，用户一完成就返回 [交接完成]，按里面的 tab_id 继续；如果本轮已经结束，用户完成后 Mines 会" +
            "自动发一条 [交接完成] 消息叫你继续。")
        if (HandoffFlags.on(HandoffFlags.OAUTH)) sb.append("注册新账号优先用邮箱或手机号，不要点「用 Google / Apple 登录」：Google / Apple 不允许在 App 内" +
            "浏览器登录，那边的登录只留在 Chrome、回不到 App 浏览器；页面只有第三方登录时停下来告诉用户。点到 Google / Apple 时 Mines 会问用户" +
            "是否在 Chrome 里打开，按结果里给的选项继续，不要重试。")
        else sb.append(legacyGoogle)
        return sb.toString()
    }
}
