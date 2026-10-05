package com.tvlauncher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_HOME -> {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                )
            }

            ACTION_LAUNCH -> {
                val repository = AppRepository(context.applicationContext)
                val componentText = intent.getStringExtra(EXTRA_COMPONENT).orEmpty()
                val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()

                when {
                    componentText.isNotEmpty() -> {
                        ComponentName.unflattenFromString(componentText)?.let {
                            repository.launchComponent(it)
                        }
                    }
                    packageName.isNotEmpty() -> repository.launchPackage(packageName)
                    label.isNotEmpty() -> repository.launchLabel(label)
                }
            }

            ACTION_WALLPAPER_REFRESH -> {
                NatureWallpaperManager.refreshAsync(
                    context.applicationContext,
                    force = true
                ) { success ->
                    if (success) notifyWallpaperChanged(context)
                }
            }

            ACTION_WALLPAPER_SET_SOURCE -> {
                val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
                if (
                    source == NatureWallpaperManager.SOURCE_SOLID ||
                    source == NatureWallpaperManager.SOURCE_BING ||
                    source == NatureWallpaperManager.SOURCE_NATURE ||
                    source == NatureWallpaperManager.SOURCE_CUSTOM
                ) {
                    NatureWallpaperManager.setSource(context, source)
                }

                intent.getStringExtra(EXTRA_CUSTOM_URL)?.let {
                    NatureWallpaperManager.setCustomUrl(context, it)
                }

                if (NatureWallpaperManager.getSource(context) == NatureWallpaperManager.SOURCE_SOLID) {
                    notifyWallpaperChanged(context)
                } else {
                    NatureWallpaperManager.refreshAsync(
                        context.applicationContext,
                        force = true
                    ) { success ->
                        if (success) notifyWallpaperChanged(context)
                    }
                }
            }
        }
    }

    private fun notifyWallpaperChanged(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_WALLPAPER_CHANGED).setPackage(context.packageName)
        )
    }

    companion object {
        const val ACTION_HOME = "com.gazelle.launcher.action.HOME"
        const val ACTION_LAUNCH = "com.gazelle.launcher.action.LAUNCH"
        const val ACTION_WALLPAPER_REFRESH =
            "com.gazelle.launcher.action.WALLPAPER_REFRESH"
        const val ACTION_WALLPAPER_SET_SOURCE =
            "com.gazelle.launcher.action.WALLPAPER_SET_SOURCE"
        const val ACTION_WALLPAPER_CHANGED =
            "com.gazelle.launcher.action.WALLPAPER_CHANGED"

        const val EXTRA_COMPONENT = "component"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_CUSTOM_URL = "custom_url"
    }
}
