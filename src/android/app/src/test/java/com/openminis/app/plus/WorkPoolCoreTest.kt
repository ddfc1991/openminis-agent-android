package com.openminis.app.plus

import com.openminis.app.plus.WorkPoolCore.Kind
import com.openminis.app.plus.WorkPoolCore.Node
import com.openminis.app.plus.WorkPoolCore.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkPoolCoreTest {
    private val siyun = Node("a", "siyun", "18.143.139.29", Role.BROWSER, true, 2, 38f, 1200, 1840)
    private val ali = Node("b", "阿里云", "8.217.89.133", Role.CRAWL, true, 2, 22f, 1200, 1740)
    private val meng = Node("c", "梦云", "38.76.209.77", Role.GATEWAY, true, 2, 31f, 1200, 2048)
    private val hatch = Node("d", "青怡 Hatch VM", "10.0.0.9", Role.EXCLUDED, true, 2, 10f, 1000, 7800)

    @Test fun roles() {
        assertEquals(Role.BROWSER, WorkPoolCore.roleOf("siyun-vps", "18.143.139.29"))
        assertEquals(Role.CRAWL, WorkPoolCore.roleOf("阿里云 香港", "8.217.89.133"))
        assertEquals(Role.GATEWAY, WorkPoolCore.roleOf("梦云", "38.76.209.77"))
        assertEquals(Role.EXCLUDED, WorkPoolCore.roleOf("青怡 VM", "x"))
        assertEquals(Role.EXCLUDED, WorkPoolCore.roleOf("Hatch", "1.2.3.4"))
        assertEquals(Role.GENERAL, WorkPoolCore.roleOf("vultr", "5.6.7.8"))
    }

    @Test fun reservesAndPool() {
        assertEquals(1840 - 1200 - 300L, siyun.schedulableMb)
        assertEquals(2048 - 1200 - 600L, meng.schedulableMb)
        assertEquals(0L, hatch.schedulableMb)
        val p = WorkPoolCore.pool(listOf(siyun, ali, meng, hatch))
        assertEquals(6, p.cores); assertEquals(3, p.members); assertEquals(3, p.online)
        assertEquals(340L + 240L + 248L, p.schedulableMb)
    }

    @Test fun pickNeverHatchAndSparesGateway() {
        val all = listOf(siyun, ali, meng, hatch)
        assertEquals("a", WorkPoolCore.pick(all, Kind.BROWSER, 200)!!.id)
        assertEquals("b", WorkPoolCore.pick(all, Kind.CRAWL, 200)!!.id)
        // heavy: siyun preferred; if siyun full → aliyun, never the gateway while aliyun can take it
        val full = siyun.copy(memUsedMb = 1700)
        assertEquals("b", WorkPoolCore.pick(listOf(full, ali, meng, hatch), Kind.HEAVY, 200)!!.id)
        assertNull(WorkPoolCore.pick(listOf(hatch), Kind.LIGHT, 10))
        assertNull(WorkPoolCore.pick(all, Kind.LIGHT, 5000))
        // only the gateway has room → it may take it (its 600 MB reserve still holds)
        assertEquals("c", WorkPoolCore.pick(listOf(full, ali.copy(memUsedMb = 1700), meng), Kind.HEAVY, 200)!!.id)
    }

    @Test fun migrateAbove88() {
        val hot = ali.copy(memUsedMb = 1600)   // 91 %
        assertEquals(listOf("b"), WorkPoolCore.overloaded(listOf(siyun, hot, meng, hatch.copy(memUsedMb = 7700))).map { it.id })
        assertEquals("内存紧张", WorkPoolCore.status(hot))
        assertNull(WorkPoolCore.pick(listOf(hot), Kind.CRAWL, 10))
        assertEquals("已排除", WorkPoolCore.status(hatch)); assertEquals("受保护", WorkPoolCore.status(meng)); assertEquals("在线", WorkPoolCore.status(siyun))
    }

    @Test fun shardsAndFormat() {
        assertEquals(listOf(4, 4, 4), WorkPoolCore.shards(12, 4))
        assertEquals(listOf(5, 2), WorkPoolCore.shards(7, 5))
        assertTrue(WorkPoolCore.shards(0, 3).isEmpty())
        assertEquals("1.8G", WorkPoolCore.mem(1840)); assertEquals("300M", WorkPoolCore.mem(300))
    }
}
