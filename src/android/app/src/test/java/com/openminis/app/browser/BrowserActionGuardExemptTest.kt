package com.openminis.app.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.13 handoff_v2] a tab handed to the user is never flagged as frozen; other tabs still are (regression). */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserActionGuardExemptTest {
    @Test fun `deadline on a handed-off tab does not flag it`() = runTest {
        val suspects = mutableListOf<String>()
        val out = BrowserActionGuard.runGuarded(clock = { testScheduler.currentTime }, onSuspect = { suspects += it }, exempt = { true }) { awaitCancellation() }
        assertTrue(out is BrowserActionGuard.Outcome.DeadlineExceeded)
        assertTrue(suspects.isEmpty())
    }

    @Test fun `deadline without a handoff still flags the tab`() = runTest {
        val suspects = mutableListOf<String>()
        BrowserActionGuard.runGuarded(clock = { testScheduler.currentTime }, onSuspect = { suspects += it }, exempt = { false }) { awaitCancellation() }
        assertEquals(1, suspects.size)
    }

    @Test fun `a late cancellation on a handed-off tab does not flag it`() = runTest {
        val suspects = mutableListOf<String>()
        val job = launch {
            try {
                BrowserActionGuard.runGuarded(clock = { testScheduler.currentTime }, onSuspect = { suspects += it }, exempt = { true }) { awaitCancellation() }
            } catch (_: CancellationException) {}
        }
        testScheduler.advanceTimeBy(40_000L)
        job.cancel(); job.join()
        assertTrue(suspects.isEmpty())
    }
}
