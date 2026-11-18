package com.zuna.player.ui.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Canvas as AndroidCanvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zuna.player.data.model.AudioFileMetadata
import com.zuna.player.data.storage.LibraryPreferences
import com.zuna.player.ui.theme.LocalFlacTheme
import com.zuna.player.ui.theme.LocalPowerSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val maxThumbCacheBytes: Int =
    (Runtime.getRuntime().maxMemory() / 5L)
        .coerceIn(16L * 1024L * 1024L, 48L * 1024L * 1024L)
        .toInt()

// Thumbnail cache (~192px RGB_565 ~72 KB each -> ~500-650 covers stay resident in RAM)
val artworkCache = object : LruCache<String, Bitmap>(maxThumbCacheBytes) {
    override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
        thumbImageCache.remove(key)
    }
}

private val thumbImageCache = ConcurrentHashMap<String, ImageBitmap>()

// Hi-res artwork & custom wallpaper cache (up to ~1440px for fullscreen cover & backgrounds)
private val hiResCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
        hiResImageCache.remove(key)
    }
}

private val hiResImageCache = ConcurrentHashMap<String, ImageBitmap>()

private val legacyBlurCache = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
    override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
}

private val noLocalArtworkSet: MutableSet<String> = ConcurrentHashMap.newKeySet()
private val noOnlineArtworkSet: MutableSet<String> = ConcurrentHashMap.newKeySet()
private val onlineCoverDownloadPermits = Semaphore(3)
private val onlineCoverMutexMap = ConcurrentHashMap<String, Mutex>()
private val artworkDecodeLocks = ConcurrentHashMap<String, Mutex>()
@OptIn(ExperimentalCoroutinesApi::class)
private val artworkIoDispatcher = Dispatchers.IO.limitedParallelism(8)
private val _artworkGeneration = MutableStateFlow(0)
val artworkGeneration = _artworkGeneration.asStateFlow()

private const val DISK_THUMB_DIR = "art_thumbs_v1"
private const val ONLINE_COVER_DIR = "online_covers_v1"

private fun thumbDiskFile(context: Context, trackId: String): File {
    val digest = MessageDigest.getInstance("SHA-1").digest(trackId.toByteArray())
    val hex = digest.joinToString("") { "%02x".format(it) }
    return File(File(context.cacheDir, DISK_THUMB_DIR), "$hex.jpg")
}

private fun onlineCoverDiskFile(context: Context, key: String): File {
    val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
    val hex = digest.joinToString("") { "%02x".format(it) }
    return File(File(context.cacheDir, ONLINE_COVER_DIR), "$hex.jpg")
}

fun refreshArtworkCacheGeneration() {
    artworkCache.evictAll()
    thumbImageCache.clear()
    hiResCache.evictAll()
    hiResImageCache.clear()
    legacyBlurCache.evictAll()
    noOnlineArtworkSet.clear()
    _artworkGeneration.value += 1
}

fun clearArtworkCache(context: Context) {
    artworkCache.evictAll()
    thumbImageCache.clear()
    hiResCache.evictAll()
    hiResImageCache.clear()
    legacyBlurCache.evictAll()
    noLocalArtworkSet.clear()
    noOnlineArtworkSet.clear()
    _artworkGeneration.value += 1
    try {
        File(context.cacheDir, DISK_THUMB_DIR).deleteRecursively()
        File(context.cacheDir, ONLINE_COVER_DIR).deleteRecursively()
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("audio_") ||
                file.name.startsWith("art_") ||
                file.extension in listOf("jpg", "png", "webp", "jpeg")
            ) {
                file.delete()
            }
        }
    } catch (_: Exception) {
    }
}

private fun computeSampleSize(srcW: Int, srcH: Int, reqSize: Int): Int {
    var sampleSize = 1
    if (srcH > reqSize || srcW > reqSize) {
        val halfH = srcH / 2
        val halfW = srcW / 2
        while ((halfH / sampleSize) >= reqSize && (halfW / sampleSize) >= reqSize) {
            sampleSize *= 2
        }
    }
    return sampleSize
}

