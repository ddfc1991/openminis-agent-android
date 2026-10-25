package com.openminis.app.plus

import com.openminis.app.plus.ChatHomeCore.AutoTask
import com.openminis.app.plus.ChatHomeCore.Kind
import com.openminis.app.plus.ChatHomeCore.Route
import com.openminis.app.plus.ChatHomeCore.St
import com.openminis.app.plus.ChatHomeCore.Tone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.14 chat_home] router quick-vs-task (fake model), task state machine, start gate, chip states, tags. */
class ChatHomeCoreTest {
    private val C = ChatHomeCore

    /** a fake router model: answers like the smoke's fake endpoint, records the prompt */
    private class FakeModel(val reply: (String) -> String?) {
        var calls = 0; var lastPrompt = ""
        suspend fun ask(system: String, prompt: String): String? { calls++; lastPrompt = prompt; return reply(prompt) }
    }

    @Test fun routerQuickAnswerStaysInline() = runBlocking {
        val m = FakeModel { """{"mode":"quick"}""" }
        val d = C.decide("今天几号") { s, p -> m.ask(s, p) }
        assertEquals(Route.QUICK, d.route); assertTrue(d.byModel); assertEquals(1, m.calls)
        assertTrue(m.lastPrompt.startsWith(C.ROUTER_MARK)); assertTrue(m.lastPrompt.contains("今天几号"))
    }

    @Test fun routerTaskGetsTheModelsName() = runBlocking {
        val d = C.decide("顺便把 siyun 的内存看一下") { _, _ -> "好的 {\"mode\":\"task\",\"name\":\"siyun 内存检查\"}" }
        assertEquals(Route.TASK, d.route); assertEquals("siyun 内存检查", d.name)
    }

    @Test fun routerTaskWithoutNameIsNamedAfterTheProject() = runBlocking {
        val d = C.decide("帮我注册 zeabur") { _, _ -> """{"mode":"task"}""" }
        assertEquals(Route.TASK, d.route); assertEquals("Zeabur 注册", d.name)
    }

    @Test fun routerFallsBackWhenModelFailsOrTalksNonsense() = runBlocking {
        assertEquals(Route.TASK, C.decide("帮我注册 zeabur") { _, _ -> null }.route)
        assertEquals(Route.QUICK, C.decide("今天几号") { _, _ -> "我觉得可以" }.route)
        assertEquals(Route.TASK, C.decide("部署一下博客") { _, _ -> throw RuntimeException("502") }.route)
        assertFalse(C.decide("今天几号") { _, _ -> null }.byModel)
    }

    @Test fun heuristicAndNames() {
        assertEquals(Route.QUICK, C.heuristic("新加坡天气怎么样").route)
        assertEquals(Route.TASK, C.heuristic("帮我注册 railway.com").route)
        assertEquals("Railway 注册", C.nameFor("帮我注册 railway.com"))
        assertEquals("Zeabur 注册", C.nameFor("帮我注册 zeabur"))
        assertEquals("清理阿里云 socat", C.nameFor("帮我清理阿里云 socat。"))
        assertTrue(C.cleanName("一二三四五六七八九十一二三四五六七八").length <= 16)
        assertNull(C.parse("not json")); assertNull(C.parse("""{"mode":"maybe"}"""))
        assertTrue(C.obviousQuick("今天几号")); assertFalse(C.obviousQuick("帮我注册 zeabur")); assertFalse(C.obviousQuick("新加坡今天下午会不会下雨，要不要带伞出门"))
        assertEquals("没有可用的模型，先在模型组里加一个", C.rejectZh("no_provider"))
    }

    private fun t(st: St = St.RUNNING) = AutoTask("t1", "s1", "Zeabur 注册", "帮我注册 zeabur", st, createdAt = 1L, updatedAt = 1L)
    private fun p(st: TaskProgressCore.St, steps: Int = 0, done: Int = 0, reason: String = "") = TaskProgressCore.Task(
        "s1", steps = (0 until steps).map { TaskProgressCore.Step("s$it", if (it < done) TaskProgressCore.StepSt.DONE else TaskProgressCore.StepSt.TODO) },
        st = st, reason = reason)

