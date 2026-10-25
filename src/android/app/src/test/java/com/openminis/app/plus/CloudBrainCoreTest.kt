package com.openminis.app.plus

import com.openminis.app.plus.ChatHomeCore.AutoTask
import com.openminis.app.plus.ChatHomeCore.St
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.10 cloud_brain] event page / cursor, task state from cloud events, rows, 同意/拒绝 parsing, back-off, JSON. */
class CloudBrainCoreTest {
    private val C = CloudBrainCore
    private fun ev(id: Long, type: String, data: JSONObject = JSONObject(), task: String = "ct1") = CloudBrainCore.Event(id, task, type, data)
    private fun task(st: St = St.RUNNING) = AutoTask("t1", "s1", "Zeabur 注册", "帮我注册 zeabur", st, cloud = "ct1")

    @Test fun pageKeepsOrderAndCursor() {
        val body = JSONObject().put("cursor", 12).put("more", false).put("events", JSONArray()
            .put(JSONObject().put("id", 12).put("task", "ct1").put("type", "say").put("data", JSONObject().put("text", "b")))
            .put(JSONObject().put("id", 11).put("task", "ct1").put("type", "say").put("data", JSONObject().put("text", "a")))
            .put(JSONObject().put("id", 9).put("task", "ct1").put("type", "say").put("data", JSONObject()))).toString()
        val p = C.parsePage(body, 10)
        assertEquals(listOf(11L, 12L), p.events.map { it.id })       // already-seen id 9 dropped, sorted
        assertEquals(12L, p.cursor)
        // an empty page never moves the cursor back
        assertEquals(40L, C.parsePage("""{"events":[],"cursor":0}""", 40).cursor)
    }

    @Test fun tokenOnlyWhenItLooksLikeOne() {
        assertEquals("mb_abcdefghijklmnopqrstuvwxyz012345", C.cleanToken("Welcome!\nmb_abcdefghijklmnopqrstuvwxyz012345\n"))
        assertNull(C.cleanToken("cat: /home/ubuntu/.mines-brain/token: No such file or directory"))
        assertNull(C.cleanToken(null))
    }

    @Test fun statusEventsMoveTheTask() {
        val now = 1000L
        assertEquals(St.DONE, C.apply(task(), ev(5, "status", JSONObject().put("st", "done").put("line", "注册好了")), now).st)
        assertEquals("注册好了", C.apply(task(), ev(5, "status", JSONObject().put("st", "done").put("line", "注册好了")), now).line)
        assertEquals(St.ERROR, C.apply(task(), ev(5, "status", JSONObject().put("st", "error").put("line", "x")), now).st)
        assertEquals("已取消", C.apply(task(), ev(5, "status", JSONObject().put("st", "cancelled")), now).line)
        val w = C.apply(task(), ev(6, "ask", JSONObject().put("kind", "approve").put("line", "要我发出去：hello").put("act", "send")), now)
        assertEquals(St.WAITING, w.st)
        assertTrue(w.line.contains("同意"))
        // the user answered: running again and the next 等你 / 完成 is told again
        val r = C.apply(w.copy(told = "waiting"), ev(7, "decision", JSONObject().put("allow", true)), now)
        assertEquals(St.RUNNING, r.st); assertEquals("", r.told)
        // step only grows
        assertEquals(3, C.apply(task().copy(step = 3), ev(8, "tool", JSONObject().put("step", 2)), now).step)
        assertEquals(4, C.apply(task().copy(step = 3), ev(8, "tool", JSONObject().put("step", 4)), now).step)
    }

    @Test fun askKinds() {
        val h = C.askOf(ev(1, "ask", JSONObject().put("kind", "handoff").put("line", "滑块验证").put("url", "https://x.com/c")))!!
        assertFalse(h.isApproval)
        assertTrue(C.askLine(h).contains("https://x.com/c")); assertTrue(C.askLine(h).contains("好了"))
        assertTrue(C.askOf(ev(1, "ask", JSONObject().put("kind", "approve").put("line", "要我付款：")))!!.isApproval)
        assertNull(C.askOf(ev(1, "say")))
    }

