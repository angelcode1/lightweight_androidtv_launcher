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
                setOrderedResult(
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
                } else {
                    repository.launchPackage(packageName)
                }

                setOrderedResult(
                    success,
                    if (success) "launch_started" else "launch_failed"
                )
            }

            ACTION_WALLPAPER_REFRESH -> {
                handleWallpaperJob(
                    context = context,
                    command = ACTION_WALLPAPER_REFRESH,
                    requestId = requestId,
                    replyPackage = replyPackage
                )
            }

            ACTION_WALLPAPER_SET_SOURCE -> {
                val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
                if (!isValidSource(source)) {
                    rejectWallpaperCommand(
                        context,
                        requestId,
                        replyPackage,
                        ACTION_WALLPAPER_SET_SOURCE,
                        "invalid_wallpaper_source"
                    )
                    return
                }

                val customUrl = intent.getStringExtra(EXTRA_CUSTOM_URL)
                if (source == NatureWallpaperManager.SOURCE_SOLID) {
                    NatureWallpaperManager.setSource(context, source)
                    context.sendBroadcast(
                        Intent(ACTION_WALLPAPER_CHANGED).setPackage(context.packageName)
                    )
                    setOrderedResult(true, "accepted")
                    sendFinalResult(
                        context,
                        requestId,
                        replyPackage,
                        ACTION_WALLPAPER_SET_SOURCE,
                        true,
                        "wallpaper_source_set"
                    )
                    return
                }

                handleWallpaperJob(
                    context = context,
                    command = ACTION_WALLPAPER_SET_SOURCE,
                    requestId = requestId,
                    replyPackage = replyPackage,
                    source = source,
                    customUrl = customUrl
                )
            }

            else -> setOrderedResult(false, "unsupported_action")
        }
    }

    private fun handleWallpaperJob(
        context: Context,
        command: String,
        requestId: String,
        replyPackage: String,
        source: String? = null,
        customUrl: String? = null
    ) {
        when (
            WallpaperRefreshJobService.schedule(
                context = context,
                command = command,
                requestId = requestId,
                replyPackage = replyPackage,
                source = source,
                customUrl = customUrl
            )
        ) {
            WallpaperRefreshJobService.Companion.ScheduleResult.ACCEPTED ->
                setOrderedResult(true, "accepted")

            WallpaperRefreshJobService.Companion.ScheduleResult.BUSY -> {
                setOrderedResult(false, "already_in_progress")
                sendFinalResult(
                    context,
                    requestId,
                    replyPackage,
                    command,
                    false,
                    "already_in_progress"
                )
            }

            WallpaperRefreshJobService.Companion.ScheduleResult.FAILED -> {
                setOrderedResult(false, "schedule_failed")
                sendFinalResult(
                    context,
                    requestId,
                    replyPackage,
                    command,
                    false,
                    "schedule_failed"
                )
            }
        }
    }

    private fun rejectWallpaperCommand(
        context: Context,
        requestId: String,
        replyPackage: String,
        command: String,
        message: String
    ) {
        setOrderedResult(false, message)
        sendFinalResult(
            context,
            requestId,
            replyPackage,
            command,
            false,
            message
        )
    }

    private fun setOrderedResult(success: Boolean, message: String) {
        if (!isOrderedBroadcast) return
        resultCode = if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED
        resultData = message
    }

    private fun sendFinalResult(
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

    private fun isValidSource(source: String): Boolean {
        return source == NatureWallpaperManager.SOURCE_SOLID ||
            source == NatureWallpaperManager.SOURCE_BING ||
            source == NatureWallpaperManager.SOURCE_AMAZON ||
            source == NatureWallpaperManager.SOURCE_NATURE ||
            source == NatureWallpaperManager.SOURCE_CUSTOM
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
