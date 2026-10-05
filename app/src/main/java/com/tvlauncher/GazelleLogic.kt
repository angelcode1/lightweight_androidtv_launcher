package com.tvlauncher

import java.util.LinkedHashSet

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

        var sample = 1
        while (
            width / (sample * 2L) >= reqWidth &&
            height / (sample * 2L) >= reqHeight
        ) {
            sample *= 2
        }
        return sample
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
            if (!replacementBySavedId.containsKey(savedId)) {
                return@forEach
            }
            result.add(replacementBySavedId[savedId] ?: savedId)
        }
        return result.toList()
    }
}
