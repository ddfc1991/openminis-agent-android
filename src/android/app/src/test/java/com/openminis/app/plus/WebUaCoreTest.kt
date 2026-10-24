package com.openminis.app.plus

import com.openminis.app.browser.WebUaCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [Mines-38.9.9] WebView UA without "; wv" / Version/x.y so Clerk-type SPAs serve the Chrome page. */
class WebUaCoreTest {
    @Test fun strips() {
        val wv = "Mozilla/5.0 (Linux; Android 14; RMX5062 Build/UKQ1.230924.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.100 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 14; RMX5062 Build/UKQ1.230924.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.100 Mobile Safari/537.36",
            WebUaCore.chromeLike(wv))
    }
    @Test fun keepsChrome() {
        val c = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
        assertEquals(c, WebUaCore.chromeLike(c)); assertNull(WebUaCore.chromeLike(null))
    }
}
