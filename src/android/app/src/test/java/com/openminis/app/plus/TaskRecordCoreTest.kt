package com.openminis.app.plus

import com.openminis.app.plus.ChatHomeCore.AutoTask
import com.openminis.app.plus.ChatHomeCore.Kind
import com.openminis.app.plus.ChatHomeCore.NoticeRef
import com.openminis.app.plus.ChatHomeCore.St
import com.openminis.app.plus.TaskRecordCore.Ev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.14.3] 聊天 task rows: collapsed by default, a real record per task, its own seeded conversation */
class TaskRecordCoreTest {
    private val R = TaskRecordCore
    private val fmt: (Long) -> String = { "t$it" }
    private val base = AutoTask("t1", "s1", "Zeabur 注册", "帮我注册 zeabur", St.QUEUED, createdAt = 100, updatedAt = 100)

    @Test fun recordsEveryStateChangeOnce() {
        var log = emptyList<Ev>()
        log = R.append(log, R.diff(null, base, 100))
        assertEquals("建立任务：Zeabur 注册", log.single().text)
        val run = base.copy(st = St.RUNNING, updatedAt = 110)
        log = R.append(log, R.diff(base, run, 110))
        assertNull(R.diff(run, run.copy(step = 2, total = 5, line = "第 2 步"), 120))   // progress while running is not a record line
        val err = run.copy(st = St.ERROR, line = "没完成：请求太频繁（429）")
        log = R.append(log, R.diff(run, err, 130))
        val again = err.copy(st = St.RUNNING)
        log = R.append(log, R.diff(err, again, 140))
        val done = again.copy(st = St.DONE, line = "完成 · 5/5 步")
        log = R.append(log, R.diff(again, done, 150))
        assertEquals(listOf("建立任务：Zeabur 注册", "开始运行", "出错：请求太频繁（429）", "重新开始", "完成：5/5 步"), log.map { it.text })
        assertEquals(log, R.append(log, Ev(160, St.DONE, "完成：5/5 步")))   // exact repeat dropped
        assertEquals(1, R.retries(log))
        assertEquals(2, R.retries(log, folded = 2))
    }

    @Test fun retriesDoNotCountTheLatestFailure() {
        val log = listOf(Ev(1, St.ERROR, "出错：a"), Ev(2, St.RUNNING, "重新开始"), Ev(3, St.ERROR, "出错：b"))
        assertEquals(1, R.retries(log))
    }

    @Test fun collapsedRowIsTitleStateTime() {
        val w = base.copy(st = St.WAITING, line = "等你在 warpstations.com 登录", updatedAt = 628)
        val row = R.rowLine(w, 7, fmt, open = false)
        assertEquals("Zeabur 注册", row.title); assertEquals("等你", row.state); assertEquals("t628", row.time)
        assertTrue(row.a11y.contains("已重试 7 次")); assertTrue(row.a11y.endsWith("点按展开"))
        assertTrue(R.rowLine(w, 0, fmt, open = true).a11y.endsWith("点按收起"))
        assertFalse(row.a11y.contains("warpstations"))   // the details stay folded
        assertEquals("进行中 2/5", R.rowLine(base.copy(st = St.RUNNING, step = 2, total = 5), 0, fmt, false).state)
    }

    @Test fun detailsCarryRequestStateRetriesAndRecord() {
        val e = base.copy(st = St.ERROR, line = "没完成：请求太频繁（429）", step = 1, total = 5, updatedAt = 200)
        val log = listOf(Ev(100, St.QUEUED, "建立任务：Zeabur 注册"), Ev(150, St.ERROR, "出错：请求太频繁（429）"))
        val d = R.details(e, log, 3, fmt).toMap()
        assertEquals("帮我注册 zeabur", d["原始请求"]); assertEquals("请求太频繁（429）", d["错误"])
        assertEquals("1/5 步 · 已重试 3 次", d["进度"]); assertEquals("开始 t100 · 更新 t200", d["时间"])
        assertEquals("出错：请求太频繁（429）", d["t150"])
    }

    @Test fun seedStartsWithTheOriginalMessageThenTheFullRecord() {
        val d = base.copy(st = St.DONE, line = "完成 · 2/2 步", step = 2, total = 2, updatedAt = 300)
        val log = listOf(Ev(100, St.QUEUED, "建立任务：Zeabur 注册"), Ev(200, St.ERROR, "出错：x"), Ev(300, St.DONE, "完成：完成 · 2/2 步"))
        val rows = R.seed(d, log, listOf("打开注册页" to true, "收验证码" to true), 1, fmt)
        assertEquals("user" to "帮我注册 zeabur", rows[0])
        assertEquals("assistant", rows[1].first)
        val b = rows[1].second
        listOf("任务记录 · Zeabur 注册", "状态：完成", "进度：2/2 步", "已重试 1 次", "1. ✓ 打开注册页", "时间线", "t200 出错：x").forEach { assertTrue(it, b.contains(it)) }
        // [38.9.15] the state word shows once (was 「状态：完成 — 完成 · 2/2 步」)
        assertFalse(b, b.contains("完成 — 完成")); assertFalse(b, b.contains("状态：完成 —"))
        assertNull(R.update(log, 300, fmt))
        assertNotNull(R.update(log + Ev(400, St.RUNNING, "重新开始"), 300, fmt))
    }

    @Test fun logJsonRoundTrip() {
        val m = mapOf("t1" to listOf(Ev(1, St.ERROR, "出错：a"), Ev(2, St.DONE, "完成：b")))
        assertEquals(m, R.logOf(R.logJson(m)))
        assertTrue(R.logOf("not json").isEmpty())
    }

    @Test fun oneRowPerTaskInChat() {
        val key: (String) -> String = { "id:$it" }
        val rows = listOf(NoticeRef("c", "a", Kind.CREATED, ""), NoticeRef("e", "a", Kind.ERROR, "x"), NoticeRef("d", "a", Kind.DONE, "ok"),
            NoticeRef("c2", "b", Kind.CREATED, ""))
        val f = ChatHomeCore.foldNotices(rows, key, oneRow = true)
        assertFalse(f["c"]!!.show); assertFalse(f["e"]!!.show); assertTrue(f["d"]!!.show)   // task a = its 完成 row only
        assertTrue(f["c2"]!!.show)                                                       // task b still running = its 已建任务 row
        assertTrue(ChatHomeCore.foldNotices(rows, key)["c"]!!.show)                       // other conversations unchanged
    }

    @Test fun stateWordShownOnce() {
        val d = base.copy(st = St.DONE, line = "完成 · 6/6 步", step = 6, total = 6)
        assertEquals("", R.lineOnce(d))
        assertEquals("已部署到 zeabur.app", R.lineOnce(d.copy(line = "完成 · 已部署到 zeabur.app")))
        assertEquals("请求太频繁（429）", R.lineOnce(base.copy(st = St.ERROR, line = "没完成：请求太频繁（429）")))
        assertEquals("需要手机号收短信", R.lineOnce(base.copy(st = St.WAITING, line = "需要手机号收短信")))
        val b = R.seed(d, emptyList(), emptyList(), 0, fmt)[1].second
        assertTrue(b, b.contains("- 状态：完成\n")); assertFalse(b, b.contains("完成 — 完成"))
        assertEquals("已完成", R.details(d, emptyList(), 0, fmt).toMap()["结果"])
        assertEquals("完成", R.diff(d.copy(st = St.RUNNING), d, 5)!!.text)
    }
}
