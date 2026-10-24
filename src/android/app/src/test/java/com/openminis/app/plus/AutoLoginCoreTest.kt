package com.openminis.app.plus

import com.openminis.app.plus.AutoLoginCore.Act
import com.openminis.app.plus.AutoLoginCore.In
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLoginCoreTest {
    /** the user's complaint: GitHub linked via Google, no 台账 password → must never pop the password card first */
    @Test fun githubViaGoogleNeverAsksPassword() {
        val base = In("google", sessionAvailable = true, sessionTried = false, providerButton = "Continue with Google", providerTried = false, ledgerPassword = false)
        assertEquals(Act.REUSE_SESSION, AutoLoginCore.decide(base))
        assertEquals(Act.PROVIDER, AutoLoginCore.decide(base.copy(sessionTried = true)))
        assertEquals(Act.PROVIDER, AutoLoginCore.decide(base.copy(sessionAvailable = false)))
        // provider pressed, still on the password page, no 台账 password → only now the card
        assertEquals(Act.CARD, AutoLoginCore.decide(base.copy(sessionTried = true, providerTried = true)))
    }

    @Test fun passwordAccountsUseLedger() {
        val i = In("password", false, false, null, false, ledgerPassword = true)
        assertEquals(Act.LEDGER_PASSWORD, AutoLoginCore.decide(i))
        assertEquals(Act.CARD, AutoLoginCore.decide(i.copy(ledgerPassword = false)))
        assertEquals(Act.CARD, AutoLoginCore.decide(i.copy(autoUse = false)))
        // a session beats the password
        assertEquals(Act.REUSE_SESSION, AutoLoginCore.decide(i.copy(sessionAvailable = true)))
    }

    @Test fun providerButtons() {
        val gh = listOf("Sign in", "Forgot password?", "Continue with Google", "Continue with Apple", "Create an account")
        assertEquals("Continue with Google", AutoLoginCore.matchProvider(gh, "google"))
        assertEquals("Continue with Apple", AutoLoginCore.matchProvider(gh, "apple"))
        assertNull(AutoLoginCore.matchProvider(gh, "microsoft"))
        assertEquals("使用 Google 账号登录", AutoLoginCore.matchProvider(listOf("登录", "使用 Google 账号登录"), "google"))
        assertEquals("谷歌登录", AutoLoginCore.matchProvider(listOf("谷歌登录"), "google"))
        assertEquals("GitHub", AutoLoginCore.matchProvider(listOf("Google", "GitHub"), "github"))
        assertNull(AutoLoginCore.matchProvider(listOf("Google Cloud pricing and the free tier for new accounts"), "google"))
        assertNull(AutoLoginCore.matchProvider(listOf("Sign in"), "password"))
    }

    @Test fun methodMapping() {
        assertEquals("google", AutoLoginCore.methodId("google", null, false))
        assertEquals("google", AutoLoginCore.methodId("web", "google", true))
        assertEquals("apple", AutoLoginCore.methodId("oauth", "apple", false))
        assertEquals("password", AutoLoginCore.methodId("password", null, false))
        assertEquals("", AutoLoginCore.methodId("web", null, false))
        assertTrue(AutoLoginCore.isOAuth("Google")); assertFalse(AutoLoginCore.isOAuth("password"))
        assertTrue(AutoLoginCore.providerHosts("google").contains("accounts.google.com"))
    }

    @Test fun exchangesAndLog() {
        assertTrue(AutoLoginCore.isExchange("www.okx.com")); assertTrue(AutoLoginCore.isExchange("accounts.binance.com"))
        assertFalse(AutoLoginCore.isExchange("github.com"))
        val a = AutoLoginCore.LogLine(1_000L, "github.com", "Google 登录", true)
        var log = AutoLoginCore.push(emptyList(), a)
        log = AutoLoginCore.push(log, a.copy(at = 20_000L, ok = true))      // same site + how within a minute → replaced
        assertEquals(1, log.size); assertEquals(true, log[0].ok)
        log = AutoLoginCore.push(log, AutoLoginCore.LogLine(30_000L, "aliyun.com", "密码", true))
        assertEquals(2, log.size); assertEquals("aliyun.com", log[0].site)
        assertEquals("github.com · Google 登录 · 自动 · 成功", AutoLoginCore.logText(log[1]))
        assertEquals("复用会话", AutoLoginCore.howLabel(Act.REUSE_SESSION, "google"))
    }
}
