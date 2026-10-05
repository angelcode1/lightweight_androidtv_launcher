package com.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.HashSet
import java.util.Random
import kotlin.math.min

object NatureWallpaperManager {
    const val SOURCE_SOLID = "solid"
    const val SOURCE_BING = "bing"
    const val SOURCE_AMAZON = "amazon"
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
    private const val PREF_SHOW_CAPTION = "show_caption"
    private const val PREF_LAST_FETCH = "last_fetch"
    private const val PREF_LAST_REMOTE_URL = "last_remote_url"
    private const val PREF_LAST_CAPTION = "last_caption"
    private const val PREF_CACHE_KEY = "cache_key"

    private const val CACHE_FILE_NAME = "gazelle-wallpaper.img"
    private const val CACHE_BACKUP_NAME = "gazelle-wallpaper.bak"
    private const val AMAZON_MANIFEST_CACHE = "amazon-collection-en-AU-v3.json"

    private const val AMAZON_CDN_BASE = "https://d21m0ezw6fosyw.cloudfront.net/"
    private const val AMAZON_CDN_HOST = "d21m0ezw6fosyw.cloudfront.net"
    private const val AMAZON_MANIFEST_URL =
        "https://d21m0ezw6fosyw.cloudfront.net/manifest/collections_en_AU_v3.json"

    private const val MAX_DOWNLOAD_BYTES = 12L * 1024L * 1024L
    private const val MAX_TEXT_BYTES = 1024L * 1024L
    private const val MAX_REDIRECTS = 5
    private const val MAX_REFRESH_DURATION_MS = 20_000L
    private const val MAX_CONNECT_TIMEOUT_MS = 5_000
    private const val MAX_READ_TIMEOUT_MS = 8_000
    private const val MAX_JSON_DEPTH = 8

    private val stateLock = Any()
    private val activeRefreshKeys = HashSet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = Random()

    enum class RefreshResult {
        SUCCESS,
        FAILED,
        BUSY,
        STALE
    }

    private data class RefreshConfig(
        val source: String,
        val customUrl: String
    ) {
        val key: String
            get() = if (source == SOURCE_CUSTOM) "$source\n$customUrl" else source
    }

    private data class WallpaperCandidate(
        val url: String,
        val caption: String = ""
    )

