package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLedgerLookupCoreTest {
    private fun row(site: String, host: String, account: String) =
        AutoAccounts.Entry(site, host, account, "已关联", "test", 1L)

    @Test fun exactSiteBeatsBroaderSubstringCandidates() {
        val exact = row("github.com", "github.com", "alice")
        val fuzzy = row("GitHub Copilot", "copilot.github.com", "bob")
        val result = AccountLedgerLookupCore.find(listOf(fuzzy, exact), "github.com")
        assertTrue(result.exact)
        assertEquals(listOf(exact), result.entries)
    }

    @Test fun exactSiteWithSeveralAccountsReturnsAllNotFirst() {
        val bob = row("github.com", "github.com", "bob")
        val alice = row("github.com", "github.com", "alice")
        val result = AccountLedgerLookupCore.find(listOf(bob, alice), "GitHub.com")
        assertTrue(result.exact)
        assertEquals(listOf(alice, bob), result.entries)
    }

    @Test fun ambiguousFuzzyCandidatesAreStableAndNeverSilentlyChosen() {
        val b = row("Alpha GitHub", "b.example", "b")
        val a = row("GitHub Beta", "a.example", "a")
        val result = AccountLedgerLookupCore.find(listOf(b, a), "github")
        assertFalse(result.exact)
        assertEquals(listOf(a, b), result.entries)
        assertTrue(result.entries.size > 1)
    }

    @Test fun blankAndMissingQueriesHaveNoCandidates() {
        assertTrue(AccountLedgerLookupCore.find(listOf(row("github.com", "github.com", "a")), " ").entries.isEmpty())
        assertTrue(AccountLedgerLookupCore.find(listOf(row("github.com", "github.com", "a")), "no-match").entries.isEmpty())
    }
}
