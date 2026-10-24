package com.openminis.app.sandbox.offload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.8 v5d] tiered screen reading: OCR trigger, OCR merge, text ranking, replay path matching + JSON. */
class ScreenTiersTest {
    private fun it(label: String, l: Int, t: Int, r: Int, b: Int, rid: String = "", desc: String = "", click: Boolean = true) =
        ScreenTiers.Item(label, desc, rid, "", l, t, r, b, click)

    @Test fun ocrOnlyWhenTreeHasNoText() {
        val game = listOf(it("#surface", 0, 0, 1080, 2400), it("SurfaceView", 0, 0, 1080, 2400), it("", 10, 10, 20, 20))
        assertTrue(ScreenTiers.needsOcr(game))
        val settings = listOf(it("Wi-Fi", 0, 100, 1080, 200), it("蓝牙", 0, 200, 1080, 300), it("显示", 0, 300, 1080, 400))
        assertFalse(ScreenTiers.needsOcr(settings))
        assertTrue(ScreenTiers.isGenericLabel("#btn_ok")); assertFalse(ScreenTiers.isGenericLabel("OK"))
    }

    @Test fun ocrMergeDropsLinesTheTreeAlreadyHas() {
        val tree = listOf(it("Start", 100, 100, 300, 160))
        val ocr = listOf(it("Start", 110, 105, 290, 155, click = false), it("Level 3", 100, 400, 300, 450, click = false), it("Level 3", 102, 402, 298, 448, click = false))
        val m = ScreenTiers.mergeOcr(tree, ocr)
        assertEquals(listOf("Level 3"), m.map { it.label })
        assertEquals("ocr", m[0].source)
    }

    @Test fun rankingPrefersExactThenPrefixThenContains() {
        val items = listOf(it("设置中心", 0, 0, 500, 100), it("设置", 0, 200, 300, 260), it("打开设置页", 0, 300, 500, 360))
        assertEquals("设置", ScreenTiers.best("设置", items)!!.label)
        assertEquals("设置中心", ScreenTiers.best("设置中", items)!!.label)
        assertEquals(100, ScreenTiers.score(" 设置 ", items[1]) - 5)
        assertNull(ScreenTiers.best("蓝牙", items))
        // content-desc counts too; fuzzy in-order match is the weakest
        assertEquals("search_btn", ScreenTiers.best("搜索", listOf(it("search_btn", 0, 0, 10, 10, desc = "搜索")))!!.label)
        assertTrue(ScreenTiers.score("wfi", it("Wi-Fi", 0, 0, 1, 1)) in 1..30)
    }

    @Test fun replayMatchesByIdThenTextNearTheSavedPlace() {
        val now = listOf(it("登录", 400, 1500, 680, 1600, rid = "app:id/login"), it("登录", 400, 300, 680, 360))
        val s = ScreenTiers.Step("tap", "登录", "app:id/login", 540, 1540)
        assertEquals(1550, ScreenTiers.matchStep(s, now)!!.cy)
        // id gone, text still there near the old place → still matches
        val moved = listOf(it("登录", 420, 1520, 700, 1620))
        assertNotNull(ScreenTiers.matchStep(s, moved))
        // the button is now on the other end of the screen → mismatch, replay stops
        assertNull(ScreenTiers.matchStep(s, listOf(it("登录", 400, 100, 680, 160))))
        assertNull(ScreenTiers.matchStep(ScreenTiers.Step("tap", "确定", "", 540, 900), listOf(it("取消", 400, 860, 680, 940))))
    }

    @Test fun pathJsonRoundTripAndSensitiveStepsAreNotSaved() {
        val p = ScreenTiers.Path("com.example", ".Main", "签到", listOf(ScreenTiers.Step("tap", "签到", "x:id/s", 10, 20), ScreenTiers.Step("raw", arg = "wait 500")), 5L, 2)
        val back = ScreenTiers.fromJson(JSONObject(ScreenTiers.toJson(p).toString()))
        assertEquals(p, back)
        assertEquals(ScreenTiers.key("com.example", "签 到"), ScreenTiers.key("com.example", "签到"))
        assertTrue(ScreenTiers.replayable(p.steps))
        assertFalse(ScreenTiers.replayable(listOf(ScreenTiers.Step("tap", "立即支付"))))
        assertFalse(ScreenTiers.replayable(emptyList()))
    }

    @Test fun elementsJsonNamesTheTier() {
        val j = ScreenTiers.elementsJson(listOf(it("OK", 0, 0, 100, 50, rid = "a:id/ok")), ScreenTiers.TIER_TREE, "com.x")
        assertEquals("2·无障碍树", j.getString("tier"))
        val e = j.getJSONArray("elements").getJSONObject(0)
        assertEquals(50, e.getJSONObject("center").getInt("x")); assertEquals("a:id/ok", e.getString("id")); assertEquals(1, e.getInt("n"))
    }

    // [Mines-38.9] cloud OCR trigger + answer parsing
    @Test fun cloudOcrWhenChineseOrThin() {
        val latinOk = listOf(it("Start game", 0, 0, 10, 10), it("Options", 0, 20, 10, 30), it("Quit", 0, 40, 10, 50))
        assertFalse(ScreenTiers.wantsCloudOcr(emptyList(), latinOk, zhLocale = false))
        assertTrue(ScreenTiers.wantsCloudOcr(emptyList(), latinOk.take(1), zhLocale = false))
        assertTrue(ScreenTiers.wantsCloudOcr(listOf(it("设置", 0, 0, 1, 1)), latinOk, zhLocale = false))
        assertTrue(ScreenTiers.wantsCloudOcr(emptyList(), listOf(it("~", 0, 0, 1, 1), it("|i", 0, 0, 1, 1), it(":.", 0, 0, 1, 1), it("ab", 0, 0, 1, 1)), zhLocale = false))
    }

    @Test fun parseCloudOcrBoxesAndPlainText() {
        val l = ScreenTiers.parseCloudOcr("```json\n[{\"t\":\"登录\",\"b\":[10,20,110,60]},{\"t\":\"忘记密码\",\"b\":[10,80,40,30]},{\"t\":\"无框\"}]\n```", 2f)
        assertEquals(listOf("登录", "忘记密码", "无框"), l.map { it.label })
        assertEquals(20, l[0].l); assertEquals(220, l[0].r); assertEquals("ocr-cloud", l[0].source)
        assertEquals(100, l[1].r)   // [x, y, w, h] answer turned into a box: 10+40=50 → ×2
        assertEquals("文字(无位置)", l[2].role)
        val p = ScreenTiers.parseCloudOcr("第一行\n第二行")
        assertEquals(listOf("第一行", "第二行"), p.map { it.label })
    }
}
