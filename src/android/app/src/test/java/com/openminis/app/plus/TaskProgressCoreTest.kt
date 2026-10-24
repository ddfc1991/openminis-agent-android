package com.openminis.app.plus

import com.openminis.app.plus.TaskProgressCore.St
import com.openminis.app.plus.TaskProgressCore.StepSt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.5 i] conversation goal / steps / state machine. */
class TaskProgressCoreTest {
    private val C = TaskProgressCore
    @Test fun planThenStepsThenDone() {
        var t = C.plan(null, "s1", "检查账号", listOf("列出账号", "测试 key", "汇总"), 1000)
        assertEquals(St.RUNNING, t.st); assertEquals(StepSt.DOING, t.steps[0].st); assertEquals(0f, t.fraction, 0.001f)
        t = C.step(t, 0, null, StepSt.DONE, "", 2000)
        assertEquals(StepSt.DOING, t.steps[1].st); assertEquals(1f / 3, t.fraction, 0.001f)
        t = C.step(t, 1, null, StepSt.DONE, "", 3000); t = C.step(t, null, "汇总", StepSt.DONE, "", 4000)
        assertEquals(St.DONE, t.st); assertEquals(1f, t.fraction, 0.001f)
    }
    @Test fun runtimeErrorAndRetry() {
        var t = C.plan(null, "s", "g", listOf("a", "b"), 0)
        t = C.retrying(t, "s", "Provider error: [502] Bad Gateway", 10)
        assertEquals(St.RUNNING, t.st); assertEquals(1, t.retries); assertEquals("服务器暂无响应（502）", t.reason)
        t = C.retrying(t, "s", "timeout", 20); assertEquals(2, t.retries)
        t = C.failed(t, "s", "HTTP 502 Bad Gateway", 30)
        assertEquals(St.ERROR, t.st); assertEquals(StepSt.FAILED, t.steps[0].st)
        assertTrue(C.divider(t)!!.startsWith("— 出错 · 服务器暂无响应（502）"))
        assertTrue(C.inputStatus(t)!!.contains("已自动重试 2 次"))
        t = C.recovered(t, "s", 40); assertEquals(St.RUNNING, t.st); assertEquals(StepSt.DOING, t.steps[0].st)
    }
    @Test fun errorClassification() {
        assertEquals("服务器超时（504）", C.errorReason("HTTP 504 Gateway Timeout"))
        assertEquals("等待超时", C.errorReason("SocketTimeoutException: timeout"))
        assertEquals("回复中途断了", C.errorReason("stream was reset: CANCEL"))
        assertEquals("请求太频繁（429）", C.errorReason("Rate limited — please try again later"))
        assertNull(C.errorReason("模型输出变成了乱码"))
    }
    @Test fun turnEndStates() {
        val t = C.plan(null, "s", "g", listOf("a", "b"), 0)
        assertEquals(St.WAITING, C.turnEnded(t, "s", false, 5).st)
        assertEquals(St.STOPPED, C.turnEnded(t, "s", true, 5).st)
        assertEquals("— 已停止 · 你停止了 · " + java.text.SimpleDateFormat("HH:mm").format(java.util.Date(5)) + " —", C.divider(C.turnEnded(t, "s", true, 5)))
        assertEquals(St.DONE, C.turnEnded(null, "s", false, 5).st)
        val w = C.waiting(t, "s", "等你点验证码", 6)
        assertEquals("在等你：等你点验证码", C.inputStatus(w))
        assertNull(C.divider(w))
    }
    @Test fun summaryLine() {
        val now = 100_000_000L
        val l = listOf(St.RUNNING, St.RUNNING, St.WAITING, St.ERROR, St.DONE, St.DONE, St.STOPPED).mapIndexed { i, s -> TaskProgressCore.Task("s$i", st = s, updatedAt = now) }
        val s = C.summary(l, now)
        assertEquals("进行中 2 · 等你 1 · 出错 1 · 完成 2", s.line)
        assertEquals("", C.summary(emptyList(), now).line)
    }
    @Test fun json() {
        val t = C.step(C.plan(null, "s", "目标", listOf("一", "二"), 1), 0, null, StepSt.DONE, "ok", 2).copy(retries = 2, account = "aliyun")
        val back = C.fromJson(C.toJson(t)); assertNotNull(back); assertEquals(t, back)
        assertTrue(C.spoken(t).startsWith("进行中，1/2 步"))
    }
}