private fun decodeSampledBitmap(
    bytes: ByteArray,
    reqSize: Int,
    useRgb565: Boolean = false
): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    val sampleSize = computeSampleSize(opts.outWidth, opts.outHeight, reqSize)
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = if (useRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
    )
}

private fun decodeSampledContentUri(
    context: Context,
    uri: Uri,
    reqSize: Int,
    useRgb565: Boolean = false
): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    } ?: return null
    val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, reqSize)
    return resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(
            input,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = if (useRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
        )
    }
}

private fun decodeSampledFile(
    file: File,
    reqSize: Int,
    useRgb565: Boolean = false
): Bitmap? {
    if (!file.exists() || !file.isFile) return null
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    val sampleSize = computeSampleSize(opts.outWidth, opts.outHeight, reqSize)
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = if (useRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
    )
}

private suspend fun extractTrackBitmap(
    context: Context,
    track: AudioFileMetadata,
    targetSize: Int,
    preferThumbnailApi: Boolean = false,
    useRgb565: Boolean = false
): Bitmap? = withContext(artworkIoDispatcher) {
    var resultBitmap: Bitmap? = null
    val uri = track.uri

    currentCoroutineContext().ensureActive()

    if (preferThumbnailApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
        try {
            resultBitmap = context.contentResolver.loadThumbnail(uri, Size(targetSize, targetSize), null)
        } catch (_: Exception) {
        }
    }

    if (resultBitmap == null) {
        currentCoroutineContext().ensureActive()
        try {
            val mmr = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") {
                    mmr.setDataSource(uri.path)
                } else {
                    val pfd = try {
                        context.contentResolver.openFileDescriptor(uri, "r")
                    } catch (_: Exception) {
                        null
                    }
                    if (pfd != null) {
                        pfd.use { mmr.setDataSource(it.fileDescriptor) }
                    } else {
                        mmr.setDataSource(context, uri)
                    }
                }
                val artBytes = mmr.embeddedPicture
                if (artBytes != null) {
                    resultBitmap = decodeSampledBitmap(artBytes, targetSize, useRgb565)
                }
            } finally {
                try {
                    mmr.release()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    if (resultBitmap == null && !preferThumbnailApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
        currentCoroutineContext().ensureActive()
        try {
            resultBitmap = context.contentResolver.loadThumbnail(uri, Size(targetSize, targetSize), null)
        } catch (_: Exception) {
        }
    }

    if (resultBitmap == null && track.artworkUri != null) {
        currentCoroutineContext().ensureActive()
        try {
            resultBitmap = decodeSampledContentUri(context, track.artworkUri, targetSize, useRgb565)
        } catch (_: Exception) {
        }
    }

    if (resultBitmap == null && uri.scheme == "content") {
        currentCoroutineContext().ensureActive()
        try {
            val lastSegment = uri.lastPathSegment?.toLongOrNull()
            if (lastSegment != null) {
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    lastSegment
                )
                resultBitmap = decodeSampledContentUri(context, albumArtUri, targetSize, useRgb565)
            }
        } catch (_: Exception) {
        }
    }

    if (resultBitmap == null && uri.scheme == "file") {
        currentCoroutineContext().ensureActive()
        try {
            val parentDir = uri.path?.let { File(it).parentFile }
            val coverFile = parentDir?.let { dir ->
                listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg")
                    .firstNotNullOfOrNull { name ->
                        File(dir, name).takeIf { it.exists() && it.isFile }
                    }
            }
            if (coverFile != null) {
                resultBitmap = decodeSampledFile(coverFile, targetSize, useRgb565)
            }
        } catch (_: Exception) {
        }
    }

    resultBitmap
}

private fun putThumbInCaches(id: String, bmp: Bitmap): ImageBitmap {
    artworkCache.put(id, bmp)
    val img = bmp.asImageBitmap()
    thumbImageCache[id] = img
    return img
}

private fun getCachedArtworkImage(trackId: String, hiRes: Boolean): ImageBitmap? {
    if (hiRes) {
        val hKey = "hires:$trackId"
        hiResImageCache[hKey]?.let { return it }
        hiResCache.get(hKey)?.let { bmp ->
            return bmp.asImageBitmap().also { hiResImageCache[hKey] = it }
        }
    }
    thumbImageCache[trackId]?.let { return it }
    artworkCache.get(trackId)?.let { bmp ->
        return bmp.asImageBitmap().also { thumbImageCache[trackId] = it }
    }
    return null
}

private fun normalizeThumbBitmap(src: Bitmap): Bitmap {
    var bmp = src
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bmp.config == Bitmap.Config.HARDWARE) {
        val soft = bmp.copy(Bitmap.Config.RGB_565, false)
        if (soft != null) {
            bmp.recycle()
            bmp = soft
        }
    }
    val maxDim = max(bmp.width, bmp.height)
    if (maxDim > 224) {
        val scale = 192f / maxDim.toFloat()
        val dstW = (bmp.width * scale).roundToInt().coerceAtLeast(16)
        val dstH = (bmp.height * scale).roundToInt().coerceAtLeast(16)
        val scaled = Bitmap.createScaledBitmap(bmp, dstW, dstH, true)
        if (scaled !== bmp) {
            bmp.recycle()
            bmp = scaled
        }
    }
    if (bmp.config != Bitmap.Config.RGB_565) {
        val rgb565 = bmp.copy(Bitmap.Config.RGB_565, false)
        if (rgb565 != null) {
            bmp.recycle()
            bmp = rgb565
        }
    }
    return bmp
}

