package com.openminis.app.plus

import java.io.File

/**
 * [Mines-38.9.11 c] files the agent makes for the user must outlive the chat (user 2026-10-19 「worker 里的文件会被自动清理」;
 * research/mines-worker-files-cleanup.md). /var/minis/workspace is per chat (filesDir/minis-sessions/<id>/workspace) and was
 * wiped by the empty-chat cleanup, the orphan sweep at start, and every chat delete. Now:
 *  - deliverables + browser downloads go to /var/minis/shared/<主题>/ and shared/下载/ (filesDir/minis-global/shared);
 *  - an automatic delete (empty-on-exit, orphan-sweep) never throws files away: a chat with workspace files is not
 *    auto-deleted, and an orphan's workspace + attachments are moved to shared/已归档/<title>-<id8>/ first;
 *  - a one-time migration archives workspaces already orphaned (no chat row) the same way.
 * Pure (java.io only), unit-tested.
 */
object SharedFilesCore {
    const val SHARED = "minis-global/shared"
    const val DOWNLOADS = "下载"
    const val ARCHIVE = "已归档"
    const val ATTACH_SUB = "附件"
    const val MIGRATION_KEY = "orphan_workspace_migrated_v1"
    /** delete reasons that the user did not ask for (SessionDeleter callers) */
    val AUTOMATIC = setOf("orphan-sweep", "empty-on-exit")
    fun isAutomatic(reason: String): Boolean = reason in AUTOMATIC
    /** [38.9.11 c] deletes whose chat files are moved to shared/已归档/ first: automatic ones + the chat list / folder deletes
     *  (their dialogs say so). 存储管理's batch delete is for freeing space, so it still deletes the files. */
    val KEEPS_FILES = AUTOMATIC + setOf("list-single", "list-multi", "folder-delete")
    fun keepsFiles(reason: String): Boolean = reason in KEEPS_FILES

    fun sharedDir(filesDir: File): File = File(filesDir, SHARED)
    fun downloadsDir(filesDir: File): File = File(sharedDir(filesDir), DOWNLOADS)
    fun archiveRoot(filesDir: File): File = File(sharedDir(filesDir), ARCHIVE)
    fun workspaceDir(filesDir: File, sessionId: String): File = File(filesDir, "minis-sessions/$sessionId/workspace")
    fun attachmentsDir(filesDir: File, sessionId: String): File = File(filesDir, "minis-sessions/$sessionId/attachments")

    private val BAD = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    /** a file / folder name that is safe on every file system the picker may copy to; never empty, ".", or ".." */
    fun safeName(raw: String?, max: Int = 40, fallback: String = "对话"): String {
        val s = (raw ?: "").replace(BAD, " ").replace(Regex("""\s+"""), " ").trim().trim('.', ' ')
        val cut = if (s.codePointCount(0, s.length) > max) s.substring(0, s.offsetByCodePoints(0, max)).trim() else s
        return cut.ifEmpty { fallback }
    }

    /** shared/<主题>/ for a topic the agent names ("报告", "myproject/data" → only the first level is the topic) */
    fun topicDir(filesDir: File, topic: String?): File = File(sharedDir(filesDir), safeName(topic?.substringBefore('/'), fallback = "未分类"))

    /** archive folder name for a chat: <title>-<id8> */
    fun archiveName(title: String?, sessionId: String): String = "${safeName(title)}-${sessionId.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "x" }}"

    /** name → name-1 → name-2 … (keeps the extension) until [taken] says it is free */
    fun unique(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var i = 1
        while (taken("$base-$i$ext")) i++
        return "$base-$i$ext"
    }

    /** true when [dir] holds at least one regular file anywhere below it (empty sub-folders do not count) */
    fun hasFiles(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return dir.walkTopDown().maxDepth(32).any { it.isFile }
    }

    /** session dirs on disk without a chat row → their workspaces are candidates for the one-time migration */
    fun orphans(sessionDirs: List<String>, isLive: (String) -> Boolean): List<String> = sessionDirs.filter { it.isNotBlank() && !it.startsWith(".") && !isLive(it) }

    /**
     * Moves [src]'s content into [dst] (created; a free name is the caller's job). rename first, copy + delete when rename
     * fails (different mount). Returns the number of files that ended up in [dst]; [src] is removed only when everything got
     * there.
     */
    fun moveInto(src: File, dst: File): Int {
        if (!src.isDirectory) return 0
        dst.mkdirs()
        var n = 0; var ok = true
        for (c in src.listFiles().orEmpty()) {
            val target = File(dst, unique(c.name) { File(dst, it).exists() })
            if (c.renameTo(target)) { n += if (target.isFile) 1 else target.walkTopDown().count { it.isFile }; continue }
            val copied = runCatching { c.copyRecursively(target, overwrite = false) }.getOrDefault(false)
            if (copied) { n += if (target.isFile) 1 else target.walkTopDown().count { it.isFile }; c.deleteRecursively() } else ok = false
        }
        if (ok) src.deleteRecursively()
        return n
    }

    /**
     * Rescues a chat's workspace (+ attachments into 附件/) into shared/已归档/<title>-<id8>/. Null when there was nothing
     * to keep. Safe to call twice (the second call finds nothing).
     */
    fun archive(filesDir: File, sessionId: String, title: String?): File? {
        val ws = workspaceDir(filesDir, sessionId); val at = attachmentsDir(filesDir, sessionId)
        val wsHas = hasFiles(ws); val atHas = hasFiles(at)
        if (!wsHas && !atHas) return null
        val root = archiveRoot(filesDir).apply { mkdirs() }
        val dst = File(root, unique(archiveName(title, sessionId)) { File(root, it).exists() })
        if (wsHas) moveInto(ws, dst)
        if (atHas) moveInto(at, File(dst, ATTACH_SUB))
        return dst
    }

    /** the agent prompt lines about where files go (kept here so the wording is tested) */
    const val PROMPT_NOTE = "File persistence: /var/minis/workspace/ belongs to THIS chat only and is deleted with the chat — use it for scratch work. " +
        "Anything the user should keep (documents, reports, images you made for them, downloads, finished code projects) goes to " +
        "/var/minis/shared/<主题>/ (one folder per topic, e.g. /var/minis/shared/旅行计划/), link it as minis://shared/…; browser " +
        "downloads land in /var/minis/shared/下载/. shared/ is visible in the phone's file picker (Mines) and survives chat deletes; " +
        "files of chats that were cleaned up automatically are kept in /var/minis/shared/已归档/."
}
