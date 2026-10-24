package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.12.1] :acra must never run app init or share the main WebView data dir. */
class ProcessGuardCoreTest {
    private val pkg = "com.openminis.mines.plus"
    @Test fun main() {
        assertTrue(ProcessGuardCore.isMain(pkg, pkg))
        assertTrue(ProcessGuardCore.isMain(null, pkg))
        assertFalse(ProcessGuardCore.isMain("$pkg:acra", pkg))
        assertFalse(ProcessGuardCore.isMain("$pkg:incog", pkg))
    }
    @Test fun suffix() {
        assertEquals("acra", ProcessGuardCore.dataSuffix("$pkg:acra", pkg))
        assertEquals("remote_1", ProcessGuardCore.dataSuffix("$pkg:remote-_1", pkg))
        assertEquals("proc", ProcessGuardCore.dataSuffix("$pkg:", pkg))
    }
}
