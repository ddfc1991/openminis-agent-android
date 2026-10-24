package com.openminis.app.plus

import com.openminis.app.provider.openai.ConnRetry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** [Mines-38.9.9] 「connection closed」 → one retry on a fresh HTTP/1.1 connection + a Chinese reason. */
class ConnRetryTest {
    @Test fun dropped() {
        assertTrue(ConnRetry.isDropped(IOException("connection closed")))
        assertTrue(ConnRetry.isDroppedText("java.io.IOException: unexpected end of stream on https://x/..."))
        assertTrue(ConnRetry.isDroppedText("StreamResetException: stream was reset: PROTOCOL_ERROR"))
        assertFalse(ConnRetry.isDroppedText("SocketTimeoutException: timeout"))
        assertFalse(ConnRetry.isDroppedText("UnknownHostException: x"))
    }
    @Test fun chinese() {
        assertTrue(ConnRetry.zh(IOException("connection closed")).contains("中途断开"))
        assertTrue(ConnRetry.zhText("stream was reset: CANCEL").contains("HTTP/2"))
        assertTrue(ConnRetry.zhText("unexpected end of stream").contains("没回完"))
    }
    @Test fun rememberHost() {
        assertFalse(ConnRetry.useH1("relay-test.example"))
        ConnRetry.rememberH1("Relay-Test.example")
        assertTrue(ConnRetry.useH1("relay-test.example"))
    }
}
