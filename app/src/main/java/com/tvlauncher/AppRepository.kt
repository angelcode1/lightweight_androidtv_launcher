package com.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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

    fun getSelectedIds(): List<String> = synchronized(selectionLock) {
        getSelectedIdsUnlocked()
    }

    private fun getSelectedIdsUnlocked(): List<String> {
        val encoded = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_ORDER, null)
            .orEmpty()
        if (encoded.isEmpty()) return emptyList()

        return encoded.split(ORDER_DELIMITER)
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_APPS)
    }

    fun saveSelectedIds(ids: Collection<String>) = synchronized(selectionLock) {
        saveSelectedIdsUnlocked(ids)
    }

    private fun saveSelectedIdsUnlocked(ids: Collection<String>) {
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
        val savedIds = getSelectedIds()
        if (savedIds.isEmpty()) return emptyList()

        val result = mutableListOf<AppEntry>()
        val replacements = mutableMapOf<String, String?>()

        savedIds.forEach { savedId ->
            val component = ComponentName.unflattenFromString(savedId)
                ?: return@forEach

            val current = resolveActivity(component)
            if (current != null) {
                result.add(current)
                replacements[savedId] = current.id
                return@forEach
            }

            val appInfo = getInstalledApplication(component.packageName)
                ?: return@forEach

            if (!isApplicationEnabled(component.packageName, appInfo)) {
                replacements[savedId] = null
                return@forEach
            }

            val fallback = resolvePreferredLauncherEntry(component.packageName)
            if (fallback != null) {
                result.add(fallback)
                replacements[savedId] = fallback.id
            }
        }

        val reconciled = GazelleLogic.reconcileStoredIds(savedIds, replacements)
        if (reconciled != savedIds) {
            synchronized(selectionLock) {
                if (getSelectedIdsUnlocked() == savedIds) {
                    saveSelectedIdsUnlocked(reconciled)
                }
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun getInstalledApplication(packageName: String): ApplicationInfo? {
        return try {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.GET_DISABLED_COMPONENTS
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun isApplicationEnabled(
        packageName: String,
        appInfo: ApplicationInfo
    ): Boolean {
        val explicitState = try {
            packageManager.getApplicationEnabledSetting(packageName)
        } catch (_: IllegalArgumentException) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }

        if (
            explicitState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            explicitState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            explicitState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        ) {
            return false
        }
        return appInfo.enabled
    }

    private fun resolveActivity(component: ComponentName): AppEntry? {
        val activity = try {
            packageManager.getActivityInfo(component, 0)
        } catch (_: Exception) {
            null
        } ?: return null

        if (!activity.enabled || !activity.applicationInfo.enabled) return null
        return activity.toEntry(component, isTvApp = false)
    }

    private fun resolvePreferredLauncherEntry(packageName: String): AppEntry? {
        val leanback = packageManager.getLeanbackLaunchIntentForPackage(packageName)
        resolveLaunchIntent(leanback, isTvApp = true)?.let { return it }

        val launcher = packageManager.getLaunchIntentForPackage(packageName)
        return resolveLaunchIntent(launcher, isTvApp = false)
    }

    private fun resolveLaunchIntent(intent: Intent?, isTvApp: Boolean): AppEntry? {
        if (intent == null) return null
        val component = intent.component ?: intent.resolveActivity(packageManager)
            ?: return null

        val activity = try {
            packageManager.getActivityInfo(component, 0)
        } catch (_: Exception) {
            null
        } ?: return null

        if (!activity.enabled || !activity.applicationInfo.enabled) return null
        return activity.toEntry(component, isTvApp)
    }

    private fun ActivityInfo.toEntry(
        component: ComponentName,
        isTvApp: Boolean
    ): AppEntry {
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
            isTvApp = isTvApp
        )
    }

    fun swapSelected(firstId: String, secondId: String): Boolean =
        synchronized(selectionLock) {
            val current = getSelectedIdsUnlocked().toMutableList()
            val first = current.indexOf(firstId)
            val second = current.indexOf(secondId)
            if (first < 0 || second < 0 || first == second) {
                return@synchronized false
            }

            val tmp = current[first]
            current[first] = current[second]
            current[second] = tmp
            saveSelectedIdsUnlocked(current)
            true
        }

    fun removeSelected(id: String) = synchronized(selectionLock) {
        saveSelectedIdsUnlocked(
            getSelectedIdsUnlocked().filterNot { it == id }
        )
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
        if (packageName.isBlank()) return false

        return try {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
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
        private val selectionLock = Any()
    }
}
