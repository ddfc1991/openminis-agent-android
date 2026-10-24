package com.openminis.app.plus

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/** [Mines-38.9.13 memory_v2] migration without loss, retrieval, token budget. Real SQLite via sqlite-jdbc. */
class MemoryV2CoreTest {
    private lateinit var dir: File
    private val conns = mutableListOf<Connection>()

    private class JdbcSql(val c: Connection) : MemoryV2Core.Sql {
        override fun exec(sql: String, args: Array<Any?>) {
            c.prepareStatement(sql).use { st -> args.forEachIndexed { i, a -> st.setObject(i + 1, a) }; st.execute() }
        }
        override fun rows(sql: String, args: Array<Any?>): List<Array<Any?>> =
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs ->
                    val n = rs.metaData.columnCount
                    val out = mutableListOf<Array<Any?>>()
                    while (rs.next()) out += Array(n) { rs.getObject(it + 1) }
                    out
                }
            }
        override fun <T> tx(block: () -> T): T {
            c.autoCommit = false
            try { val r = block(); c.commit(); return r } catch (e: Throwable) { c.rollback(); throw e } finally { c.autoCommit = true }
        }
    }

    private fun index(fts4: Boolean = false, db: String = ":memory:"): MemoryV2Core.Index {
        val c = DriverManager.getConnection("jdbc:sqlite:$db").also { conns += it }
        return MemoryV2Core.Index(JdbcSql(c), dir, forceFts4 = fts4)
    }

    private fun write(name: String, text: String) = File(dir, name).writeText(text)
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun entry(ts: String, body: String) = "<!-- $ts -->\n$body\n\n"

    @Before fun setUp() { dir = Files.createTempDirectory("memv2").toFile() }
    @After fun tearDown() { conns.forEach { it.close() }; dir.deleteRecursively() }

    private fun fixture() {
        write("GLOBAL.md", "我叫杨arling，半盲，不要大字。\n\n## 账号\nGitHub 账号 ddfc1991，邮箱 deningyang740@gmail.com\n\n## 偏好\n喜欢先看截图再发布。\n")
        write("2026-10-24.md",
            entry("2026-10-24 15:49:00", "## 项目 Mines 38.9.13\n记忆 v2 全部放手机上，不用服务器。") +
                entry("2026-10-24 12:00:00", "梦云服务器 SSH 端口 22，root 登录，部署了 Hermes。") +
                entry("2026-10-24 09:00:00", "## 技能\n构建失败先看 CI 日志的第一条 error 再改。"))
        write("2026-10-23.md", "stray text before markers\n" +
            entry("2026-10-23 20:00:00", "OKX account login uses Gmail, 2FA on phone.") +
            entry("2026-10-23 08:00:00", "随手记：天气不错"))
    }

    // ------------------------------------------------------------ migration

    @Test fun parseTilesEveryFileExactly() {
        fixture()
        write("notes.md", "no markers at all\nline 2")
        write("empty.md", "")
        for (f in dir.listFiles()!!) {
            val text = f.readText()
            val es = MemoryV2Core.parse(f.name, text)
            var at = 0
            for (e in es) { assertEquals("${f.name} gap", at, e.start); at = e.end }
            assertEquals("${f.name} end", text.length, at)
            assertEquals(text, es.joinToString("") { text.substring(it.start, it.end) })
        }
    }

    @Test fun migrationKeepsOriginalsAndLosesNothing() {
        fixture()
        val before = dir.listFiles()!!.associate { it.name to sha(it) }
        val ix = index()
        ix.sync()
        assertEquals("original files untouched", before, dir.listFiles()!!.associate { it.name to sha(it) })
        val expected = dir.listFiles()!!.flatMap { f -> MemoryV2Core.parse(f.name, f.readText()).filter { it.body.isNotBlank() }.map { it.body } }
        val items = ix.items()
        assertEquals(expected.size, items.size)
        val got = items.map { ix.body(it) }
        assertEquals(expected.sorted(), got.map { it!! }.sorted())
        items.forEach { assertTrue(it.dir in MemoryV2Core.DIRS) }
        // every item reachable by read, text identical to the original
        for (it in items) assertTrue(ix.read("${it.dir}/${it.key}").endsWith(ix.body(it)!!.trim()))
    }

    @Test fun syncFollowsEditsAndDeletesAndIsIdempotent() {
        fixture()
        val ix = index(); ix.sync()
        assertEquals(0, ix.sync())
        val n = ix.items().size
        val f = File(dir, "2026-10-24.md")
        f.writeText(entry("2026-10-24 18:00:00", "新的一条：偏好用中文回复") + f.readText())
        f.setLastModified(f.lastModified() + 2000)
        assertEquals(1, ix.sync())
        assertEquals(n + 1, ix.items().size)
        File(dir, "2026-10-23.md").delete()
        ix.sync()
        assertFalse(ix.items().any { it.file == "2026-10-23.md" })
        assertTrue(ix.find("OKX").isEmpty())
    }

    @Test fun explicitDirSurvivesReindex() {
        fixture()
        val ix = index(); ix.sync()
        ix.setDir("随手记：天气不错", "偏好")
        assertEquals("偏好", ix.items().first { it.title.startsWith("随手记") }.dir)
        val f = File(dir, "2026-10-23.md"); f.writeText(entry("2026-10-23 21:00:00", "another") + f.readText()); f.setLastModified(f.lastModified() + 2000)
        ix.sync()
        assertEquals("偏好", ix.items().first { it.title.startsWith("随手记") }.dir)
    }

    @Test fun classifyRules() {
        assertEquals("机器", MemoryV2Core.classify("梦云服务器 SSH 端口 22"))
        assertEquals("账号", MemoryV2Core.classify("OKX account login uses Gmail"))
        assertEquals("技能", MemoryV2Core.classify("## 技能\n随便"))
        assertEquals(MemoryV2Core.OTHER, MemoryV2Core.classify("天气不错"))
    }

    // ------------------------------------------------------------ retrieval

    @Test fun findChineseAndEnglish() {
        fixture()
        for (fts4 in listOf(false, true)) {
            val ix = index(fts4); ix.sync()
            if (!fts4) assertEquals("fts5", ix.fts) else assertEquals("fts4", ix.fts)
            assertEquals("2026-10-24.md", ix.find("梦云 端口").first().item.file)
            assertTrue(ix.find("梦云 端口").first().item.title.startsWith("梦云"))
            assertTrue(ix.find("okx 2fa").first().item.title.startsWith("OKX"))
            assertTrue(ix.find("服务器").isNotEmpty())   // 2-char CJK
            assertTrue(ix.find("云").isNotEmpty())        // lone CJK char
            assertTrue(ix.find("完全无关的词").isEmpty())
        }
    }

    @Test fun fts4AndFts5RankTheSame() {
        fixture()
        val a = index(false).also { it.sync() }
        val b = index(true).also { it.sync() }
        for (q in listOf("梦云", "账号 邮箱", "构建 日志", "okx gmail", "截图 发布")) {
            assertEquals(q, a.find(q).map { it.item.key }, b.find(q).map { it.item.key })
        }
    }

    @Test fun andThenOrFallback() {
        fixture()
        val ix = index(); ix.sync()
        // no single item has both words → OR still returns something
        assertTrue(ix.find("梦云 天气").isNotEmpty())
    }

    @Test fun directoryNarrows() {
        fixture()
        val ix = index(); ix.sync()
        val hits = ix.find("邮箱", dir = "账号")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.item.dir == "账号" })
        // a directory named in the query narrows too
        assertTrue(ix.find("机器 root").all { it.item.dir == "机器" })
        // narrowing that finds nothing falls back to all directories
        assertTrue(ix.find("偏好 梦云").isNotEmpty())
    }

    @Test fun vectorBoostReranks() {
        fixture()
        val ix = index(); ix.sync()
        val okx = ix.items().first { it.title.startsWith("OKX") }
        ix.putVec(ix.body(okx)!!, "m", floatArrayOf(1f, 0f))
        ix.items().filter { it != okx }.forEach { ix.putVec(ix.body(it)!!, "m", floatArrayOf(0f, 1f)) }
        // pure semantic hit with no shared words
        val hits = ix.find("交易所", qVec = "m" to floatArrayOf(1f, 0f))
        assertEquals(okx.key, hits.first().item.key)
        // other model's vectors are ignored
        assertTrue(ix.find("交易所", qVec = "other" to floatArrayOf(1f, 0f)).isEmpty())
    }

    @Test fun lsReadAndSummaries() {
        fixture()
        val ix = index(); ix.sync()
        val root = ix.ls("")
        MemoryV2Core.DIRS.forEach { assertTrue(root.contains("$it/")) }
        assertTrue(ix.ls("机器").contains("梦云"))
        assertTrue(ix.ls("nope").startsWith("没有这个目录"))
        assertTrue(ix.read("GLOBAL.md").startsWith("我叫杨arling"))
        assertTrue(ix.read("../../etc/passwd.md").startsWith("没有"))
        // L0/L1: fallback until a summary for the current content exists
        assertTrue("机器" in ix.staleDirs())
        val its = ix.items("机器")
        ix.saveSummary("机器", ix.sig(its), "服务器与设备", "- 梦云：SSH 22")
        assertFalse("机器" in ix.staleDirs())
        assertTrue(ix.read("机器").contains("梦云：SSH 22"))
        // content change makes it stale again
        val f = File(dir, "2026-10-22.md"); f.writeText(entry("2026-10-22 10:00:00", "新服务器 VPS 在新加坡"))
        ix.sync()
        assertTrue("机器" in ix.staleDirs())
        assertEquals(("服务器与设备" to "x"), MemoryV2Core.parseSummary("好的 {\"l0\":\"服务器与设备\",\"l1\":\"x\"}"))
    }

    // ------------------------------------------------------------ token budget

    @Test fun promptFragmentStaysWithinBudget() {
        // a big memory: 60 days × 40 entries, a long GLOBAL.md
        for (d in 1..60) {
            val sb = StringBuilder()
            for (k in 0 until 40) sb.append(entry("2026-08-%02d 10:%02d:00".format(d % 28 + 1, k), "第 $d 天第 $k 条：服务器 项目 账号 偏好 技能 " + "很长的内容".repeat(30)))
            write("2026-%02d-%02d.md".format(8 + d / 29, d % 28 + 1), sb.toString())
        }
        val global = "全局偏好：" + "不要大字，先发图检查。".repeat(800)
        write("GLOBAL.md", global)
        val ix = index(); ix.sync()
        val frag = ix.promptFragment(global)!!
        assertTrue("fragment ${MemoryV2Core.estimateTokens(frag)} tokens", MemoryV2Core.estimateTokens(frag) <= MemoryV2Core.PROMPT_BUDGET_TOKENS)
        assertTrue(frag.contains("memory read GLOBAL.md"))
        MemoryV2Core.DIRS.filter { ix.count(it) > 0 }.forEach { assertTrue(frag.contains("$it/")) }
        // tool outputs are capped too
        assertTrue(ix.ls("项目").toByteArray().size <= MemoryV2Core.MAX_OUT_BYTES + 200)
        assertTrue(ix.read("GLOBAL.md").toByteArray().size <= MemoryV2Core.MAX_OUT_BYTES + 200)
        assertTrue(ix.findText("服务器", null).toByteArray().size <= MemoryV2Core.MAX_OUT_BYTES + 200)
    }

    @Test fun smallMemoryShownWhole() {
        fixture()
        val ix = index(); ix.sync()
        val g = File(dir, "GLOBAL.md").readText()
        val frag = ix.promptFragment(g)!!
        assertTrue(frag.contains(g.trimEnd()))
        assertTrue(MemoryV2Core.estimateTokens(frag) <= MemoryV2Core.PROMPT_BUDGET_TOKENS)
    }

    @Test fun indexIsDerivedAndRebuildable() {
        fixture()
        val db = File(dir.parentFile, "memv2-${System.nanoTime()}.db")
        try {
            val a = index(db = db.path); a.sync()
            val keys = a.items().map { it.key }.sorted()
            assertNotNull(a.find("梦云").firstOrNull())
            db.delete()
            val b = index(db = db.path); b.sync()
            assertEquals(keys, b.items().map { it.key }.sorted())
        } finally { db.delete() }
    }
}
