package com.openminis.app.plus

import com.openminis.app.plus.TaskProgressCore.HMsg
import com.openminis.app.plus.TaskProgressCore.St
import com.openminis.app.plus.TaskProgressCore.StepSt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.6 i12] automatic goal / steps / summary for every conversation (live turns + rebuilt from history). */
class TaskProgressAutoTest {
    private val C = TaskProgressCore

    @Test fun plainQaFromTheFirstMessage() {
        var t = C.autoTurnStart(null, "s", "今天新加坡天气怎么样？", 1000)
        assertTrue(t.auto); assertEquals(St.RUNNING, t.st)
        assertEquals("问：今天新加坡天气怎么样？", t.steps.single().title); assertEquals(StepSt.DOING, t.steps[0].st)
        assertEquals("今天新加坡天气怎么样？", t.goal)
        t = C.autoTurnEnd(t, "s", false, "**多云**，31 °C，傍晚可能有阵雨。", 2000)
        assertEquals(St.DONE, t.st)
        assertEquals(listOf("问：今天新加坡天气怎么样？", "答：多云，31 °C，傍晚可能有阵雨。"), t.steps.map { it.title })
        assertTrue(t.steps.all { it.st == StepSt.DONE })
        assertTrue(C.oneLine(t).startsWith("已回答："))
        assertEquals(1f, t.fraction, 0.001f)
    }

    @Test fun toolsBecomeStepsAndAreCapped() {
        var t = C.autoTurnStart(null, "s", "帮我领取免费云浏览器", 0)
        listOf("打开注册页", "填邮箱", "等验证码", "登录控制台", "核对额度", "截图").forEachIndexed { i, n ->
            t = C.autoToolStart(t, "s", n, 10L + i); t = C.autoToolEnd(t, "s", n, n != "等验证码", 11L + i)
        }
        val titles = t.steps.map { it.title }
        assertEquals("问：帮我领取免费云浏览器", titles[0])
        assertEquals(listOf("打开注册页", "填邮箱", "等验证码", "登录控制台"), titles.subList(1, 5))
        assertEquals("又做了 2 步", titles[5])
        assertEquals(StepSt.FAILED, t.steps[3].st)   // a failed tool marks its step only
        assertEquals(St.RUNNING, t.st)
        assertTrue(C.oneLine(t).startsWith("正在："))
        t = C.autoTurnEnd(t, "s", false, "领好了", 100)
        assertEquals(St.DONE, t.st); assertTrue(t.summary.contains("用了 5 步工具"))
        assertTrue(t.steps.size <= C.MAX_AUTO_STEPS)
    }

    @Test fun agentPlanWinsOverAutoSteps() {
        var t = C.autoTurnStart(null, "s", "部署 relay", 0)
        t = C.plan(t, "s", "relay 部署", listOf("连服务器", "装依赖", "启动服务"), 5)
        assertFalse(t.auto)
        t = C.autoToolStart(t, "s", "ssh 连接", 6)
        assertEquals(listOf("连服务器", "装依赖", "启动服务"), t.steps.map { it.title })
        t = C.autoTurnStart(t, "s", "继续", 7)
        assertEquals(3, t.steps.size)
    }

    @Test fun stoppedAndNextTurn() {
        var t = C.autoTurnStart(null, "s", "写个脚本", 0)
        t = C.autoTurnEnd(t, "s", true, "", 5)
        assertEquals(St.STOPPED, t.st); assertEquals("你停止了这一轮", t.summary)
        t = C.autoTurnStart(t, "s", "接着写", 9)
        assertEquals(StepSt.DONE, t.steps[0].st); assertEquals("问：接着写", t.steps.last().title)
    }

    @Test fun old38_9_5RowWithoutStepsBecomesAuto() {
        val old = TaskProgressCore.Task("s", st = St.DONE, updatedAt = 1)
        val t = C.autoTurnStart(old, "s", "你好", 2)
        assertTrue(t.auto); assertEquals(1, t.steps.size)
    }

    @Test fun historyOfAnOldChat() {
        val tools = """[{"type":"toolUse","value":{"name":"shell_execute","input":"{\"tool_title\":\"查看日志\",\"command\":\"ls\"}"}},{"type":"text","value":"日志里没有报错，服务正常。"}]"""
        val msgs = listOf(
            C.parseParts("user", """[{"type":"text","value":"<system-reminder>x</system-reminder>帮我看看服务器日志"}]""", 10),
            C.parseParts("assistant", tools, 20),
            C.parseParts("user", """[{"type":"text","value":"那再重启一下"}]""", 30),
            C.parseParts("assistant", """[{"type":"text","value":"已重启，状态 active。"}]""", 40),
        )
        assertEquals(listOf("查看日志"), msgs[1].tools)
        val t = C.fromHistory("s", msgs, 40, "服务器日志")
        assertEquals("history", t.origin); assertEquals(St.DONE, t.st); assertEquals("服务器日志", t.goal)
        assertEquals(listOf("问：帮我看看服务器日志", "答：日志里没有报错，服务正常。", "问：那再重启一下", "答：已重启，状态 active。"), t.steps.map { it.title })
        assertEquals("用了 1 步工具", t.steps[0].note)
        assertEquals("共 2 轮问答 · 最近：那再重启一下", t.summary)
        assertEquals(40L, t.updatedAt)
    }

    @Test fun historyEndingWithAnUnansweredQuestion() {
        val t = C.fromHistory("s", listOf(HMsg("user", "在吗", at = 5)), 5, null)
        assertEquals(St.STOPPED, t.st); assertTrue(t.summary.contains("没有回复"))
        assertEquals("在吗", t.goal)
    }

    @Test fun emptyChatStillHasALine() {
        val t = C.fromHistory("s", emptyList(), 0, "")
        assertEquals("还没有消息", C.oneLine(t)); assertEquals("这个对话", t.goal)
    }

    @Test fun clipStripsMarkdownAndLinks() {
        assertEquals("看 文档 和 代码", C.clip("看 [文档](https://x.y) 和 `代码`", 30))
        assertEquals("一二三…", C.clip("一二三四五", 3))
    }

    @Test fun jsonKeepsAutoFields() {
        val t = C.autoTurnEnd(C.autoTurnStart(null, "s", "hi", 1), "s", false, "hello", 2)
        assertEquals(t, C.fromJson(C.toJson(t)))
    }

    @Test fun cardNeverSaysNoStepTable() {
        val dir = listOf("src/main/java/com/openminis/app", "app/src/main/java/com/openminis/app").map { File(it) }.first { it.isDirectory }
        val ui = File(dir, "ui/plus/TaskProgressUi.kt").readText()
        assertFalse(ui.contains("还没有步骤表"))
        assertTrue(ui.contains("TaskProgressCore.oneLine(t)"))
        assertTrue("every row has the progress entry", ui.contains("val t = all[sid]\n    val m = mines()\n    val st = t?.st"))
    }
}
