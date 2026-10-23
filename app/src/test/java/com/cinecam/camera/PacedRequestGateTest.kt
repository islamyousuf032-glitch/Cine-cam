package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test

class PacedRequestGateTest {
    @Test fun backpressureDoesNotSubmitOrConsumeRequestBudget() {
        val gate = PacedRequestGate(8)
        repeat(100) { assertFalse(gate.trySubmit(0)) }
        assertFalse(gate.trySubmit(-1))
        assertEquals(0, gate.submitted)
        assertTrue(gate.trySubmit(3))
        assertFalse(gate.trySubmit(3)) // Never two requests in flight.
        assertTrue(gate.finish())
        assertFalse(gate.finish()) // Duplicate completion must not advance request budget.
        assertEquals(1, gate.completed)
    }
    @Test fun eightFramesAreFiniteEvenWithWriterStalls() {
        val gate = PacedRequestGate(8)
        repeat(8) {
            assertFalse(gate.trySubmit(0))
            assertTrue(gate.trySubmit(1))
            assertFalse(gate.exhausted)
            assertTrue(gate.finish()) // Matching image OR capture failure releases the gate.
        }
        assertTrue(gate.exhausted)
        assertFalse(gate.trySubmit(3))
        assertEquals(8, gate.submitted)
        assertEquals(8, gate.completed)
    }
    @Test fun longSessionBudgetStillKeepsOneRequestInFlight() {
        val gate = PacedRequestGate(120)
        repeat(120) {
            assertTrue(gate.trySubmit(1))
            assertFalse(gate.trySubmit(1)) // One RAW request at a time, always.
            assertTrue(gate.finish())
        }
        assertTrue(gate.exhausted)
        assertEquals(120, gate.submitted)
        assertEquals(120, gate.completed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLimitRejected() { PacedRequestGate(0) }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedLimitRejected() { PacedRequestGate(RecordingPlanner.MAX_FRAMES + 1) }
}
