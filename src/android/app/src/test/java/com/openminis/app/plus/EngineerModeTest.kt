package com.openminis.app.plus

import com.openminis.app.agent.EngineerMode
import com.openminis.app.tools.AgentToolSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.7] 高级工程模式: rule text, default on, and the system prompt appends it like 技能优先. */
class EngineerModeTest {
    @Test fun promptSaysWhatItMust() {
        val p = EngineerMode.PROMPT
        listOf("plan", "Read before you edit", "Verify after every change", "read the error", "more than 2 times", "skill", "Minimal", "NOT verified", "truly blocked")
            .forEach { assertTrue("missing: $it", p.contains(it)) }
    }

    @Test fun defaultOn() {
        assertEquals("mines.agent.engineer.enabled", AgentToolSwitch.ENGINEER.key)
        assertTrue(AgentToolSwitch.ENGINEER.defaultValue)
    }

    @Test fun includedInSystemPrompt() {
        val f = listOf(File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"), File("app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")).first { it.exists() }
        val t = f.readText()
        val i = t.indexOf("AgentToolSwitch.ENGINEER.isEnabled(context)")
        assertTrue("ENGINEER gate missing in buildSystemPrompt", i > 0)
        assertTrue(t.indexOf("append(com.openminis.app.agent.EngineerMode.PROMPT)", i) in i..(i + 300))
        assertTrue("must come after 技能优先", t.indexOf("SkillFirst.PROMPT") in 1 until i)
    }
}
