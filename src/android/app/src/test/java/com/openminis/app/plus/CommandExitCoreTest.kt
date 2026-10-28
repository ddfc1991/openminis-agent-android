package com.openminis.app.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandExitCoreTest {
    @Test fun benignOutputDoesNotOverrideZeroExitAndWarningsDoNotTurnFailureIntoSuccess() {
        assertTrue(MinesJobs.Job(1, "check", "", "", exit = 0, lines = listOf("warning: optional config unavailable")).ok)
        assertFalse(MinesJobs.Job(2, "check", "", "", exit = 1, lines = listOf("warning only")).ok)
        assertFalse(MinesJobs.Job(3, "running", "", "", exit = null).ok)
    }
}
