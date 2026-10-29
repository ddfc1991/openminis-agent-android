package com.openminis.app.plus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PatrolConcurrencyCoreTest {
    @Test fun boundedMapPreservesInputOrderAndCapsParallelism() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val input = (0 until 24).toList()
        val results = boundedOrderedMap(input, 4) { n ->
            val now = active.incrementAndGet()
            maximum.updateAndGet { old -> maxOf(old, now) }
            try {
                kotlinx.coroutines.delay(((23 - n) % 5 + 1).toLong())
                n * 2
            } finally { active.decrementAndGet() }
        }
        assertEquals(input.map { it * 2 }, results.map { it.getOrThrow() })
        assertTrue(maximum.get() <= 4)
        assertTrue(maximum.get() > 1)
    }

    @Test fun isolatesOrdinaryFailureButDoesNotSwallowCancellation() = runBlocking {
        val results = boundedOrderedMap(listOf(1, 2, 3), 2) { n ->
            if (n == 2) error("fixture") else n
        }
        assertEquals(1, results[0].getOrThrow())
        assertTrue(results[1].isFailure)
        assertEquals(3, results[2].getOrThrow())

        val job = async {
            boundedOrderedMap(listOf(1), 1) { throw CancellationException("test cancellation") }
        }
        try {
            job.await()
            throw AssertionError("cancellation was swallowed")
        } catch (_: CancellationException) {
            assertTrue(job.isCancelled)
        }
    }
}
