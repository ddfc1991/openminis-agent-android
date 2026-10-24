package com.openminis.app.plus

import com.openminis.app.plus.SkillInstallKey.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillInstallKeyTest {
    private val K = SkillInstallKey
    @Test fun sameNameOtherPublisherIsNotInstalled() {
        val src = K.source("alice", "weather", "1.0.1")
        assertEquals("skillhub:alice/weather@1.0.1", src)
        assertEquals(State.MINE, K.state(src, "alice", "weather"))
        assertEquals(State.MINE, K.state(src, "Alice", "weather"))
        assertEquals(State.OTHER, K.state(src, "bob", "weather"))
        assertEquals(State.NONE, K.state(null, "bob", "weather"))
        assertEquals(State.LOCAL, K.state("local", "bob", "weather"))
    }
    @Test fun legacySourceWithoutNamespaceIsNeverClaimed() {
        assertEquals(State.OTHER, K.state("skillhub:weather@1.0.1", "alice", "weather"))
        assertEquals(State.MINE, K.state("skillhub:weather@1.0.1", "", "weather"))
        assertEquals(Triple("", "weather", "1.0.1"), K.parse("skillhub:weather@1.0.1"))
        assertNull(K.parse("local"))
        assertEquals("bob/weather", K.rowKey("bob", "weather")); assertEquals("weather", K.rowKey("", "weather"))
    }
}
