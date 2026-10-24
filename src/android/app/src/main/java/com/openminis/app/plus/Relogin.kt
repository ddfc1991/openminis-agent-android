package com.openminis.app.plus

import android.content.Context
import com.openminis.app.ui.plus.LinkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [Mines-38.9.3] user 2026-10-17 「动不动就账号失效登录」 → before a 「要重新登录」 badge is shown the sign-in is checked
 * again for real ([recheck]: a web AI account opens its page in the hidden WebView and is classified; a 账号连接 web
 * login first gets its kept cookies back, then its session cookie decides). 「一键重新登录」 ([silent]) opens the site's
 * sign-in with 一键关联's automatic mode, which fills the saved password / the recorded identity and presses 登录 itself —
 * the user is not asked again (only a captcha / QR code still needs them).
 */
object Relogin {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastCheck = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val _checking = MutableStateFlow<Set<String>>(emptySet())
    /** sites (registrable domains) being re-checked right now (rows say 「正在确认…」) */
    val checking: StateFlow<Set<String>> = _checking

    /** checked for real in the last 10 min (a 「要重新登录」 badge may show) */
    fun verified(site: String): Boolean {
        val pid = runCatching { LinkAll.platformFor(site) }.getOrNull() ?: return true   // nothing to re-check with
        return !SessionStatus.needsRecheck(lastCheck[pid] ?: 0L, System.currentTimeMillis())
    }

    /** Re-check [site] in the background unless it was checked in the last 10 min ([force] = the user asked). */
    fun recheckAsync(c: Context, site: String, force: Boolean = false) {
        val app = c.applicationContext
        scope.launch { runCatching { recheck(app, site, force) } }
    }

    /** true = signed in, false = confirmed signed out, null = could not tell (no platform, blip, too soon). */
    suspend fun recheck(c: Context, site: String, force: Boolean = false): Boolean? {
        val pid = runCatching { LinkAll.platformFor(site) }.getOrNull() ?: return null
        val now = System.currentTimeMillis()
        if (!force && !SessionStatus.needsRecheck(lastCheck[pid] ?: 0L, now)) return null
        lastCheck[pid] = now
        _checking.value = _checking.value + site
        try {
            val k = LinkAll.connKind(pid)
            val r: Boolean? = if (k != null) {
                withContext(Dispatchers.Main) { runCatching { PlusConnect.restoreCookies(k) } }
                if (LoginWeb.signedIn(k)) true else null    // no cookie is not proof of a sign-out
            } else {
                val spec = com.openminis.app.plus.group.AiCatalog.spec(pid) ?: return null
                val chk = withContext(Dispatchers.Main) { com.openminis.app.plus.group.WebChat.check(c, spec, pid, 20_000) }
                when (chk.state) {
                    com.openminis.app.plus.group.WebChat.PageState.SIGNED_IN -> true
                    com.openminis.app.plus.group.WebChat.PageState.LOGIN, com.openminis.app.plus.group.WebChat.PageState.GUEST ->
                        if (com.openminis.app.plus.group.WebChat.signedIn(spec, pid)) true
                        else if (com.openminis.app.plus.group.GroupPrefs.get("weblogin_$pid") == "0") false else null
                    else -> null
                }
            }
            runCatching { IdentityStore.sync(c, force = true) }
            if (r == true) runCatching { IdentityStore.recFor(c, site)?.let { IdentityStore.setStatus(c, it.key, IdentityCore.LINKED) } }
            // [Mines-38.9.5 i] a CONFIRMED sign-out with a one-tap way in → sign in again by itself (hidden, ≤ once / 6 h)
            if (r == false) runCatching { AccountsTool.autoAfterSignOut(c, site) }
            runCatching { Keychain.rev.value++ }
            return r
        } finally { _checking.value = _checking.value - site }
    }

    /** 「一键重新登录」: the platform's sign-in page in automatic mode (saved password / recorded identity, no questions). */
    fun silent(site: String, go: (String) -> Unit): Boolean {
        val pid = runCatching { LinkAll.platformFor(site) }.getOrNull() ?: return false
        LinkAll.autoLogin = true
        LinkAll.open(pid, go, auto = true)
        // automatic mode is for this one sign-in only (一键关联 runs set it themselves)
        scope.launch { delay(180_000); withContext(Dispatchers.Main) { if (!LinkAll.running) LinkAll.autoLogin = false } }
        return true
    }
}
