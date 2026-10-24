package com.openminis.app.plus

import android.content.Context
import android.webkit.CookieManager

/**
 * [Mines-38.9.11 b] 无痕-style isolation for the in-app browser (user 2026-10-20 08:47 「只要做好隔离应该挺好的呀」):
 *  - every 2nd+ account of a site already signs in inside its own WebView profile (LoginWeb.profileName, "base#n");
 *  - Google accounts for Gemini each get their own profile (GeminiAccounts.webProfile);
 *  - passwords are filled only from 账号关联 / 保险箱 (SavedLogins / PlusVault), never typed by the agent;
 *  - this file adds the wipe: 清除这个网站的网页数据 drops the site's cookies from the shared store and from the account's
 *    own profile, and forgets the kept session snapshot so it is not restored on the next start.
 */
object WebIsolation {
    /** Clears [host]'s web data; returns a one-line Chinese result for the page. */
    fun wipeSite(c: Context, host: String, accountId: String? = null): String {
        val root = Keychain.root(host).ifBlank { host }.removePrefix("www.")
        if (root.isBlank()) return "没有网站地址，不能清除"
        var n = 0
        val stores = buildList {
            add(CookieManager.getInstance())
            if (accountId != null) runCatching { add(LoginWeb.cookies(accountId)) }
        }.distinct()
        for (cm in stores) for (u in listOf("https://$root/", "https://www.$root/", "https://$host/")) {
            val names = WebIsolationCore.cookieNames(runCatching { cm.getCookie(u) }.getOrNull())
            for (name in names) for (line in WebIsolationCore.expireLines(name, root, host)) { runCatching { cm.setCookie(u, line) }; n++ }
        }
        stores.forEach { runCatching { it.flush() } }
        // the account's own profile: storage + cache go too
        LoginWeb.profileName(accountId)?.let { name ->
            runCatching { if (LoginWeb.profilesSupported()) androidx.webkit.ProfileStore.getInstance().getProfile(name)?.webStorage?.deleteAllData() }
        }
        val snaps = runCatching { SessionKeeper.forgetSite(c, root) }.getOrDefault(0)
        return if (n == 0 && snaps == 0) "$root 没有存着的网页数据" else "已清除 $root 的网页数据（下次打开要重新登录，保存的密码还在）"
    }
}

/** Pure part (unit-tested in WebHintsCoreTest). */
object WebIsolationCore {
    fun cookieNames(header: String?): List<String> = header.orEmpty().split(';').map { it.trim() }
        .filter { it.contains('=') }.map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()

    /** Set-Cookie lines that expire [name] on the host, the bare root and the dotted root (cookies may sit on any of them). */
    fun expireLines(name: String, root: String, host: String): List<String> {
        val base = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
        return listOf(base, "$base; Domain=.$root", "$base; Domain=$root", "$base; Domain=$host").distinct()
    }
}