private fun normalizeOnlineQuery(raw: String): String {
    return raw
        .replace(Regex("\\s*\\([^)]*\\)\\s*"), " ")
        .replace(Regex("\\s*\\[[^\\]]*\\]\\s*"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun buildOnlineCoverKey(track: AudioFileMetadata): String {
    val a = normalizeOnlineQuery(track.artist)
        .takeIf { it.isNotBlank() && !it.contains("неизвест", ignoreCase = true) && !it.contains("unknown", ignoreCase = true) }
        ?.lowercase() ?: ""
    val t = normalizeOnlineQuery(track.title).lowercase()
    val al = normalizeOnlineQuery(track.album)
        .takeIf { it.isNotBlank() && !it.contains("без альбома", ignoreCase = true) && !it.contains("unknown", ignoreCase = true) }
        ?.lowercase() ?: ""
    return "$a::${t.ifBlank { al }}"
}

private fun fetchUrlStringBlocking(urlStr: String): String? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 6000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Zuna-Android/1.1.0")
        }
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else null
    } catch (_: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}

private fun downloadImageBytesBlocking(urlStr: String): ByteArray? {
    var currentUrl = urlStr
    for (hop in 0..3) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 7000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Zuna-Android/1.1.0")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: return null
                currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                continue
            }
            if (code in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                return if (bytes.size >= 1024) bytes else null
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            conn?.disconnect()
        }
    }
    return null
}

