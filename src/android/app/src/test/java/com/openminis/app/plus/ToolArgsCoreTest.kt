package com.openminis.app.plus

import com.openminis.app.agent.ToolArgsCore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgsCoreTest {
    private fun cmd(raw: String) = JSONObject(ToolArgsCore.normalize(raw).first).optString("command")

    @Test fun plainUntouched() { val r = ToolArgsCore.normalize("""{"command":"ls","tool_title":"x"}"""); assertEquals(0, r.second); assertEquals("ls", cmd(r.first)) }
    @Test fun wrappedObject() = assertEquals("ls", cmd("""{"arguments":{"command":"ls"}}"""))
    @Test fun wrappedString() = assertEquals("ls", cmd("""{"arguments":"{\"command\":\"ls\"}"}"""))
    @Test fun otherWrappers() { for (k in listOf("args", "parameters", "input")) assertEquals("ls", cmd("""{"$k":{"command":"ls"}}""")) }
    @Test fun twoLevels() { val r = ToolArgsCore.normalize("""{"input":{"arguments":{"command":"ls"}}}"""); assertEquals(2, r.second); assertEquals("ls", cmd(r.first)) }
    @Test fun jsonStringPayload() = assertEquals("ls", cmd("\"{\\\"command\\\":\\\"ls\\\"}\""))
    @Test fun notAWrapperWhenMoreKeys() { val r = ToolArgsCore.normalize("""{"input":{"command":"ls"},"tool_title":"t"}"""); assertEquals(0, r.second) }
    @Test fun missingAndError() {
        val m = ToolArgsCore.missing("shell_execute", """{"cmd":"ls"}""")
        assertEquals(listOf("command"), m)
        val e = ToolArgsCore.error("shell_execute", m, """{"cmd":"ls"}""")
        assertTrue(e.contains("`command`")); assertTrue(e.contains("cmd")); assertTrue(e.contains("\"command\":\"ls -la\""))
        assertTrue(ToolArgsCore.missing("shell_execute", ToolArgsCore.normalize("""{"arguments":{"command":"ls"}}""").first).isEmpty())
        assertTrue(ToolArgsCore.missing("unknown_tool", "{}").isEmpty())
    }
    @Test fun garbage() { assertEquals(-1, ToolArgsCore.normalize("not json").second); assertEquals("{}", ToolArgsCore.normalize("").first) }
}
