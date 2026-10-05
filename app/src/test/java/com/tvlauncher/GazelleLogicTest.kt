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
    fun sampleSizeUsesDominantDimension() {
        assertEquals(
            2,
            GazelleLogic.calculateSampleSize(3840, 2160, 1920, 1080)
        )
        assertEquals(
            5,
            GazelleLogic.calculateSampleSize(1920, 5000, 1920, 1080)
        )
        assertEquals(
            1,
            GazelleLogic.calculateSampleSize(1280, 720, 1920, 1080)
        )
    }

    @Test
    fun oneHourIntervalRemainsIndexZero() {
        val values = longArrayOf(1L, 6L, 12L, 24L)
        assertEquals(0, GazelleLogic.intervalIndex(values, 1L, 1))
        assertEquals(1, GazelleLogic.intervalIndex(values, 99L, 1))
    }

    @Test
    fun unresolvedSelectionIsPreserved() {
        val saved = listOf("disabled/.Main", "kodi/.Main")
        val replacements = mapOf<String, String?>(
            "disabled/.Main" to null,
            "kodi/.Main" to "kodi/.Main"
        )
        assertEquals(saved, GazelleLogic.reconcileStoredIds(saved, replacements))
    }

    @Test
    fun renamedSelectionIsRepairedWithoutDroppingOthers() {
        val saved = listOf("app/.OldActivity", "disabled/.Main")
        val replacements = mapOf<String, String?>(
            "app/.OldActivity" to "app/.NewActivity",
            "disabled/.Main" to null
        )
        assertEquals(
            listOf("app/.NewActivity", "disabled/.Main"),
            GazelleLogic.reconcileStoredIds(saved, replacements)
        )
    }
}
