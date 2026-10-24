package com.openminis.app.plus

/**
 * [Mines-38.9.6 i12] user 2026-10-18: chat archive.
 *  - auto: a chat with no activity for [days] (default 3) leaves 最近对话 for 「已归档」 — never while it is 进行中 / 等你,
 *    never a pinned one, never a draft without a model;
 *  - manual: one tap (progress card 「归档」, row long-press, chat ⋯ menu) archives it at once, with 撤销;
 *  - 取消归档 / opening it restores it (and restarts the idle clock).
 * Pure; unit-tested in ArchiveCoreTest.
 */
object ArchiveCore {
    const val DEFAULT_DAYS = 3
    const val DAY_MS = 86_400_000L

    data class Input(
        val lastActivity: Long,
        val pinned: Boolean = false,
        val draft: Boolean = false,
        /** the chat is running or waiting for the user (TaskProgress 进行中 / 等你) */
        val busy: Boolean = false,
        /** when the user archived it by hand (null = never) */
        val manualAt: Long? = null,
        /** when the user last restored / opened it (0 = never) */
        val restoredAt: Long = 0L,
    )

    fun isArchived(i: Input, now: Long, auto: Boolean = true, days: Int = DEFAULT_DAYS): Boolean {
        // a hand archive holds until the user restores (or a new message arrives after it)
        if (i.manualAt != null && i.manualAt >= i.restoredAt && i.manualAt >= i.lastActivity) return true
        if (!auto || i.pinned || i.draft || i.busy) return false
        val touch = maxOf(i.lastActivity, i.restoredAt, i.manualAt ?: 0L)
        return now - touch > days.coerceIn(1, 365) * DAY_MS
    }

    /** when this chat will be auto-archived (null = never, e.g. busy / pinned / auto off) */
    fun archivesAt(i: Input, auto: Boolean = true, days: Int = DEFAULT_DAYS): Long? {
        if (!auto || i.pinned || i.draft || i.busy) return null
        return maxOf(i.lastActivity, i.restoredAt, i.manualAt ?: 0L) + days.coerceIn(1, 365) * DAY_MS
    }
}
