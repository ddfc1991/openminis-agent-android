package com.openminis.app.plus

/**
 * [Mines-38.9.11.3] user: the sandbox / the agent's 无痕 browser must never expose the real IP.
 *  - 严格代理 on (or a 无痕 session — strict by default): the WebView proxy override is the passing proxy, or a closed port
 *    ([BLOCK]) when none passes — never cleared to direct. An HTTP proxy rule means Chromium sends the host name in CONNECT,
 *    so DNS is resolved by the proxy, not the phone.
 *  - WebRTC cannot reveal local / public IPs: [WEBRTC_JS] (document start) removes RTCPeerConnection & co.
 * Pure, unit-tested (StrictWebCoreTest).
 */
object StrictWebCore {
    const val BLOCK = "127.0.0.1:1"
    /** 无痕 sessions use 严格代理 unless the user turns it off */
    const val STRICT_INCOGNITO_DEFAULT = true

    /** the WebView proxy rule: a passing port; strict and none → [BLOCK]; else null = no override (direct) */
    fun rule(strict: Boolean, port: Int?): String? = when {
        port != null -> "127.0.0.1:$port"
        strict -> BLOCK
        else -> null
    }

    /** a 无痕 session in the main process cannot have its own proxy: with strict and no passing proxy it opens in the
     *  separate :incog process, whose own override can block */
    fun incognitoInProcess(profileSupported: Boolean, strictIncognito: Boolean, port: Int?): Boolean =
        !profileSupported || (strictIncognito && port == null)

    const val WEBRTC_JS = "(function(){try{var n=['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection','RTCDataChannel','RTCIceCandidate','RTCSessionDescription'];" +
        "n.forEach(function(k){try{Object.defineProperty(window,k,{value:undefined,writable:false,configurable:false});}catch(e){try{window[k]=undefined;}catch(e2){}}});" +
        "if(navigator.mediaDevices){try{navigator.mediaDevices.getUserMedia=function(){return Promise.reject(new Error('blocked'));};}catch(e){}}}catch(e){}})();"
}
