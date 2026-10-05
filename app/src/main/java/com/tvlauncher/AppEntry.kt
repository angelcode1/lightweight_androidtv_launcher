package com.tvlauncher

import android.content.ComponentName

data class AppEntry(
    val component: ComponentName,
    val label: String,
    val packageName: String,
    val activityName: String,
    val isTvApp: Boolean
) {
    val id: String
        get() = component.flattenToString()
}
