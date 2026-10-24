package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.5 i] login-based model routes: relay base, reasons, status rows. */
class RouteStatusCoreTest {
    private val C = RouteStatusCore
    @Test fun relayBaseAlwaysV1() {
        assertEquals("http://8.217.89.133:8787/v1", C.relayBase("http://8.217.89.133:8787"))
        assertEquals("http://8.217.89.133:8787/v1", C.relayBase("8.217.89.133:8787/v1/"))
        assertEquals("https://r.example/v1", C.relayBase("https://r.example/v1/chat/completions"))
        assertEquals("https://r.example/v1", C.relayBase("https://r.example/v1/auth/verify"))
        assertEquals("", C.relayBase(" "))
    }
    @Test fun reasons() {
        assertTrue(C.httpReason(502, "", "newapi.ydn44086.dpdns.org").contains("new-api 没在跑"))
        assertEquals("服务器暂无响应（502）", C.httpReason(502, "", "api.openai.com"))
        assertEquals("密钥失效（bad_key）：重新登录一次", C.httpReason(401, """{"error":"bad_key"}"""))
        assertEquals("额度用完了", C.httpReason(402, """{"error":{"code":"out_of_tokens"}}"""))
        assertEquals("登录过期或密钥无效（401）", C.httpReason(401))
        assertTrue(C.selfHostedGateway("http://8.217.89.133:8787/v1"))
    }
    @Test fun rows() {
        assertEquals(RouteStatusCore.St.OK, C.fromPartner("nanomuse", "Muse 中继", "中继", "READY", null, true).st)
        val r = C.fromPartner("coze", "扣子", "扣子", "NEED_KEY", "扣子令牌过期了", true)
        assertEquals(RouteStatusCore.St.RELOGIN, r.st); assertTrue(r.canAutoRelogin)
        assertEquals(RouteStatusCore.St.DOWN, C.fromPartner("x", "x", "", "UNREACHABLE", "中继没有响应", false).st)
        assertEquals("扣子令牌过期了：一键重新登录会自动拿新的", C.cozeReason(4100))
    }

    @Test fun bailianWorkspaceKeyGoesToMaas() {
        val C = RouteStatusCore
        assertEquals(C.BAILIAN_WS_DEFAULT, C.bailianBase("sk-ws-abcdEFGH12345678", null))
        assertEquals(C.BAILIAN_WS_DEFAULT, C.bailianBase("sk-ws-abcdEFGH12345678", C.BAILIAN_LEGACY))
        assertEquals("https://ws-x.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", C.bailianBase("sk-ws-abcdEFGH12345678", null, "https://ws-x.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/"))
        assertEquals(C.BAILIAN_LEGACY, C.bailianBase("sk-0123456789abcdef0123456789abcdef", null))
        assertEquals("https://ws-y.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", C.bailianBase("sk-ws-zz", "https://ws-y.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"))
        assertTrue(C.isDashScopeHost(C.BAILIAN_WS_DEFAULT)); assertTrue(C.isDashScopeHost(C.BAILIAN_LEGACY)); assertFalse(C.isDashScopeHost("https://api.openai.com/v1"))
    }
}
