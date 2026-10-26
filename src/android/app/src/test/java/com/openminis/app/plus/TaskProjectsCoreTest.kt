package com.openminis.app.plus

import com.openminis.app.plus.TaskProjectsCore.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.13 task_projects] naming, 第N次 / 账号N grouping, 2-day small-task archive, JSON. */
class TaskProjectsCoreTest {
    private val C = TaskProjectsCore

    @Test fun namesAfterTheProject() {
        assertEquals("Railway 注册", C.projectName("railway.com"))
        assertEquals("Cloudflare 注册", C.projectName("dash.cloudflare.com"))
        assertEquals("Instabox 激活", C.projectName("instabox.io", "激活"))
        assertEquals("Zeabur", C.brand("https://zeabur.com/pricing"))
    }

    @Test fun findsSiteAndIntent() {
        assertEquals("zeabur.com", C.siteOf("看这篇 https://www.zeabur.com/blog/free 帮我注册一个"))
        assertEquals("railway.com", C.siteOf("去 railway.com 开个号"))
        assertNull(C.siteOf("那就帮我注册一个吧"))
        assertTrue(C.registerIntent("那就帮我注册一个吧"))
        assertTrue(C.registerIntent("帮我 sign up"))
        assertFalse(C.registerIntent("这篇文章讲了什么"))
        assertEquals("激活", C.verbOf("帮我激活 InstaBox"))
        assertEquals("注册", C.verbOf("帮我开通"))
    }

    @Test fun smallTaskKinds() {
        assertEquals(Kind.ARTICLE, C.kindOf("Cloudflare 新免费额度", "帮我看看这篇 https://blog.cloudflare.com/x"))
        assertEquals(Kind.QUESTION, C.kindOf("梦云 SSH", "SSH 连不上怎么办"))
        assertEquals(Kind.OTHER, C.kindOf("画图", "画一只猫"))
        assertEquals("看文章：Cloudflare 新免费额度", C.smallTitle("Cloudflare 新免费额度", Kind.ARTICLE))
        assertEquals("看文章：X", C.smallTitle("看文章：X", Kind.ARTICLE))
        assertEquals("画图", C.smallTitle("画图", Kind.OTHER))
    }

    @Test fun secondAttemptAndSecondAccountNestUnderTheProject() {
        var l = C.register(emptyList(), "s1", "railway.com", "注册", "darling", "k7q2@mail.tm", "2026-10-20", 1)
        assertEquals(1, l.size)
        assertEquals("Railway 注册", C.titleFor(l, "s1"))
        assertEquals("Railway 注册 · 第1次", C.displayName(l, "s1"))
        // the same site again from another conversation = 第2次, same account
        l = C.register(l, "s2", "https://railway.com/login", "注册", "", "K7Q2@mail.tm", "2026-10-24", 2)
        assertEquals(1, l.size)
        assertEquals("Railway 注册 · 第2次", C.titleFor(l, "s2"))
        assertEquals("Railway 注册 · 第2次", C.displayName(l, "s2"))
        assertEquals(1, l[0].accounts.size)
        assertEquals(1, C.attemptOf(l[0], "s2")!!.acct)
        // another account = 账号 2, 第3次
        l = C.register(l, "s3", "railway.com", "注册", "darling02", "qq44@163.com", "2026-10-24", 3)
        assertEquals(2, l[0].accounts.size)
        assertEquals(2, C.attemptOf(l[0], "s3")!!.acct)
        assertEquals(3, C.attemptOf(l[0], "s3")!!.n)
        // re-registering the same conversation does not add an attempt
        l = C.register(l, "s3", "railway.com", "注册", "darling02", "qq44@163.com", "2026-10-24", 4)
        assertEquals(3, l[0].attempts.size)
        // another site = another project
        l = C.register(l, "s4", "zeabur.com", "注册", "", "", "2026-10-24", 5)
        assertEquals(2, l.size)
        assertNull(C.displayName(l, "nope"))
    }

