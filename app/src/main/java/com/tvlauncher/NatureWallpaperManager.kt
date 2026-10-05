package com.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

object NatureWallpaperManager {
    const val SOURCE_SOLID = "solid"
    const val SOURCE_BING = "bing"
    const val SOURCE_NATURE = "nature"
    const val SOURCE_CUSTOM = "custom"

    const val INTERVAL_1H = 60L * 60L * 1000L
    const val INTERVAL_6H = 6L * INTERVAL_1H
    const val INTERVAL_12H = 12L * INTERVAL_1H
    const val INTERVAL_24H = 24L * INTERVAL_1H

    private const val PREFS_NAME = "gazelle_wallpaper"
    private const val PREF_SOURCE = "source"
    private const val PREF_INTERVAL = "interval"
    private const val PREF_CUSTOM_URL = "custom_url"
    private const val PREF_DIM = "dim"
    private const val PREF_LAST_FETCH = "last_fetch"
    private const val PREF_LAST_REMOTE_URL = "last_remote_url"

    private const val CACHE_FILE_NAME = "gazelle-wallpaper.img"
    private const val MAX_DOWNLOAD_BYTES = 12L * 1024L * 1024L
    private const val MAX_TEXT_BYTES = 1024L * 1024L
    private const val MAX_REDIRECTS = 5
    private const val MAX_REFRESH_DURATION_MS = 20_000L
    private const val MAX_CONNECT_TIMEOUT_MS = 5_000
    private const val MAX_READ_TIMEOUT_MS = 8_000

    private val refreshing = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class RefreshResult {
        SUCCESS,
        FAILED,
        BUSY
    }

