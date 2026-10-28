package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9] 经验库: retrieval, prompt block, learning from a run, upsert versions, loop-guard consult at the 2nd repeat. */
class ExperienceCoreTest {
    private fun e(id: String, kind: ExperienceCore.Kind, pat: String, steps: String, uses: Int = 0) =
        ExperienceCore.Entry(id, kind, pat, steps, uses = uses, keywords = ExperienceCore.tokens(pat))

    private val lib = listOf(
        e("1", ExperienceCore.Kind.HOWTO, "注册网站账号", "browser_use: 打开注册页\nbrowser_use: wait_for_otp 填验证码"),
        e("2", ExperienceCore.Kind.LESSON, "注册网站账号", "别用 shell 起远程浏览器，改用 browser_use"),
        e("3", ExperienceCore.Kind.HOWTO, "在 B 站发评论", "android-a11y-cli do --task 发评论"),
        e("4", ExperienceCore.Kind.HOWTO, "查服务器磁盘", "shell_execute: ssh alvps df -h"),
    )

    @Test fun retrievesRelevantChineseAndLatin() {
        val r = ExperienceCore.retrieve("帮我注册一个 memoh 网站的账号", lib).map { it.first.id }
        assertTrue(r.containsAll(listOf("1", "2")))
        assertFalse(r.contains("3"))
        val s = ExperienceCore.retrieve("alvps 磁盘满了吗", lib).map { it.first.id }
        assertEquals("4", s.first())
        assertTrue(ExperienceCore.retrieve("今天天气", lib).isEmpty())
    }

    @Test fun promptBlockIsCompact() {
        val b = ExperienceCore.promptBlock(lib, 300)
        assertTrue(b.length <= 300)
        assertTrue(b.contains("[做法 v1] 注册网站账号"))
        assertEquals("", ExperienceCore.promptBlock(emptyList()))
    }

    @Test fun learnsHowtoOnlyWhenRunWentWell() {
        val calls = listOf(ExperienceCore.Call("browser_use", "navigate https://memoh.ai/signup"),
            ExperienceCore.Call("browser_use", "click 注册", true, "error: element not found"),
            ExperienceCore.Call("browser_use", "click Sign up"), ExperienceCore.Call("browser_use", "wait_for_otp"))
        val h = ExperienceCore.learnHowto("帮我注册 memoh 账号。用 163 邮箱", calls, "注册好了，已登录。", 1L, "x")
        assertNotNull(h); h!!
        assertEquals("帮我注册 memoh 账号", h.pattern)
        assertEquals(3, h.steps.lines().size)
        assertTrue(h.pitfalls.contains("element not found"))
        assertNull(ExperienceCore.learnHowto("帮我注册 memoh 账号", calls, "没成功，网站打不开", 1L, "y"))
        assertNull(ExperienceCore.learnHowto("帮我注册 memoh 账号", calls.take(2), "好了", 1L, "z"))
    }

    @Test fun upsertBumpsVersionAndKeepsUserSteps() {
        val a = e("a", ExperienceCore.Kind.HOWTO, "查服务器磁盘", "旧步骤").copy(source = "手动")
        val n = e("b", ExperienceCore.Kind.HOWTO, "查服务器磁盘。", "新步骤").copy(pitfalls = "ssh 超时")
        val l = ExperienceCore.upsert(listOf(a), n)
        assertEquals(1, l.size)
        assertEquals(2, l[0].version); assertEquals("a", l[0].id); assertEquals("旧步骤", l[0].steps); assertEquals("ssh 超时", l[0].pitfalls)
        val l2 = ExperienceCore.upsert(l, e("c", ExperienceCore.Kind.LESSON, "查服务器磁盘", "x"))
        assertEquals(2, l2.size)
    }

    @Test fun successfulShellWithMergedStderrWarningDoesNotTriggerFailureOrEscalation() {
        val output = "Failed to connect to new control master\n登录成功: ser152570142301\nactive"
        val signature = FailurePatternGuard.signature(output, isError = false, tool = "shell_execute")
        assertNull(signature)
        val guard = FailurePatternGuard()
        repeat(3) { assertNull(guard.onResult("shell_execute", isError = false, content = output)) }
        val call = ModelScoreCore.Call("shell_execute", failed = signature != null, sig = signature.orEmpty())
        assertNull(ModelScoreCore.escalation(listOf(call, call, call), ModelScoreCore.Kind.TOOL))
    }

    @Test fun loopGuardConsultsAtSecondRepeatAndStopsAtThird() {
        val g = FailurePatternGuard()
        assertNull(g.onResult("shell_execute", true, "Error: connectOverCDP failed: session 4f1a2b limit"))
        assertTrue(g.consult.isEmpty())
        assertNull(g.onResult("shell_execute", true, "Error: connectOverCDP failed: session 9c8d7e limit"))
        assertEquals(1, g.consult.size)
        val note = ExperienceCore.consultNote(g.consult[0].first, g.consult[0].second, ExperienceCore.retrieve("注册网站账号 远程浏览器", lib).map { it.first })
        assertTrue(note.contains("不要用同样的方法试第 3 次"))
        assertTrue(note.contains("改用 browser_use"))
        assertNotNull(g.onResult("shell_execute", true, "Error: connectOverCDP failed: session 1a2b3c limit"))
        // a different error does not count towards the first one
        val g2 = FailurePatternGuard()
        g2.onResult("shell_execute", true, "Error: A"); g2.onResult("shell_execute", true, "Error: B")
        assertTrue(g2.consult.isEmpty())
    }
}
