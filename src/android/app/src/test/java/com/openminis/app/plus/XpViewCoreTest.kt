package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.11 i32] cases are the user's 2026-10-20 08:12 screenshots of 工作流记录 / 经验. */
class XpViewCoreTest {
    private fun noRaw(s: String) {
        assertFalse(s, s.contains('<')); assertFalse(s, s.contains("scheduled_task")); assertFalse(s, s.contains("system-remi"))
        assertFalse(s, Regex("[0-9a-f]{8}-[0-9a-f]{4}").containsMatchIn(s)); assertFalse(s, s.contains("Minis runt"))
    }

    @Test fun scheduledTaskBecomesItsLabel() {
        val raw = "<scheduled_task id=\"59927f28-47b1-410b-a6d6-eb1e58049ff1\" label=\"检查梦云\" next=\"1760922000000\">\n看一下梦云服务器状态\n</scheduled_task>"
        val s = XpViewCore.scheduled(raw)!!
        assertEquals("59927f28-47b1-410b-a6d6-eb1e58049ff1", s.id); assertEquals("检查梦云", s.label)
        assertEquals("定时任务：检查梦云", XpViewCore.heuristicTitle(raw, emptyList()))
        // stored tasks were cut at 300 chars: a truncated envelope still parses / cleans
        val cut = "<scheduled_task id=\"1d2682b4-3e57-47a1-bb6"
        assertNotNull(XpViewCore.scheduled(cut))
        noRaw(XpViewCore.heuristicTitle(cut, listOf("shell_execute" to "检查 Chrome 当前页面")))
        noRaw(XpViewCore.clean(cut))
    }

    @Test fun systemReminderNeverShows() {
        val raw = "<system-reminder>[Minis runtime reminder] keep persona</system-reminder>\n查看当前页面状态"
        assertEquals("查看当前页面状态", XpViewCore.clean(raw))
        val t = XpViewCore.heuristicTitle("<system-reminder>[Minis runt", listOf("browser_use" to "browser_use: 查看当前页面状态"))
        noRaw(t); assertEquals("查看当前页面状态", t)
    }

    @Test fun userSentenceTitles() {
        assertEquals("给codebuddy网址给我我去登录看看".take(17) + "…",
            XpViewCore.heuristicTitle("这样，你给codebuddy网址给我我去登录看看", emptyList()))
        // a bare question → the first meaningful step
        assertEquals("打开 WorkBuddy 并读界面", XpViewCore.heuristicTitle("哪里有地区？", listOf("shell_execute" to "shell_execute: 打开 WorkBuddy 并读界面")))
    }

    @Test fun codesAreSecretAndTrivial() {
        for (c in listOf("012746", "Qd5xs3d", "Qd5c,s3d", "sk-abcdef1234567890")) assertTrue(c, XpViewCore.isSecretish(c))
        for (c in listOf("能用哪个免费", "我说workbuddy能不能用", "WorkBuddy")) assertFalse(c, XpViewCore.isSecretish(c))
        assertTrue(XpViewCore.trivial("012746", "shell_execute: 检查 Chrome 当前页面"))
        assertTrue(XpViewCore.trivial("Qd5c,s3d", "shell_execute: 填验证码并发送邮箱验证"))
        assertFalse(XpViewCore.trivial("能用哪个免费", "shell_execute: 拉起 WorkBuddy 并查 region"))
        assertEquals("shell_execute: 填验证码 ***", XpViewCore.scrub("shell_execute: 填验证码 Qd5xs3d"))
        assertEquals("填验证码 ***", XpViewCore.stepLine("shell_execute", "shell_execute: 填验证码 Qd5xs3d"))
        assertEquals("token=***", XpViewCore.scrub("token=ghp_abcdefghijklmnop"))
    }

    @Test fun toolChipsInChinese() {
        assertEquals("浏览器", XpViewCore.tool("browser_use").zh)
        assertEquals("命令", XpViewCore.tool("shell_execute").zh)
        assertEquals("读文件", XpViewCore.tool("file_read").zh)
        assertEquals("看图", XpViewCore.tool("read_image").zh)
        assertEquals(listOf("命令", "浏览器"), XpViewCore.chips(listOf("shell_execute", "browser_use", "shell_execute")).map { it.zh })
        val s = XpViewCore.heuristicSummary(listOf("shell_execute" to "验证 CodeBuddy 官网地址", "browser_use" to "打开登录页"), true)
        assertTrue(s, s.startsWith("用命令、浏览器 2 步")); assertFalse(s.contains("shell_execute"))
    }

    @Test fun nearDuplicatesMerge() {
        assertTrue(XpViewCore.similar("我说workbuddy能不能用", "workbuddy能不能用"))
        assertTrue(XpViewCore.similar("登录 CodeBuddy 官网", "登录CodeBuddy官网看看"))
        assertFalse(XpViewCore.similar("登录 CodeBuddy 官网", "检查梦云服务器状态"))
        val g = XpViewCore.groupSimilar(listOf("登录 CodeBuddy 官网", "检查梦云", "登录CodeBuddy官网看看")) { it }
        assertEquals(2, g.size); assertEquals(2, g[0].size)
    }

    @Test fun modelAnswerValidated() {
        assertEquals("登录 CodeBuddy 官网" to "用浏览器打开官网并登录，成功",
            XpViewCore.parseModelSummary("好的：{\"title\":\"「登录 CodeBuddy 官网」\",\"summary\":\"用浏览器打开官网并登录，成功\"}"))
        assertNull(XpViewCore.parseModelSummary("no json"))
        assertNull(XpViewCore.parseModelSummary("{\"title\":\"Login CodeBuddy\",\"summary\":\"x\"}"))
        assertNull(XpViewCore.parseModelSummary("{\"title\":\"012746\",\"summary\":\"x\"}"))
        assertNull(XpViewCore.parseModelSummary("{\"title\":\"这是一个特别特别特别特别长的不合格的标题哦哦\",\"summary\":\"x\"}"))
        val p = XpViewCore.summaryPrompt("<scheduled_task id=\"x\" label=\"y\">验证码 123456</scheduled_task>", listOf("shell_execute" to "填验证码 Qd5xs3d"), "ok", true)
        assertFalse(p, p.contains("123456")); assertFalse(p, p.contains("Qd5xs3d")); assertFalse(p, p.contains("scheduled_task"))
    }
}