    fun getSource(context: Context): String = synchronized(stateLock) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_SOURCE, SOURCE_SOLID)
            ?: SOURCE_SOLID
    }

    fun setSource(context: Context, source: String) = synchronized(stateLock) {
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

    fun getCustomUrl(context: Context): String = synchronized(stateLock) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_CUSTOM_URL, "")
            .orEmpty()
    }

    fun setCustomUrl(context: Context, url: String) = synchronized(stateLock) {
        val trimmed = url.trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(PREF_CUSTOM_URL, "").orEmpty() != trimmed) {
            prefs.edit()
                .putString(PREF_CUSTOM_URL, trimmed)
                .putLong(PREF_LAST_FETCH, 0L)
                .apply()
        }
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

    fun isCaptionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_SHOW_CAPTION, true)
    }

    fun setCaptionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_SHOW_CAPTION, enabled)
            .apply()
    }

    fun getCachedCaption(context: Context): String {
        val config = snapshotConfig(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(PREF_CACHE_KEY, "").orEmpty() != config.key) {
            return ""
        }
        return prefs.getString(PREF_LAST_CAPTION, "").orEmpty()
    }

    fun shouldRefresh(context: Context): Boolean {
        val config = snapshotConfig(context)
        if (config.source == SOURCE_SOLID) return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cache = cacheFile(context)
        if (!cache.exists() || cache.length() == 0L) return true
        if (prefs.getString(PREF_CACHE_KEY, "").orEmpty() != config.key) return true

        val lastFetch = prefs.getLong(PREF_LAST_FETCH, 0L)
        return System.currentTimeMillis() - lastFetch >= getInterval(context)
    }

    fun loadCachedDrawable(
        context: Context,
        targetWidth: Int,
        targetHeight: Int
    ): Drawable? {
        val config = snapshotConfig(context)
        if (config.source == SOURCE_SOLID) return null

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(PREF_CACHE_KEY, "").orEmpty() != config.key) {
            return null
        }

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
        val config = snapshotConfig(context)
        if (config.source == SOURCE_SOLID) {
            mainHandler.post { onComplete(RefreshResult.SUCCESS) }
            return
        }

        if (!force && !shouldRefresh(context)) {
            mainHandler.post { onComplete(RefreshResult.SUCCESS) }
            return
        }

        synchronized(stateLock) {
            if (!activeRefreshKeys.add(config.key)) {
                mainHandler.post { onComplete(RefreshResult.BUSY) }
                return
            }
        }

        Thread {
            var result = RefreshResult.FAILED
            val deadline = SystemClock.elapsedRealtime() + MAX_REFRESH_DURATION_MS
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val previousUrl = prefs.getString(PREF_LAST_REMOTE_URL, "").orEmpty()
                val candidate = when (config.source) {
                    SOURCE_BING ->
                        fetchBingImageUrl(previousUrl, deadline)?.let { WallpaperCandidate(it) }
                    SOURCE_AMAZON ->
                        fetchAmazonCandidate(context, previousUrl, deadline)
                    SOURCE_NATURE ->
                        fetchNatureImageUrl(previousUrl, deadline)?.let { WallpaperCandidate(it) }
                    SOURCE_CUSTOM ->
                        config.customUrl
                            .takeIf { isHttpsUrl(it) }
                            ?.let { WallpaperCandidate(it) }
                    else -> null
                }

                if (candidate != null && !deadlineExpired(deadline)) {
                    val temp = downloadImageToTemp(context, candidate.url, deadline)
                    if (temp != null) {
                        result = commitDownloadedImage(
                            context = context,
                            temp = temp,
                            config = config,
                            candidate = candidate
                        )
                    }
                }
            } catch (_: Exception) {
                result = RefreshResult.FAILED
            } catch (_: OutOfMemoryError) {
                result = RefreshResult.FAILED
            } finally {
                synchronized(stateLock) {
                    activeRefreshKeys.remove(config.key)
                }
                mainHandler.post { onComplete(result) }
            }
        }.apply {
            name = "gazelle-wallpaper-refresh"
            isDaemon = true
        }.start()
    }

    private fun snapshotConfig(context: Context): RefreshConfig = synchronized(stateLock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        RefreshConfig(
            source = prefs.getString(PREF_SOURCE, SOURCE_SOLID) ?: SOURCE_SOLID,
            customUrl = prefs.getString(PREF_CUSTOM_URL, "").orEmpty()
        )
    }

    private fun commitDownloadedImage(
        context: Context,
        temp: File,
        config: RefreshConfig,
        candidate: WallpaperCandidate
    ): RefreshResult = synchronized(stateLock) {
        val current = snapshotConfig(context)
        if (current != config) {
            downloadTemp.delete()
            return@synchronized RefreshResult.STALE
        }

        val destination = cacheFile(context)
        val backup = File(context.cacheDir, CACHE_BACKUP_NAME)
        backup.delete()

        if (destination.exists() && !destination.renameTo(backup)) {
            downloadTemp.delete()
            return@synchronized RefreshResult.FAILED
        }

        if (!temp.renameTo(destination)) {
            if (backup.exists()) {
                backup.renameTo(destination)
            }
            downloadTemp.delete()
            return@synchronized RefreshResult.FAILED
        }

        backup.delete()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_LAST_FETCH, System.currentTimeMillis())
            .putString(PREF_LAST_REMOTE_URL, candidate.url)
            .putString(PREF_LAST_CAPTION, candidate.caption)
            .putString(PREF_CACHE_KEY, config.key)
            .apply()

        RefreshResult.SUCCESS
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

    private fun fetchAmazonCandidate(
        context: Context,
        previousUrl: String,
        deadline: Long
    ): WallpaperCandidate? {
        val manifest = fetchAmazonManifest(context, deadline) ?: return null
        val root = try {
            JSONTokener(manifest).nextValue()
        } catch (_: Exception) {
            return null
        }

        val candidates = mutableListOf<WallpaperCandidate>()
        collectAmazonCandidates(root, candidates, 0)

        val unique = LinkedHashMap<String, WallpaperCandidate>()
        candidates.forEach { candidate ->
            unique[candidate.url] = candidate
        }

        val pool = unique.values
            .filter { it.url != previousUrl }
            .ifEmpty { unique.values.toList() }

        if (pool.isEmpty()) return null
        return pool[random.nextInt(pool.size)]
    }

    private fun fetchAmazonManifest(context: Context, deadline: Long): String? {
        val cache = File(context.cacheDir, AMAZON_MANIFEST_CACHE)
        val fresh = fetchText(AMAZON_MANIFEST_URL, deadline)

        if (!fresh.isNullOrBlank()) {
            try {
                cache.writeText(fresh)
            } catch (_: Exception) {
            }
            return fresh
        }

        return try {
            if (cache.exists() && cache.length() in 1..MAX_TEXT_BYTES) {
                cache.readText()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun collectAmazonCandidates(
        node: Any?,
        output: MutableList<WallpaperCandidate>,
        depth: Int
    ) {
        if (node == null || depth > MAX_JSON_DEPTH) return

        when (node) {
            is JSONObject -> {
                amazonCandidateFromObject(node)?.let(output::add)
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectAmazonCandidates(node.opt(keys.next()), output, depth + 1)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectAmazonCandidates(node.opt(i), output, depth + 1)
                }
            }
        }
    }

    private fun amazonCandidateFromObject(obj: JSONObject): WallpaperCandidate? {
        val directPath = firstString(
            obj,
            arrayOf(
                "compressed",
                "compressedPath",
                "compressed_path",
                "compressedImage",
                "compressed_image",
                "imagePath",
                "image_path",
                "path",
                "url"
            )
        )

        val fallbackPath = firstDirectJpegString(obj)
            ?: firstString(
                obj,
                arrayOf("filename", "fileName", "file_name")
            )

        val rawPath = directPath
            ?.takeIf { isJpegPath(it) }
            ?: fallbackPath?.takeIf { isJpegPath(it) }
            ?: return null

        val url = resolveAmazonUrl(rawPath) ?: return null
        val caption = firstString(
            obj,
            arrayOf("caption", "title", "description")
        ).orEmpty().trim()

        return WallpaperCandidate(url = url, caption = caption)
    }

    private fun firstDirectJpegString(obj: JSONObject): String? {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val value = obj.opt(keys.next())
            if (value is String && isJpegPath(value)) {
                return value
            }
        }
        return null
    }

    private fun firstString(obj: JSONObject, keys: Array<String>): String? {
        keys.forEach { key ->
            val value = obj.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun resolveAmazonUrl(raw: String): String? {
        val resolved = try {
            if (raw.startsWith("https://", ignoreCase = true)) {
                raw
            } else {
                URL(URL(AMAZON_CDN_BASE), raw.removePrefix("/")).toString()
            }
        } catch (_: Exception) {
            return null
        }

        return if (isAllowedAmazonImageUrl(resolved)) resolved else null
    }

    private fun isAllowedAmazonImageUrl(value: String): Boolean {
        return try {
            val url = URL(value)
            url.protocol.equals("https", ignoreCase = true) &&
                url.host.equals(AMAZON_CDN_HOST, ignoreCase = true) &&
                isJpegPath(url.path)
        } catch (_: Exception) {
            false
        }
    }

    private fun isJpegPath(value: String): Boolean {
        val clean = value.substringBefore('?').lowercase()
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg")
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
            var temp: File? = null
            try {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutFor(deadline, MAX_CONNECT_TIMEOUT_MS)
                    readTimeout = timeoutFor(deadline, MAX_READ_TIMEOUT_MS)
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "GazelleLauncher/1.0 (Android TV)")
                    setRequestProperty("Accept", "application/json,text/plain,*/*")
                }

                when (connection.responseCode) {
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

                val contentLength = connection.contentLength.toLong()
                if (contentLength > MAX_TEXT_BYTES) return null

                connection.inputStream.bufferedReader().use { reader ->
                    val builder = StringBuilder()
                    val buffer = CharArray(4096)
                    var total = 0L
                    while (!deadlineExpired(deadline)) {
                        val count = reader.read(buffer)
                        if (count <= 0) return builder.toString()
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

    private fun downloadImageToTemp(
        context: Context,
        initialUrl: String,
        deadline: Long
    ): File? {
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
                    setRequestProperty("Accept", "image/*")
                }

                when (connection.responseCode) {
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

                val contentLength = connection.contentLength.toLong()
                if (contentLength > MAX_DOWNLOAD_BYTES) return null

                val type = connection.contentType.orEmpty().lowercase()
                if (type.isNotEmpty() && !type.startsWith("image/")) return null

                val downloadTemp = File.createTempFile(
                    "gazelle-wallpaper-",
                    ".tmp",
                    context.cacheDir
                )
                temp = downloadTemp
                var total = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(downloadTemp).use { output ->
                        val buffer = ByteArray(8192)
                        while (!deadlineExpired(deadline)) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            total += count
                            if (total > MAX_DOWNLOAD_BYTES) {
                                downloadTemp.delete()
                                return null
                            }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }

                if (deadlineExpired(deadline)) {
                    downloadTemp.delete()
                    return null
                }

                if (total < 1024L || !isValidImage(temp)) {
                    downloadTemp.delete()
                    return null
                }

                return downloadTemp
            } catch (_: Exception) {
                temp?.delete()
                return null
            } finally {
                connection?.disconnect()
            }
        }
        return null
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
