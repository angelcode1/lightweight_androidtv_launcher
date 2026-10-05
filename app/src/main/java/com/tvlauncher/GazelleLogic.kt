package com.tvlauncher

import java.util.LinkedHashSet
import kotlin.math.max

object GazelleLogic {
    const val MAX_SOURCE_PIXELS: Long = 3840L * 2160L

    fun isSafeImageDimensions(
        width: Int,
        height: Int,
        maxPixels: Long = MAX_SOURCE_PIXELS
    ): Boolean {
        if (width <= 0 || height <= 0 || maxPixels <= 0L) return false
        return width.toLong() <= maxPixels / height.toLong()
    }

    fun calculateSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) {
            return 1
        }

        val widthRatio = ceilDiv(width, reqWidth)
        val heightRatio = ceilDiv(height, reqHeight)
        return max(1, max(widthRatio, heightRatio))
    }

    fun intervalIndex(
        values: LongArray,
        storedValue: Long,
        defaultIndex: Int
    ): Int {
        if (values.isEmpty()) return 0
        val found = values.indexOf(storedValue)
        if (found >= 0) return found
        return defaultIndex.coerceIn(values.indices)
    }

    fun reconcileStoredIds(
        savedIds: List<String>,
        replacementBySavedId: Map<String, String?>
    ): List<String> {
        val result = LinkedHashSet<String>()
        savedIds.forEach { savedId ->
            val replacement = replacementBySavedId[savedId]
            result.add(replacement ?: savedId)
        }
        return result.toList()
    }

    private fun ceilDiv(value: Int, divisor: Int): Int {
        return ((value.toLong() + divisor.toLong() - 1L) / divisor.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }
}
