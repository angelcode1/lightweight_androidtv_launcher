package com.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GazelleLogicTest {
    @Test
    fun rejectsTallCompressedDecodeBombDimensions() {
        assertFalse(GazelleLogic.isSafeImageDimensions(1000, 60000))
        assertFalse(GazelleLogic.isSafeImageDimensions(3839, 100000))
        assertTrue(GazelleLogic.isSafeImageDimensions(3840, 2160))
    }

    @Test
    fun centreCropSamplingNeverForcesUpscaling() {
        assertEquals(
            2,
            GazelleLogic.calculateSampleSize(3840, 2160, 1920, 1080)
        )
        assertEquals(
            1,
            GazelleLogic.calculateSampleSize(2560, 1440, 1920, 1080)
        )
        assertEquals(
            1,
            GazelleLogic.calculateSampleSize(1920, 1200, 1920, 1080)
        )
    }

    @Test
    fun sampleSizeIsAlwaysPowerOfTwo() {
        assertEquals(
            4,
            GazelleLogic.calculateSampleSize(8000, 5000, 1920, 1080)
        )
    }

    @Test
    fun oneHourIntervalRemainsIndexZero() {
        val values = longArrayOf(1L, 6L, 12L, 24L)
        assertEquals(0, GazelleLogic.intervalIndex(values, 1L, 1))
        assertEquals(1, GazelleLogic.intervalIndex(values, 99L, 1))
    }

    @Test
    fun disabledSelectionIsPreservedButUninstalledSelectionIsDropped() {
        val saved = listOf("disabled/.Main", "gone/.Main", "kodi/.Old")
        val replacements = mapOf<String, String?>(
            "disabled/.Main" to null,
            "kodi/.Old" to "kodi/.Main"
        )
        assertEquals(
            listOf("disabled/.Main", "kodi/.Main"),
            GazelleLogic.reconcileStoredIds(saved, replacements)
        )
    }
}
