package com.cinecam.camera

/** Handler-confined finite scheduler. A capacity observation is not a reserved pool lease.
 * Only one paced producer/request may be active, with no DNG/other RAW producer alongside it.
 */
internal class PacedRequestGate(private val limit: Int) {
    init { require(limit in 1..8) }
    var submitted = 0
        private set
    var completed = 0
        private set
    var inFlight = false
        private set
    val exhausted: Boolean get() = submitted == limit && !inFlight

    fun trySubmit(freeSlots: Int): Boolean {
        if (freeSlots <= 0 || inFlight || submitted >= limit) return false
        submitted++
        inFlight = true
        return true
    }

    fun finish(): Boolean {
        if (!inFlight) return false
        completed++
        inFlight = false
        return true
    }
}
