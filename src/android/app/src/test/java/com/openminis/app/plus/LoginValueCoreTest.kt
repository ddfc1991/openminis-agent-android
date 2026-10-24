package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginValueCoreTest {
    @Test fun decodesEvaluateJavascriptString() {
        assertEquals("a\u0001b\u0001c", LoginValueCore.decodeJs("\"a\\u0001b\\u0001c\""))
        assertEquals("", LoginValueCore.decodeJs("null"))
        assertEquals("he said \"hi\"\n", LoginValueCore.decodeJs("\"he said \\\"hi\\\"\\n\""))
        assertEquals("blank", LoginValueCore.decodeJs("\"blank\""))
    }

    @Test fun separatorBugValuesAreRejected() {
        // what 38.9.5 saved for a Memoh web sign-in with nothing typed
        assertEquals("", LoginValueCore.cleanAccount("\\u0001\\u0001"))
        assertEquals("", LoginValueCore.cleanAccount("\u0001\u0001"))
        assertTrue(LoginValueCore.badAccount("\\u0001\\u0001"))
        assertFalse(LoginValueCore.badAccount("me@163.com"))
        assertEquals("me@163.com", LoginValueCore.cleanAccount(" me@163.com "))
    }

    @Test fun parsesRealAndEscapedSeparators() {
        val a = LoginValueCore.parseAccount("u@x.com\u0001\u0001p@ss")
        assertEquals("u@x.com", a.user); assertEquals("", a.token); assertEquals("p@ss", a.pass)
        val b = LoginValueCore.parseAccount("\\u0001eyJa.b.c\\u0001")
        assertEquals("", b.user); assertEquals("eyJa.b.c", b.token); assertEquals("", b.pass)
        assertEquals("", LoginValueCore.parseAccount("").user)
        assertFalse(a.toString().contains("p@ss"))
    }

    @Test fun hostPrefixedCookiesStayHostOnly() {
        val l = LoginValueCore.cookieLine("__Host-session", "v", "memoh.net", true, 100)
        assertFalse(l.contains("Domain"))
        assertTrue(l.contains("Secure")); assertTrue(l.contains("Path=/"))
        assertTrue(LoginValueCore.cookieLine("sid", "v", "memoh.net", true, 100).contains("Domain=.memoh.net"))
        assertTrue(LoginValueCore.cookieLine("__Secure-x", "v", "memoh.net", false, 100).contains("Secure"))
        assertFalse(LoginValueCore.cookieLine("sid", "v", "10.0.0.2", false, 100).contains("Domain"))
        assertEquals(1, LoginValueCore.deleteLines("__Host-s", "memoh.net", false).size)
        assertTrue(LoginValueCore.deleteLines("__Host-s", "memoh.net", false).first().contains("Secure"))
        assertEquals(3, LoginValueCore.deleteLines("sid", "memoh.net", false).size)
    }
}