    @Test fun stateMachine() {
        val q = t(St.QUEUED)
        assertEquals(St.QUEUED, C.onProgress(q, p(TaskProgressCore.St.RUNNING), true, 5).st)   // queued until the gate starts it
        val r = C.started(q, 5); assertEquals(St.RUNNING, r.st)
        val r2 = C.onProgress(r, p(TaskProgressCore.St.RUNNING, 5, 2), true, 6)
        assertEquals(St.RUNNING, r2.st); assertEquals(2, r2.step); assertEquals(5, r2.total)
        val w = C.onProgress(r2, p(TaskProgressCore.St.WAITING, 5, 2, "等你在 zeabur.com 登录"), true, 7)
        assertEquals(St.WAITING, w.st); assertEquals("等你在 zeabur.com 登录", w.line); assertTrue(C.needsNotice(w))
        val told = w.copy(told = St.WAITING.id); assertFalse(C.needsNotice(told))
        val back = C.resumed(told, 8); assertEquals(St.RUNNING, back.st); assertEquals("", back.told)
        // an error while the loop still runs (auto-retry) is not final; after it is
        assertEquals(St.RUNNING, C.onProgress(back, p(TaskProgressCore.St.ERROR, reason = "502"), true, 9).st)
        val e = C.onProgress(back, p(TaskProgressCore.St.ERROR, reason = "服务器 502"), false, 9)
        assertEquals(St.ERROR, e.st); assertEquals("服务器 502", e.line)
        // done only once the loop has stopped; the reply becomes the result
        assertEquals(St.RUNNING, C.onProgress(back, p(TaskProgressCore.St.DONE), true, 10).st)
        val d = C.runEnded(C.onProgress(back, p(TaskProgressCore.St.DONE), false, 10), "## 结果\n**已注册** zeabur 账号", 11)
        assertEquals(St.DONE, d.st); assertEquals("结果 已注册 zeabur 账号", d.line)
        // a waiting task is not turned into 完成 by the run returning
        assertEquals(St.WAITING, C.runEnded(w, "需要你登录", 12).st)
        assertEquals(St.ERROR, C.runFailed(r, "没有可用的模型", 13).st)
    }

    @Test fun gateKeepsMemoryAndASlotForTheUser() {
        assertTrue(C.canStart(0, 0, 5, false))
        assertFalse(C.canStart(0, 0, 5, true))                 // heap ≥ 88 %
        assertFalse(C.canStart(C.AUTO_CAP, 2, 5, false))        // auto cap
        assertFalse(C.canStart(1, 4, 5, false))                 // last slot stays for the user / scheduled
        val a = t(St.QUEUED).copy(id = "a", createdAt = 5); val b = t(St.QUEUED).copy(id = "b", createdAt = 3)
        assertEquals("b", C.nextQueued(listOf(a, b, t(St.RUNNING)))?.id)
    }

    @Test fun chipRendering() {
        val c0 = C.chip(t(St.QUEUED).copy(step = 1, total = 4)); assertEquals("排队", c0.state); assertEquals("", c0.progress); assertEquals(Tone.IDLE, c0.tone)
        val c1 = C.chip(t(St.RUNNING).copy(step = 2, total = 5)); assertEquals("进行中", c1.state); assertEquals("2/5", c1.progress); assertEquals(Tone.RUN, c1.tone)
        assertEquals("已建任务 Zeabur 注册，进行中，第 2/5 步", c1.a11y)
        assertEquals(Tone.WAIT, C.chip(t(St.WAITING)).tone); assertEquals("等你", C.chip(t(St.WAITING)).state)
        assertEquals(Tone.ERR, C.chip(t(St.ERROR)).tone); assertEquals("出错", C.chip(t(St.ERROR)).state)
        assertEquals(Tone.DONE, C.chip(t(St.DONE)).tone); assertEquals("完成", C.chip(t(St.DONE)).state)
        assertEquals("", C.chip(t(St.RUNNING)).progress)
        val (wait, run, done) = C.groups(listOf(t(St.WAITING), t(St.ERROR), t(St.QUEUED), t(St.RUNNING), t(St.DONE)))
        assertEquals(2, wait.size); assertEquals(2, run.size); assertEquals(1, done.size)
    }

    @Test fun tagsRoundTrip() {
        val a = C.tagText("t_1", Kind.CREATED, C.createdLine("Zeabur 注册"))
        val ta = C.parseTag(a)!!; assertEquals("t_1", ta.taskId); assertEquals(Kind.CREATED, ta.kind); assertTrue(ta.body.startsWith("已创建任务：Zeabur 注册"))
        val d = C.parseTag(C.tagText("t_1", Kind.DONE, "已注册"))!!; assertEquals(Kind.DONE, d.kind); assertEquals("已注册", d.body)
        assertEquals(Kind.ASK, C.parseTag(C.tagText("t_1", Kind.ASK, "等你登录"))!!.kind)
        assertNull(C.parseTag("普通回复")); assertNull(C.parseTag("⟦mines_task:⟧ x"))
        assertEquals(Kind.ERROR to "没完成：502", C.noticeOf(t(St.ERROR).copy(line = "502")))
        assertNull(C.noticeOf(t(St.RUNNING)))
    }

    @Test fun json() {
        val l = listOf(t(St.WAITING).copy(step = 1, total = 3, line = "等你", told = "waiting"))
        assertEquals(l, C.fromJson(C.toJson(l)))
        assertTrue(C.fromJson("broken").isEmpty())
    }
}
