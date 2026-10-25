package com.openminis.app.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** [Mines-38.9.15 伪人核心] HumanActuator timing stays inside a person's range, and a slip is always corrected. */
class HumanActuatorCoreTest {

    /** replay a plan the way the page sees it */
    private fun typed(keys: List<HumanActuatorCore.Key>): String {
        val sb = StringBuilder()
        keys.forEach { k -> if (k.ch == HumanActuatorCore.BACKSPACE) sb.setLength((sb.length - 1).coerceAtLeast(0)) else sb.append(k.ch) }
        return sb.toString()
    }

    @Test fun gapsAreWithinBounds() {
        repeat(50) { seed ->
            val keys = HumanActuatorCore.plan("hello world, this is fine", Random(seed), maxTotalMs = Long.MAX_VALUE)
            keys.filter { !it.typo && it.ch != HumanActuatorCore.BACKSPACE }.forEach { k ->
                assertTrue("gap ${k.delayMs}", k.delayMs >= HumanActuatorCore.MIN_GAP_MS)
                assertTrue("gap ${k.delayMs}", k.delayMs <= HumanActuatorCore.MAX_GAP_MS + HumanActuatorCore.THINK_MAX_MS)
            }
        }
    }

    @Test fun cadenceVaries() {
        val gaps = HumanActuatorCore.plan("the quick brown fox jumps over", Random(7)).map { it.delayMs }.toSet()
        assertTrue("same gap every key looks scripted", gaps.size > 5)
    }

    @Test fun theFieldEndsUpExactlyAsIntended() {
        val text = "this sentence is long enough to contain a slip or two somewhere in the middle of it, really"
        repeat(30) { seed -> assertEquals(text, typed(HumanActuatorCore.plan(text, Random(seed)))) }
    }

    @Test fun longTextsGetASlipThatIsBackspaced() {
        val text = "a".repeat(10) + " this sentence is long enough to contain a slip or two somewhere in the middle of it"
        val anyTypo = (0 until 30).any { seed -> HumanActuatorCore.plan(text, Random(seed)).any { it.ch == HumanActuatorCore.BACKSPACE } }
        assertTrue(anyTypo)
        assertTrue("short texts never slip", (0 until 30).none { s -> HumanActuatorCore.plan("ok thanks", Random(s)).any { it.ch == HumanActuatorCore.BACKSPACE } })
    }

    @Test fun aWholeFieldIsCapped() {
        val keys = HumanActuatorCore.plan("x".repeat(1500), Random(3))
        assertTrue(HumanActuatorCore.planMs(keys) <= HumanActuatorCore.MAX_FIELD_MS + keys.size)   // per-key floor of 8 ms rounding
        assertTrue(keys.all { it.delayMs >= 8 })
    }

    @Test fun dwellAndPausesAreBounded() {
        repeat(100) { s ->
            val r = Random(s)
            assertTrue(HumanActuatorCore.readDwellMs(0, r) in 600L..6_000L)
            assertTrue(HumanActuatorCore.readDwellMs(100_000, r) in 600L..6_000L)
            assertTrue(HumanActuatorCore.clickPauseMs(r) in 250L..700L)
            assertTrue(HumanActuatorCore.pressMs(r) in 60L..140L)
            val j = HumanActuatorCore.jitter(30f, r); assertTrue(j in -5f..5f)
        }
        assertEquals(0f, HumanActuatorCore.jitter(0f), 0f)
    }

    @Test fun scrollAddsUpAndIsUneven() {
        repeat(20) { s ->
            val p = HumanActuatorCore.scrollPlan(-700, Random(s))
            assertEquals(-700, p.sumOf { it.px })
            assertTrue(p.size in 3..6)
            assertTrue(p.all { it.gapMs in 120L..500L })
        }
    }

    @Test fun remainingBudget() {
        assertEquals(5, HumanActuatorCore.remaining(10, 5)); assertEquals(0, HumanActuatorCore.remaining(10, 50))
    }
}