private suspend fun fetchOnlineTrackBitmap(
    context: Context,
    track: AudioFileMetadata,
    targetSize: Int,
    useRgb565: Boolean
): Bitmap? {
    val prefs = LibraryPreferences.getInstance(context)
    if (!prefs.isOnlineCoversEnabled.value) return null

    val key = buildOnlineCoverKey(track)
    if (key.isBlank() || key == "::") return null

    val diskFile = onlineCoverDiskFile(context, key)
    if (noOnlineArtworkSet.contains(key) && !diskFile.exists()) return null

    val mutex = onlineCoverMutexMap.computeIfAbsent(key) { Mutex() }
    return mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!prefs.isOnlineCoversEnabled.value) return@withLock null

        val cachedOnDisk = withContext(artworkIoDispatcher) {
            if (diskFile.exists()) decodeSampledFile(diskFile, targetSize, useRgb565) else null
        }
        if (cachedOnDisk != null) return@withLock cachedOnDisk
        if (noOnlineArtworkSet.contains(key)) return@withLock null

        val bytes = onlineCoverDownloadPermits.withPermit {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val cleanTitle = normalizeOnlineQuery(track.title).ifBlank { track.title.trim() }
                val cleanArtist = normalizeOnlineQuery(track.artist).takeIf {
                    it.isNotBlank() && !it.contains("неизвест", ignoreCase = true) && !it.contains("unknown", ignoreCase = true)
                } ?: ""
                val cleanAlbum = normalizeOnlineQuery(track.album).takeIf {
                    it.isNotBlank() && !it.contains("без альбома", ignoreCase = true) && !it.contains("unknown", ignoreCase = true)
                } ?: ""

                val queries = mutableListOf<String>()
                if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) queries.add("$cleanArtist $cleanTitle")
                if (cleanArtist.isNotBlank() && cleanAlbum.isNotBlank() && cleanAlbum != cleanTitle) queries.add("$cleanArtist $cleanAlbum")
                if (cleanTitle.isNotBlank()) queries.add(cleanTitle)

                var artUrl: String? = null
                for (q in queries) {
                    if (!prefs.isOnlineCoversEnabled.value) return@withContext null
                    val enc = URLEncoder.encode(q, "UTF-8")
                    val jsonStr = fetchUrlStringBlocking("https://itunes.apple.com/search?term=$enc&media=music&entity=song&limit=5")
                    if (!jsonStr.isNullOrBlank()) {
                        try {
                            val results = JSONObject(jsonStr).optJSONArray("results")
                            if (results != null && results.length() > 0) {
                                var chosenUrl: String? = null
                                for (i in 0 until results.length()) {
                                    val obj = results.optJSONObject(i) ?: continue
                                    val u = obj.optString("artworkUrl100", "")
                                    if (u.isBlank()) continue
                                    val rArtist = obj.optString("artistName", "")
                                    if (cleanArtist.isBlank() || rArtist.contains(cleanArtist, ignoreCase = true) || cleanArtist.contains(rArtist, ignoreCase = true)) {
                                        chosenUrl = u
                                        break
                                    }
                                    if (chosenUrl == null) chosenUrl = u
                                }
                                if (!chosenUrl.isNullOrBlank()) {
                                    artUrl = chosenUrl.replace("100x100bb", "600x600bb", ignoreCase = true)
                                    break
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                if (artUrl == null) {
                    for (q in queries.take(2)) {
                        if (!prefs.isOnlineCoversEnabled.value) return@withContext null
                        val enc = URLEncoder.encode(q, "UTF-8")
                        val dzStr = fetchUrlStringBlocking("https://api.deezer.com/search?q=$enc&limit=3")
                        if (!dzStr.isNullOrBlank()) {
                            try {
                                val data = JSONObject(dzStr).optJSONArray("data")
                                if (data != null && data.length() > 0) {
                                    for (i in 0 until data.length()) {
                                        val albumObj = data.optJSONObject(i)?.optJSONObject("album") ?: continue
                                        val u = albumObj.optString("cover_xl", "")
                                            .ifBlank { albumObj.optString("cover_big", "") }
                                            .ifBlank { albumObj.optString("cover_medium", "") }
                                        if (u.isNotBlank()) {
                                            artUrl = u
                                            break
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                        if (artUrl != null) break
                    }
                }

                val rawBytes = artUrl?.let { downloadImageBytesBlocking(it) } ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

                try {
                    diskFile.parentFile?.mkdirs()
                    FileOutputStream(diskFile).use { it.write(rawBytes) }
                } catch (_: Exception) {
                }
                rawBytes
            }
        }

        if (bytes == null) {
            noOnlineArtworkSet.add(key)
            return@withLock null
        }

        withContext(artworkIoDispatcher) {
            decodeSampledBitmap(bytes, targetSize, useRgb565)
        }
    }
}

suspend fun loadTrackArtwork(
    context: Context,
    track: AudioFileMetadata?,
    debounceBeforeExtractMs: Long = 0L
): Bitmap? {
    if (track == null) return null
    val prefs = LibraryPreferences.getInstance(context)
    if (!prefs.isEmbeddedCoversEnabled.value) return null

    val id = track.id
    val onlineEnabled = prefs.isOnlineCoversEnabled.value
    if (noLocalArtworkSet.contains(id) && !onlineEnabled) return null
    artworkCache.get(id)?.let { return it }

    val mutex = artworkDecodeLocks.computeIfAbsent("thumb:$id") { Mutex() }
    return mutex.withLock {
        if (noLocalArtworkSet.contains(id) && !prefs.isOnlineCoversEnabled.value) return@withLock null
        artworkCache.get(id)?.let { return@withLock it }

        val diskFile = thumbDiskFile(context, id)
        val fromDisk = withContext(artworkIoDispatcher) {
            if (diskFile.exists()) {
                BitmapFactory.decodeFile(
                    diskFile.absolutePath,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                )
            } else null
        }
        if (fromDisk != null) {
            putThumbInCaches(id, fromDisk)
            return@withLock fromDisk
        }

        if (debounceBeforeExtractMs > 0L) {
            delay(debounceBeforeExtractMs)
        }

        val localRaw = if (!noLocalArtworkSet.contains(id)) {
            extractTrackBitmap(
                context = context,
                track = track,
                targetSize = 192,
                preferThumbnailApi = true,
                useRgb565 = true
            )
        } else null

        if (localRaw != null) {
            val bmp = normalizeThumbBitmap(localRaw)
            putThumbInCaches(id, bmp)
            withContext(artworkIoDispatcher) {
                try {
                    diskFile.parentFile?.mkdirs()
                    FileOutputStream(diskFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 84, out)
                    }
                } catch (_: Exception) {
                }
            }
            return@withLock bmp
        }

        noLocalArtworkSet.add(id)
        if (!prefs.isOnlineCoversEnabled.value) return@withLock null

        val onlineRaw = fetchOnlineTrackBitmap(context, track, targetSize = 192, useRgb565 = true)
        val onlineBmp = onlineRaw?.let { normalizeThumbBitmap(it) }
        if (onlineBmp != null) {
            putThumbInCaches(id, onlineBmp)
        }
        onlineBmp
    }
}

suspend fun loadTrackArtworkHiRes(context: Context, track: AudioFileMetadata?): Bitmap? {
    if (track == null) return null
    val prefs = LibraryPreferences.getInstance(context)
    if (!prefs.isEmbeddedCoversEnabled.value) return null

    val key = "hires:${track.id}"
    hiResCache.get(key)?.let { return it }
    if (noLocalArtworkSet.contains(track.id) && !prefs.isOnlineCoversEnabled.value) return null

    val mutex = artworkDecodeLocks.computeIfAbsent(key) { Mutex() }
    return mutex.withLock {
        if (noLocalArtworkSet.contains(track.id) && !prefs.isOnlineCoversEnabled.value) return@withLock null
        hiResCache.get(key)?.let { return@withLock it }

        val localBmp = if (!noLocalArtworkSet.contains(track.id)) {
            extractTrackBitmap(
                context = context,
                track = track,
                targetSize = 960,
                preferThumbnailApi = false,
                useRgb565 = false
            )
        } else null

        if (localBmp != null) {
            hiResCache.put(key, localBmp)
            hiResImageCache[key] = localBmp.asImageBitmap()
            return@withLock localBmp
        }

        noLocalArtworkSet.add(track.id)
        if (!prefs.isOnlineCoversEnabled.value) return@withLock null

        val onlineBmp = fetchOnlineTrackBitmap(context, track, targetSize = 960, useRgb565 = false)
        if (onlineBmp != null) {
            hiResCache.put(key, onlineBmp)
            hiResImageCache[key] = onlineBmp.asImageBitmap()
        }
        onlineBmp
    }
}

suspend fun loadCustomBackgroundBitmap(path: String): Bitmap? {
    if (path.isBlank()) return null
    val key = "custom_raw:$path"
    hiResCache.get(key)?.let { return it }

    val decoded = withContext(artworkIoDispatcher) {
        decodeSampledFile(File(path), 1280)
    } ?: return null

    hiResCache.put(key, decoded)
    return decoded
}

/**
 * Generates one cached frosted background texture for zero-overhead per-card glass blitting.
 */
suspend fun getPanelFrostedBg(
    context: Context,
    mode: String,
    customPath: String,
    isVideo: Boolean,
    bgBlurPx: Int,
    panelBlurPx: Int,
    monochrome: Boolean,
    track: AudioFileMetadata?
): Bitmap? {
    if (mode == "none" || (mode == "custom" && isVideo) || panelBlurPx <= 0) return null
    val combinedBlur = (bgBlurPx + panelBlurPx * 2).coerceIn(1, 130)
    val baseId = if (mode == "cover") "cov:${track?.id ?: return null}" else "cust:$customPath"
    val cacheKey = "frosted:$baseId|b=$combinedBlur|m=$monochrome"
    legacyBlurCache.get(cacheKey)?.let { return it }

    val src = when (mode) {
        "cover" -> loadTrackArtworkHiRes(context, track)
        "custom" -> loadCustomBackgroundBitmap(customPath)
        else -> null
    } ?: return null

    return getLegacyBlurredTexture(cacheKey, src, combinedBlur, monochrome)
}

/**
 * Software blur fallback exclusively for API < 31 (Android 11 and older) with smooth linear radius.
 */
suspend fun getLegacyBlurredTexture(
    cacheKey: String,
    source: Bitmap?,
    blurPx: Int,
    monochrome: Boolean
): Bitmap? {
    if (source == null) return null
    val key = "$cacheKey|b=$blurPx|m=$monochrome"
    legacyBlurCache.get(key)?.let { return it }

    return withContext(Dispatchers.Default) {
        val targetMax = 480
        val ratio = source.width.toFloat() / source.height.coerceAtLeast(1).toFloat()
        val dstW: Int
        val dstH: Int
        if (source.width >= source.height) {
            dstW = targetMax.coerceAtMost(source.width.coerceAtLeast(1))
            dstH = (dstW / ratio).toInt().coerceAtLeast(16)
        } else {
            dstH = targetMax.coerceAtMost(source.height.coerceAtLeast(1))
            dstW = (dstH * ratio).toInt().coerceAtLeast(16)
        }

        val scaled = Bitmap.createScaledBitmap(source, dstW, dstH, true)
        val processed = if (monochrome) {
            val gray = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(gray)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                val cm = ColorMatrix().apply { setSaturation(0f) }
                colorFilter = ColorMatrixColorFilter(cm)
            }
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            if (scaled !== source && scaled !== gray) scaled.recycle()
            gray
        } else {
            if (scaled === source) source.copy(Bitmap.Config.ARGB_8888, true) else scaled
        }

        val stackRadius = (blurPx * 0.45f).roundToInt().coerceIn(0, 42)
        val out = if (stackRadius > 0) stackBlur(processed, stackRadius) else processed
        legacyBlurCache.put(key, out)
        out
    }
}

private fun stackBlur(bitmap: Bitmap, radius: Int): Bitmap {
    if (radius < 1) return bitmap
    val w = bitmap.width
    val h = bitmap.height
    val pix = IntArray(w * h)
    bitmap.getPixels(pix, 0, w, 0, 0, w, h)

    val wm = w - 1
    val hm = h - 1
    val wh = w * h
    val div = radius + radius + 1

    val r = IntArray(wh)
    val g = IntArray(wh)
    val b = IntArray(wh)
    var rsum: Int
    var gsum: Int
    var bsum: Int
    var x: Int
    var y: Int
    var i: Int
    var p: Int
    var yp: Int
    var yi: Int
    var yw: Int
    val vmin = IntArray(max(w, h))

    var divsum = (div + 1) shr 1
    divsum *= divsum
    val dv = IntArray(256 * divsum)
    for (idx in 0 until 256 * divsum) {
        dv[idx] = idx / divsum
    }

    yi = 0
    yw = 0
    val stack = Array(div) { IntArray(3) }
    var stackpointer: Int
    var stackstart: Int
    var sir: IntArray
    var rbs: Int
    val r1 = radius + 1
    var routsum: Int
    var goutsum: Int
    var boutsum: Int
    var rinsum: Int
    var ginsum: Int
    var binsum: Int

    y = 0
    while (y < h) {
        bsum = 0; gsum = 0; rsum = 0
        boutsum = 0; goutsum = 0; routsum = 0
        binsum = 0; ginsum = 0; rinsum = 0
        i = -radius
        while (i <= radius) {
            p = pix[yi + min(wm, max(i, 0))]
            sir = stack[i + radius]
            sir[0] = (p and 0xff0000) shr 16
            sir[1] = (p and 0x00ff00) shr 8
            sir[2] = p and 0x0000ff
            rbs = r1 - abs(i)
            rsum += sir[0] * rbs
            gsum += sir[1] * rbs
            bsum += sir[2] * rbs
            if (i > 0) {
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
            } else {
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
            }
            i++
        }
        stackpointer = radius
        x = 0
        while (x < w) {
            r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum]
            rsum -= routsum; gsum -= goutsum; bsum -= boutsum
            stackstart = stackpointer - radius + div
            sir = stack[stackstart % div]
            routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
            if (y == 0) vmin[x] = min(x + radius + 1, wm)
            p = pix[yw + vmin[x]]
            sir[0] = (p and 0xff0000) shr 16
            sir[1] = (p and 0x00ff00) shr 8
            sir[2] = p and 0x0000ff
            rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
            rsum += rinsum; gsum += ginsum; bsum += binsum
            stackpointer = (stackpointer + 1) % div
            sir = stack[stackpointer % div]
            routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
            rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
            yi++; x++
        }
        yw += w
        y++
    }

    x = 0
    while (x < w) {
        bsum = 0; gsum = 0; rsum = 0
        boutsum = 0; goutsum = 0; routsum = 0
        binsum = 0; ginsum = 0; rinsum = 0
        yp = -radius * w
        i = -radius
        while (i <= radius) {
            yi = max(0, yp) + x
            sir = stack[i + radius]
            sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
            rbs = r1 - abs(i)
            rsum += r[yi] * rbs
            gsum += g[yi] * rbs
            bsum += b[yi] * rbs
            if (i > 0) {
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
            } else {
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
            }
            if (i < hm) yp += w
            i++
        }
        yi = x
        stackpointer = radius
        y = 0
        while (y < h) {
            pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
            rsum -= routsum; gsum -= goutsum; bsum -= boutsum
            stackstart = stackpointer - radius + div
            sir = stack[stackstart % div]
            routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
            if (x == 0) vmin[y] = min(y + r1, hm) * w
            p = x + vmin[y]
            sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
            rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
            rsum += rinsum; gsum += ginsum; bsum += binsum
            stackpointer = (stackpointer + 1) % div
            sir = stack[stackpointer]
            routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
            rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
            yi += w
            y++
        }
        x++
    }

    bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    return bitmap
}

@Composable
fun TrackArtwork(
    track: AudioFileMetadata?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = LocalFlacTheme.current.radiusSm,
    iconSize: Dp = 20.dp,
    hiRes: Boolean = false
) {
    val context = LocalContext.current
    val tokens = LocalFlacTheme.current
    val powerSaver = LocalPowerSaver.current
    val effectiveHiRes = hiRes && !powerSaver.useThumbCovers
    val prefs = remember { LibraryPreferences.getInstance(context) }
    val embeddedCovers by prefs.isEmbeddedCoversEnabled.collectAsState()
    val onlineCovers by prefs.isOnlineCoversEnabled.collectAsState()
    val artGen by artworkGeneration.collectAsState()
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val trackId = track?.id

    var bitmapState by remember(trackId, effectiveHiRes, embeddedCovers, onlineCovers, artGen) {
        mutableStateOf(
            if (!embeddedCovers || trackId == null) null
            else getCachedArtworkImage(trackId, effectiveHiRes)
        )
    }

    if (bitmapState == null && embeddedCovers && track != null &&
        (!noLocalArtworkSet.contains(track.id) || onlineCovers)
    ) {
        LaunchedEffect(track.id, effectiveHiRes, onlineCovers, artGen) {
            if (effectiveHiRes) {
                loadTrackArtworkHiRes(context, track)
            } else {
                loadTrackArtwork(context, track, debounceBeforeExtractMs = 35L)
            }
            bitmapState = getCachedArtworkImage(track.id, effectiveHiRes)
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(tokens.bg2)
            .then(
                if (tokens.panelLine.alpha > 0f) Modifier.border(1.dp, tokens.panelLine, shape)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmapState
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = track?.title ?: "Обложка",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = tokens.panelFg2,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
