package com.openminis.app.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [Mines-38.9.15 限制层] The limits are the user's: the agent can hit them and never move them.
 * User 2026-10-25: 「限制层听我号令」.
 */
class LimitGuardCoreTest {
    private val acct = PersonaCore.Account("weibo.com|me", "weibo.com", "me", allowed = true)
    private val off = acct.copy(allowed = false)
    private val limits = LimitGuardCore.Limits()

    private fun check(a: PersonaCore.Account?, act: LimitGuardCore.Act, used: Int = 0, hours: Boolean = true,
                      l: LimitGuardCore.Limits = limits, flag: Boolean = true, approved: Boolean = false, require: Boolean = false) =
        LimitGuardCore.check(flag, l, a, act, used, hours, approved, require)

    @Test fun nothingStartsAllowListed() {
        assertFalse(PersonaCore.Account("x|y", "x.com", "y").allowed)
        val d = check(off, LimitGuardCore.Act.POST)
        assertFalse(d.allowed); assertEquals(LimitGuardCore.Why.NOT_ALLOWED, d.why)
    }

    @Test fun sendPostDeletePayWaitForTheUser() {
        listOf(LimitGuardCore.Act.SEND, LimitGuardCore.Act.POST, LimitGuardCore.Act.DELETE, LimitGuardCore.Act.PAY).forEach {
            val d = check(acct, it)
            assertFalse("$it should wait", d.allowed); assertEquals(LimitGuardCore.Why.NEEDS_CONFIRM, d.why)
        }
    }

    @Test fun theUsersApprovalLetsOneThrough() {
        assertTrue(check(acct, LimitGuardCore.Act.SEND, approved = true).allowed)
        // the account's own switch, flipped by the user, is the standing version of the same yes
        assertTrue(check(acct.copy(confirmSend = false), LimitGuardCore.Act.SEND).allowed)
    }

    @Test fun readingAndDraftingAlwaysPass() {
        assertTrue(check(acct, LimitGuardCore.Act.READ).allowed)
        assertTrue(check(off, LimitGuardCore.Act.DRAFT).allowed)
    }

    @Test fun killSwitchStopsEverythingIncludingReads() {
        val k = limits.copy(killed = true)
        listOf(LimitGuardCore.Act.READ, LimitGuardCore.Act.DRAFT, LimitGuardCore.Act.TOUCH, LimitGuardCore.Act.SEND).forEach {
            val d = check(acct, it, l = k, approved = true)
            assertFalse("$it after kill", d.allowed); assertEquals(LimitGuardCore.Why.KILLED, d.why)
        }
    }

    @Test fun capAndQuietHoursHold() {
        assertEquals(LimitGuardCore.Why.CAP, check(acct.copy(confirmSend = false), LimitGuardCore.Act.SEND, used = acct.dailyCap).why)
        assertEquals(LimitGuardCore.Why.QUIET_HOURS, check(acct.copy(confirmSend = false), LimitGuardCore.Act.SEND, hours = false).why)
        assertTrue(check(acct, LimitGuardCore.Act.READ, used = 9999, hours = false).allowed)
    }

    @Test fun sendingWithoutAnAccountIsRefused() {
        val d = check(null, LimitGuardCore.Act.SEND, require = true)
        assertFalse(d.allowed); assertEquals(LimitGuardCore.Why.NOT_ALLOWED, d.why)
    }

    @Test fun paymentIsNeverAutomaticEvenOffTheUsersAccounts() {
        assertFalse(check(null, LimitGuardCore.Act.PAY).allowed)
    }

    @Test fun actOfClassifiesBrowserCalls() {
        assertEquals(LimitGuardCore.Act.SEND, LimitGuardCore.actOf("browser_use", "click", "发送"))
        assertEquals(LimitGuardCore.Act.POST, LimitGuardCore.actOf("browser_use", "click", "发布微博"))
        assertEquals(LimitGuardCore.Act.DELETE, LimitGuardCore.actOf("browser_use", "click", "删除"))
        assertEquals(LimitGuardCore.Act.SEND, LimitGuardCore.actOf("persona", "send_email", null))
        assertEquals(LimitGuardCore.Act.READ, LimitGuardCore.actOf("browser_use", "get_text", null))
        assertEquals(LimitGuardCore.Act.TOUCH, LimitGuardCore.actOf("browser_use", "type", "你好"))
        // a sign-in step, not a message to anyone
        assertEquals(LimitGuardCore.Act.TOUCH, LimitGuardCore.actOf("browser_use", "click", "发送验证码"))
    }

    @Test fun theRefusalTellsTheModelToStop() {
        val r = LimitGuardCore.refusal(check(off, LimitGuardCore.Act.POST))
        assertTrue(r.contains("限制层"))
        assertTrue(r.contains("等你处理"))
        assertFalse(r.contains("可以改"))
    }

    @Test fun limitsAndLogSurviveAJsonRoundTrip() {
        val l = LimitGuardCore.Limits(killed = true, quietHours = false)
        assertEquals(l, LimitGuardCore.limitsFromJson(LimitGuardCore.limitsToJson(l)))
        val log = listOf(LimitGuardCore.Entry(1L, "a", "send", false, "NEEDS_CONFIRM", "→ x"))
        assertEquals(log, LimitGuardCore.logFromJson(LimitGuardCore.logToJson(log)))
    }

    /**
     * The structural half of 「限制层听我号令」: no agent-reachable code may write the limits. Every setter is named
     * userSet* / userApprove / clearLog and takes a USER_TAP token, and only the 伪人核心 UI and the stop action of
     * the notification pass one.
     */
    private fun code(f: File): String = f.readLines().filterNot { val t = it.trimStart(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") }
        .joinToString("\n")

    @Test fun noAgentPathWritesTheLimits() {
        val root = listOf("src/main/java/com/openminis/app", "app/src/main/java/com/openminis/app").map { File(it) }.first { it.isDirectory }
        val callers = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { f -> Regex("""Persona\.(userSet|userApprove|clearLog)""").containsMatchIn(code(f)) }
            .map { it.relativeTo(root).path.replace('\\', '/') }.toSet()
        val allowed = setOf("ui/plus/PersonaUi.kt", "persona/PersonaNotify.kt", "persona/Persona.kt", "persona/PersonaSmoke.kt")
        assertTrue("limits written outside the user's own UI: " + (callers - allowed), (callers - allowed).isEmpty())

        val persona = File(root, "persona/Persona.kt").readText()
        assertTrue("every user setter must check the tap token",
            Regex("""fun userSet\w+\([^)]*token: String""").findAll(persona).count() >= 4)
        assertTrue(persona.contains("private fun userOnly(token: String): Boolean = token == LimitGuardCore.USER_TAP"))

        // the agent's own tool must not even mention a setter
        val tool = File(root, "persona/PersonaTool.kt").readText()
        assertFalse(tool.contains("userSet"))
        assertFalse(tool.contains("USER_TAP"))
    }
}