    @Test fun addAccountFromTheProjectPage() {
        var l = C.register(emptyList(), "s1", "instabox.io", "激活", "darling01", "ds19@163.com", "2026-10-20", 1)
        val id = l[0].id
        l = C.addAccount(l, id, "darling02", "qq44@163.com", "2026-10-24")
        assertEquals(listOf(1, 2), l[0].accounts.map { it.idx })
        l = C.addAccount(l, id, "", "QQ44@163.com", "2026-10-24")
        assertEquals(2, l[0].accounts.size)
        assertEquals(1, l[0].attempts.size)
    }

    @Test fun aConversationLivesInOneProject() {
        var l = C.register(emptyList(), "s1", "a.com", "注册", "", "", "d", 1)
        l = C.register(l, "s1", "b.com", "注册", "", "", "d", 2)
        assertEquals("B 注册", C.titleFor(l, "s1"))
        assertTrue(l.first { it.site == "a.com" }.attempts.isEmpty())
        l = C.moveSid(l, "s1", "real")
        assertEquals("B 注册", C.titleFor(l, "real"))
    }

    @Test fun smallTasksArchiveAfterTwoDaysProjectsNever() {
        assertNull(C.archiveDays(true, 3))
        assertEquals(2, C.archiveDays(false, 3))
        assertEquals(1, C.archiveDays(false, 1))
        val day = ArchiveCore.DAY_MS
        assertEquals("2天后归档", C.archiveHint(0, 1))
        assertEquals("1天后归档", C.archiveHint(0, day + 1))
        assertNull(C.archiveHint(0, 2 * day))
        // the shared archive rule with the small-task days
        assertTrue(ArchiveCore.isArchived(ArchiveCore.Input(lastActivity = 0), 2 * day + 1, true, C.archiveDays(false, 3)!!))
    }

    @Test fun searchAndLedgerNote() {
        assertTrue(C.matches("ssh 梦云", "查问题：梦云 SSH 连不上", null))
        assertFalse(C.matches("railway", "看文章：Cloudflare"))
        assertTrue(C.matches("  ", "x"))
        assertEquals("注册中 2026-10-24 · Zeabur 注册", C.ledgerNote("", "Zeabur 注册", "2026-10-24"))
        assertEquals("注册于 2026-10-20 · Railway 注册", C.ledgerNote("注册于 2026-10-20", "Railway 注册", "2026-10-24"))
        assertEquals("注册于 2026-10-20 · Railway 注册", C.ledgerNote("注册于 2026-10-20 · Railway 注册", "Railway 注册", "x"))
    }

    @Test fun jsonRoundTrip() {
        var l = C.register(emptyList(), "s1", "railway.com", "注册", "darling", "k7q2@mail.tm", "2026-10-20", 1)
        l = C.register(l, "s2", "instabox.io", "激活", "", "ds19@163.com", "2026-10-22", 2)
        assertEquals(l, C.fromJson(C.toJson(l)))
        assertTrue(C.fromJson("garbage").isEmpty())
        assertTrue(C.fromJson(null).isEmpty())
    }

    // [38.9.15.1] yesterday's tasks get their 台账 account silently
    @Test fun backfillLinksOldTasksToLedger() {
        val now = 10 * 86_400_000L
        val tasks = listOf(
            Triple("s1", "Zeabur 注册\n帮我注册 zeabur", now - 86_400_000L),
            Triple("s2", "今天天气", now - 3_600_000L),
            Triple("s3", "Railway 注册", now - 9 * 86_400_000L),            // too old
            Triple("s4", "看看 zeaburx 的价格", now - 3_600_000L))          // not the same word
        val ledger = listOf("dash.zeabur.com" to "mine1@dcpa.net", "railway.com" to "r@x.com", "zeabur.com" to "older@x.com")
        val plan = C.backfillPlan(emptyList(), tasks, ledger, now)
        assertEquals(listOf(TaskProjectsCore.Backfill("s1", "dash.zeabur.com", "mine1@dcpa.net")), plan)
        // a task already in a project is left alone
        val proj = C.register(emptyList(), "s1", "zeabur.com", "注册", "", "", "2026-10-25", now)
        assertTrue(C.backfillPlan(proj, tasks, ledger, now).isEmpty())
    }

}
