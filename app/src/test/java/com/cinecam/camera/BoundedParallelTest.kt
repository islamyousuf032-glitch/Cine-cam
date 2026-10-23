package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BoundedParallelTest {
    @Test fun resultsComeBackInIndexOrderNotCompletionOrder() {
        val results = BoundedParallel.map(3, 12) { index ->
            // Deliberately make later indexes finish first.
            Thread.sleep((11 - index) * 3L)
            "item-$index"
        }
        assertEquals((0 until 12).map { "item-$it" }, results)
    }

    @Test fun sequentialPathMatchesParallelForSingleWorkerAndSingleItem() {
        assertEquals(listOf("0", "1", "2"), BoundedParallel.map(1, 3) { it.toString() })
        assertEquals(listOf("only"), BoundedParallel.map(4, 1) { "only" })
        assertEquals(emptyList<String>(), BoundedParallel.map(4, 0) { "never" })
    }

    @Test fun firstFailureStopsTheBatchAndIsRethrown() {
        val ran = AtomicInteger()
        val started = System.currentTimeMillis()
        try {
            BoundedParallel.map(3, 60) { index ->
                ran.incrementAndGet()
                if (index == 1) throw IllegalStateException("bad frame")
                Thread.sleep(40) // Long enough that a full run would be obvious.
                index
            }
            fail("failure must be rethrown")
        } catch (e: IllegalStateException) {
            assertEquals("bad frame", e.message)
        }
        assertTrue("not every task may run after a failure: $ran", ran.get() < 60)
        assertTrue("the batch must not run to completion", System.currentTimeMillis() - started < 2_000)
    }

    @Test fun workerCountIsBounded() {
        try { BoundedParallel.map(0, 3) { it }; fail("0 workers") } catch (_: IllegalArgumentException) { }
        try { BoundedParallel.map(BoundedParallel.MAX_WORKERS + 1, 3) { it }; fail("too many workers") }
        catch (_: IllegalArgumentException) { }
        try { BoundedParallel.map(2, -1) { it }; fail("negative count") } catch (_: IllegalArgumentException) { }
    }

    @Test fun everyIndexRunsExactlyOnce() {
        val seen = IntArray(24)
        BoundedParallel.map(3, 24) { index -> seen[index]++; index }
        assertTrue(seen.all { it == 1 })
    }
}
