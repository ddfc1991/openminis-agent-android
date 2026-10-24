package com.openminis.app.plus

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import java.io.File

/** [Mines-38.9.11 c] Android side of [SharedFilesCore]: rescue before automatic deletes + the one-time orphan migration. */
object SharedFiles {
    private const val TAG = "SharedFiles"
    /** 自检 / smoke: what the last rescue / migration did */
    @Volatile var lastNote: String = ""

    fun downloadsDir(c: Context): File = SharedFilesCore.downloadsDir(c.applicationContext.filesDir).apply { mkdirs() }
    fun hasWorkspaceFiles(c: Context, sessionId: String): Boolean = runCatching {
        val fd = c.applicationContext.filesDir
        SharedFilesCore.hasFiles(SharedFilesCore.workspaceDir(fd, sessionId)) || SharedFilesCore.hasFiles(SharedFilesCore.attachmentsDir(fd, sessionId))
    }.getOrDefault(true)   // unsure → keep the chat

    /** SessionDeleter hook: automatic + chat-list / folder deletes keep the files (moved to shared/已归档/); 存储管理 batch delete still frees them */
    suspend fun beforeDelete(c: Context, repo: ChatRepository, sessionId: String, reason: String) {
        if (!SharedFilesCore.keepsFiles(reason)) return
        runCatching {
            val title = runCatching { repo.getSession(sessionId)?.title }.getOrNull()
            SharedFilesCore.archive(c.applicationContext.filesDir, sessionId, title)?.let {
                lastNote = "已归档 ${it.name}（$reason）"
                AppLogger.info(TAG, "rescued workspace of ${sessionId.take(8)} → shared/${SharedFilesCore.ARCHIVE}/${it.name} ($reason)")
            }
        }.onFailure { AppLogger.warning(TAG, "rescue failed for ${sessionId.take(8)}: ${it.message}") }
    }

    /** once per install: workspaces whose chat row is already gone → shared/已归档/ */
    suspend fun migrateOrphansOnce(c: Context, repo: ChatRepository) {
        val sp = c.getSharedPreferences("mines_shared_files", 0)
        if (sp.getBoolean(SharedFilesCore.MIGRATION_KEY, false)) return
        val fd = c.applicationContext.filesDir
        val dirs = File(fd, "minis-sessions").list()?.toList().orEmpty()
        val live = HashSet<String>()
        for (id in dirs) if (runCatching { repo.getSession(id) != null }.getOrDefault(true)) live += id   // unsure → live (kept)
        var moved = 0
        for (id in SharedFilesCore.orphans(dirs) { it in live }) {
            runCatching { SharedFilesCore.archive(fd, id, null) }.getOrNull()?.let { moved++ }
        }
        sp.edit().putBoolean(SharedFilesCore.MIGRATION_KEY, true).apply()
        lastNote = "迁移：$moved 个没有对话的工作区已移到 shared/${SharedFilesCore.ARCHIVE}/"
        AppLogger.info(TAG, "orphan workspace migration: $moved archived of ${dirs.size} session dirs")
    }
}
