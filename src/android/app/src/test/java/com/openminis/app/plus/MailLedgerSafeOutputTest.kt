package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailLedgerSafeOutputTest {
    @Test fun otpProjectionExposesOnlyPresenceAndTimestamp() {
        val secretCode = "829104"
        assertTrue(MailLedgerSafeOutput.hasRecentCode(secretCode))
        assertEquals("验证码已记录", MailLedgerSafeOutput.codeLabel(secretCode))
        assertEquals(1234L, MailLedgerSafeOutput.codeTimestamp(secretCode, 1234L))
        assertFalse(MailLedgerSafeOutput.codeLabel(secretCode)!!.contains(secretCode))
    }

    @Test fun emailMaskKeepsDomainButHidesLocalPart() {
        assertEquals("s***@mail.example", MailLedgerSafeOutput.maskEmail("sample@mail.example"))
        assertEquals("***", MailLedgerSafeOutput.maskEmail("not-an-email"))
        assertEquals("***", MailLedgerSafeOutput.maskEmail("@example.test"))
        assertEquals("***", MailLedgerSafeOutput.maskEmail("part@leaked@example.test"))
        assertEquals("***", MailLedgerSafeOutput.maskEmail("user@example.test\nOTP: 123456"))
    }

    @Test fun jsonProjectionNeverContainsOtpOrFreeformNoteAndMasksEmail() {
        val entry = MailLedger.Entry(
            id = "fixture-1", kind = MailLedger.TEMP, provider = "mail.tm",
            address = "private-user@safe.example", domain = "safe.example",
            sites = listOf(MailLedger.Site("平台甲", true, 10L)),
            lastCode = "OTP-TEST-SECRET-9182", lastCodeAt = 20L,
            keepAliveOn = true, keepAliveH = 6, keepAliveAt = 30L,
            note = "NOTE-SECRET-7731", at = 1L, lastUsedAt = 40L,
        )
        val rendered = MailLedgerSafeOutput.toJson(entry).toString()
        assertTrue(rendered.contains("p***@safe.example"))
        assertTrue(rendered.contains("linkedPlatformCount"))
        assertTrue(rendered.contains("lastUsedAt"))
        assertFalse(rendered.contains("private-user"))
        assertFalse(rendered.contains("OTP-TEST-SECRET-9182"))
        assertFalse(rendered.contains("NOTE-SECRET-7731"))
    }

    @Test fun configuredMoaktDomainsMatchTheProvidedLedgerExample() {
        assertEquals(listOf(
            "drmail.in", "teml.net", "tmpeml.com", "tmpbox.net", "moakt.cc",
            "disbox.net", "tmpmail.org", "tmpmail.net", "tmails.net", "disbox.org",
            "moakt.co", "moakt.ws", "tmail.ws", "bareed.ws",
        ), MailLedger.TEMP_DOMAINS)
    }

    @Test fun labelsRejectControlTextAndBoundLongSecretLikeValues() {
        assertEquals("平台甲", MailLedgerSafeOutput.cleanLabel(" 平台甲 "))
        assertEquals(null, MailLedgerSafeOutput.cleanLabel("platform\nINJECTED"))
        assertEquals(null, MailLedgerSafeOutput.cleanLabel("x".repeat(30)))
        assertEquals("example.test", MailLedgerSafeOutput.cleanLabel("example.test"))
    }

    @Test fun lookupNeverPicksAnArbitraryDuplicateId() {
        fun entry(id: String, address: String) = MailLedger.Entry(
            id = id, kind = MailLedger.TEMP, provider = "manual", address = address,
            domain = address.substringAfter('@'), sites = emptyList(), lastCode = "",
            lastCodeAt = 0L, keepAliveOn = false, keepAliveH = 6, keepAliveAt = 0L,
            note = "", at = 1L,
        )
        val entries = listOf(entry("duplicate", "one@example.test"), entry("duplicate", "two@example.test"))
        assertEquals("one@example.test", MailLedgerSafeOutput.resolve(entries, "one@example.test").entry?.address)
        val byId = MailLedgerSafeOutput.resolve(entries, "duplicate")
        assertEquals(null, byId.entry)
        assertTrue(byId.ambiguous)
        val failure = byId.failureResult()
        assertEquals(1, failure.exitCode)
        assertTrue(failure.output.contains("o***@example.test"))
        assertTrue(failure.output.contains("t***@example.test"))
        assertFalse(failure.output.contains("one@example.test"))
        assertFalse(failure.output.contains("two@example.test"))
        assertTrue(failure.output.contains("未擅自选择"))
    }

    @Test fun listOrderIsStableRegardlessOfInsertionOrder() {
        fun entry(id: String, address: String) = MailLedger.Entry(
            id = id, kind = MailLedger.TEMP, provider = "manual", address = address,
            domain = address.substringAfter('@'), sites = emptyList(), lastCode = "",
            lastCodeAt = 0L, keepAliveOn = false, keepAliveH = 6, keepAliveAt = 0L,
            note = "", at = 1L,
        )
        val a = entry("id-a", "alpha@z.example")
        val b = entry("id-b", "beta@a.example")
        val c = entry("id-c", "alpha@a.example")
        assertEquals(listOf(c, b, a), MailLedgerSafeOutput.stableSort(listOf(a, b, c)))
        assertEquals(MailLedgerSafeOutput.stableSort(listOf(a, b, c)), MailLedgerSafeOutput.stableSort(listOf(c, a, b)))
    }

    @Test fun siteFilterUsesExactCaseInsensitiveLabelsAndIgnoresMalformedLegacyLabels() {
        fun entry(id: String, sites: List<MailLedger.Site>) = MailLedger.Entry(
            id = id, kind = MailLedger.TEMP, provider = "manual", address = "$id@example.test",
            domain = "example.test", sites = sites, lastCode = "", lastCodeAt = 0L,
            keepAliveOn = false, keepAliveH = 6, keepAliveAt = 0L, note = "", at = 1L,
        )
        val github = entry("github", listOf(MailLedger.Site("GitHub", true, 1L)))
        val githubMirror = entry("mirror", listOf(MailLedger.Site("GitHub mirror", false, 2L)))
        val malformed = entry("legacy", listOf(MailLedger.Site("GitHub\nOTP 123456", true, 3L)))

        assertTrue(MailLedgerSafeOutput.matchesSite(github, "github"))
        assertFalse(MailLedgerSafeOutput.matchesSite(githubMirror, "GitHub"))
        assertFalse(MailLedgerSafeOutput.matchesSite(malformed, "GitHub"))
        assertTrue(MailLedgerSafeOutput.matchesSite(githubMirror, null))
    }

    @Test fun emptyOtpHasNoLabelAndNoTimestamp() {
        assertFalse(MailLedgerSafeOutput.hasRecentCode(""))
        assertEquals(null, MailLedgerSafeOutput.codeLabel(""))
        assertEquals(0L, MailLedgerSafeOutput.codeTimestamp("", 1234L))
    }
}