    fun getSource(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_SOURCE, SOURCE_SOLID)
            ?: SOURCE_SOLID
    }

    fun setSource(context: Context, source: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(PREF_SOURCE, SOURCE_SOLID) != source) {
            prefs.edit()
                .putString(PREF_SOURCE, source)
                .putLong(PREF_LAST_FETCH, 0L)
                .apply()
        }
    }

    fun getInterval(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_INTERVAL, INTERVAL_6H)
    }

    fun setInterval(context: Context, interval: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_INTERVAL, interval)
            .apply()
    }

    fun getCustomUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_CUSTOM_URL, "")
            .orEmpty()
    }

    fun setCustomUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_CUSTOM_URL, url.trim())
            .putLong(PREF_LAST_FETCH, 0L)
            .apply()
    }

    fun isDimEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_DIM, true)
    }

    fun setDimEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_DIM, enabled)
            .apply()
    }

    fun shouldRefresh(context: Context): Boolean {
        if (getSource(context) == SOURCE_SOLID) return false
        val cache = cacheFile(context)
        if (!cache.exists() || cache.length() == 0L) return true

        val lastFetch = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_LAST_FETCH, 0L)
        return System.currentTimeMillis() - lastFetch >= getInterval(context)
    }

    fun loadCachedDrawable(
        context: Context,
        targetWidth: Int,
        targetHeight: Int
    ): Drawable? {
        val file = cacheFile(context)
        if (!file.exists() || file.length() == 0L) return null

        val bitmap = decodeForDisplay(file, targetWidth, targetHeight) ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    fun refreshAsync(
        context: Context,
        force: Boolean,
        onComplete: (RefreshResult) -> Unit = {}
    ) {
        if (getSource(context) == SOURCE_SOLID) {
            mainHandler.post { onComplete(RefreshResult.SUCCESS) }
            return
        }

        if (!force && !shouldRefresh(context)) {
            mainHandler.post { onComplete(RefreshResult.SUCCESS) }
            return
        }

        if (!refreshing.compareAndSet(false, true)) {
            mainHandler.post { onComplete(RefreshResult.BUSY) }
            return
        }

        Thread {
            var result = RefreshResult.FAILED
            val deadline = SystemClock.elapsedRealtime() + MAX_REFRESH_DURATION_MS
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val previousUrl = prefs.getString(PREF_LAST_REMOTE_URL, "").orEmpty()
                val remoteUrl = when (getSource(context)) {
                    SOURCE_BING -> fetchBingImageUrl(previousUrl, deadline)
                    SOURCE_NATURE -> fetchNatureImageUrl(previousUrl, deadline)
                    SOURCE_CUSTOM -> getCustomUrl(context).takeIf { isHttpsUrl(it) }
                    else -> null
                }

                if (!remoteUrl.isNullOrBlank() && !deadlineExpired(deadline)) {
                    if (downloadImage(context, remoteUrl, deadline)) {
                        prefs.edit()
                            .putLong(PREF_LAST_FETCH, System.currentTimeMillis())
                            .putString(PREF_LAST_REMOTE_URL, remoteUrl)
                            .apply()
                        result = RefreshResult.SUCCESS
                    }
                }
            } catch (_: Exception) {
                result = RefreshResult.FAILED
            } catch (_: OutOfMemoryError) {
                result = RefreshResult.FAILED
            } finally {
                refreshing.set(false)
                mainHandler.post { onComplete(result) }
            }
        }.apply {
            name = "gazelle-wallpaper-refresh"
            isDaemon = true
        }.start()
    }

    private fun fetchBingImageUrl(previousUrl: String, deadline: Long): String? {
        val endpoint =
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-AU"
        val body = fetchText(endpoint, deadline) ?: return null
        val images = JSONObject(body).optJSONArray("images") ?: return null

        val urls = mutableListOf<String>()
        for (i in 0 until images.length()) {
            val relative = images.optJSONObject(i)?.optString("url").orEmpty()
            if (relative.isEmpty()) continue

            val resolved = if (relative.startsWith("https://")) {
                relative
            } else {
                URL(URL("https://www.bing.com/"), relative).toString()
            }
            if (isHttpsUrl(resolved)) urls.add(resolved)
        }

        if (urls.isEmpty()) return null
        return urls.firstOrNull { it != previousUrl } ?: urls.first()
    }

    private fun fetchNatureImageUrl(previousUrl: String, deadline: Long): String? {
        val endpoint =
            "https://wallhaven.cc/api/v1/search?q=nature&categories=100&purity=100&sorting=random&ratios=16x9"
        val body = fetchText(endpoint, deadline) ?: return null
        val data = JSONObject(body).optJSONArray("data") ?: return null

        val urls = mutableListOf<String>()
        for (i in 0 until data.length()) {
            val path = data.optJSONObject(i)?.optString("path").orEmpty()
            if (isHttpsUrl(path)) urls.add(path)
        }

        if (urls.isEmpty()) return null
        return urls.firstOrNull { it != previousUrl } ?: urls.first()
    }

    private fun fetchText(initialUrl: String, deadline: Long): String? {
        var currentUrl = initialUrl
        var redirects = 0

        while (redirects <= MAX_REDIRECTS && !deadlineExpired(deadline)) {
            if (!isHttpsUrl(currentUrl)) return null

            val url = try {
                URL(currentUrl)
            } catch (_: Exception) {
                return null
            }

            var connection: HttpURLConnection? = null
            try {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutFor(deadline, MAX_CONNECT_TIMEOUT_MS)
                    readTimeout = timeoutFor(deadline, MAX_READ_TIMEOUT_MS)
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "GazelleLauncher/1.0 (Android TV)")
                    setRequestProperty("Accept", "application/json,text/plain,*/*")
                }

                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308 -> {
                        val location = connection.getHeaderField("Location") ?: return null
                        currentUrl = URL(url, location).toString()
                        redirects++
                        continue
                    }
                    in 200..299 -> Unit
                    else -> return null
                }

                val contentLength = connection.contentLengthLong
                if (contentLength > MAX_TEXT_BYTES) return null

                connection.inputStream.bufferedReader().use { reader ->
                    val builder = StringBuilder()
                    val buffer = CharArray(4096)
                    var total = 0L
                    while (!deadlineExpired(deadline)) {
                        val count = reader.read(buffer)
                        if (count <= 0) {
                            return builder.toString()
                        }
                        total += count
                        if (total > MAX_TEXT_BYTES) return null
                        builder.append(buffer, 0, count)
                    }
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    private fun downloadImage(
        context: Context,
        initialUrl: String,
        deadline: Long
    ): Boolean {
        var currentUrl = initialUrl
        var redirects = 0

        while (redirects <= MAX_REDIRECTS && !deadlineExpired(deadline)) {
            if (!isHttpsUrl(currentUrl)) return false

            val url = try {
                URL(currentUrl)
            } catch (_: Exception) {
                return false
            }

            var connection: HttpURLConnection? = null
            try {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutFor(deadline, MAX_CONNECT_TIMEOUT_MS)
                    readTimeout = timeoutFor(deadline, MAX_READ_TIMEOUT_MS)
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "GazelleLauncher/1.0 (Android TV)")
                    setRequestProperty("Accept", "image/*")
                }

                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308 -> {
                        val location = connection.getHeaderField("Location") ?: return false
                        currentUrl = URL(url, location).toString()
                        redirects++
                        continue
                    }
                    in 200..299 -> Unit
                    else -> return false
                }

                val contentLength = connection.contentLengthLong
                if (contentLength > MAX_DOWNLOAD_BYTES) return false

                val type = connection.contentType.orEmpty().lowercase()
                if (type.isNotEmpty() && !type.startsWith("image/")) return false

                val temp = File(context.cacheDir, CACHE_FILE_NAME + ".tmp")
                var total = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(8192)
                        while (!deadlineExpired(deadline)) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            total += count
                            if (total > MAX_DOWNLOAD_BYTES) {
                                temp.delete()
                                return false
                            }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }

                if (deadlineExpired(deadline)) {
                    temp.delete()
                    return false
                }

                if (total < 1024L || !isValidImage(temp)) {
                    temp.delete()
                    return false
                }

                val destination = cacheFile(context)
                if (destination.exists() && !destination.delete()) {
                    temp.delete()
                    return false
                }

                if (!temp.renameTo(destination)) {
                    temp.delete()
                    return false
                }
                return true
            } catch (_: Exception) {
                return false
            } finally {
                connection?.disconnect()
            }
        }
        return false
    }

    private fun isValidImage(file: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            GazelleLogic.isSafeImageDimensions(options.outWidth, options.outHeight)
        } catch (_: Exception) {
            false
        } catch (_: OutOfMemoryError) {
            false
        }
    }

    private fun decodeForDisplay(
        file: File,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (!GazelleLogic.isSafeImageDimensions(bounds.outWidth, bounds.outHeight)) {
                return null
            }

            val reqWidth = targetWidth.coerceIn(640, 1920)
            val reqHeight = targetHeight.coerceIn(360, 1080)
            val sample = GazelleLogic.calculateSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                reqWidth,
                reqHeight
            )

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun timeoutFor(deadline: Long, maximumMs: Int): Int {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) return 1
        return min(remaining, maximumMs.toLong())
            .coerceAtLeast(1L)
            .toInt()
    }

    private fun deadlineExpired(deadline: Long): Boolean {
        return SystemClock.elapsedRealtime() >= deadline
    }

    private fun cacheFile(context: Context): File {
        return File(context.cacheDir, CACHE_FILE_NAME)
    }

    private fun isHttpsUrl(url: String): Boolean {
        return try {
            URL(url).protocol.equals("https", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }
}
