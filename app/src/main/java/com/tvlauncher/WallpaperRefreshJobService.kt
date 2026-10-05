package com.tvlauncher

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle

class WallpaperRefreshJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val extras = params.extras
        val command = extras.getString(EXTRA_COMMAND, ControlReceiver.ACTION_WALLPAPER_REFRESH)
        val requestId = extras.getString(ControlReceiver.EXTRA_REQUEST_ID, "")
        val replyPackage = extras.getString(ControlReceiver.EXTRA_REPLY_PACKAGE, "")

        if (command == ControlReceiver.ACTION_WALLPAPER_SET_SOURCE) {
            val source = extras.getString(ControlReceiver.EXTRA_SOURCE, "")
            NatureWallpaperManager.setSource(applicationContext, source)
            extras.getString(ControlReceiver.EXTRA_CUSTOM_URL, "")
                .takeIf { it.isNotEmpty() }
                ?.let { NatureWallpaperManager.setCustomUrl(applicationContext, it) }
        }

        NatureWallpaperManager.refreshAsync(
            applicationContext,
            force = true
        ) { result ->
            val success = result == NatureWallpaperManager.RefreshResult.SUCCESS
            if (success) {
                notifyWallpaperChanged()
            }

            val message = when (result) {
                NatureWallpaperManager.RefreshResult.SUCCESS ->
                    if (command == ControlReceiver.ACTION_WALLPAPER_SET_SOURCE) {
                        "wallpaper_source_set"
                    } else {
                        "wallpaper_refreshed"
                    }
                NatureWallpaperManager.RefreshResult.BUSY -> "already_in_progress"
                NatureWallpaperManager.RefreshResult.STALE -> "superseded"
                NatureWallpaperManager.RefreshResult.FAILED ->
                    if (command == ControlReceiver.ACTION_WALLPAPER_SET_SOURCE) {
                        "wallpaper_source_fetch_failed"
                    } else {
                        "wallpaper_refresh_failed"
                    }
            }

            sendFinalResult(
                requestId = requestId,
                replyPackage = replyPackage,
                command = command,
                success = success,
                message = message
            )
            jobFinished(params, false)
        }

        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return true
    }

    private fun notifyWallpaperChanged() {
        sendBroadcast(
            Intent(ControlReceiver.ACTION_WALLPAPER_CHANGED).setPackage(packageName)
        )
    }

    private fun sendFinalResult(
        requestId: String,
        replyPackage: String,
        command: String,
        success: Boolean,
        message: String
    ) {
        if (replyPackage.isEmpty()) return

        val reply = Intent(ControlReceiver.ACTION_RESULT)
            .setPackage(replyPackage)
            .putExtra(ControlReceiver.EXTRA_REQUEST_ID, requestId)
            .putExtra(ControlReceiver.EXTRA_COMMAND, command)
            .putExtra(ControlReceiver.EXTRA_SUCCESS, success)
            .putExtra(ControlReceiver.EXTRA_MESSAGE, message)

        sendBroadcast(reply, ControlReceiver.CONTROL_PERMISSION)
    }

    companion object {
        private const val JOB_ID = 0x475A11
        private const val EXTRA_COMMAND = "job_command"

        enum class ScheduleResult {
            ACCEPTED,
            BUSY,
            FAILED
        }

        fun schedule(
            context: Context,
            command: String,
            requestId: String,
            replyPackage: String,
            source: String? = null,
            customUrl: String? = null
        ): ScheduleResult {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE)
                as? JobScheduler ?: return ScheduleResult.FAILED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (scheduler.getPendingJob(JOB_ID) != null) {
                    return ScheduleResult.BUSY
                }
            }

            val extras = PersistableBundle().apply {
                putString(EXTRA_COMMAND, command)
                putString(ControlReceiver.EXTRA_REQUEST_ID, requestId)
                putString(ControlReceiver.EXTRA_REPLY_PACKAGE, replyPackage)
                if (source != null) putString(ControlReceiver.EXTRA_SOURCE, source)
                if (customUrl != null) putString(ControlReceiver.EXTRA_CUSTOM_URL, customUrl)
            }

            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, WallpaperRefreshJobService::class.java)
            )
                .setExtras(extras)
                .build()

            return if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) {
                ScheduleResult.ACCEPTED
            } else {
                ScheduleResult.FAILED
            }
        }
    }
}
