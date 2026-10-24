package com.openminis.app.plus

import com.openminis.app.plus.FeedRefresh.Mode
import com.openminis.app.plus.FeedRefresh.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.2] 消息 pull-to-refresh: a pull is never dropped, never served from a cache, and says what happened. */
class FeedRefreshTest {

    /** The 38.9.1 bug: a pull while the open-refresh was still running did nothing. Now it waits, then reads again. */
    @Test fun pullWaitsForRunningRefreshThenReadsAgain() = runTest {
        val gate = FeedRefresh.Gate()
        var reads = 0
        val release = CompletableDeferred<Unit>()
        val first = async { gate.run(waitIfBusy = true) { reads++; release.await(); "open" } }
        yield()
        assertTrue(gate.running)
        // a background refresh while one runs: skipped, as before
        assertNull(gate.run(waitIfBusy = false) { reads++; "bg" })
        // the user's pull while one runs: must not be dropped
        val pull = async { gate.run(waitIfBusy = true) { reads++; "pull" } }
        yield()
        assertEquals(1, reads)
        release.complete(Unit)
        assertEquals("open", first.await())
        assertEquals("pull", pull.await())
        assertEquals(2, reads)
    }

    @Test fun pullIgnoresEveryCache() {
        assertEquals(0L, FeedRefresh.maxAgeMs(Mode.PULL, 5 * 60_000L))
        assertEquals(0L, FeedRefresh.maxAgeMs(Mode.PULL, 10 * 60_000L))
        assertEquals(60_000L, FeedRefresh.maxAgeMs(Mode.OPEN, 10 * 60_000L))
        assertEquals(5 * 60_000L, FeedRefresh.maxAgeMs(Mode.BACKGROUND, 5 * 60_000L))
    }

    @Test fun freshIds() {
        assertEquals(listOf("c"), FeedRefresh.freshIds(setOf("a", "b"), listOf("c", "a", "b"), countAll = false))
        // first load of the page: nothing is "new"
        assertEquals(emptyList<String>(), FeedRefresh.freshIds(emptySet(), listOf("a", "b"), countAll = false))
        // a pull on an empty feed: everything that appeared is new
        assertEquals(listOf("a", "b"), FeedRefresh.freshIds(emptySet(), listOf("a", "b", "a"), countAll = true))
    }

    @Test fun resultLine() {
        assertEquals("新到 3 条", FeedRefresh.line(Outcome(listOf("a", "b", "c"), emptyList())))
        assertEquals("没有新消息", FeedRefresh.line(Outcome(emptyList(), emptyList())))
        assertEquals("没有新消息", FeedRefresh.line(null))
        assertEquals("没有新消息 · 163 授权码失效", FeedRefresh.line(Outcome(emptyList(), listOf("163 授权码失效", "163 授权码失效"))))
        assertEquals("新到 1 条 · 163 授权码失效；X 读取超时 等 3 处没读到",
            FeedRefresh.line(Outcome(listOf("x"), listOf("163 授权码失效", "X 读取超时", "GitHub 令牌失效"))))
    }

    @Test fun newCardsGoToTheTopAfterPinned() {
        data class C(val id: String, val pinned: Boolean = false)
        val list = listOf(C("p", true), C("a"), C("b"), C("n1"), C("c"), C("n2"))
        val out = FeedRefresh.freshFirst(list, setOf("n1", "n2"), { it.id }, { it.pinned })
        assertEquals(listOf("p", "n1", "n2", "a", "b", "c"), out.map { it.id })
        assertEquals(list, FeedRefresh.freshFirst(list, emptySet(), { it.id }, { it.pinned }))
    }

    @Test fun mailFailureLines() {
        assertEquals("163", FeedRefresh.mailLabel("demo@163.com"))
        assertEquals("QQ 邮箱", FeedRefresh.mailLabel("demo@qq.com"))
        assertEquals("163 demo…", FeedRefresh.mailLabel("demo.one@163.com", many = true))
        assertEquals("163 授权码失效", FeedRefresh.mailFail("163", FeedRefresh.LoginRejected("m2 NO LOGIN Login error password error")))
        assertEquals("Gmail 应用专用密码失效", FeedRefresh.mailFail("Gmail", FeedRefresh.LoginRejected("NO [AUTHENTICATIONFAILED]")))
        assertEquals("163 拦了登录（Unsafe Login）", FeedRefresh.mailFail("163", FeedRefresh.LoginRejected("NO SELECT Unsafe Login. Please contact kefu@188.com")))
        assertEquals("163 连接超时", FeedRefresh.mailFail("163", null))
        assertEquals("163 连接超时", FeedRefresh.mailFail("163", java.net.SocketTimeoutException("read timed out")))
        assertEquals("QQ 邮箱 网络不通", FeedRefresh.mailFail("QQ 邮箱", java.net.UnknownHostException("imap.qq.com")))
        assertEquals("Gmail 网页登录过期", FeedRefresh.mailFail("Gmail", FeedRefresh.SourceFail("Gmail 网页登录过期")))
        assertEquals("GitHub 令牌失效", FeedRefresh.netFail("GitHub", RuntimeException("wrap", FeedRefresh.SourceFail("GitHub 令牌失效"))))
        assertEquals("GitHub 加密连接失败", FeedRefresh.netFail("GitHub", javax.net.ssl.SSLHandshakeException("bad cert")))
        assertEquals("GitHub 暂时读不到", FeedRefresh.netFail("GitHub", IllegalStateException()))
    }
}
