package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test

class TimestampPairingWindowTest {
    @Test fun delayedResultsPauseAcquisitionWithoutDiscardingImages() {
        val window = TimestampPairingWindow<String, String>()
        window.addImage(1, "image1"); window.addImage(2, "image2")
        assertFalse(window.canAcquire)
        assertNull(window.pollMatch())
        window.addResult(2, "result2")
        assertEquals(2L, window.pollMatch()!!.timestamp)
        assertTrue(window.canAcquire)
        window.addImage(3, "image3")
        window.addResult(1, "result1"); window.addResult(3, "result3")
        assertEquals(1L, window.pollMatch()!!.timestamp)
        assertEquals(3L, window.pollMatch()!!.timestamp)
        assertNull(window.pollMatch())
    }
    @Test fun exactJoinDoesNotGuessNearestTimestamp() {
        val window = TimestampPairingWindow<Int, Int>()
        window.addImage(100, 1); window.addResult(101, 2)
        assertNull(window.pollMatch())
        assertEquals(listOf(100L), window.imageTimestamps)
        assertEquals(listOf(101L), window.resultTimestamps)
        assertEquals(listOf(1), window.clearImages())
        window.clearResults()
        assertTrue(window.canAcquire)
        assertTrue(window.resultTimestamps.isEmpty())
    }
    @Test fun resultsFirstAndBoundedEvictionAreExplicit() {
        val window = TimestampPairingWindow<Int, Int>(2, 2)
        assertNull(window.addResult(1, 1)); window.addResult(2, 2)
        assertEquals(1L, window.addResult(3, 3))
        window.addImage(3, 30)
        val pair = window.pollMatch()!!
        assertEquals(30, pair.image); assertEquals(3, pair.result)
    }
    @Test fun duplicateOwnershipReturnedAndWindowCannotOverflow() {
        val window = TimestampPairingWindow<String, Int>()
        window.addImage(1, "old")
        assertEquals("old", window.addImage(1, "new"))
        window.addImage(2, "two")
        try { window.addImage(3, "three"); fail("must pause acquisition") }
        catch (_: IllegalStateException) { }
        assertEquals(listOf("new", "two"), window.clearImages())
    }
}
