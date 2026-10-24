package com.openminis.app.plus

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * [Mines-38.9.5 i] real web-session check (see [WebSessionCore]): flush the WebView cookies, send ONE authenticated request
 * with them, and publish the result per service so the detail hero, the 网站登录 row and 「我已登录好了」 agree.
 */
object WebSession {
    private val _state = MutableStateFlow<Map<String, WebSessionCore.St>>(emptyMap())
    val state: StateFlow<Map<String, WebSessionCore.St>> = _state

    fun of(kindId: String): WebSessionCore.St = _state.value[kindId] ?: WebSessionCore.St.UNCHECKED

    private fun put(kindId: String, st: WebSessionCore.St) { _state.value = _state.value + (kindId to st) }

    /** Check now; updates [state], the vault session record and the identity row. Never throws. */
    suspend fun verify(c: Context, kind: ConnectionKind): WebSessionCore.St {
        val prev = of(kind.id)
        put(kind.id, WebSessionCore.St.CHECKING)
        val res = withContext(Dispatchers.IO) {
            runCatching {
                LoginWeb.flush()
                val probe = WebSessionCore.probeFor(kind.id, kind.loginUrl, kind.panelUrl, kind.site) ?: return@runCatching WebSessionCore.St.UNCHECKED
                val cm = LoginWeb.cookies(kind.id)
                val cookie = listOf(probe.url, kind.loginUrl, kind.panelUrl, kind.site).filterNotNull()
                    .mapNotNull { u -> runCatching { cm.getCookie(u) }.getOrNull() }.firstOrNull { it.isNotBlank() }.orEmpty()
                val hasCookie = cookie.isNotBlank() && (kind.doneCookies.isEmpty() || MailParse.cookieHas(cookie, kind.doneCookies))
                if (!hasCookie) return@runCatching WebSessionCore.St.SIGNED_OUT
                val h = URL(probe.url).openConnection() as HttpURLConnection
                h.instanceFollowRedirects = false
                h.connectTimeout = 8000; h.readTimeout = 10000
                h.requestMethod = probe.method
                h.setRequestProperty("Cookie", cookie)
                h.setRequestProperty("User-Agent", android.webkit.WebSettings.getDefaultUserAgent(c))
                h.setRequestProperty("Accept", "application/json, text/html;q=0.9")
                if (probe.body != null) {
                    h.doOutput = true; h.setRequestProperty("Content-Type", "application/json")
                    h.outputStream.use { it.write(probe.body.toByteArray()) }
                }
                val code = h.responseCode
                val body = runCatching { (if (code < 400) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText().take(4000) } }.getOrNull().orEmpty()
                WebSessionCore.judge(probe, code, body, h.getHeaderField("Location"), true)
            }.getOrElse { WebSessionCore.St.ERROR }
        }
        val st = WebSessionCore.next(prev, res)
        put(kind.id, st)
        runCatching {
            when (st) {
                WebSessionCore.St.SIGNED_IN -> LoginWeb.markSignedIn(kind.id, null)
                WebSessionCore.St.SIGNED_OUT -> LoginWeb.markSignedOut(kind.id)
                else -> {}
            }
            val host = Keychain.hostOf(kind.loginUrl ?: kind.site ?: kind.panelUrl)
            IdentityStore.recFor(c, host)?.let { r ->
                when (st) {
                    WebSessionCore.St.SIGNED_IN -> IdentityStore.setStatus(c, r.key, IdentityCore.LINKED)
                    WebSessionCore.St.SIGNED_OUT -> IdentityStore.setStatus(c, r.key, IdentityCore.EXPIRED)
                    else -> {}
                }
            }
        }
        return st
    }
}
