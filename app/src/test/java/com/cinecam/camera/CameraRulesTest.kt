package com.cinecam.camera

import org.junit.Assert.*
import org.junit.Test

class CameraRulesTest {
    @Test fun legacyAndExternalAreNotRankedAboveFull() {
        assertTrue(CameraRules.cinemaReady(true, true, 1))
        assertTrue(CameraRules.cinemaReady(true, true, 3))
        for (level in listOf(0, 2, 4, -1)) assertFalse(CameraRules.cinemaReady(true, true, level))
        assertFalse(CameraRules.cinemaReady(false, true, 3))
        assertFalse(CameraRules.cinemaReady(true, false, 3))
    }

    @Test fun sensorRotationHandlesAllDisplayOrientations() {
        assertEquals(90, CameraRules.relativeRotation(90, 0, false))
        assertEquals(0, CameraRules.relativeRotation(90, 90, false))
        assertEquals(270, CameraRules.relativeRotation(90, 180, false))
        assertEquals(180, CameraRules.relativeRotation(90, 270, false))
        assertEquals(0, CameraRules.relativeRotation(270, 90, true))
    }
}
