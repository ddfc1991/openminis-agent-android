package com.openminis.app.plus

import com.openminis.app.plus.ChatHomeCore.Kind
import com.openminis.app.plus.ChatHomeCore.NoticeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.14.1] one card per task in 聊天: retries / repeated state cards fold into the newest one */
class ChatHomeFoldTest {
    private val C = ChatHomeCore
    private fun t(id: String, goal: String) = ChatHomeCore.AutoTask(id, "s$id", goal.take(12), goal)
    private val tasks = listOf(t("a", "那么账号关联这里有没有办法解决？"), t("a2", "那么账号关联这里有没有办法解决？"), t("b", "Doroai 注册"))
    private fun key(id: String) = C.rootKey(tasks.firstOrNull { it.id == id }, id)

    @Test fun userScreenshotFoldsToOneDoneCard() {
        // the phone: 出错 → 等你 → 出错 → 完成 for one question (two task ids from the same message), plus another task's 出错
        val rows = listOf(
            NoticeRef("1", "b", Kind.ERROR, "没完成：Software caused connection abort"),
            NoticeRef("2", "a", Kind.ERROR, "没完成：Software caused connection abort"),
            NoticeRef("3", "a", Kind.ASK, "Software caused connection abort"),
            NoticeRef("4", "a2", Kind.ERROR, "没完成：Software caused connection abort"),
            NoticeRef("5", "a2", Kind.DONE, "完成 · 6/6 步"),
        )
        val f = C.foldNotices(rows, ::key)
        assertTrue(f["1"]!!.show)                        // a different task keeps its own card
        assertFalse(f["2"]!!.show); assertFalse(f["3"]!!.show); assertFalse(f["4"]!!.show)
        val last = f["5"]!!
        assertTrue(last.show)
        assertEquals(listOf("2", "3", "4"), last.history.map { it.id })
        assertEquals(2, last.retries)
        assertEquals("已重试 2 次", last.retryLine)
    }

    @Test fun latestStateWins() {
        val f = C.foldNotices(listOf(NoticeRef("1", "b", Kind.DONE, "ok"), NoticeRef("2", "b", Kind.ERROR, "x")), ::key)
        assertFalse(f["1"]!!.show); assertTrue(f["2"]!!.show); assertEquals(0, f["2"]!!.retries)
    }

    @Test fun singleCardUnchanged() {
        val f = C.foldNotices(listOf(NoticeRef("1", "b", Kind.DONE, "ok")), ::key)
        assertTrue(f["1"]!!.show); assertTrue(f["1"]!!.history.isEmpty()); assertEquals("", f["1"]!!.retryLine)
    }

    @Test fun chipOncePerRoot() {
        val f = C.foldNotices(listOf(NoticeRef("c1", "a", Kind.CREATED, "已创建任务"), NoticeRef("c2", "a2", Kind.CREATED, "已创建任务"),
            NoticeRef("c3", "b", Kind.CREATED, "已创建任务")), ::key)
        assertTrue(f["c1"]!!.show); assertFalse(f["c2"]!!.show); assertTrue(f["c3"]!!.show)
    }

    @Test fun unknownTaskIsItsOwnRoot() {
        assertEquals("id:zz", C.rootKey(null, "zz"))
        val f = C.foldNotices(listOf(NoticeRef("1", "zz", Kind.ERROR, "x"), NoticeRef("2", "yy", Kind.ERROR, "y")), ::key)
        assertTrue(f["1"]!!.show); assertTrue(f["2"]!!.show)
    }
}
