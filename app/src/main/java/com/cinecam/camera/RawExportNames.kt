package com.cinecam.camera

/** Flat shared directory: all companion files keep one unique, recognisable capture prefix. */
internal object RawExportNames {
    fun name(session: String, original: String): String {
        require(session.matches(Regex("CineCam_[0-9]+_[a-f0-9]{8}")))
        require(original.length in 1..200)
        require(original.matches(Regex("[A-Za-z0-9_.-]+")) && original != "." && original != "..")
        return "${session}_$original"
    }
    fun sequenceReference(session: String, current: String, originals: Set<String>): String {
        val dngs = originals.filter { it.endsWith(".dng") }
        if (current in dngs) return name(session, current)
        return dngs.map { name(session, it) }.firstOrNull { it == current }
            ?: error("Sequence DNG export mapping missing")
    }
}
