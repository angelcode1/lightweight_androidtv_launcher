package com.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

class AppRepository(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    private val iconCache = object : LinkedHashMap<String, Drawable>(
        ICON_CACHE_MAX,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Drawable>?
        ): Boolean = size > ICON_CACHE_MAX
    }

    fun queryLaunchableApps(): List<AppEntry> {
        val apps = LinkedHashMap<String, AppEntry>()
        queryCategory(Intent.CATEGORY_LEANBACK_LAUNCHER, true, apps)
        queryCategory(Intent.CATEGORY_LAUNCHER, false, apps)

        return apps.values.sortedWith(
            compareBy<AppEntry> { it.label.lowercase(Locale.ROOT) }
                .thenBy { it.packageName }
                .thenBy { it.activityName }
        )
    }

    @Suppress("DEPRECATION")
    private fun queryCategory(
        category: String,
        isTvApp: Boolean,
        destination: LinkedHashMap<String, AppEntry>
    ) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(category)
        }

        packageManager.queryIntentActivities(intent, 0).forEach { resolveInfo ->
            val activity = resolveInfo.activityInfo ?: return@forEach
            if (!activity.enabled || !activity.applicationInfo.enabled) return@forEach
            if (activity.packageName == context.packageName) return@forEach

            val component = ComponentName(activity.packageName, activity.name)
            val id = component.flattenToString()
            val label = resolveInfo.loadLabel(packageManager)
                ?.toString()
                ?.trim()
                .takeUnless { it.isNullOrEmpty() }
                ?: activity.packageName

            val existing = destination[id]
            if (existing == null) {
                destination[id] = AppEntry(
                    component = component,
                    label = label,
                    packageName = activity.packageName,
                    activityName = activity.name,
                    isTvApp = isTvApp
                )
            } else if (isTvApp && !existing.isTvApp) {
                destination[id] = existing.copy(isTvApp = true)
            }
        }
    }

    fun getSelectedIds(): List<String> {
        val encoded = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_ORDER, null)
            .orEmpty()
        if (encoded.isEmpty()) return emptyList()

        return encoded.split(ORDER_DELIMITER)
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_APPS)
    }

    fun saveSelectedIds(ids: Collection<String>) {
        val normalized = LinkedHashSet<String>()
        ids.forEach { id ->
            if (id.isNotBlank() && normalized.size < MAX_APPS) {
                normalized.add(id)
            }
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_ORDER, normalized.joinToString(ORDER_DELIMITER))
            .apply()
    }

    fun getSelectedEntries(): List<AppEntry> {
        val ids = getSelectedIds()
        if (ids.isEmpty()) return emptyList()

        val repairedIds = mutableListOf<String>()
        val result = mutableListOf<AppEntry>()
        var launchableApps: List<AppEntry>? = null

        ids.forEach { id ->
            val component = ComponentName.unflattenFromString(id) ?: return@forEach
            val current = resolveActivity(component)

            if (current != null) {
                result.add(current)
                repairedIds.add(current.id)
                return@forEach
            }

            // App updates sometimes rename launcher aliases/activities. Preserve the
            // tile by resolving the package's current preferred launcher component.
            val apps = launchableApps ?: queryLaunchableApps().also {
                launchableApps = it
            }
            val fallback = preferredEntryForPackage(component.packageName, apps)
                ?: return@forEach

            result.add(fallback)
            repairedIds.add(fallback.id)
        }

        if (repairedIds != ids) {
            saveSelectedIds(repairedIds)
        }
        return result
    }

    private fun resolveActivity(component: ComponentName): AppEntry? {
        val activity = try {
            packageManager.getActivityInfo(component, 0)
        } catch (_: Exception) {
            null
        } ?: return null

        if (!activity.enabled || !activity.applicationInfo.enabled) return null
        return activity.toEntry(component)
    }

    private fun preferredEntryForPackage(
        packageName: String,
        apps: List<AppEntry> = queryLaunchableApps()
    ): AppEntry? {
        return apps.asSequence()
            .filter { it.packageName == packageName }
            .sortedByDescending { it.isTvApp }
            .firstOrNull()
    }

    private fun ActivityInfo.toEntry(component: ComponentName): AppEntry {
        val label = loadLabel(packageManager)
            ?.toString()
            ?.trim()
            .takeUnless { it.isNullOrEmpty() }
            ?: packageName

        return AppEntry(
            component = component,
            label = label,
            packageName = packageName,
            activityName = name,
            isTvApp = false
        )
    }

    fun moveSelected(from: Int, to: Int): Boolean {
        val current = getSelectedIds().toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) {
            return false
        }
        Collections.swap(current, from, to)
        saveSelectedIds(current)
        return true
    }

    fun removeSelected(id: String) {
        saveSelectedIds(getSelectedIds().filterNot { it == id })
    }

    fun launch(entry: AppEntry): Boolean {
        return launchComponent(entry.component, entry.packageName)
    }

    fun launchComponent(
        component: ComponentName,
        fallbackPackage: String = component.packageName
    ): Boolean {
        if (launchComponentExact(component)) return true
        return launchPackage(fallbackPackage)
    }

    private fun launchComponentExact(component: ComponentName): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun launchPackage(packageName: String): Boolean {
        val match = preferredEntryForPackage(packageName)
        if (match != null && launchComponentExact(match.component)) {
            return true
        }

        return try {
            val fallback = packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun loadRoundedIcon(entry: AppEntry, sizePx: Int): Drawable {
        val cacheKey = entry.id + "@" + sizePx
        synchronized(iconCache) {
            iconCache[cacheKey]?.let { return it }
        }

        val source = try {
            packageManager.getActivityInfo(entry.component, 0).loadIcon(packageManager)
        } catch (_: Exception) {
            packageManager.defaultActivityIcon
        }

        val rendered = renderRoundedIcon(source, sizePx)
        synchronized(iconCache) {
            iconCache[cacheKey] = rendered
        }
        return rendered
    }

    private fun renderRoundedIcon(source: Drawable, sizePx: Int): Drawable {
        if (sizePx <= 0) return source

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val radius = sizePx * 0.22f
        val path = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(path)
        source.setBounds(0, 0, sizePx, sizePx)
        source.draw(canvas)
        canvas.restore()

        return BitmapDrawable(context.resources, bitmap)
    }

    fun clearIconCache() {
        synchronized(iconCache) {
            iconCache.clear()
        }
    }

    companion object {
        const val MAX_APPS = 17
        private const val ICON_CACHE_MAX = 24
        private const val PREFS_NAME = "gazelle_home"
        private const val PREF_ORDER = "components"
        private const val ORDER_DELIMITER = "\u001E"
    }
}
