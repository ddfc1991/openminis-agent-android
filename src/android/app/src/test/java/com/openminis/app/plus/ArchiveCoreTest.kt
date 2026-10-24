package com.openminis.app.plus

import com.openminis.app.plus.ArchiveCore.DAY_MS
import com.openminis.app.plus.ArchiveCore.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.6 i12] 3-day auto archive + manual archive / restore. */
class ArchiveCoreTest {
    private val now = 100 * DAY_MS
    private fun arch(i: Input, auto: Boolean = true, days: Int = 3) = ArchiveCore.isArchived(i, now, auto, days)

    @Test fun idleMoreThanThreeDaysIsArchived() {
        assertTrue(arch(Input(lastActivity = now - 3 * DAY_MS - 1)))
        assertFalse(arch(Input(lastActivity = now - 3 * DAY_MS)))          // exactly 3 days: not yet
        assertFalse(arch(Input(lastActivity = now - 2 * DAY_MS)))
        assertEquals(3, ArchiveCore.DEFAULT_DAYS)
    }
    @Test fun runningOrWaitingIsNeverAutoArchived() {
        assertFalse(arch(Input(lastActivity = now - 30 * DAY_MS, busy = true)))
    }
    @Test fun pinnedAndDraftsStay() {
        assertFalse(arch(Input(lastActivity = now - 30 * DAY_MS, pinned = true)))
        assertFalse(arch(Input(lastActivity = now - 30 * DAY_MS, draft = true)))
    }
    @Test fun autoOffKeepsEverything() { assertFalse(arch(Input(lastActivity = now - 30 * DAY_MS), auto = false)) }
    @Test fun restoreRestartsTheClock() {
        assertFalse(arch(Input(lastActivity = now - 10 * DAY_MS, restoredAt = now - DAY_MS)))
        assertTrue(arch(Input(lastActivity = now - 10 * DAY_MS, restoredAt = now - 4 * DAY_MS)))
    }
    @Test fun manualArchiveIsImmediateAndBeatsBusyAndPinned() {
        assertTrue(arch(Input(lastActivity = now - 60_000, manualAt = now)))
        assertTrue(arch(Input(lastActivity = now - 60_000, manualAt = now, pinned = true, busy = true)))
    }
    @Test fun undoOrNewMessageBringsItBack() {
        // 撤销 / 取消归档 = restore after the manual archive
        assertFalse(arch(Input(lastActivity = now - 60_000, manualAt = now - 1000, restoredAt = now)))
        // a new message after the archive: back in 最近对话
        assertFalse(arch(Input(lastActivity = now, manualAt = now - 1000)))
    }
    @Test fun archivesAtTellsWhen() {
        assertEquals(now + 3 * DAY_MS, ArchiveCore.archivesAt(Input(lastActivity = now)))
        assertNull(ArchiveCore.archivesAt(Input(lastActivity = now, busy = true)))
    }
}
