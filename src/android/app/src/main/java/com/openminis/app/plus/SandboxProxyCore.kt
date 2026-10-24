package com.openminis.app.plus

/**
 * [Mines-38.9.11.2] which way sandbox traffic leaves (user 2026-10-21 自检: with 分流代理 off the passing local proxy never
 * reached the sandbox, so curl / pip / npm left through the phone's own IP). Pure, unit-tested (SandboxProxyCoreTest).
 * Order: 分流代理 (when listening) › a local proxy port that passed a REAL outbound request (LocalProxy: generate_204 through
 * the port; a port that only listens is never "best") › a reachable system proxy › 严格代理 on → BLOCK › direct.
 */
object SandboxProxyCore {
    enum class Way { SPLIT, LOCAL, SYSTEM, BLOCK, DIRECT }
    /** a closed local port: proxy-aware tools fail at once instead of leaving through the phone's own IP */
    const val BLOCK_URL = "http://127.0.0.1:1"
    const val BLOCK_MSG = "严格代理已开：现在没有能出网的代理，沙箱网络已暂停（不会用手机自己的网络直连）。打开 Clash 或自建保底后会自动恢复。"

    fun choose(splitReady: Boolean, localBest: Int?, systemConfigured: Boolean, systemReachable: Boolean, strict: Boolean): Way = when {
        splitReady -> Way.SPLIT
        localBest != null -> Way.LOCAL
        systemConfigured && systemReachable -> Way.SYSTEM
        strict -> Way.BLOCK
        else -> Way.DIRECT
    }
}
