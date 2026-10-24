package com.openminis.app.plus

import com.openminis.app.plus.VpsPlanCore.ALIYUN
import com.openminis.app.plus.VpsPlanCore.AWS
import com.openminis.app.plus.VpsPlanCore.GHA
import com.openminis.app.plus.VpsPlanCore.Health
import com.openminis.app.plus.VpsPlanCore.INSTABOX
import com.openminis.app.plus.VpsPlanCore.MENGYUN
import com.openminis.app.plus.VpsPlanCore.Overrides
import com.openminis.app.plus.VpsPlanCore.PHONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpsPlanCoreTest {
    private val before = VpsPlanCore.MIGRATE_AT - 1
    private val after = VpsPlanCore.MIGRATE_AT + 1
    private fun target(o: Overrides, id: String, now: Long = before) = VpsPlanCore.rules(o, now).first { it.id == id }.target
    private val allUp = VpsPlanCore.MACHINES.associate { it.id to Health(true, true, 40) }

    @Test fun defaultsAndMigration() {
        val o = Overrides()
        assertEquals(PHONE, target(o, "login"))
        assertEquals(ALIYUN, target(o, "cn"))
        assertEquals(MENGYUN, target(o, "cn", after))            // 12-14: 国内网站 / 中转 move to 梦云 by default
        assertEquals(INSTABOX, target(o, "heavy"))
        assertEquals(AWS, target(o, "light"))
        assertEquals(GHA, target(o, "build"))
        assertEquals(listOf(INSTABOX, AWS, MENGYUN, ALIYUN), VpsPlanCore.failover(o))
        assertEquals(88, VpsPlanCore.threshold(o))
    }

    @Test fun changedRowsAndRestore() {
        var o = VpsPlanCore.setTarget(Overrides(), "cn", AWS, before)
        val cn = VpsPlanCore.rules(o, before).first { it.id == "cn" }
        assertEquals(AWS, cn.target)
        assertEquals(ALIYUN, VpsPlanCore.changedFrom(cn, before))  // 已改 · 默认是 阿里云
        // picking the default again clears the override
        o = VpsPlanCore.setTarget(o, "cn", ALIYUN, before)
        assertTrue(o.targets.isEmpty())
        // an unchanged rule follows the migration; a changed one keeps the user's choice
        assertEquals(MENGYUN, target(o, "cn", after))
        o = VpsPlanCore.setTarget(o, "cn", AWS, before)
        assertEquals(AWS, target(o, "cn", after))
        o = VpsPlanCore.bumpThreshold(o, 2)
        assertEquals(90, VpsPlanCore.threshold(o))
        o = VpsPlanCore.restoreRouting(o.copy(paused = setOf(AWS)))
        assertEquals(ALIYUN, target(o, "cn"))
        assertEquals(88, VpsPlanCore.threshold(o))
        assertEquals(setOf(AWS), o.paused)                         // machine settings survive 恢复默认
    }

    @Test fun loginLockedToPhone() {
        val o = VpsPlanCore.setTarget(Overrides(), "login", AWS, before)
        assertEquals(PHONE, target(o, "login"))
        assertEquals(PHONE, target(Overrides(targets = mapOf("login" to AWS)), "login"))
        // the phone is never offered for other rules
        assertFalse(PHONE in VpsPlanCore.TARGETS)
        assertEquals(INSTABOX, target(VpsPlanCore.setTarget(Overrides(), "heavy", PHONE, before), "heavy"))
        assertEquals(PHONE, VpsPlanCore.pick(Overrides(), "login", emptyMap(), before))
    }

    @Test fun customRules() {
        var o = VpsPlanCore.addRule(Overrides(), "  视频转码 ", INSTABOX, "c1")
        val r = VpsPlanCore.rules(o, before).last()
        assertEquals("视频转码", r.label); assertTrue(r.custom); assertNull(VpsPlanCore.changedFrom(r, before))
        o = VpsPlanCore.setTarget(o, "c1", AWS, before)
        assertEquals(AWS, target(o, "c1"))
        assertEquals(o, VpsPlanCore.addRule(o, " ", AWS, "c2"))
        assertEquals(o, VpsPlanCore.addRule(o, "x", PHONE, "c2"))
        assertTrue(VpsPlanCore.removeRule(o, "c1").custom.isEmpty())
    }

    @Test fun failoverSort() {
        val f = VpsPlanCore.move(VpsPlanCore.FAILOVER_DEFAULT, 3, 1)
        assertEquals(listOf(INSTABOX, ALIYUN, AWS, MENGYUN), f)
        assertTrue(VpsPlanCore.failoverChanged(Overrides(failover = f)))
        assertFalse(VpsPlanCore.failoverChanged(Overrides(failover = listOf())))
        assertEquals(f, VpsPlanCore.move(f, 9, 0))
    }

    @Test fun pickFollowsRulesAndFailover() {
        val o = Overrides()
        assertEquals(INSTABOX, VpsPlanCore.pick(o, "heavy", allUp, before))
        // InstaBox down → next in failover order
        assertEquals(AWS, VpsPlanCore.pick(o, "heavy", allUp + (INSTABOX to Health(true, false)), before))
        // AWS over the stop line and paused InstaBox → 梦云
        assertEquals(MENGYUN, VpsPlanCore.pick(o.copy(paused = setOf(INSTABOX)), "heavy", allUp + (AWS to Health(true, true, 88)), before))
        // not set up in 工作位置 counts as unavailable; unknown online state counts as available
        assertEquals(AWS, VpsPlanCore.pick(o, "heavy", allUp + (INSTABOX to Health(false)), before))
        assertEquals(INSTABOX, VpsPlanCore.pick(o, "heavy", allUp + (INSTABOX to Health(true, null, null)), before))
        // nothing can take it → queue
        assertNull(VpsPlanCore.pick(o, "heavy", emptyMap(), before))
        // a raised stop line lets the 88 % box work again
        assertEquals(AWS, VpsPlanCore.pick(o.copy(threshold = 90), "light", allUp + (AWS to Health(true, true, 88)), before))
    }

    @Test fun matchServers() {
        val names = listOf("vultr 5.6.7.8", "siyun-vps 18.143.139.29", "梦云 38.76.209.77", "HK 8.217.89.133")
        assertEquals(1, VpsPlanCore.matchIndex(VpsPlanCore.machine(AWS)!!, names))
        assertEquals(2, VpsPlanCore.matchIndex(VpsPlanCore.machine(MENGYUN)!!, names))
        assertEquals(3, VpsPlanCore.matchIndex(VpsPlanCore.machine(ALIYUN)!!, names))
        assertEquals(-1, VpsPlanCore.matchIndex(VpsPlanCore.machine(INSTABOX)!!, names))
        assertEquals(-1, VpsPlanCore.matchIndex(VpsPlanCore.machine(GHA)!!, names))
    }

    @Test fun promptAndJsonRoundTrip() {
        val o = Overrides(targets = mapOf("cn" to AWS), custom = listOf(VpsPlanCore.Rule("c1", "转码", INSTABOX, custom = true)),
            failover = listOf(AWS, INSTABOX), threshold = 80, paused = setOf(INSTABOX), sub = mapOf(INSTABOX to true),
            accepts = mapOf(AWS to "调度"), forbids = mapOf(AWS to "编译"), idleMin = 15)
        assertEquals(o, VpsPlanCore.fromJson(VpsPlanCore.toJson(o)))
        assertEquals(Overrides(), VpsPlanCore.fromJson(VpsPlanCore.toJson(Overrides())))
        assertEquals(Overrides(), VpsPlanCore.fromJson("not json"))
        val p = VpsPlanCore.promptBlock(o, mapOf(AWS to "1.2.3.4"), allUp, before)
        assertTrue(p, p.contains("国内网站 / 中转 → AWS siyun-vps(1.2.3.4)"))
        assertTrue(p, p.contains("登录 / 验证码 / 支付 → 手机（本机）（锁定"))
        assertTrue(p, p.contains("重任务 / 长任务 → InstaBox；现在 InstaBox 不可用，改去 AWS"))
        assertTrue(p, p.contains("内存超过 80%"))
        assertTrue(p, p.contains("已暂停（不分新任务）：InstaBox"))
    }

    @Test fun mengyunIsNoLongerLocked() {
        val m = VpsPlanCore.machine(MENGYUN)!!
        assertTrue(m.canPause && m.canSub && m.inSubDefault)
        assertTrue(MENGYUN in VpsPlanCore.TARGETS)
        assertEquals(MENGYUN, target(VpsPlanCore.setTarget(Overrides(), "light", MENGYUN, before), "light"))
    }
}