    @Test fun rowsForTheTaskConversation() {
        assertNotNull(C.row(ev(1, "created")))
        assertTrue(C.row(ev(2, "say", JSONObject().put("step", 2).put("text", "先看看")))!!.contains("第 2 步"))
        assertNull(C.row(ev(3, "say", JSONObject().put("text", "  "))))
        assertNull(C.row(ev(4, "tool_done", JSONObject().put("out", "big"))))
        assertNull(C.row(ev(5, "model_retry")))
        assertTrue(C.row(ev(6, "status", JSONObject().put("st", "done").put("line", "OK")))!!.startsWith("☁ 完成"))
        assertNull(C.row(ev(7, "status", JSONObject().put("st", "running"))))
    }

    @Test fun replyVerbs() {
        assertEquals(CloudBrainCore.Verb.ALLOW, C.verbOf("同意", true))
        assertEquals(CloudBrainCore.Verb.ALLOW, C.verbOf(" 可以。", true))
        assertEquals(CloudBrainCore.Verb.TEXT, C.verbOf("同意", false))          // nothing to approve: it is just text
        assertEquals(CloudBrainCore.Verb.DENY, C.verbOf("拒绝", true))
        assertEquals(CloudBrainCore.Verb.CANCEL, C.verbOf("取消任务", false))
        assertEquals(CloudBrainCore.Verb.TEXT, C.verbOf("用 a@b.com 注册", true))
    }

    @Test fun backoffGrowsAndCaps() {
        assertEquals(2_000L, C.backoffMs(1)); assertEquals(4_000L, C.backoffMs(2)); assertEquals(60_000L, C.backoffMs(20))
    }

    @Test fun providersJsonDedupesAndCaps() {
        val l = (1..9).map { CloudBrainCore.Prov("p$it", "https://g/v1", "k$it", if (it < 3) "m" else "m$it") }
        val a = JSONObject(C.providersJson(l)).getJSONArray("providers")
        assertEquals(6, a.length())
        assertEquals("m", a.getJSONObject(0).getString("model"))
        assertEquals("m3", a.getJSONObject(1).getString("model"))
        assertEquals("https://api.openai.com/v1", C.defaultBase("openAI")); assertNull(C.defaultBase("anthropic"))
        val s = JSONObject(C.submitJson("帮我注册 zeabur", "Zeabur 注册", C.clientRef("t1")))
        assertEquals("mines-t1", s.getString("client_ref"))
    }

    @Test fun phoneOnlyJobsStayOnThePhone() {
        assertTrue(C.phoneOnly("把相册里昨天的照片发给我"))
        assertTrue(C.phoneOnly("打开微信看看"))
        assertFalse(C.phoneOnly("帮我注册 doroai"))
        assertFalse(C.phoneOnly("查一下 siyun 服务器内存"))
    }

    @Test fun autoTaskCloudFieldRoundTrips() {
        val t = task().copy(cloud = "ct9")
        assertEquals("ct9", ChatHomeCore.fromJson(ChatHomeCore.toJson(listOf(t)))[0].cloud)
        assertEquals("", ChatHomeCore.fromJson("""[{"id":"a","sid":"b","n":"c","g":"d","st":"running"}]""")[0].cloud)   // old saves
        assertEquals("进行中 · 云端", ChatHomeCore.chip(t).state)
        assertEquals("完成", ChatHomeCore.chip(t.copy(st = St.DONE)).state)
    }

    @Test fun statusLine() {
        assertTrue(C.statusLine(false, null, 0, null).startsWith("已关闭"))
        assertTrue(C.statusLine(true, "siyun", 3, null).contains("3 个模型"))
        assertTrue(C.statusLine(true, null, 0, "连不上").contains("连不上"))
    }
}
