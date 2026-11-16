package com.antigravity.audioplayer.ui.components

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import com.antigravity.audioplayer.ui.theme.LocalFlacTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val maxThumbCacheBytes: Int =
    (Runtime.getRuntime().maxMemory() / 10L)
        .coerceIn(6L * 1024L * 1024L, 16L * 1024L * 1024L)
        .toInt()

// Thumbnail cache (up to ~320px for crisp track rows & grid cards)
val artworkCache = object : LruCache<String, Bitmap>(maxThumbCacheBytes) {
    override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
}

// Hi-res artwork & custom wallpaper cache (up to ~1440px for fullscreen cover & backgrounds)
private val hiResCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
}

private val legacyBlurCache = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
    override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
}

private val noArtworkSet: MutableSet<String> = ConcurrentHashMap.newKeySet()

fun clearArtworkCache(context: Context) {
    artworkCache.evictAll()
    hiResCache.evictAll()
    legacyBlurCache.evictAll()
    noArtworkSet.clear()
    try {
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

private fun decodeSampledBitmap(bytes: ByteArray, reqSize: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    var sampleSize = 1
    if (opts.outHeight > reqSize || opts.outWidth > reqSize) {
        val halfH = opts.outHeight / 2
        val halfW = opts.outWidth / 2
        while ((halfH / sampleSize) >= reqSize && (halfW / sampleSize) >= reqSize) {
            sampleSize *= 2
        }
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    )
}

private fun decodeSampledFile(file: File, reqSize: Int): Bitmap? {
    if (!file.exists() || !file.isFile) return null
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    var sampleSize = 1
    if (opts.outHeight > reqSize || opts.outWidth > reqSize) {
        val halfH = opts.outHeight / 2
        val halfW = opts.outWidth / 2
        while ((halfH / sampleSize) >= reqSize && (halfW / sampleSize) >= reqSize) {
            sampleSize *= 2
        }
    }
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    )
}

private suspend fun extractTrackBitmap(
    context: Context,
    track: AudioFileMetadata,
    targetSize: Int
): Bitmap? = withContext(Dispatchers.IO) {
    var resultBitmap: Bitmap? = null
    val uri = track.uri

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
                resultBitmap = decodeSampledBitmap(artBytes, targetSize)
            }
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    } catch (_: Exception) {
    }

    if (resultBitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
        try {
            resultBitmap = context.contentResolver.loadThumbnail(uri, Size(targetSize, targetSize), null)
        } catch (_: Exception) {
        }
    }

    if (resultBitmap == null && track.artworkUri != null) {
        try {
            context.contentResolver.openInputStream(track.artworkUri)?.use { input ->
                val bytes = input.readBytes()
                resultBitmap = decodeSampledBitmap(bytes, targetSize)
            }
        } catch (_: Exception) {
        }
    }

    if (resultBitmap == null && uri.scheme == "content") {
        try {
            val lastSegment = uri.lastPathSegment?.toLongOrNull()
            if (lastSegment != null) {
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    lastSegment
                )
                context.contentResolver.openInputStream(albumArtUri)?.use { input ->
                    val bytes = input.readBytes()
                    resultBitmap = decodeSampledBitmap(bytes, targetSize)
                }
            }
        } catch (_: Exception) {
        }
    }

    if (resultBitmap == null && uri.scheme == "file") {
        try {
            val parentDir = uri.path?.let { File(it).parentFile }
            val coverFile = parentDir?.let { dir ->
                listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg")
                    .firstNotNullOfOrNull { name ->
                        File(dir, name).takeIf { it.exists() && it.isFile }
                    }
            }
            if (coverFile != null) {
                resultBitmap = decodeSampledFile(coverFile, targetSize)
            }
        } catch (_: Exception) {
        }
    }

    resultBitmap
}

suspend fun loadTrackArtwork(context: Context, track: AudioFileMetadata?): Bitmap? {
    if (track == null) return null
    val prefs = LibraryPreferences.getInstance(context)
    if (!prefs.isEmbeddedCoversEnabled.value) return null

    val id = track.id
    if (noArtworkSet.contains(id)) return null
    artworkCache.get(id)?.let { return it }

    val bmp = extractTrackBitmap(context, track, 384)
    if (bmp != null) {
        artworkCache.put(id, bmp)
    } else {
        noArtworkSet.add(id)
    }
    return bmp
}

suspend fun loadTrackArtworkHiRes(context: Context, track: AudioFileMetadata?): Bitmap? {
    if (track == null) return null
    val prefs = LibraryPreferences.getInstance(context)
    if (!prefs.isEmbeddedCoversEnabled.value) return null

    val key = "hires:${track.id}"
    hiResCache.get(key)?.let { return it }
    if (noArtworkSet.contains(track.id)) return null

    val bmp = extractTrackBitmap(context, track, 960)
    if (bmp != null) {
        hiResCache.put(key, bmp)
    } else {
        noArtworkSet.add(track.id)
    }
    return bmp
}

suspend fun loadCustomBackgroundBitmap(path: String): Bitmap? {
    if (path.isBlank()) return null
    val key = "custom_raw:$path"
    hiResCache.get(key)?.let { return it }

    val decoded = withContext(Dispatchers.IO) {
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
    val shape = RoundedCornerShape(cornerRadius)

    val bitmapState by produceState<ImageBitmap?>(
        initialValue = track?.id?.let {
            if (hiRes) hiResCache.get("hires:$it")?.asImageBitmap() ?: artworkCache.get(it)?.asImageBitmap()
            else artworkCache.get(it)?.asImageBitmap()
        },
        key1 = track?.id,
        key2 = hiRes
    ) {
        value = track?.let {
            if (hiRes) loadTrackArtworkHiRes(context, it)?.asImageBitmap()
            else loadTrackArtwork(context, it)?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(tokens.bg2)
            .border(1.dp, tokens.line, shape),
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
                tint = tokens.fg2,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
