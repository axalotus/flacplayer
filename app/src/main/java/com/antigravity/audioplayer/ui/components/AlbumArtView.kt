package com.antigravity.audioplayer.ui.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

// Thread-safe cache for artwork bitmaps - max 120 entries (~60MB max)
val artworkCache = LruCache<String, Bitmap>(120)
private val noArtworkSet: MutableSet<String> = ConcurrentHashMap.newKeySet()

fun clearArtworkCache(context: Context) {
    artworkCache.evictAll()
    noArtworkSet.clear()
    try {
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("audio_") || file.name.startsWith("art_") || file.extension in listOf("jpg", "png", "webp", "jpeg")) {
                file.delete()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

suspend fun loadTrackArtwork(context: Context, track: AudioFileMetadata?): Bitmap? {
    if (track == null) return null
    val prefs = LibraryPreferences.getInstance(context)
    if (!prefs.isEmbeddedCoversEnabled.value) {
        return null
    }

    val id = track.id
    if (noArtworkSet.contains(id)) return null
    val cached = artworkCache.get(id)
    if (cached != null) return cached

    return withContext(Dispatchers.IO) {
        var resultBitmap: Bitmap? = null
        val uri = track.uri

        // Strategy 1: MediaMetadataRetriever via FileDescriptor (most reliable for SAF & local files)
        try {
            val mmr = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") {
                    mmr.setDataSource(uri.path)
                } else {
                    val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null }
                    if (pfd != null) {
                        pfd.use { mmr.setDataSource(it.fileDescriptor) }
                    } else {
                        mmr.setDataSource(context, uri)
                    }
                }
                val artBytes = mmr.embeddedPicture
                if (artBytes != null) {
                    resultBitmap = decodeSampledBitmap(artBytes, 512, 512)
                }
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Strategy 2: Android 10+ (Q+) ContentResolver loadThumbnail
        if (resultBitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            try {
                resultBitmap = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            } catch (_: Exception) {}
        }

        // Strategy 3: MediaStore album art URI query if URI contains ID
        if (resultBitmap == null && uri.scheme == "content") {
            try {
                val lastSegment = uri.lastPathSegment?.toLongOrNull()
                if (lastSegment != null) {
                    val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), lastSegment)
                    context.contentResolver.openInputStream(albumArtUri)?.use { input ->
                        resultBitmap = BitmapFactory.decodeStream(input)
                    }
                }
            } catch (_: Exception) {}
        }

        // Strategy 4: Local folder sibling cover image (cover.jpg, folder.jpg, album.jpg, front.jpg)
        if (resultBitmap == null && uri.scheme == "file") {
            try {
                val file = File(uri.path ?: "")
                val parent = file.parentFile
                if (parent != null && parent.isDirectory) {
                    val coverNames = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "album.jpg", "album.png", "front.jpg", "front.png")
                    for (name in coverNames) {
                        val coverFile = File(parent, name)
                        if (coverFile.exists() && coverFile.isFile) {
                            resultBitmap = decodeSampledBitmap(coverFile.absolutePath, 600, 600)
                            if (resultBitmap != null) break
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (resultBitmap != null) {
            artworkCache.put(id, resultBitmap)
            resultBitmap
        } else {
            noArtworkSet.add(id)
            null
        }
    }
}

private fun decodeSampledBitmap(data: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeByteArray(data, 0, data.size, options)
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return try {
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, options)
    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    return try {
        BitmapFactory.decodeFile(path, options)
    } catch (_: OutOfMemoryError) {
        null
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val height = options.outHeight
    val width = options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

@Composable
fun AlbumArtView(
    track: AudioFileMetadata?,
    modifier: Modifier = Modifier,
    cornerRadius: Int = 12
) {
    val context = LocalContext.current
    var bitmap by remember(track?.id) { mutableStateOf(track?.id?.let { artworkCache.get(it) }) }

    LaunchedEffect(track?.id) {
        if (track != null && bitmap == null) {
            bitmap = loadTrackArtwork(context, track)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = track?.album ?: "Album Art",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun BlurredAlbumBackground(
    track: AudioFileMetadata?,
    modifier: Modifier = Modifier,
    blurRadius: Float = 40f,
    overlayAlpha: Float = 0.75f
) {
    val context = LocalContext.current
    var bitmap by remember(track?.id) { mutableStateOf(track?.id?.let { artworkCache.get(it) }) }

    LaunchedEffect(track?.id) {
        if (track != null && bitmap == null) {
            bitmap = loadTrackArtwork(context, track)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(radius = blurRadius.dp),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = overlayAlpha))
        )
    }
}
