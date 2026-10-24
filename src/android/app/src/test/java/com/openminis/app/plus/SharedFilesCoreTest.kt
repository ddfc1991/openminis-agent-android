package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** [Mines-38.9.11 c] files the user should keep survive chat deletes: shared/<主题>, 下载, 已归档 rescue + migration rules. */
class SharedFilesCoreTest {
    private fun tmp(): File = Files.createTempDirectory("sfc").toFile()
    private fun write(f: File, s: String = "x") { f.parentFile.mkdirs(); f.writeText(s) }

    @Test fun paths() {
        val fd = File("/data/x/files")
        assertEquals(File("/data/x/files/minis-global/shared/下载"), SharedFilesCore.downloadsDir(fd))
        assertEquals(File("/data/x/files/minis-global/shared/已归档"), SharedFilesCore.archiveRoot(fd))
        assertEquals(File("/data/x/files/minis-sessions/abc/workspace"), SharedFilesCore.workspaceDir(fd, "abc"))
        assertEquals(File("/data/x/files/minis-global/shared/旅行计划"), SharedFilesCore.topicDir(fd, "旅行计划/day1"))
        assertEquals(File("/data/x/files/minis-global/shared/未分类"), SharedFilesCore.topicDir(fd, "  "))
    }

    @Test fun safeNames() {
        assertEquals("a b c", SharedFilesCore.safeName("a/b:c"))
        assertEquals("对话", SharedFilesCore.safeName(null)); assertEquals("对话", SharedFilesCore.safeName("..")); assertEquals("对话", SharedFilesCore.safeName(" / "))
        assertEquals(40, SharedFilesCore.safeName("长".repeat(80)).length)
        assertEquals("帮我写周报-1a2b3c4d", SharedFilesCore.archiveName("帮我写周报", "1a2b3c4d-9999"))
        assertEquals("对话-x", SharedFilesCore.archiveName(null, "--"))
    }

    @Test fun uniqueKeepsExtension() {
        val taken = setOf("a.pdf", "a-1.pdf", "dir")
        assertEquals("a-2.pdf", SharedFilesCore.unique("a.pdf") { it in taken })
        assertEquals("dir-1", SharedFilesCore.unique("dir") { it in taken })
        assertEquals("b.pdf", SharedFilesCore.unique("b.pdf") { it in taken })
    }

    @Test fun onlyAutomaticDeletesRescue() {
        assertTrue(SharedFilesCore.isAutomatic("empty-on-exit")); assertTrue(SharedFilesCore.isAutomatic("orphan-sweep"))
        for (r in listOf("list-single", "list-multi", "folder-delete", "storage-multi")) assertFalse(r, SharedFilesCore.isAutomatic(r))
        for (r in listOf("empty-on-exit", "orphan-sweep", "list-single", "list-multi", "folder-delete")) assertTrue(r, SharedFilesCore.keepsFiles(r))
        for (r in listOf("storage-multi", "debug", "")) assertFalse(r, SharedFilesCore.keepsFiles(r))
    }

    @Test fun hasFilesIgnoresEmptyFolders() {
        val d = tmp(); File(d, "a/b/c").mkdirs()
        assertFalse(SharedFilesCore.hasFiles(d)); assertFalse(SharedFilesCore.hasFiles(File(d, "missing")))
        write(File(d, "a/b/c/x.txt")); assertTrue(SharedFilesCore.hasFiles(d))
    }

    @Test fun archiveMovesWorkspaceAndAttachments() {
        val fd = tmp()
        write(File(fd, "minis-sessions/S1/workspace/report.md"), "hello")
        write(File(fd, "minis-sessions/S1/workspace/proj/main.py"))
        write(File(fd, "minis-sessions/S1/attachments/chart.png"))
        write(File(fd, "minis-sessions/S1/offloads/big.txt"))           // temp: not rescued (deleted with the chat as before)
        val dst = SharedFilesCore.archive(fd, "S1", "周报")
        assertNotNull(dst)
        assertEquals(File(fd, "minis-global/shared/已归档/周报-S1"), dst)
        assertEquals("hello", File(dst, "report.md").readText())
        assertTrue(File(dst, "proj/main.py").isFile); assertTrue(File(dst, "附件/chart.png").isFile)
        assertFalse(File(fd, "minis-sessions/S1/workspace").exists())
        assertNull("second call finds nothing", SharedFilesCore.archive(fd, "S1", "周报"))
        // same title again → a free name, nothing overwritten
        write(File(fd, "minis-sessions/S1/workspace/report.md"), "v2")
        val dst2 = SharedFilesCore.archive(fd, "S1", "周报")!!
        assertEquals("周报-S1-1", dst2.name); assertEquals("hello", File(dst, "report.md").readText())
    }

    @Test fun emptyWorkspaceIsNotArchived() {
        val fd = tmp(); File(fd, "minis-sessions/S2/workspace/sub").mkdirs()
        assertNull(SharedFilesCore.archive(fd, "S2", null))
        assertFalse(File(fd, "minis-global/shared/已归档").exists())
    }

    @Test fun orphanMigrationRule() {
        val live = setOf("a", "b")
        assertEquals(listOf("c"), SharedFilesCore.orphans(listOf("a", "b", "c", ".tmp", "")) { it in live })
    }

    @Test fun promptSteersToShared() {
        val p = SharedFilesCore.PROMPT_NOTE
        assertTrue(p.contains("/var/minis/shared/<主题>/") && p.contains("/var/minis/shared/下载/") && p.contains("deleted with the chat"))
        assertTrue(p.contains("minis://shared/") && p.contains("已归档"))
    }
}
