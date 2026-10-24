package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WsLinkCoreTest {
    @Test fun schemeFollowsPort() {
        assertEquals("wss://38.76.209.77:2053/secret", WsLinkCore.fixScheme("ws://38.76.209.77:2053/secret"))
        assertEquals("wss://38.76.209.77:2053/secret", WsLinkCore.fixScheme("wss://38.76.209.77:2053/secret"))
        assertEquals("ws://38.76.209.77:8880/secret", WsLinkCore.fixScheme("wss://38.76.209.77:8880/secret"))
        assertEquals("ws://8.217.89.133:8880/", WsLinkCore.fixScheme("ws://8.217.89.133:8880/"))
        assertEquals("wss://8.217.89.133:2053", WsLinkCore.fixScheme("ws://8.217.89.133:2053"))
        // other ports / non-WS relays untouched
        assertEquals("ws://10.0.2.2:8022/", WsLinkCore.fixScheme("ws://10.0.2.2:8022/"))
        assertEquals("wss://tunnel.example.com/", WsLinkCore.fixScheme("wss://tunnel.example.com/"))
        assertEquals("socks5://127.0.0.1:1080", WsLinkCore.fixScheme("socks5://127.0.0.1:1080"))
    }

    @Test fun awsHasNoWsBridge() {
        assertFalse(WsLinkCore.hasWs("18.143.139.29"))
        assertTrue(WsLinkCore.hasWs("38.76.209.77"))
        assertEquals("", WsLinkCore.relayFor("18.143.139.29", "ws://18.143.139.29:2053/x"))
        assertEquals("ssh://root@8.217.89.133:22", WsLinkCore.relayFor("18.143.139.29", "ssh://root@8.217.89.133:22"))
        assertEquals("wss://38.76.209.77:2053/x", WsLinkCore.relayFor("38.76.209.77", "ws://38.76.209.77:2053/x"))
        assertEquals("", WsLinkCore.relayFor("38.76.209.77", ""))
    }
}
