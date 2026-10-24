package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.8] the app picks the skill / workflow before the model starts. */
class SkillMatchCoreTest {
    private val cands = listOf(
        SkillMatchCore.Cand(SkillMatchCore.Kind.SKILL, "s1", "gmail-code", "读取 Gmail 邮箱里的验证码", path = "/var/minis/skills/gmail-code/SKILL.md"),
        SkillMatchCore.Cand(SkillMatchCore.Kind.SKILL, "s2", "weather", "查询城市天气预报"),
        SkillMatchCore.Cand(SkillMatchCore.Kind.WORKFLOW, "w1", "部署 nginx 到服务器", "", "1. ssh 登录\n2. apt install nginx"),
    )

    @Test fun picksSkill() {
        val m = SkillMatchCore.best("帮我看看邮箱里的验证码", cands)
        assertEquals("s1", m?.cand?.id)
        assertTrue(SkillMatchCore.promptLine(m).contains("/var/minis/skills/gmail-code/SKILL.md"))
        assertEquals("用技能：gmail-code", SkillMatchCore.chip(m))
    }

    @Test fun picksWorkflow() {
        val m = SkillMatchCore.best("再部署一次 nginx 到服务器上", cands)
        assertEquals("w1", m?.cand?.id)
        assertTrue(SkillMatchCore.promptLine(m).contains("apt install nginx"))
    }

    @Test fun smallTalkAndUnrelatedGetNothing() {
        assertNull(SkillMatchCore.best("你好", cands))
        assertNull(SkillMatchCore.best("讲个笑话给我听听", cands))
        assertEquals("", SkillMatchCore.chip(null))
    }

    @Test fun frontMatter() {
        assertEquals("a-b" to "做某事", SkillMatchCore.frontMatter("---\nname: a-b\ndescription: \"做某事\"\n---\nbody", "dir"))
        assertEquals("dir" to "", SkillMatchCore.frontMatter("no fm", "dir"))
    }
}
