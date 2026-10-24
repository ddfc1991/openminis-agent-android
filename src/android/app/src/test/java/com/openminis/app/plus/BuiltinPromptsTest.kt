package com.openminis.app.plus

import com.openminis.app.agent.BuiltinPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.9] 内置提示词: always on by default, editable / switchable per section, byte-stable. */
class BuiltinPromptsTest {
    @Test fun defaultsAllOn() {
        val b = BuiltinPrompts.block { null }
        listOf("排障纪律", "解决后归档", "资深工程师", "自动化", "仅限授权").forEach { assertTrue("missing $it", b.contains(it)) }
        assertEquals(b, BuiltinPrompts.block { null })
    }
    @Test fun debugRulesSayWhatUserAsked() {
        val d = BuiltinPrompts.SECTIONS.first { it.key == "debug" }.default
        listOf("本来是干什么的", "反代", "测量", "耗时", "数字", "一个假设", "最小实验", "两次", "换更强的模型", "测到的", "猜的")
            .forEach { assertTrue("missing $it", d.contains(it)) }
        assertTrue(d.length < 600)
    }
    @Test fun offAndEdited() {
        val b = BuiltinPrompts.block { k -> when (k) { "redblue" -> BuiltinPrompts.Stored(null, false); "senior" -> BuiltinPrompts.Stored("我的规则"); else -> null } }
        assertFalse(b.contains("仅限授权")); assertTrue(b.contains("我的规则")); assertFalse(b.contains("资深工程师"))
        assertEquals("", BuiltinPrompts.block { BuiltinPrompts.Stored(null, false) })
        val s = BuiltinPrompts.SECTIONS[0]
        assertEquals(s.default, BuiltinPrompts.textOf(s, BuiltinPrompts.Stored("  ")))
    }
}
