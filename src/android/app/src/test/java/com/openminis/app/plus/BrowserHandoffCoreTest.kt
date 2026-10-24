package com.openminis.app.plus

import com.openminis.app.browser.BrowserHandoffCore
import com.openminis.app.browser.BrowserHandoffCore.TabLite
import com.openminis.app.browser.BrowserHandoffCore.UserPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.7] agent takeover drives the user's tab / page with the same session. */
class BrowserHandoffCoreTest {
    @Test fun agentTakesTheUsersTab() {
        val tabs = listOf(TabLite(0, false, true), TabLite(3, false, true))
        assertEquals(3, BrowserHandoffCore.pickAgentTab(3, tabs))          // was: tab 0
        assertEquals(0, BrowserHandoffCore.pickAgentTab(3, listOf(TabLite(0, false, true), TabLite(3, true, true))))
        assertEquals(0, BrowserHandoffCore.pickAgentTab(null, tabs))
        assertNull(BrowserHandoffCore.pickAgentTab(3, listOf(TabLite(3, false, false))))
    }

    @Test fun adoptOnlyFreshForeignNonIncognito() {
        val now = 10_000_000L
        val p = UserPage("https://console.example.com/home", false, now - 60_000, "{\"tok\":\"x\"}", poolKey = 1)
        assertTrue(BrowserHandoffCore.adopt(p, now, poolHasTabs = false, poolKey = 2))
        assertFalse(BrowserHandoffCore.adopt(p, now, poolHasTabs = true, poolKey = 2))
        assertFalse(BrowserHandoffCore.adopt(p, now, false, 1))   // same pool: its own tab is used
        assertFalse(BrowserHandoffCore.adopt(p.copy(at = now - 21 * 60_000L), now, false, 2))
        assertFalse(BrowserHandoffCore.adopt(p.copy(incognito = true), now, false, 2))
        assertEquals("https://console.example.com", p.origin)
    }

    @Test fun sessionStorageRestore() {
        assertNull(BrowserHandoffCore.usableDump("{}")); assertNull(BrowserHandoffCore.usableDump("")); assertNull(BrowserHandoffCore.usableDump("nope"))
        val d = BrowserHandoffCore.usableDump("{\"tok\":\"abc\"}")!!
        val js = BrowserHandoffCore.restoreSessionJs("https://a.example", d)
        assertTrue(js.contains("location.origin!==\"https://a.example\"")); assertTrue(js.contains("\"tok\":\"abc\"")); assertTrue(js.contains("__mh_ho"))
    }
}
