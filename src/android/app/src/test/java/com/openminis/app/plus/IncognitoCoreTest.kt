package com.openminis.app.plus

import com.openminis.app.browser.IncognitoCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [Mines-38.9.7] 无痕标签 rules + wiring (separate profile, wiped on close, never handed over / recorded). */
class IncognitoCoreTest {
    private fun src(rel: String): String =
        listOf(File("src/main/java/com/openminis/app/$rel"), File("app/src/main/java/com/openminis/app/$rel")).first { it.exists() }.readText()

    @Test fun pathNeverSharesTheNormalJar() {
        // [38.9.11.2] profiles → PROFILE; no profiles on Android 9+ → own process; older → refused (nothing would isolate)
        assertEquals(IncognitoCore.Path.PROFILE, IncognitoCore.path(true, 26))
        assertEquals(IncognitoCore.Path.PROCESS, IncognitoCore.path(false, 28))
        assertEquals(IncognitoCore.Path.PROCESS, IncognitoCore.path(false, 34))
        assertEquals(IncognitoCore.Path.NONE, IncognitoCore.path(false, 27))
        assertEquals(IncognitoCore.Plan.Open, IncognitoCore.plan(false))
        assertTrue(IncognitoCore.selfCheck(false, null).second.contains("单独进程"))
        assertTrue(IncognitoCore.selfCheck(true, "21:00 已清除").second.contains("已清除"))
    }

    @Test fun freshProfilePerSessionAndLeftovers() {
        val a = IncognitoCore.newProfileName(1_700_000_000_000L, 42L)
        val b = IncognitoCore.newProfileName(1_700_000_000_001L, 42L)
        val c = IncognitoCore.newProfileName(1_700_000_000_000L, 43L)
        assertTrue(a.startsWith(IncognitoCore.PROFILE_PREFIX) && a != b && a != c)
        assertTrue(IncognitoCore.isIncognitoProfile(a) && IncognitoCore.isIncognitoProfile(IncognitoCore.PROFILE))
        assertFalse(IncognitoCore.isIncognitoProfile("Default")); assertFalse(IncognitoCore.isIncognitoProfile("github.com#2"))
        val left = IncognitoCore.leftovers(listOf("Default", "github.com#2", IncognitoCore.PROFILE, a, b), open = listOf(b))
        assertEquals(listOf(IncognitoCore.PROFILE, a), left)
    }

    @Test fun processAndPolicy() {
        assertTrue(IncognitoCore.isIncognitoProcess("com.openminis.mines.plus:incog", "com.openminis.mines.plus"))
        assertFalse(IncognitoCore.isIncognitoProcess("com.openminis.mines.plus", "com.openminis.mines.plus"))
        assertFalse(IncognitoCore.isIncognitoProcess(null, "com.openminis.mines.plus"))
        assertTrue(IncognitoCore.processDataDirs().all { it.endsWith("_" + IncognitoCore.DATA_SUFFIX) })
        assertTrue(IncognitoCore.processDataDirs().contains("app_webview_incog"))
        val p = IncognitoCore.POLICY
        assertTrue(p.noCache); assertFalse(p.saveFormData || p.autofill || p.thirdPartyCookies || p.geolocation || p.mediaPermissions || p.history)
        assertFalse(IncognitoCore.savesLogin(true, userTapped = false)); assertTrue(IncognitoCore.savesLogin(true, userTapped = true))
        assertTrue(IncognitoCore.savesLogin(false, userTapped = false))
    }

    @Test fun saveResultParsing() {
        assertEquals(null, IncognitoCore.parseSaveResult(null)); assertEquals(null, IncognitoCore.parseSaveResult("null"))
        val js = "\"{\\\"u\\\":\\\"me@x.cn\\\",\\\"p\\\":\\\"s3cr3t\\\",\\\"url\\\":\\\"https://x.cn/\\\"}\""
        assertEquals(Triple("https://x.cn/", "me@x.cn", "s3cr3t"), IncognitoCore.parseSaveResult(js))
        assertEquals(null, IncognitoCore.parseSaveResult("\"{\\\"u\\\":\\\"a\\\",\\\"p\\\":\\\"\\\"}\""))
    }

