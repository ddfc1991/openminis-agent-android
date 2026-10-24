package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.11.2] 合并重复供应商 */
class ProviderMergeCoreTest {
    private fun p(id: String, url: String?, keys: List<String>, models: List<String>, at: Long) =
        ProviderMergeCore.P(id, "L-$id", url, keys, models, at)

    @Test fun hostNormalised() {
        assertEquals("token.sensenova.cn", ProviderMergeCore.host("https://token.sensenova.cn/v1"))
        assertEquals("api.x.com", ProviderMergeCore.host("www.API.x.com/v1/"))
        assertEquals("", ProviderMergeCore.host(null))
        assertEquals("", ProviderMergeCore.host("  "))
    }

    @Test fun groupsOnlySameHost() {
        val g = ProviderMergeCore.plan(listOf(
            p("a", "https://token.sensenova.cn/v1", listOf("k1"), listOf("m1"), 3),
            p("b", "https://token.sensenova.cn/v1/", listOf("k2"), listOf("m1", "m2", "m3"), 2),
            p("c", "https://token.sensenova.cn", listOf("k1", " "), listOf(), 1),
            p("d", "https://other.example", listOf("k9"), listOf("x"), 1),
            p("e", null, listOf("k8"), listOf("y"), 1),
        ))
        assertEquals(1, g.size)
        val x = g[0]
        assertEquals("b", x.keeper)                       // most models
        assertEquals(listOf("b", "c", "a"), x.members)   // keeper, then oldest first
        assertEquals(listOf("k2", "k1"), x.keys)          // keeper key first, duplicates + blanks dropped
        assertEquals(listOf("m1", "m2", "m3"), x.models)
        assertFalse(x.pathsDiffer)                        // /v1, /v1/ and "" are the same path
    }

    @Test fun keeperTieGoesToOldest() {
        val g = ProviderMergeCore.plan(listOf(p("new", "a.com", listOf("1"), listOf("m"), 9), p("old", "a.com", listOf("2"), listOf("n"), 1)))
        assertEquals("old", g[0].keeper)
        assertEquals(listOf("2", "1"), g[0].keys)
    }

    @Test fun pathsDifferFlaggedInLine() {
        val g = ProviderMergeCore.plan(listOf(p("a", "https://h.io/openai/v1", listOf("1"), listOf("m"), 1), p("b", "https://h.io/anthropic", listOf("2"), listOf(), 2)))
        assertTrue(g[0].pathsDiffer)
        val line = ProviderMergeCore.line(g[0])
        assertTrue(line, line.contains("2 个") && line.contains("h.io") && line.contains("注意"))
    }

    @Test fun noDuplicatesNoPlan() {
        assertTrue(ProviderMergeCore.plan(listOf(p("a", "a.com", listOf(), listOf(), 1), p("b", "b.com", listOf(), listOf(), 1))).isEmpty())
    }

    @Test fun pickRoundRobin() {
        assertNull(ProviderMergeCore.pick(emptyList(), 0))
        val pool = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c", "a"), (0L..3L).map { ProviderMergeCore.pick(pool, it) })
        assertEquals("c", ProviderMergeCore.pick(pool, -1))
    }
}
