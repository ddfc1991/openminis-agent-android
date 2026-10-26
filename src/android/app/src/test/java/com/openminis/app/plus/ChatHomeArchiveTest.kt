package com.openminis.app.plus

import com.openminis.app.plus.ChatHomeCore.AutoTask
import com.openminis.app.plus.ChatHomeCore.St
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [38.9.15 task_archive] user 2026-10-26 10:14 「这个页面没办法手动归档」: 归档 / 撤销 on the 任务 page and the task's own chat. */
class ChatHomeArchiveTest {
    private val C = ChatHomeCore
    private fun t(id: String, st: St, line: String = "", u: Long = 100) = AutoTask(id, "s$id", "任务$id", "帮我做 $id", st, 1, 5, line, 10, u)

    @Test fun waitingTaskIsStoppedAndLeavesTheLists() {
        val w = t("qq", St.WAITING, "需要手机号收短信")
        val a = C.archive(w, 500)
        assertTrue(a.archived); assertEquals(St.ERROR, a.st); assertEquals(C.ARCHIVED_LINE, a.line)
        assertFalse("no 出错 notice for an archived task", C.needsNotice(a))
        val (wait, run, done) = C.groups(listOf(a, t("x", St.WAITING)))
        assertEquals(listOf("x"), wait.map { it.id }); assertTrue(run.isEmpty()); assertTrue(done.isEmpty())
        assertEquals(listOf("qq"), C.archivedList(listOf(a, t("x", St.WAITING))).map { it.id })
    }

    @Test fun undoPutsItBackExactly() {
        val w = t("doro", St.WAITING, "等你回复")
        val back = C.unarchive(C.archive(w, 500), w, 600)
        assertFalse(back.archived); assertEquals(St.WAITING, back.st); assertEquals("等你回复", back.line)
        assertEquals(listOf("doro"), C.groups(listOf(back)).first.map { it.id })
    }

    @Test fun runningTaskIsQueuedAgainOnUndo() {
        val r = t("proxy", St.RUNNING)
        val a = C.archive(r, 500)
        assertEquals(St.ERROR, a.st)
        val back = C.unarchive(a, r, 600)
        assertEquals(St.QUEUED, back.st); assertEquals("", back.told)
        assertEquals("proxy", C.nextQueued(listOf(back))?.id)
        assertEquals(null, C.nextQueued(listOf(C.archive(t("q", St.QUEUED), 1))))   // an archived queued task never starts
    }

    @Test fun doneTaskKeepsItsResult() {
        val d = t("z", St.DONE, "已部署")
        val a = C.archive(d, 500)
        assertEquals(St.DONE, a.st); assertEquals("已部署", a.line); assertTrue(a.archived); assertFalse(C.needsNotice(a))
        assertEquals(d.copy(archivedAt = 0L, told = "done"), C.unarchive(a, null, 600).copy(told = "done"))
    }

    @Test fun archiveSurvivesJson() {
        val a = C.archive(t("j", St.WAITING), 777)
        val back = C.fromJson(C.toJson(listOf(a, t("k", St.DONE)))).associateBy { it.id }
        assertEquals(777L, back["j"]!!.archivedAt); assertEquals(0L, back["k"]!!.archivedAt)
        assertEquals(0L, C.fromJson("""[{"id":"o","sid":"s","n":"n","g":"g","st":"done"}]""").single().archivedAt)   // 38.9.14.x data
    }
}
