package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTierTest {
    data class Tool(val name: String)
    private val tools = listOf(
        "shell_execute", "file_read", "file_write", "file_edit", "read_image", "browser_use",
        "memory_write", "memory", "subagent_task", "search_knowledge", "mines_selfcheck",
        "app_settings", "app_self", "accounts", "task_progress", "persona", "app_agent", "cloudflare", "workspace_register",
    ).map(::Tool)

    @Test fun tiersExposeOnlyExistingNamesAndHonorCaps() {
        val lite = ToolTier.filter(tools, ToolTier.LITE) { it.name }
        val medium = ToolTier.filter(tools, ToolTier.MEDIUM) { it.name }
        val heavy = ToolTier.filter(tools, ToolTier.HEAVY) { it.name }
        assertEquals(10, lite.size)
        assertEquals(15, medium.size)
        assertEquals(tools.size, heavy.size)
        assertTrue(lite.all { it.name in ToolTier.LITE_TOOLS })
        assertTrue(medium.all { it.name in ToolTier.MEDIUM_TOOLS })
        assertTrue(lite.any { it.name == "shell_execute" })
        assertTrue(lite.any { it.name == "accounts" })
        assertTrue(lite.any { it.name == "app_self" })
        assertTrue("enabled persona safety tool must survive lite-tier trimming", lite.any { it.name == "persona" })
        assertTrue("task progress must remain available in lite tier", lite.any { it.name == "task_progress" })
        assertFalse("preserve the ten-tool cap; persona displaces optional aliases", lite.any { it.name == "read_image" || it.name == "memory" })
        assertFalse(lite.any { it.name == "browser_use" })
        assertTrue(medium.any { it.name == "browser_use" })
        assertTrue(medium.any { it.name == "app_settings" })
        assertTrue(medium.any { it.name == "mines_selfcheck" })
    }

    @Test fun duplicateDefinitionsKeepOriginalOrderAfterRequiredTools() {
        val input = listOf(Tool("browser_use"), Tool("shell_execute"), Tool("shell_execute"), Tool("accounts"))
        val result = ToolTier.filter(input, ToolTier.LITE) { it.name }
        assertEquals(listOf("shell_execute", "shell_execute", "accounts"), result.map { it.name })
    }

    @Test fun legacyMemoryModeAlsoKeepsItsRealToolNames() {
        // Model only the legacy alias: avoid also keeping the modern memory_get entry,
        // which would create an artificial duplicate and consume one of the ten slots.
        val legacy = tools.filterNot { it.name == "memory_get" }.map { if (it.name == "memory") Tool("memory_get") else it }
        val lite = ToolTier.filter(legacy, ToolTier.LITE) { it.name }
        assertEquals(10, lite.size)
        assertTrue(lite.any { it.name == "memory_get" })
        assertTrue(lite.any { it.name == "persona" })
    }

    @Test fun oldPhantomToolNamesAreNotAdvertised() {
        assertFalse(ToolTier.LITE_TOOLS.any { it in setOf("todo_write", "ask_user", "finish") })
    }
}
