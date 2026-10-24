package com.openminis.app.plus

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * [Mines-38.9.1] user 2026-10-16 「很多网站登录很慢」: connect / sign-in WebViews skip third-party trackers, ad beacons and
 * floating chat widgets (not the site's own scripts): they are the slowest requests on CN consoles behind a VPN and some
 * (火山's 在线咨询) cover the page. Only exact third-party hosts are blocked; the page's own domain is never touched.
 */
object WebBlock {
    private val HOSTS = listOf(
        "google-analytics.com", "googletagmanager.com", "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "hm.baidu.com", "cnzz.com", "umeng.com", "umengcloud.com", "growingio.com", "sensorsdata.cn", "mixpanel.com", "segment.io",
        "segment.com", "hotjar.com", "clarity.ms", "fullstory.com", "amplitude.com", "heap.io", "facebook.net", "connect.facebook.net",
        "intercom.io", "intercomcdn.com", "crisp.chat", "zdassets.com", "zendesk.com", "tawk.to", "drift.com", "livechatinc.com",
        "53kf.com", "udesk.cn", "meiqia.com", "sobot.com", "bytegoofy.com/goofy/ark/chat", "mcs.zijieapi.com", "tiktokv.com/collect",
        "arms-retcode.aliyuncs.com", "retcode.alicdn.com", "log.mmstat.com", "g.alicdn.com/alilog", "px.ads.linkedin.com", "bat.bing.com",
    )
    @Volatile var blocked = 0; private set

    fun shouldBlock(url: String, pageHost: String): Boolean {
        val u = url.lowercase()
        val host = runCatching { android.net.Uri.parse(u).host.orEmpty() }.getOrDefault("")
        if (host.isBlank() || Keychain.root(host) == Keychain.root(pageHost)) return false
        val hp = host + runCatching { android.net.Uri.parse(u).path.orEmpty() }.getOrDefault("")
        return HOSTS.any { h -> if (h.contains('/')) hp.startsWith(h) || hp.contains(".$h") else host == h || host.endsWith(".$h") }
    }

    /** For WebViewClient.shouldInterceptRequest: an empty 204 for a blocked request, else null (load normally). */
    fun intercept(req: WebResourceRequest?, pageUrl: String?): WebResourceResponse? {
        val u = req?.url?.toString() ?: return null
        if (req.isForMainFrame) return null
        val ph = runCatching { android.net.Uri.parse(pageUrl ?: "").host.orEmpty() }.getOrDefault("")
        if (!shouldBlock(u, ph)) return null
        blocked++
        return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
    }
}
