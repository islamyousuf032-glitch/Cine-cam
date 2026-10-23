package com.cinecam.camera

/** Handler-confined finite scheduler. A capacity observation is not a reserved pool lease.
 * Only one paced producer/request may be active, with no DNG/other RAW producer alongside it.
 */
internal class PacedRequestGate(private val limit: Int, val depth: Int = 1) {
    // A planned session budget may exceed the old 8-frame burst. Depth 1 is the safe
    // default; depth 2 lets the camera work on the next frame while the previous one is
    // still being matched, which is what actually limits RAW throughput.
    init {
        require(limit in 1..RecordingPlanner.MAX_FRAMES) { "frame budget সীমার বাইরে" }
        require(depth in 1..MAX_DEPTH) { "pipeline depth সীমার বাইরে" }
    }
    var submitted = 0
        private set
    var completed = 0
        private set
    var inFlightCount = 0
        private set
    val inFlight: Boolean get() = inFlightCount > 0
    val exhausted: Boolean get() = submitted == limit && inFlightCount == 0

    fun trySubmit(freeSlots: Int): Boolean {
        if (freeSlots <= 0 || inFlightCount >= depth || submitted >= limit) return false
        submitted++
        inFlightCount++
        return true
    }

    fun finish(): Boolean {
        if (inFlightCount <= 0) return false
        completed++
        inFlightCount--
        return true
    }

    companion object {
        /** Two in-flight requests keep <=2 acquired Images and leave a driver slot free. */
        const val MAX_DEPTH = 2
    }
}
