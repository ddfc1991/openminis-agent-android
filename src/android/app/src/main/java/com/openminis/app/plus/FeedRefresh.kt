package com.openminis.app.plus

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [Mines-38.9.2] 消息 pull-to-refresh, the parts that need no Android (unit-tested in FeedRefreshTest).
 *
 * User report on 38.9.1 (2026-10-17): 「消息没做好，下拉不更新」. Root causes in 38.9.1:
 *  1. Digest.refresh() returned at once when a refresh was already running (`if (busy) return`). Opening the page
 *     always starts one and it stayed "busy" through link previews + model headlines, so a pull in that window did nothing.
 *  2. When it did run, every source came from a cache: mail 5 min, X 10 min, 微博/B站/知乎 10 min.
 *  3. The spinner was a fake ≤4 s timer, not tied to the read; failures were swallowed (IMAP login refused → empty list).
 *  4. Coming back to the page (ON_RESUME) did not refresh; 邮箱 tab had the same "busy → skip" bug.
 * Now: a pull waits for a running refresh and then reads every source again with no cache ([Gate], [Mode.PULL]),
 * the spinner lasts exactly as long as that read, new cards go to the top, and one short Chinese line says what happened.
 */
object FeedRefresh {
    /** BACKGROUND = patrol / home card (skips when one is running, caches allowed); OPEN = page opened or resumed;
     *  PULL = the user pulled down (waits for a running one, then no cache at all). */
    enum class Mode { BACKGROUND, OPEN, PULL }

    /** How old a source's cached read may be for this refresh. */
    fun maxAgeMs(mode: Mode, background: Long): Long = when (mode) {
        Mode.PULL -> 0L
        Mode.OPEN -> minOf(background, 60_000L)
        Mode.BACKGROUND -> background
    }

    /** What one refresh found: ids of cards that were not there before, and one plain line per source that failed. */
    data class Outcome(val fresh: List<String>, val fails: List<String>)

    /** Cards that are new since [before]. On the very first load (nothing shown yet) nothing counts as new unless [countAll]. */
    fun freshIds(before: Set<String>, after: List<String>, countAll: Boolean): List<String> =
        if (before.isEmpty() && !countAll) emptyList() else after.filter { it !in before }.distinct()

    /** The short line under the tabs after a pull: 「新到 3 条」 / 「没有新消息」 / + 「163 授权码失效」 per failed source. */
    fun line(o: Outcome?): String {
        if (o == null) return "没有新消息"
        val head = if (o.fresh.isNotEmpty()) "新到 ${o.fresh.size} 条" else "没有新消息"
        val f = o.fails.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (f.isEmpty()) return head
        val shown = f.take(2).joinToString("；") + (if (f.size > 2) " 等 ${f.size} 处没读到" else "")
        return "$head · $shown"
    }

    /** Pinned cards first, then the cards that just arrived (in their score order), then the rest (stable). */
    fun <T> freshFirst(list: List<T>, fresh: Set<String>, id: (T) -> String, pinned: (T) -> Boolean): List<T> {
        if (fresh.isEmpty()) return list
        val (p, rest) = list.partition(pinned)
        val (n, old) = rest.partition { id(it) in fresh }
        return p + n + old
    }

    /** The mailbox as the user knows it: 「163」「QQ 邮箱」「Gmail」…, with the start of the address when they have several there. */
    fun mailLabel(address: String, many: Boolean = false): String {
        val d = address.substringAfter('@', "").lowercase()
        val p = when {
            d == "gmail.com" || d == "googlemail.com" -> "Gmail"
            d == "163.com" || d == "vip.163.com" -> "163"
            d == "126.com" -> "126"
            d == "yeah.net" -> "yeah"
            d == "qq.com" || d == "foxmail.com" || d == "vip.qq.com" -> "QQ 邮箱"
            d.contains("outlook") || d.contains("hotmail") || d == "live.com" -> "Outlook"
            d == "sina.com" || d == "sina.cn" -> "新浪邮箱"
            d.endsWith("aliyun.com") -> "阿里邮箱"
            else -> d.substringBefore('.').ifBlank { "邮箱" }
        }
        return if (many) "$p ${address.substringBefore('@').take(4)}…" else p
    }

    /** The mail server refused LOGIN (wrong / expired 授权码 or app password). */
    class LoginRejected(val server: String) : Exception(server)
    /** A failure that already carries its plain line (e.g. 「Gmail 网页登录过期」). */
    class SourceFail(val line: String) : Exception(line)

    /** One plain line for a mailbox that could not be read. */
    fun mailFail(label: String, t: Throwable?): String = when (t) {
        null -> "$label 连接超时"
        is SourceFail -> t.line
        is LoginRejected -> when {
            label.startsWith("Gmail") -> "$label 应用专用密码失效"
            t.server.lowercase().contains("unsafe login") -> "$label 拦了登录（Unsafe Login）"
            else -> "$label 授权码失效"
        }
        else -> netFail(label, t)
    }

    /** Network-ish failures of any source, in plain words. */
    fun netFail(label: String, t: Throwable): String {
        var e: Throwable? = t
        while (e != null) {
            when (e) {
                is SourceFail -> return e.line
                is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> return "$label 连接超时"
                is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> return "$label 网络不通"
                is javax.net.ssl.SSLException -> return "$label 加密连接失败"
            }
            e = e.cause
        }
        return "$label 暂时读不到"
    }

    /**
     * Only one refresh at a time. A background caller skips when one is running (returns null); a pull or a page
     * open waits for the running one to end and then runs its own read — so it never silently does nothing.
     */
    class Gate {
        private val mutex = Mutex()
        val running: Boolean get() = mutex.isLocked
        suspend fun <T> run(waitIfBusy: Boolean, block: suspend () -> T): T? {
            if (!waitIfBusy && mutex.isLocked) return null
            return mutex.withLock { block() }
        }
    }
}
