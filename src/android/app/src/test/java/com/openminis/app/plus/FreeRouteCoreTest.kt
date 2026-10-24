package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.8] 免登录 AI: dead routes hidden, retest window, distinct names for duplicates. */
class FreeRouteCoreTest {
    @Test fun visibility() {
        val live = mapOf("web:kimi" to FreeRouteCore.Live("web:kimi", true, "", 100), "web:yuanbao" to FreeRouteCore.Live("web:yuanbao", false, "45 秒没有回复", 100))
        assertTrue(FreeRouteCore.visible("web:kimi", live)); assertFalse(FreeRouteCore.visible("web:yuanbao", live)); assertTrue(FreeRouteCore.visible("web:new", live))
        assertFalse(FreeRouteCore.needsTest("web:kimi", live, 200)); assertTrue(FreeRouteCore.needsTest("web:kimi", live, 100 + FreeRouteCore.RETEST_MS + 1))
        val (ok, line) = FreeRouteCore.summary(mapOf("web:kimi" to "Kimi", "web:yuanbao" to "元宝", "coze:1" to "Bot"), live)
        assertFalse(ok); assertTrue(line, line.contains("能用 1 个：Kimi")); assertTrue(line.contains("已隐藏 1 个：元宝（45 秒没有回复）")); assertTrue(line.contains("没测 1 个"))
        assertTrue(FreeRouteCore.summary(emptyMap(), live).first)
    }

    @Test fun answered() {
        assertTrue(FreeRouteCore.answered("OK")); assertTrue(FreeRouteCore.answered("好的，OK"))
        assertFalse(FreeRouteCore.answered("")); assertFalse(FreeRouteCore.answered(null)); assertFalse(FreeRouteCore.answered("Error: 401"))
    }

    @Test fun distinctNames() {
        val n = FreeRouteCore.distinctNames(listOf("coze:7696936574432591908" to "Mines-Test", "coze:7696936160823738402" to "Mines-Test", "web:kimi" to "Kimi"))
        assertEquals("Mines-Test · 1908", n["coze:7696936574432591908"]); assertEquals("Mines-Test · 8402", n["coze:7696936160823738402"]); assertEquals("Kimi", n["web:kimi"])
        val same = FreeRouteCore.distinctNames(listOf("a:x11908" to "B", "b:y21908" to "B"))
        assertTrue(same["a:x11908"] != same["b:y21908"])
    }
}
