package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpsMonitorCoreTest {
    private val out = """
        CORES 2
        LOAD 0.52 0.40 0.31 1/123 4567
        UP 180000.55
        MemTotal:        2000000 kB
        MemAvailable:    1500000 kB
        DF /dev/vda1 41152736 7407492 31632460 19% /
        cpu  100 0 100 800 0 0 0 0 0 0
        NET 1000 2000
        cpu  150 0 150 880 20 0 0 0 0 0
        NET 4072 3024
        PING ct
        5 packets transmitted, 5 received, 0% packet loss, time 1204ms
        rtt min/avg/max/mdev = 88.1/91.4/95.0/2.3 ms
        PING cu
        5 packets transmitted, 4 received, 20% packet loss, time 1208ms
        rtt min/avg/max/mdev = 36.0/38.2/41.0/1.9 ms
        PING cm
        TCP 47
        TCP x
        TCP 45
        TCP 49
        TCP 51
    """.trimIndent()

    @Test fun parsesAllReadings() {
        val r = VpsMonitorCore.parse(out)
        assertEquals(2, r.cores)
        assertEquals(0.52, r.load1!!, 1e-9)
        assertEquals(26.0, r.loadPct!!, 1e-9)
        assertEquals(180000L, r.uptimeSec)
        assertEquals(25.0, r.memPct!!, 1e-9)
        assertEquals(19.0, r.diskPct!!, 1e-9)
        // dt = 1200-1000 = 200 ; idle delta = (880+20)-(800+0) = 100 → 50 %
        assertEquals(50.0, r.cpuPct!!, 1e-6)
        assertEquals(3072.0, r.rxRate!!, 1e-9)
        assertEquals(1024.0, r.txRate!!, 1e-9)
        assertEquals(4072L, r.rxTotal); assertEquals(3024L, r.txTotal); assertEquals(7096L, r.trafficTotal)
        assertEquals(91.4, r.lat["ct"]!!.ms!!, 1e-9); assertEquals(0.0, r.lat["ct"]!!.lossPct!!, 1e-9)
        assertEquals(38.2, r.lat["cu"]!!.ms!!, 1e-9); assertEquals(20.0, r.lat["cu"]!!.lossPct!!, 1e-9)
        assertEquals(48.0, r.lat["cm"]!!.ms!!, 1e-9); assertEquals(20.0, r.lat["cm"]!!.lossPct!!, 1e-9)
    }

    @Test fun missingReadingsStayNull() {
        val r = VpsMonitorCore.parse("CORES \nLOAD \nPING ct\nping: connect: Network is unreachable\nPING cu\n5 packets transmitted, 0 received, 100% packet loss, time 4000ms\n")
        assertNull(r.cpuPct); assertNull(r.memPct); assertNull(r.diskPct); assertNull(r.load1); assertNull(r.cores); assertNull(r.loadPct)
        assertNull(r.rxRate); assertNull(r.rxTotal); assertNull(r.uptimeSec)
        assertNull(r.lat["ct"])
        assertNull(r.lat["cu"]!!.ms); assertEquals(100.0, r.lat["cu"]!!.lossPct!!, 1e-9)
        assertEquals(2, VpsMonitorCore.latLevel(r.lat["cu"]))
        assertEquals(-1, VpsMonitorCore.latLevel(null))
        assertEquals("—", VpsMonitorCore.pct(r.cpuPct)); assertEquals("—", VpsMonitorCore.bytes(null)); assertEquals("—", VpsMonitorCore.uptime(null))
    }

    @Test fun barsAndFormat() {
        assertEquals(0, VpsMonitorCore.cells(null)); assertEquals(4, VpsMonitorCore.cells(22.0)); assertEquals(20, VpsMonitorCore.cells(140.0))
        assertEquals(0, VpsMonitorCore.latLevel(VpsMonitorCore.Lat(38.0, 0.0)))
        assertEquals(1, VpsMonitorCore.latLevel(VpsMonitorCore.Lat(150.0, 0.0)))
        assertEquals(1, VpsMonitorCore.latLevel(VpsMonitorCore.Lat(60.0, 10.0)))
        assertEquals("46.3 MB", VpsMonitorCore.bytes(48_548_454))
        assertEquals("3.1 KB/s", VpsMonitorCore.rate(3154.0))
        assertEquals("2天", VpsMonitorCore.uptime(180_000))
        assertTrue(VpsMonitorCore.PROBE.contains("219.141.136.12") && VpsMonitorCore.PROBE.contains("PING cm"))
    }

    @Test fun daysAndQuota() {
        val day = 86_400_000L
        assertNull(VpsMonitorCore.daysLeft(0L, 1000L))
        assertEquals(622L, VpsMonitorCore.daysLeft(1000L + 622 * day, 1000L))
        assertEquals(1L, VpsMonitorCore.daysLeft(1000L + day / 2, 1000L))
        assertEquals(0L, VpsMonitorCore.daysLeft(500L, 1000L))
        assertNull(VpsMonitorCore.quotaBytes("2GB RAM 实验性 VPS"))
        assertEquals(1024L * 1024 * 1024 * 1024, VpsMonitorCore.quotaBytes("流量 1TB/月"))
        assertEquals(500L * 1024 * 1024 * 1024, VpsMonitorCore.quotaBytes("2核 · 月流量：500 GB"))
    }
}
