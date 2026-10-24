package com.openminis.app.plus

import com.openminis.app.browser.ConvProfilesCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.11.2] 每个对话的隔离，账号隔离. */
class ConvProfilesCoreTest {
    @Test fun oneProfilePerConversation() {
        assertFalse(ConvProfilesCore.isConversation(null)); assertFalse(ConvProfilesCore.isConversation("minis-browser-use"))
        assertFalse(ConvProfilesCore.isConversation("smoke397-1")); assertTrue(ConvProfilesCore.isConversation("3f2a-uuid"))
        val a = ConvProfilesCore.profileName("8b1e6c2a-1111-2222-3333-444455556666")
        val b = ConvProfilesCore.profileName("8b1e6c2a-1111-2222-3333-444455556667")
        assertTrue(a.startsWith(ConvProfilesCore.PREFIX) && a != b)
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]+")))
        val long = ConvProfilesCore.profileName("x".repeat(200))
        assertTrue(long.length < 80 && long != ConvProfilesCore.profileName("x".repeat(201)))
        assertEquals(listOf("mines-conv-old"), ConvProfilesCore.orphans(listOf("Default", "mines-conv-a", "mines-conv-old", "mines-incog-x"), listOf("mines-conv-a")))
    }

    @Test fun accountsBoundPerConversation() {
        var b = mapOf<String, String>()
        assertTrue("home browser unchanged", ConvProfilesCore.mayAutoFill(b, null, "x.cn", "me"))
        assertFalse("not picked here", ConvProfilesCore.mayAutoFill(b, "convA", "x.cn", "me"))
        b = b + (ConvProfilesCore.bindKey("convA", "www.X.cn") to "me")
        assertTrue(ConvProfilesCore.mayAutoFill(b, "convA", "x.cn", "ME"))
        assertFalse("another conversation", ConvProfilesCore.mayAutoFill(b, "convB", "x.cn", "me"))
        assertFalse("another account", ConvProfilesCore.mayAutoFill(b, "convA", "x.cn", "other"))
        val r = ConvProfilesCore.renameSession(b, "convA", "real1")
        assertTrue(ConvProfilesCore.mayAutoFill(r, "real1", "x.cn", "me")); assertFalse(ConvProfilesCore.mayAutoFill(r, "convA", "x.cn", "me"))
        assertTrue(ConvProfilesCore.dropSession(r, "real1").isEmpty())
    }

    @Test fun wiring() {
        fun src(rel: String) = listOf(File("src/main/java/com/openminis/app/$rel"), File("app/src/main/java/com/openminis/app/$rel")).first { it.exists() }.readText()
        val pool = src("browser/BrowserTabPool.kt")
        assertTrue(pool.contains("ConvProfiles.profileFor(context, sessionId)") && pool.indexOf("ConvProfiles.profileFor") < pool.indexOf("PayCards.install(webView)"))
        assertTrue(src("data/repository/ChatRepository.kt").contains("ConvProfiles.forget(id)"))
        assertTrue(src("ui/chat/ChatViewModel.kt").contains("ConvProfiles.rename(sessionId, session.id)"))
        // [38.9.11.3] 台账 accounts fill in any conversation (LedgerCore ranking), then bind to it
        assertTrue(src("plus/AgentLogin.kt").contains("Ledger.choose(ctx, host, sessionId") && src("plus/AgentLogin.kt").contains("ConvProfiles.bind(sessionId"))
        assertTrue(src("MainActivity.kt").contains("LedgerChipHost()") && src("plus/AccountsTool.kt").contains("\"ledger\" ->"))
    }
}
