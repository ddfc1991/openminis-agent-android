package com.openminis.app.plus

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * [Mines-login-32] One place that sets up every login / key WebView.
 *
 * build-31 on the user's realme: passport.aliyun.com rendered all white and
 * supabase.com all black, leaving only the 「我已登录好了」 button. Causes that
 * an embedded WebView has and Chrome does not, all handled here:
 *  - the UA: the real WebView UA carries "; wv" and "Version/4.0" (many login
 *    pages refuse embedded WebViews). We build a plain mobile-Chrome UA from the
 *    device's own WebView version, so it matches the Sec-CH-UA client hints
 *    (a hard-coded Chrome version that disagrees with the hints is itself a bot
 *    signal);
 *  - the `X-Requested-With: <package>` header WebView adds to every request
 *    (switched off where the WebView supports it);
 *  - algorithmic darkening (dark theme) — can paint a dark page black;
 *  - JS / DOM storage / database / third-party cookies all on;
 *  - an opaque white background, so a page that is still loading never shows
 *    as a black hole on the dark theme.
 * If a page still shows nothing after ~8 s, [blankJs] says so and the caller
 * moves that provider to a Chrome Custom Tab ([GoogleAuthRouter]).
 */
object LoginWeb {
    @Volatile private var ua: String? = null

