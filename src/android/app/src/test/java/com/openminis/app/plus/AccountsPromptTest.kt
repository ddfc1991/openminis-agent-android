package com.openminis.app.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.11.3] the 台账 rule is in the agent's built-in system prompt and the accounts tool is always registered. */
class AccountsPromptTest {
    private fun src(rel: String) = listOf(File("src/main/java/com/openminis/app/$rel"), File("app/src/main/java/com/openminis/app/$rel")).first { it.exists() }.readText()

    @Test fun ruleWording() {
        val r = AccountsPrompt.LEDGER_RULE
        listOf("多号", "注册", "登录", "换号", "accounts(action=list)", "accounts(action=ledger", "register_pick", "register_done",
            "token", "API key", "cookie", "备注", "不要问用户台账里已经有的东西", "{{secret:ID}}", "绝不在聊天、日志、文件里写出真值")
            .forEach { assertTrue("rule lacks $it", r.contains(it)) }
    }

    @Test fun promptStartsWithRule_andIsAlwaysAppended() {
        val tool = src("plus/AccountsTool.kt")
        assertTrue(tool.contains("const val LEDGER_RULE = AccountsPrompt.LEDGER_RULE"))
        assertTrue(tool.contains("const val PROMPT = LEDGER_RULE + "))
        val vm = src("ui/chat/ChatViewModel.kt")
        val i = vm.indexOf("append(com.openminis.app.plus.AccountsTool.PROMPT)")
        assertTrue(i > 0)
        // not behind a helper / language condition: the line before it is the 38.9.11.3 always-comment
        val before = vm.substring(maxOf(0, i - 260), i)
        assertTrue(before.contains("always in the built-in prompt"))
        assertFalse(before.contains("if (!isHelper"))
    }

    @Test fun toolAlwaysRegistered() {
        val t = src("tools/AgentTools.kt")
        val line = t.lines().first { it.contains("AccountsTool.definition()") }.trim()
        assertTrue("accounts tool must be unconditional: $line", line.startsWith("add(com.openminis.app.plus.AccountsTool.definition())"))
        val vm = src("ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("com.openminis.app.plus.AccountsTool.TOOL_NAME ->"))
        val tool = src("plus/AccountsTool.kt")
        listOf("\"ledger\" ->", "\"use\" ->", "\"register_pick\" ->", "\"register_done\" ->", "\"secret\" ->").forEach { assertTrue(it, tool.contains(it)) }
    }
}
