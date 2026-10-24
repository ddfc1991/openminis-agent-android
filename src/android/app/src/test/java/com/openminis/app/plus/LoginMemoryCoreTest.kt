package com.openminis.app.plus

import com.openminis.app.plus.LoginMemoryCore.Field
import com.openminis.app.plus.LoginMemoryCore.Memo
import com.openminis.app.plus.LoginMemoryCore.Method
import com.openminis.app.plus.LoginMemoryCore.Page
import com.openminis.app.plus.LoginMemoryCore.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.5 i] remembered sign-ins + the secure login card (user 22:42 / 22:43). */
class LoginMemoryCoreTest {
    private fun memo(acct: String, m: Method, at: Long, session: Boolean = false, pw: Boolean = false, chosen: Boolean = false, via: String = "") =
        Memo("notion.so", acct, m, via, session, chosen, pw, at)

    @Test fun replayOrderSessionThenSilentThenCard() {
        assertEquals(listOf(Step.REUSE_SESSION, Step.SILENT_RELOGIN, Step.CARD), LoginMemoryCore.replay(memo("a@b.com", Method.PASSWORD, 1, session = true, pw = true)))
        assertEquals(listOf(Step.SILENT_RELOGIN, Step.CARD), LoginMemoryCore.replay(memo("a@b.com", Method.GOOGLE, 1)))
        assertEquals(listOf(Step.REUSE_SESSION, Step.CARD), LoginMemoryCore.replay(memo("a@b.com", Method.PASSWORD, 1, session = true, pw = false)))
        assertEquals(listOf(Step.CARD), LoginMemoryCore.replay(null))
        assertEquals(Step.CARD, LoginMemoryCore.replay(memo("x", Method.CODE, 1)).last())
    }

    @Test fun recordThenReplayUsesWhatWorked() {
        var l = LoginMemoryCore.record(emptyList(), memo("a@b.com", Method.CODE, 10, via = "Gmail"))
        l = LoginMemoryCore.record(l, memo("A@b.com", Method.CODE, 20, session = true))
        assertEquals(1, l.size)
        assertEquals("Gmail", l[0].codeVia)          // mailbox kept when the later record did not know it
        assertTrue(l[0].hasSession)
        assertEquals(Step.REUSE_SESSION, LoginMemoryCore.replay(LoginMemoryCore.pick(l, "notion.so")).first())
    }

    @Test fun usersChoiceWinsAmongSeveralAccounts() {
        var l = LoginMemoryCore.record(emptyList(), memo("old@b.com", Method.PASSWORD, 10, pw = true, chosen = true))
        l = LoginMemoryCore.record(l, memo("new@b.com", Method.PASSWORD, 50, pw = true))
        assertEquals("old@b.com", LoginMemoryCore.pick(l, "notion.so")!!.account)
        l = LoginMemoryCore.record(l, memo("new@b.com", Method.PASSWORD, 60, pw = true, chosen = true))
        assertEquals("new@b.com", LoginMemoryCore.pick(l, "notion.so")!!.account)
        assertFalse(l.first { it.account == "old@b.com" }.chosen)
        assertNull(LoginMemoryCore.pick(l, "github.com"))
    }

    @Test fun lastLineMasksAccount() {
        assertEquals("上次用 sm***@example.com 登录 · 密码", LoginMemoryCore.lastLine(memo("smoke@example.com", Method.PASSWORD, 1)))
        assertEquals("上次用 136****8658 登录 · 验证码（163 邮箱）", LoginMemoryCore.lastLine(memo("13690688658", Method.CODE, 1, via = "163 邮箱")))
        assertNull(LoginMemoryCore.lastLine(null))
    }

    @Test fun cardAsksOnlyWhatThePageWants() {
        val pw = Page("email", password = true, code = false)
        assertEquals(listOf(Field.EMAIL, Field.PASSWORD), LoginMemoryCore.cardFields(pw, false, false, -1, false, "Notion")!!.fields)
        assertEquals(listOf(Field.EMAIL, Field.PASSWORD), LoginMemoryCore.cardFields(pw, true, false, -1, false, "Notion")!!.fields)
        assertNull(LoginMemoryCore.cardFields(pw, true, true, -1, false, "Notion"))
        val phoneCode = Page("phone", password = false, code = true)
        assertEquals(listOf(Field.PHONE), LoginMemoryCore.cardFields(phoneCode, false, false, -1, false, "豆包")!!.fields)
        assertNull(LoginMemoryCore.cardFields(phoneCode, true, false, 10_000, false, "豆包"))          // still waiting for the mailbox / SMS
        val code = LoginMemoryCore.cardFields(phoneCode, true, false, 31_000, false, "豆包")!!
        assertEquals(listOf(Field.CODE), code.fields); assertEquals("输入验证码", code.title)
        val r = LoginMemoryCore.cardFields(pw, true, true, -1, true, "Notion")!!
        assertEquals("密码不对，重新输入", r.title); assertTrue(r.refused)
        assertEquals(listOf(Field.EMAIL, Field.PASSWORD), r.fields)
    }
}
