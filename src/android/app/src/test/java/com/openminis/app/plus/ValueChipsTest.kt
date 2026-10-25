package com.openminis.app.plus

import com.openminis.app.plus.ValueChips.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.5 i] copy chips + 引用回复 (user 2026-10-17 22:23). */
class ValueChipsTest {
    private fun kinds(t: String) = ValueChips.detect(t).map { it.kind to it.value }

    @Test fun urlTrimsTrailingPunctuation() {
        assertEquals(listOf(Kind.URL to "https://github.com/ddfc1991/x"), kinds("看这里：https://github.com/ddfc1991/x。"))
    }

    @Test fun apiBaseIsSeparateKind() {
        val k = kinds("接口地址 http://8.217.89.133:8787/v1 和 https://ws-abc.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
        assertEquals(listOf(Kind.API, Kind.API), k.map { it.first })
        assertEquals("http://8.217.89.133:8787/v1", k[0].second)
    }

    @Test fun ipPortNotInsideUrl() {
        val k = kinds("服务器 38.76.209.77:8090 已启动，地址 http://38.76.209.77:8090/m3894/a.apk")
        assertEquals(Kind.IP to "38.76.209.77:8090", k[0])
        assertEquals(Kind.URL, k[1].first)
        assertEquals(2, k.size)
    }

    @Test fun invalidIpAndVersionsIgnored() {
        assertTrue(kinds("版本 1.23.5 和 300.1.1.1").none { it.first == Kind.IP })
    }

    @Test fun emailAndAccount() {
        val k = kinds("邮箱 smoke@example.com，账号：demo_user")
        assertEquals(listOf(Kind.EMAIL to "smoke@example.com", Kind.ACCOUNT to "demo_user"), k)
    }

    @Test fun phoneNumbers() {
        assertEquals(listOf(Kind.PHONE to "13690688658"), kinds("手机 136 9068 8658 收到"))
        assertEquals(Kind.PHONE, kinds("call +65 6123 4567")[0].first)
    }

    @Test fun codesAndOrders() {
        assertEquals(listOf(Kind.CODE to "482913"), kinds("你的验证码是 482913，5 分钟内有效"))
        assertEquals(listOf(Kind.ORDER to "SF1234567890"), kinds("运单号：SF1234567890"))
        assertTrue(kinds("code: abcdef").isEmpty())   // no digit → not a code
    }

    @Test fun paths() {
        val k = kinds("APK 在 /workspace/work/apk/mines-38.9.5.apk ，配置 ~/.dsh/auth.json")
        assertEquals(listOf(Kind.PATH to "/workspace/work/apk/mines-38.9.5.apk", Kind.PATH to "~/.dsh/auth.json"), k)
        assertTrue(kinds("比例 1/2").isEmpty())
    }

    @Test fun secretsMaskedAndNeverCopyable() {
        val t = "key sk-ws-AbCdEf0123456789xyzQW 和 ghp_abcdefghijklmnopqrstuvwxyz0123 密码：Hunter2!x"
        val chips = ValueChips.detect(t)
        assertEquals(3, chips.size)
        chips.forEach {
            assertEquals(Kind.SECRET, it.kind)
            assertFalse(it.copyable)
            assertEquals("", it.value)
            assertFalse(it.display.contains("0123"))
            assertFalse(it.display.contains("Hunter"))
        }
        assertEquals("sk-ws-••••••", chips[0].display)
    }

    @Test fun secretInsideUrlWinsOverUrl() {
        val chips = ValueChips.detect("https://api.example.com/v1?token=sk-AbCdEf0123456789xyzQW")
        assertTrue(chips.any { it.kind == Kind.SECRET })
        assertTrue(chips.none { it.copyable && it.value.contains("sk-AbCd") })
    }

    @Test fun jwtIsSecret() {
        val c = ValueChips.detect("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.abcdefghijk")
        assertEquals(Kind.SECRET, c.single().kind)
    }

    @Test fun dedupeAndCap() {
        val t = (1..20).joinToString(" ") { "https://a.example.com/p$it" } + " https://a.example.com/p1"
        val c = ValueChips.detect(t)
        assertEquals(ValueChips.MAX_CHIPS, c.size)
        assertEquals(c.size, c.map { it.value }.toSet().size)
    }

    @Test fun longValueEllipsizedButCopiesWhole() {
        val u = "https://example.com/" + "a".repeat(80)
        val c = ValueChips.detect(u).single()
        assertEquals(u, c.value)
        assertTrue(c.display.length <= 28)
        assertTrue(c.display.contains("…"))
    }

    @Test fun quoteCompose() {
        assertEquals("> 第一行\n>\n> 第二行\n\n好的", QuoteReply.compose("第一行\n\n第二行", "好的"))
        assertEquals("你好", QuoteReply.compose(null, "你好"))
        assertEquals("> 只引用", QuoteReply.compose("只引用", ""))
        assertEquals("a b c", QuoteReply.preview("a\n b   c"))
        // [Mines-38.9.14.1] 回复: a short reference, not the full quote
        assertEquals("（回复 Mines：「第一行 第二行」）\n好的", QuoteReply.composeReply("Mines", "第一行\n第二行", "好的"))
        assertEquals("好的", QuoteReply.composeReply("Mines", "  ", "好的"))
        assertEquals("（回复 Mines：「x」）", QuoteReply.composeReply("Mines", "x", ""))
        assertTrue(QuoteReply.preview("x".repeat(100)).length == 60)
    }
}
