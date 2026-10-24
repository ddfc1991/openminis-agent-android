package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictWebCoreTest {
    @Test fun neverDirectWhenStrict() {
        assertEquals("127.0.0.1:7890", StrictWebCore.rule(true, 7890))
        assertEquals(StrictWebCore.BLOCK, StrictWebCore.rule(true, null))
        assertNull(StrictWebCore.rule(false, null))
        assertEquals("127.0.0.1:7899", StrictWebCore.rule(false, 7899))
    }

    @Test fun incognitoGoesToOwnProcessWhenItCannotBeProxied() {
        assertTrue(StrictWebCore.STRICT_INCOGNITO_DEFAULT)
        assertTrue(StrictWebCore.incognitoInProcess(true, true, null))
        assertFalse(StrictWebCore.incognitoInProcess(true, true, 7890))
        assertFalse(StrictWebCore.incognitoInProcess(true, false, null))
        assertTrue(StrictWebCore.incognitoInProcess(false, false, 7890))
    }

    @Test fun webRtcScriptRemovesPeerConnection() {
        listOf("RTCPeerConnection", "webkitRTCPeerConnection", "RTCDataChannel", "getUserMedia").forEach { assertTrue(StrictWebCore.WEBRTC_JS.contains(it)) }
    }
}
