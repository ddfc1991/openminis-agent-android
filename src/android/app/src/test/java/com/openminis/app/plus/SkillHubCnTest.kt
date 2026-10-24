package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [Mines-38.9.4] SkillHub (skillhub.cn) parsing — fixtures saved from the live API on 2026-10-17. */
class SkillHubCnTest {
    private fun fx(name: String) = javaClass.classLoader!!.getResource("skillhub/$name")!!.readText()

    @Test fun pluginsParse() {
        val p = SkillHubCn.parsePlugins(fx("plugins.json"))
        assertEquals(3, p.items.size)   // [38.9.7] deepseek-harness (the host itself) is not listed
        assertEquals(1, p.hidden)
        assertTrue(p.total > 1000)
        val first = p.items[0]
        assertTrue(first.owner.isNotBlank() && first.name.isNotBlank())
        assertTrue(first.avatar.startsWith("https://"))
        assertTrue(first.repo.startsWith("https://github.com/"))
        assertFalse(first.verified)   // fixture: 'unsupported'
    }

    @Test fun pluginsDropBadRows() {
        val body = """{"items":[{"owner":"a b","name":"x"},{"owner":"ok","name":"n","installability":"verified","avatarUrl":"http://x","categoryKey":"memory","banned":false},
            {"owner":"z","name":"y","banned":true}],"total":3}"""
        val p = SkillHubCn.parsePlugins(body)
        assertEquals(1, p.items.size)
        assertTrue(p.items[0].verified)
        assertEquals("", p.items[0].avatar)   // non-https refused
        assertEquals("记忆", p.items[0].categoryLabel)
    }

    @Test fun skillsParsePrefersChinese() {
        val s = SkillHubCn.parseSkills(fx("skills.json"))
        assertEquals(3, s.items.size)
        assertTrue(s.total > 1000)
        s.items.forEach { assertTrue(it.slug.matches(Regex("^[a-z0-9][a-z0-9_-]*$"))); assertTrue(it.description.isNotBlank()) }
        val body = """{"code":0,"data":{"skills":[{"slug":"abc","name":"A","description":"en","description_zh":"中文","downloads":5}],"total":1}}"""
        assertEquals("中文", SkillHubCn.parseSkills(body).items[0].description)
        // [38.9.4 h4] namespace handle kept: slugs are not unique across publishers (download needs &namespace=)
        assertEquals("indiv-integrator", s.items[0].namespace)
        assertEquals("", SkillHubCn.parseSkills(body).items[0].namespace)
    }

    @Test fun skillsError() {
        try { SkillHubCn.parseSkills("""{"code":500,"message":"boom"}"""); fail() } catch (e: IllegalStateException) { assertEquals("boom", e.message) }
    }

    @Test fun installPlanPinned() {
        val src = SkillHubCn.resolveInstallSource(fx("install-plan.json"), "vshulcz", "deja-vu")
        assertTrue(src.matches(Regex("^github:vshulcz/deja-vu#[0-9a-f]{40}$")))
    }

    @Test fun installPlanRejects() {
        val plan = fx("install-plan.json")
        try { SkillHubCn.resolveInstallSource(plan, "evil", "deja-vu"); fail() } catch (_: IllegalStateException) {}
        try { SkillHubCn.resolveInstallSource("""{"command":"dsh plugin --profile web add github:a/b#abcdef1 --force","source":"github:a/b#abcdef1"}""", "a", "b"); fail() } catch (_: IllegalStateException) {}
        try { SkillHubCn.resolveInstallSource("""{"source":"npm:foo"}""", "a", "b"); fail() } catch (_: IllegalStateException) {}
        assertEquals("github:a/b#abcdef1", SkillHubCn.resolveInstallSource("""{"command":"dsh plugin --profile web add github:a/b#abcdef1"}""", "a", "b"))
    }

    @Test fun signatureMd5() {
        assertEquals("3ccd2083ff6d2c7c4bb7796c3a592fd0", SkillHubCn.parseSignatureMd5(fx("signature.json")))
        assertNull(SkillHubCn.parseSignatureMd5("""{"signed":false}"""))
        assertNull(SkillHubCn.parseSignatureMd5("not json"))
    }

    @Test fun zipPaths() {
        assertEquals("pkg/", SkillHubCn.commonTopDir(listOf("pkg/SKILL.md", "pkg/a/b.txt")))
        assertEquals("", SkillHubCn.commonTopDir(listOf("SKILL.md", "a/b.txt")))
        assertEquals("SKILL.md", SkillHubCn.normalizeEntry("pkg/SKILL.md", "pkg/"))
        assertNull(SkillHubCn.normalizeEntry("../etc/passwd", ""))
        assertNull(SkillHubCn.normalizeEntry("/abs", ""))
        assertNull(SkillHubCn.normalizeEntry("a//b", ""))
    }

    @Test fun urls() {
        val u = SkillHubCn.pluginsUrl("search 工具", "memory", "updated", 2, false)
        assertTrue(u.startsWith("https://api.skillhub.cn/api/v1/plugins?q=search+"))
        assertTrue(u.contains("scope=all") && u.contains("sort=updated") && u.contains("category=memory") && u.contains("page=2") && u.contains("page_size=24"))
        assertFalse(SkillHubCn.pluginsUrl("", "bogus", "x", 0, true).contains("category"))
        val s = SkillHubCn.skillsUrl("", null, 1)
        assertTrue(s.contains("sortBy=downloads") && s.contains("pageSize=24"))
    }

    @Test fun installedMatch() {
        val p = SkillHubCn.Plugin("vshulcz", "deja-vu", "vshulcz/deja-vu", "", 0, "", true, "", "")
        assertTrue(SkillHubCn.isInstalled(p, emptySet(), mapOf("dsh-deja" to "github:vshulcz/deja-vu#527884b")))
        assertTrue(SkillHubCn.isInstalled(p, setOf("deja-vu"), emptyMap()))
        assertFalse(SkillHubCn.isInstalled(p, setOf("other"), mapOf("x" to "^1.0.0")))
    }
}
