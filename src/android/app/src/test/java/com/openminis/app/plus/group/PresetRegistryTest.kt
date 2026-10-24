package com.openminis.app.plus.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.8] Pure-JVM checks of the preset registry and the response parsers (no Android, no network). */
class PresetRegistryTest {
    @Test fun nanoMuseNeedsAKey() {
        val s = PresetRegistry.preset("nanomuse")!!.toSpec()
        assertFalse(s.keyOptional)
        assertEquals(Proto.OPENAI, s.proto)
        assertEquals(AiCatalog.NANOMUSE_BASE, s.baseUrl)
        val fixed = PresetRegistry.override(AiSpec("nanomuse", "Muse 中继", null, Proto.OPENAI, AiCatalog.NANOMUSE_BASE, "", keyOptional = true))
        assertFalse(fixed.keyOptional)
        assertEquals("deepseek-v4.1-flash", fixed.defaultModel)
    }

    @Test fun transportsMapOnto387Protos() {
        assertEquals(Proto.COZE, PresetRegistry.preset("coze")!!.legacyProto())
        assertEquals(Proto.ADAPTER, PresetRegistry.preset("memoh")!!.legacyProto())
        assertEquals(Proto.WEBAPP, PresetRegistry.preset("web:hark")!!.legacyProto())
        assertTrue(PresetRegistry.preset("web:manus")!!.toSpec().keyOptional)
        assertEquals("memoh", PresetRegistry.preset("memoh:bot-1")!!.id)
        assertEquals("coze", PresetRegistry.preset("coze:7123")!!.id)
        assertEquals("web:hark", PresetRegistry.preset("web:hark#2")!!.id)
    }

    @Test fun museIsOffByDefault() {
        assertFalse(PresetRegistry.visible("web:muse"))
        assertTrue(PresetRegistry.visible("web:hark"))
        assertTrue(PresetRegistry.visible("deepseek"))
    }

    @Test fun extraSpecsOnlyAddsMissingBridges() {
        val ids = PresetRegistry.extraSpecs(setOf("web:hark")).map { it.id }
        assertFalse("web:hark" in ids)
        assertTrue("web:manus" in ids)
        assertFalse("memoh" in ids)
    }

    @Test fun relayKeyParsing() {
        assertEquals("nm_abcdefghijklmnopqrstu", PresetAuth.keyFromVerify("""{"key":"nm_abcdefghijklmnopqrstu","account":{"id":3}}"""))
        assertEquals("nm_abcdefghijklmnopqrstu", PresetAuth.keyFromVerify("""{"data":{"api_key":"nm_abcdefghijklmnopqrstu"}}"""))
        assertEquals("nm_ABCDEFGHIJKLMNOPQRST", PresetAuth.keyFromVerify("ok nm_ABCDEFGHIJKLMNOPQRST"))
        assertNull(PresetAuth.keyFromVerify("""{"ok":true}"""))
    }

    @Test fun memohBaseNormalisation() {
        assertEquals(listOf("http://1.2.3.4:8080/api", "http://1.2.3.4:8080"), PresetAuth.normalizeMemohBase("1.2.3.4:8080/"))
        assertEquals(listOf("https://m.example/api", "https://m.example"), PresetAuth.normalizeMemohBase("https://m.example/api"))
    }

    @Test fun memohAssistantTurns() {
        val body = """{"items":[
          {"id":"t2","role":"assistant","timestamp":"2026-10-15T10:00:02Z","text":"","messages":[{"type":"text","content":"你好"},{"type":"tool","content":"x"}]},
          {"id":"t1","role":"user","timestamp":"2026-10-15T10:00:01Z","text":"hi"},
          {"id":"t0","role":"assistant","timestamp":"2026-10-15T09:00:00Z","text":"old"}]}"""
        val l = MemohAdapter.assistantTurns(body)
        assertEquals(listOf("t0" to "old", "t2" to "你好"), l)
    }

    @Test fun flatKeepsTheLastQuestion() {
        val r = TurnRequest("你在群聊里", listOf(AiClient.Turn("user", "【群主】a"), AiClient.Turn("assistant", "b"), AiClient.Turn("user", "【群主】c")), 100)
        assertTrue(r.flat().endsWith("请回复最新一条：\n【群主】c"))
        assertTrue(r.flat().contains("（你）b"))
    }
}
