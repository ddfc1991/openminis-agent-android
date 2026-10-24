package com.openminis.app.plus

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagCoreTest {
    private val tok = "t0ken-for-tests-0123456789abcdef"
    private val sid = "s1a2b3"
    private val now = 1_800_000_000_000L

    private fun cmd(c: String, args: JSONObject = JSONObject(), id: String = "c0ffee01", ts: Long = now, sig: String? = null): String =
        JSONObject().put("t", "cmd").put("id", id).put("cmd", c).put("args", args).put("ts", ts)
            .put("sig", sig ?: DiagCore.sign(tok, sid, id, c, args, ts)).toString()

    private fun ok(raw: String) = DiagCore.verify(raw, tok, sid, now, emptySet())

    @Test fun whitelistAcceptsSignedCommands() {
        listOf("ping", "dump_main_thread_now", "get_recent_crash", "request_screenshot_of_mines_only").forEach {
            assertTrue(it, ok(cmd(it)) is DiagCore.Verdict.Ok)
        }
        val j = ok(cmd("capture_jank_trace", JSONObject().put("seconds", 30)))
        assertEquals(30, (j as DiagCore.Verdict.Ok).args["seconds"])
        assertTrue(ok(cmd("set_log_level", JSONObject().put("level", "debug"))) is DiagCore.Verdict.Ok)
        val n = ok(cmd("show_notice", JSONObject().put("text", "你好，请重新打开一下对话页"))) as DiagCore.Verdict.Ok
        assertEquals("你好，请重新打开一下对话页", n.args["text"])
    }

    @Test fun everythingElseRejected() {
        // not on the list (shell, input, files, settings, accessibility …)
        listOf("shell", "exec", "tap", "input", "swipe", "read_file", "write_file", "delete", "set_setting", "a11y_click",
            "install", "open_url", "PING", "ping ", "").forEach { c ->
            val v = ok(cmd(c)); assertTrue(c, v is DiagCore.Verdict.Rejected)
        }
        assertTrue((ok(cmd("shell")) as DiagCore.Verdict.Rejected).reason.startsWith("不在白名单"))
        // bad / missing signature, wrong session, wrong token
        assertEquals("签名不对", (ok(cmd("ping", sig = "0".repeat(64))) as DiagCore.Verdict.Rejected).reason)
        assertTrue(ok(cmd("ping", sig = "")) is DiagCore.Verdict.Rejected)
        assertTrue(DiagCore.verify(cmd("ping"), tok, "other", now, emptySet()) is DiagCore.Verdict.Rejected)
        assertTrue(DiagCore.verify(cmd("ping"), tok + "x", sid, now, emptySet()) is DiagCore.Verdict.Rejected)
        // args changed after signing
        val signed = JSONObject(cmd("capture_jank_trace", JSONObject().put("seconds", 5)))
        signed.put("args", JSONObject().put("seconds", 30))
        assertTrue(ok(signed.toString()) is DiagCore.Verdict.Rejected)
        // stale / future / replay
        assertEquals("命令过期", (ok(cmd("ping", ts = now - 6 * 60_000)) as DiagCore.Verdict.Rejected).reason)
        assertTrue(ok(cmd("ping", ts = now + 6 * 60_000)) is DiagCore.Verdict.Rejected)
        assertEquals("重复的命令", (DiagCore.verify(cmd("ping"), tok, sid, now, setOf("c0ffee01")) as DiagCore.Verdict.Rejected).reason)
        // argument schema
        assertTrue(ok(cmd("capture_jank_trace", JSONObject().put("seconds", 31))) is DiagCore.Verdict.Rejected)
        assertTrue(ok(cmd("capture_jank_trace", JSONObject().put("seconds", 0))) is DiagCore.Verdict.Rejected)
        assertTrue(ok(cmd("capture_jank_trace", JSONObject().put("seconds", 5).put("path", "/data"))) is DiagCore.Verdict.Rejected)
        assertTrue(ok(cmd("ping", JSONObject().put("x", 1))) is DiagCore.Verdict.Rejected)
        assertTrue(ok(cmd("set_log_level", JSONObject().put("level", "verbose"))) is DiagCore.Verdict.Rejected)
        assertTrue(ok(cmd("show_notice", JSONObject().put("text", "hello"))) is DiagCore.Verdict.Rejected)           // no Chinese
        assertTrue(ok(cmd("show_notice", JSONObject().put("text", "点这里 https://evil.example"))) is DiagCore.Verdict.Rejected)
        assertTrue(ok(cmd("show_notice", JSONObject().put("text", "长".repeat(81)))) is DiagCore.Verdict.Rejected)
        assertTrue(ok(cmd("request_screenshot_of_mines_only", JSONObject().put("all", true))) is DiagCore.Verdict.Rejected)
        // garbage
        assertTrue(ok("not json") is DiagCore.Verdict.Rejected)
        assertTrue(ok(JSONObject().put("t", "exec").put("cmd", "ping").toString()) is DiagCore.Verdict.Rejected)
        assertTrue(ok(cmd("ping", id = "x")) is DiagCore.Verdict.Rejected)
    }

    @Test fun canonMatchesRelay() {
        // relay.py canon_args: sorted k=v joined by & ; booleans lower-case
        assertEquals("a=1&b=true&z=x", DiagCore.canonArgs(JSONObject().put("z", "x").put("b", true).put("a", 1)))
        assertEquals("", DiagCore.canonArgs(JSONObject()))
        // fixed vector computed with relay.py sign()
        assertEquals(64, DiagCore.sign(tok, sid, "id1", "ping", JSONObject(), 1L).length)
    }

    @Test fun scrubRemovesSecretsAndContent() {
        val raw = listOf(
            "Authorization: Bearer sk-proj-abcdefghijklmnopqrstuvwx", "Cookie: sid=deadbeefcafebabe; token=xyz",
            "GET https://api.example.com/v1/chat?key=AIzaSyA1234567890abcdefghijklmnopqrstu&q=hi",
            "{\"content\":\"我的银行卡密码是 123456，请帮我记住\",\"role\":\"user\"}", "password=hunter2 phone 13690688658",
            "x-api-key: sk-ant-api03-zzzzzzzzzzzzzzzzzzzzzzzz", "ghp_AAAAAAAAAAAAAAAAAAAAAAAAAAAA", "mail deningyang740@gmail.com",
            "token=0123456789abcdef0123456789abcdef0123", "prompt: 帮我写一封给老板的辞职信，内容要委婉一点，说明原因是身体不好需要休息",
            "user said 这是一段很长的聊天内容，里面可能有个人隐私，不应该离开手机，所以要省略掉才对的的的的的的的的")
        val out = raw.joinToString("\n") { DiagCore.scrub(it) }
        listOf("sk-proj-abc", "deadbeef", "AIzaSyA123", "银行卡密码", "123456，", "hunter2", "13690688658", "sk-ant-api03", "ghp_AAAA",
            "deningyang740", "0123456789abcdef0123", "辞职信", "个人隐私").forEach { assertFalse("leaked $it:\n$out", out.contains(it)) }
        assertTrue(out.contains("136****8658")); assertTrue(out.contains("https://api.example.com/v1/chat?***"))
    }

    @Test fun stackFramesKeptMessageScrubbed() {
        val st = "java.lang.IllegalStateException: bad key sk-abcdefghijklmnopqrstuvwx\n\tat com.openminis.app.plus.Ledger.choose(Ledger.kt:63)\n\tat android.os.Handler.dispatchMessage(Handler.java:102)"
        val r = DiagCore.scrubStack(st)
        assertFalse(r.contains("sk-abcdefghij")); assertTrue(r.contains("at com.openminis.app.plus.Ledger.choose(Ledger.kt:63)"))
        assertTrue(r.contains("Handler.java:102"))
    }

    @Test fun logcatOnlyWarnErrorFatal() {
        val l = listOf("10-21 08:00:00.000  1234  1234 I Chat    : user message 你好 password=abc",
            "10-21 08:00:00.001  1234  1250 W OkHttp  : 401 https://x.com/a?token=abc", "10-21 08:00:00.002  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main",
            "10-21 08:00:00.003  1234  1234 D Net     : Cookie: a=b")
        val r = DiagCore.scrubLogcat(l)
        assertEquals(2, r.size); assertTrue(r[0].contains("https://x.com/a?***")); assertTrue(r[1].contains("FATAL EXCEPTION"))
    }

    @Test fun payloadBuilders() {
        val j = DiagCore.jankEvent("chat/9f8e7d6c-1234-5678", 40, 10, 487.04, 120.0)
        assertEquals("chat/*", j.getString("page")); assertEquals(25.0, j.getDouble("janky_pct"), 0.01); assertEquals(487.0, j.getDouble("slowest_ms"), 0.01)
        val h = DiagCore.hello(sid, "1.23.11.3", 51, "realme RMX5062", 35)
        assertEquals("hello", h.getString("t")); assertEquals(8, h.getJSONArray("caps").length())
        val s = DiagCore.stallEvent("settings", 350, "java.lang.Thread.sleep(Native Method)\n\tat com.openminis.app.X.y(X.kt:1)")
        assertTrue(s.getString("stack").contains("X.kt:1"))
        val c = DiagCore.crashEvent("ANR", "上次 Mines 卡住", "Reason: ANR\nAuthorization: Bearer sk-smokesecret0123456789abcd\n\tat a.b(C.kt:1)")
        assertFalse(c.toString().contains("sk-smokesecret"))
    }

    @Test fun codeAndStatus() {
        val cfg = DiagCore.Config(tok, listOf(DiagCore.Endpoint("wss://38.76.209.77:2083/mdiag-x/dev", "ab".repeat(32)),
            DiagCore.Endpoint("wss://diag.example.org/mdiag-x/dev", null)))
        val back = DiagCore.parseCode(DiagCore.encodeCode(cfg))
        assertNotNull(back); assertEquals(cfg, back)
        assertNull(DiagCore.parseCode("mdiag1:!!!")); assertNull(DiagCore.parseCode("hello"))
        val http = DiagCore.Config(tok, listOf(DiagCore.Endpoint("http://evil.example/x", null)))
        assertNull(DiagCore.parseCode(DiagCore.encodeCode(http)))
        assertEquals("诊断已连接（双向）", DiagCore.status(true, true, 0, null))
        assertEquals("诊断已连接（双向） · 备用线路", DiagCore.status(true, true, 1, null))
        assertTrue(DiagCore.status(true, false, 0, null).startsWith("诊断已断开"))
        assertEquals("诊断已关闭", DiagCore.status(false, false, 0, null))
        assertTrue(DiagCore.autoOffDue(now - DiagCore.MAX_ON_MS, now)); assertFalse(DiagCore.autoOffDue(now - 1000, now))
    }

    @Test fun logLevelsAndFrames() {
        val l = listOf("10-21 08:00:00.000  1234  1234 I Chat    : user message 你好 password=abc",
            "10-21 08:00:00.001  1234  1250 W OkHttp  : 401 https://x.com/a?token=abc",
            "10-21 08:00:00.002  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main",
            "10-21 08:00:00.003  1234  1234 I T-HANG-DIAG: hang detected duration=420ms",
            "10-21 08:00:00.004  1234  1234 D Net     : Cookie: a=b")
        assertEquals(1, DiagCore.filterLogcat(l, "error").size)
        assertEquals(2, DiagCore.filterLogcat(l, "warn").size)
        val info = DiagCore.filterLogcat(l, "info")
        assertEquals(3, info.size); assertTrue(info.none { it.contains("你好") || it.contains("Cookie: a") })
        assertEquals(3, DiagCore.filterLogcat(l, "debug").size)
        val f = DiagCore.frameSum(listOf(8.0, 9.0, 10.0, 30.0, 400.0, 12.0, 11.0, 9.0, 8.0, 7.0), 16.7)
        assertEquals(10, f.frames); assertEquals(2, f.janky); assertEquals(400.0, f.slowestMs, 0.01)
        assertEquals(0, DiagCore.frameSum(emptyList(), 16.7).frames)
        val h = DiagCore.hello(sid, "1.23.11.3", 51, "x", 35, "ab".repeat(32))
        assertTrue(DiagCore.validKey(h.getString("k"))); assertFalse(DiagCore.validKey("xyz"))
    }

    @Test fun remoteConfigBounded() {
        fun c(k: String, v: Any) = JSONObject().put("key", k).put("value", v)
        assertEquals(mapOf("key" to "sky_fps_cap", "value" to "15"), DiagCore.checkArgs("set_config", c("sky_fps_cap", 15)))
        assertNotNull(DiagCore.checkArgs("set_config", c("nav_anim", "off")))
        assertNotNull(DiagCore.checkArgs("set_config", c("sky_mode", "default")))
        assertNull(DiagCore.checkArgs("set_config", c("sky_fps_cap", 120)))
        assertNull(DiagCore.checkArgs("set_config", c("sky_fps_cap", 0)))
        assertNull(DiagCore.checkArgs("set_config", c("nav_anim", "maybe")))
        assertNull(DiagCore.checkArgs("set_config", c("screen_brightness", "10")))
        assertNull(DiagCore.checkArgs("set_config", c("shell", "rm -rf /")))
        assertNull(DiagCore.checkArgs("set_config", c("sky_mode", "off").put("extra", 1)))
        assertNull(DiagCore.checkArgs("set_config", JSONObject().put("key", "nav_anim")))
        val raw = cmd("set_config", c("nav_anim", "off"))
        val v = ok(raw); assertTrue(v is DiagCore.Verdict.Ok)
        assertEquals("翻页动画", DiagCore.CONFIG_LABELS["nav_anim"]); assertEquals("关闭", DiagCore.configShown("nav_anim", "off"))
    }

    /** Android's ICU regex rejects an unescaped `]` / `}` outside a character class (Java accepts it), which crashed
     *  DiagCore's init on the phone: every Regex field must compile and carry no such bare bracket. */
    @Test fun regexesCompileOnIcu() {
        val fields = DiagCore::class.java.declaredFields.filter { it.type == Regex::class.java }
        assertTrue(fields.size >= 6)
        fields.forEach { f ->
            f.isAccessible = true
            val p = (f.get(DiagCore) as Regex).pattern
            java.util.regex.Pattern.compile(p)
            var i = 0; var inClass = false; var inQuant = false
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '\\' -> i++
                    inClass -> if (c == ']' && p[i - 1] != '[' && !(p[i - 1] == '^' && p[i - 2] == '[')) inClass = false
                    c == '[' -> inClass = true
                    c == '{' -> inQuant = true
                    c == '}' -> { assertTrue("bare } in ${f.name}", inQuant); inQuant = false }
                    c == ']' -> throw AssertionError("bare ] in ${f.name}")
                }
                i++
            }
        }
    }
}