    /** Mobile Chrome UA without the WebView markers, from this phone's WebView. */
    fun userAgent(context: Context): String {
        ua?.let { return it }
        val real = runCatching { WebSettings.getDefaultUserAgent(context.applicationContext) }.getOrNull().orEmpty()
        val chrome = Regex("""Chrome/([0-9.]+)""").find(real)?.groupValues?.get(1)
        val android = Regex("""Android ([0-9.]+)""").find(real)?.groupValues?.get(1) ?: android.os.Build.VERSION.RELEASE
        val out = if (chrome != null) {
            // Chrome itself reports a reduced "Linux; Android 10; K" UA since v110.
            "Mozilla/5.0 (Linux; Android $android; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chrome.substringBefore('.')}.0.0.0 Mobile Safari/537.36"
        } else MINES_MOBILE_UA
        ua = out
        return out
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    /**
     * [Mines-accounts-35.1] 多账号隔离 (user 2026-10-14): the 2nd, 3rd … account of one service (id "base#n") gets its
     * own WebView profile (androidx.webkit MULTI_PROFILE: own cookies, localStorage, cache), so signing in, 一键接入,
     * 一键修复 and registration for that account never touch the first account's session. The first account keeps
     * the default profile (existing logins stay). Returns false when the phone's WebView is too old for profiles.
     */
    fun profileName(accountId: String?): String? =
        accountId?.takeIf { it.contains('#') }?.let { "mines_acct_" + it.replace(Regex("[^A-Za-z0-9_]"), "_") }

    fun profilesSupported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    private fun useProfile(wv: WebView, accountId: String?): Boolean {
        val name = profileName(accountId) ?: return true
        if (!profilesSupported()) return false
        return runCatching {
            androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(name)
            androidx.webkit.WebViewCompat.setProfile(wv, name)
            true
        }.getOrDefault(false)
    }

    /** The cookie store an account's WebView uses (its own profile for "base#n"). */
    fun cookies(accountId: String?): CookieManager {
        val name = profileName(accountId)
        if (name != null && profilesSupported()) runCatching {
            return androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(name).cookieManager
        }
        return CookieManager.getInstance()
    }

    /** True when [accountId] would share the first account's session because profiles are unsupported. */
    @Volatile var lastShared = false; private set

    fun configure(wv: WebView, accountId: String? = null) {
        // must happen before anything is loaded into the WebView
        lastShared = !useProfile(wv, accountId)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.mediaPlaybackRequiresUserGesture = true
        s.userAgentString = userAgent(wv.context)
        // [Mines-38.9.11 b] client hints that match the Chrome UA (WebView otherwise sends brand "Android WebView", which
        // Cloudflare / Google score as an embedded browser: grok.com / chatgpt.com challenge loops, user 2026-10-20 08:32)
        applyChromeHints(wv)
        // [Mines-zoom-34] login pages are zoomable too (pinch / double-tap, unlocked viewport)
        // [Mines-webfit-38.3] same fit-to-screen settings as every in-app WebView (UA above is mobile)
        com.openminis.app.browser.WebFit.apply(wv)
        wv.setBackgroundColor(android.graphics.Color.WHITE)
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false)
        }
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)
    }

    /** Sec-CH-UA brands = Chrome (same major version as [userAgent]); no-op on WebViews without USER_AGENT_METADATA. */
    @SuppressLint("RequiresFeature")
    fun applyChromeHints(wv: WebView) {
        runCatching {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
            val real = runCatching { WebSettings.getDefaultUserAgent(wv.context.applicationContext) }.getOrNull().orEmpty()
            val full = WebHintsCore.chromeFullVersion(real) ?: return
            val brands = WebHintsCore.brands(full).map { (b, major, f) ->
                androidx.webkit.UserAgentMetadata.BrandVersion.Builder().setBrand(b).setMajorVersion(major).setFullVersion(f).build()
            }
            val md = androidx.webkit.UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(full)
                .setPlatform("Android")
                .setPlatformVersion(android.os.Build.VERSION.RELEASE ?: "")
                .setModel(android.os.Build.MODEL ?: "")
                .setMobile(true)
                .build()
            WebSettingsCompat.setUserAgentMetadata(wv.settings, md)
        }
    }

    /** Persist cookies to disk now (after every page and when the app pauses). */
    fun flush() {
        runCatching { CookieManager.getInstance().flush() }
        runCatching { SessionKeeper.snapshotQuiet() }   // [Mines-38.9.3] keep sign-ins across restarts / updates
        runCatching { if (profilesSupported()) androidx.webkit.ProfileStore.getInstance().allProfileNames.filter { it.startsWith("mines_acct_") }
            .forEach { androidx.webkit.ProfileStore.getInstance().getProfile(it)?.cookieManager?.flush() } }
    }

    /**
     * JS returning "blank" when the page shows nothing a person could use: no
     * visible text and almost no inputs / buttons / images. Pages built in
     * canvas or inside cross-origin iframes count as not blank.
     */
    // [Mines-web-35.1] counts only what a person can actually SEE: text / controls inside the viewport whose
    // element chain is displayed, not visibility:hidden and not faded to opacity ~0. siliconflow's Next.js
    // login (user 2026-10-14) stayed pure white while its DOM was full of inputs, so the old count said "ok".
    const val BLANK_JS = """(function(){try{var b=document.body;if(!b)return 'blank';var W=innerWidth,H=innerHeight;
function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2||r.bottom<0||r.right<0||r.top>H||r.left>W)return false;
var o=1;for(var p=e;p&&p.nodeType==1;p=p.parentElement){var s=getComputedStyle(p);if(s.display=='none'||s.visibility=='hidden')return false;
o*=parseFloat(s.opacity||'1');if(o<0.05)return false;}return true;}
var t=0,k=0,n,w=document.createTreeWalker(b,NodeFilter.SHOW_TEXT);
while((n=w.nextNode())&&k<600){k++;var x=(n.nodeValue||'').replace(/\s+/g,'');if(x&&n.parentElement&&vis(n.parentElement)){t+=x.length;if(t>=8)break;}}
var c=0,els=document.querySelectorAll('input,button,img,canvas,svg,iframe,video');
for(var i=0;i<els.length&&i<300;i++){if(vis(els[i])){c++;if(c>=3)break;}}
return (t<8&&c<3)?'blank':'ok';}catch(e){return 'ok'}})()"""

    /** True when the session cookie a service sets after login is already in the shared WebView store. */
    fun signedIn(kind: ConnectionKind): Boolean {
        if (kind.doneCookies.isEmpty()) return false
        val urls = listOfNotNull(kind.loginUrl, kind.panelUrl) +
            listOfNotNull(kind.loginUrl, kind.panelUrl).map { "https://" + rootDomain(PlusConnect.hostOf(it)) + "/" }
        return urls.any { u ->
            // [Mines-38.7] an extra account with its own WebView profile is checked in ITS store (account 2 was 「已登录」
            // because account 1 was); without profiles 账号连接 keeps one account in the shared jar (PlusConnect swaps)
            val c = runCatching { cookies(kind.id).getCookie(u) }.getOrNull().orEmpty()
            MailParse.cookieHas(c, kind.doneCookies) // [Mines-sources-33] exact cookie names ("SID" is not "HSID")
        }
    }

    private fun rootDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size <= 2) host else parts.takeLast(if (parts[parts.size - 2].length <= 3 && parts.last().length <= 2) 3 else 2).joinToString(".")
    }

    // ---- per-provider session record (kept in the vault extras) ----

    fun markSignedIn(kindId: String, account: String?) {
        PlusVault.setExtra(kindId, "session", "1")
        PlusVault.setExtra(kindId, "session_verified", System.currentTimeMillis().toString())
        account?.takeIf { it.isNotBlank() }?.let { PlusVault.setExtra(kindId, "session_account", it) }
    }

    fun markSignedOut(kindId: String) { PlusVault.setExtra(kindId, "session", "0") }

    fun lastVerified(kindId: String): Long = PlusVault.extra(kindId, "session_verified")?.toLongOrNull() ?: 0L
}
