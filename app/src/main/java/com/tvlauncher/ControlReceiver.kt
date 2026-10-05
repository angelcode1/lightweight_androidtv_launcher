package com.tvlauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE).orEmpty()

        when (intent.action) {
            ACTION_HOME -> {
                val success = try {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                        }
                    )
                    true
                } catch (_: Exception) {
                    false
                }
                finishSync(
                    context,
                    requestId,
                    replyPackage,
                    ACTION_HOME,
                    success,
                    if (success) "home_started" else "home_start_failed"
                )
            }

            ACTION_LAUNCH -> {
                val repository = AppRepository(context.applicationContext)
                val componentText = intent.getStringExtra(EXTRA_COMPONENT).orEmpty()
                val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()

                val success = if (componentText.isNotEmpty()) {
                    val component = ComponentName.unflattenFromString(componentText)
                    if (component == null) {
                        false
                    } else {
                        repository.launchComponent(
                            component,
                            packageName.ifEmpty { component.packageName }
                        )
                    }
                } else if (packageName.isNotEmpty()) {
                    repository.launchPackage(packageName)
                } else {
                    false
                }

                finishSync(
                    context,
                    requestId,
                    replyPackage,
                    ACTION_LAUNCH,
                    success,
                    if (success) "launch_started" else "launch_failed"
                )
            }

            ACTION_WALLPAPER_REFRESH -> {
                val pending = goAsync()
                NatureWallpaperManager.refreshAsync(
                    context.applicationContext,
                    force = true
                ) { success ->
                    if (success) notifyWallpaperChanged(context)
                    sendReply(
                        context,
                        requestId,
                        replyPackage,
                        ACTION_WALLPAPER_REFRESH,
                        success,
                        if (success) "wallpaper_refreshed" else "wallpaper_refresh_failed"
                    )
                    pending.finish()
                }
            }

            ACTION_WALLPAPER_SET_SOURCE -> {
                val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
                val validSource =
                    source == NatureWallpaperManager.SOURCE_SOLID ||
                        source == NatureWallpaperManager.SOURCE_BING ||
                        source == NatureWallpaperManager.SOURCE_NATURE ||
                        source == NatureWallpaperManager.SOURCE_CUSTOM

                if (!validSource) {
                    finishSync(
                        context,
                        requestId,
                        replyPackage,
                        ACTION_WALLPAPER_SET_SOURCE,
                        false,
                        "invalid_wallpaper_source"
                    )
                    return
                }

                NatureWallpaperManager.setSource(context, source)
                intent.getStringExtra(EXTRA_CUSTOM_URL)?.let {
                    NatureWallpaperManager.setCustomUrl(context, it)
                }

                if (source == NatureWallpaperManager.SOURCE_SOLID) {
                    notifyWallpaperChanged(context)
                    finishSync(
                        context,
                        requestId,
                        replyPackage,
                        ACTION_WALLPAPER_SET_SOURCE,
                        true,
                        "wallpaper_source_set"
                    )
                    return
                }

                val pending = goAsync()
                NatureWallpaperManager.refreshAsync(
                    context.applicationContext,
                    force = true
                ) { success ->
                    if (success) notifyWallpaperChanged(context)
                    sendReply(
                        context,
                        requestId,
                        replyPackage,
                        ACTION_WALLPAPER_SET_SOURCE,
                        success,
                        if (success) "wallpaper_source_set" else "wallpaper_source_fetch_failed"
                    )
                    pending.finish()
                }
            }

            else -> {
                finishSync(
                    context,
                    requestId,
                    replyPackage,
                    intent.action.orEmpty(),
                    false,
                    "unsupported_action"
                )
            }
        }
    }

    private fun finishSync(
        context: Context,
        requestId: String,
        replyPackage: String,
        command: String,
        success: Boolean,
        message: String
    ) {
        if (isOrderedBroadcast) {
            resultCode = if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED
            resultData = message
        }
        sendReply(context, requestId, replyPackage, command, success, message)
    }

    private fun sendReply(
        context: Context,
        requestId: String,
        replyPackage: String,
        command: String,
        success: Boolean,
        message: String
    ) {
        if (replyPackage.isEmpty()) return

        val reply = Intent(ACTION_RESULT)
            .setPackage(replyPackage)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_COMMAND, command)
            .putExtra(EXTRA_SUCCESS, success)
            .putExtra(EXTRA_MESSAGE, message)

        context.sendBroadcast(reply, CONTROL_PERMISSION)
    }

    private fun notifyWallpaperChanged(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_WALLPAPER_CHANGED).setPackage(context.packageName)
        )
    }

    companion object {
        const val CONTROL_PERMISSION = "com.gazelle.launcher.permission.CONTROL"

        const val ACTION_HOME = "com.gazelle.launcher.action.HOME"
        const val ACTION_LAUNCH = "com.gazelle.launcher.action.LAUNCH"
        const val ACTION_WALLPAPER_REFRESH =
            "com.gazelle.launcher.action.WALLPAPER_REFRESH"
        const val ACTION_WALLPAPER_SET_SOURCE =
            "com.gazelle.launcher.action.WALLPAPER_SET_SOURCE"
        const val ACTION_WALLPAPER_CHANGED =
            "com.gazelle.launcher.action.WALLPAPER_CHANGED"
        const val ACTION_RESULT =
            "com.gazelle.launcher.action.RESULT"

        const val EXTRA_COMPONENT = "component"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_CUSTOM_URL = "custom_url"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_REPLY_PACKAGE = "reply_package"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
    }
}