    @Test fun incognitoFeedsNothing() {
        assertFalse(IncognitoCore.recordsHistory(true)); assertFalse(IncognitoCore.feedsAccounts(true))
        assertFalse(IncognitoCore.handsOver(true)); assertFalse(IncognitoCore.persistsTabs(true))
        assertTrue(IncognitoCore.recordsHistory(false) && IncognitoCore.feedsAccounts(false) && IncognitoCore.persistsTabs(false))
        assertEquals("无痕 · 百度", IncognitoCore.title("百度", true)); assertEquals("百度", IncognitoCore.title("百度", false))
    }

    @Test fun wiring() {
        val pool = src("browser/BrowserTabPool.kt")
        assertTrue(pool.contains("if (incognito && !Incognito.attach(webView, incognitoProfile))"))
        assertTrue("profile must be set before PayCards touches the WebView",
            pool.indexOf("Incognito.attach(webView") < pool.indexOf("PayCards.install(webView)"))
        assertTrue("policy after the manager's own setup", pool.indexOf("Incognito.harden(webView") > pool.indexOf("BrowserUseManager(\n"))
        assertTrue(pool.contains("IncognitoCore.persistsTabs(incognito)"))
        val mgr = src("browser/BrowserUseManager.kt")
        assertTrue(mgr.contains("IncognitoCore.feedsAccounts(incognito)") && mgr.contains("IncognitoCore.recordsHistory(incognito)"))
        val inc = src("browser/Incognito.kt")
        assertTrue(inc.contains("removeAllCookies") && inc.contains("deleteAllData") && inc.contains("clearHistory") && inc.contains("deleteProfile"))
        assertTrue(inc.contains("LOAD_NO_CACHE") && inc.contains("saveFormData") && inc.contains("IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS"))
        assertTrue(inc.contains("setWebContentsDebuggingEnabled(com.openminis.app.BuildConfig.DEBUG)"))
        assertFalse("never the snapshot / clear / restore trick on the shared jar", inc.contains("CookieManager.getInstance().removeAllCookies"))
        val act = src("browser/IncognitoActivity.kt")
        assertTrue(act.contains("removeAllCookies") && act.contains("WebStorage.getInstance().deleteAllData()") && act.contains("destroy()")
            && act.contains("killProcess") && act.contains("callback?.invoke(origin, false, false)") && act.contains("request?.deny()"))
        val proc = src("browser/IncognitoProcess.kt")
        assertTrue(proc.indexOf("setDataDirectorySuffix") in 0 until proc.indexOf("wipeDirs(app)"))
        val app = src("MinisApp.kt")
        assertTrue("the :incog check is the first thing after super.onCreate",
            app.indexOf("IncognitoProcess.onAppCreate(this)) return") in app.indexOf("super.onCreate()") until app.indexOf("SplitProxy.init(this)"))
        val home = src("ui/sessions/SessionListScreen.kt")
        assertTrue("home browser must be the shared pool", home.contains("sharedBrowserTabPool"))
        assertFalse(home.contains("remember { com.openminis.app.browser.BrowserTabPool(context) }"))
        assertTrue(home.contains("Incognito.open(context") && home.contains("Incognito.close("))
        val sheet = src("ui/browser/BrowserSheet.kt")
        assertTrue(sheet.contains("BrowserHandoff.noteUser(") && sheet.contains("tabPool.incognito"))
        assertTrue(sheet.contains("IncognitoCore.CLEAR_ACTION") && sheet.contains("IncognitoCore.INDICATOR") && sheet.contains("IncognitoCore.SAVE_ACTION"))
        assertTrue(src("browser/BrowserHandoff.kt").contains("if (incognito) return"))
    }
}
