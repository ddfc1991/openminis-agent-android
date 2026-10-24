package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.5 i] codes only from the site being signed into, recent, code or link only. */
class CodeSourcesCoreTest {
    private val C = CodeSourcesCore
    private val now = 1_800_000_000_000L
    private fun m(from: String, subj: String, text: String, ago: Long) = MailParse.Mail(from + ago, "Gmail", from, subj, text, now - ago)
    @Test fun onlyTheSitesOwnRecentMail() {
        val mails = listOf(
            m("Bank <no-reply@bank.example>", "您的验证码", "验证码 111111", 10_000),
            m("GitHub <noreply@github.com>", "[GitHub] Please verify your device", "Verification code: 482913", 30_000),
            m("GitHub <noreply@github.com>", "old", "Verification code: 999999", 20 * 60_000),
        )
        assertEquals("482913", C.find(mails, "github.com", now - 5 * 60_000, now)?.code)
        assertNull(C.find(mails, "aliyun.com", now - 5 * 60_000, now))
        assertEquals("aliyun.com", C.senderDomain("阿里云 <service@notice.aliyun.com>"))
        assertTrue(C.fromSite("阿里云 <service@notice.aliyun.com>", "", "bailian.console.aliyun.com"))
    }
    @Test fun loginLink() {
        val t = "Click https://www.example-ai.com/auth/verify?token=abc to sign in, or https://evil.test/login"
        assertEquals("https://www.example-ai.com/auth/verify?token=abc", C.linkFrom(t, "example-ai.com"))
        assertNull(C.linkFrom("https://evil.test/login", "example-ai.com"))
        assertEquals("48****", C.mask("482913"))
    }
    @Test fun providersAndApis() {
        assertEquals(listOf("agentmail", "agmail", "mailtm", "selfhost"), C.PROVIDERS.map { it.id })
        assertTrue(C.PROVIDERS.first { it.id == "mailtm" }.temporary); assertTrue(C.PROVIDERS.first { it.id == "selfhost" }.advanced)
        val mt = C.mailTmMessages("""{"hydra:member":[{"id":"1","from":{"address":"noreply@github.com","name":"GitHub"},"subject":"code","intro":"Your code is 123456","createdAt":"2026-10-17T11:00:00+00:00"}]}""")
        assertEquals("GitHub <noreply@github.com>", mt.first().from); assertTrue(mt.first().at > 0)
        val mt2 = C.mailTmMessages("""[{"id":"2","from":{"address":"a@b.c","name":"A"},"subject":"s","intro":"code 777777"}]""")   // Accept: application/json shape
        assertEquals("mt:2", mt2.first().id); assertEquals("code 777777", mt2.first().text)
        val am = C.agentMailMessages("""{"count":1,"messages":[{"message_id":"m1","from":"noreply@github.com","subject":"s","preview":"code 654321","timestamp":"2026-10-17T11:00:00Z"}]}""")
        assertEquals("code 654321", am.first().text)
    }
}
