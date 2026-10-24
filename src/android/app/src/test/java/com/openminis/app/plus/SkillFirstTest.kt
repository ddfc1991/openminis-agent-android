package com.openminis.app.plus

import com.openminis.app.agent.SkillFirst
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.6 i17] rule text present; no bundled skill for bulk / batch account registration ships in the APK. */
class SkillFirstTest {
    @Test fun promptSaysWhatItMust() {
        val p = SkillFirst.PROMPT
        assertTrue(p.contains("SKILL.md")); assertTrue(p.contains("已存成技能")); assertTrue(p.contains("bulk / batch account registration"))
        assertTrue(p.contains("Never put passwords"))
    }

    @Test fun noBundledBatchRegistrationSkills() {
        val roots = listOf(File("src/main/assets"), File("app/src/main/assets")).filter { it.isDirectory }
        val bad = Regex("(批量注册|批量开号|bulk[ -]?regist|batch[ -]?regist|mass[ -]?regist)", RegexOption.IGNORE_CASE)
        val hits = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.name.equals("SKILL.md", true) && bad.containsMatchIn(it.readText()) }.map { it.path }.toList() }
        assertTrue("bundled batch-registration skills: $hits", hits.isEmpty())
    }
}
