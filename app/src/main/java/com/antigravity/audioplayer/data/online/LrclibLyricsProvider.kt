package com.antigravity.audioplayer.data.online

import android.content.Context
import android.util.Log
import com.antigravity.audioplayer.data.model.AudioFileMetadata
import com.antigravity.audioplayer.data.model.LyricsData
import com.antigravity.audioplayer.data.model.LyricsSource
import com.antigravity.audioplayer.data.parser.LyricsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

object LrclibLyricsProvider {

    private const val TAG = "LrclibLyricsProvider"
    private const val LRCLIB_API = "https://lrclib.net/api/"
    private const val LRCLIB_PROVIDER_NAME = "LRCLIB"
    private const val CACHE_VERSION = 3
    private const val MAX_DURATION_DIFF = 3

    private val AUDIO_EXT = Pattern.compile("\\.(mp3|m4a|flac|ogg|oga|opus|wav|aac|alac|wma)$", Pattern.CASE_INSENSITIVE)
    private val DECORATION = Pattern.compile("\\s*[(\\[][^)\\]]*(official|lyric|video|audio|visualizer|remaster|hq|hd)[^)\\]]*[)\\]]", Pattern.CASE_INSENSITIVE)

    // Negative cache: keys for which LRCLIB returned 404 (not found)
    private val negativeCache: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    data class Query(
        val artist: String?,
        val title: String?,
        val album: String?,
        val durationSec: Int
    ) {
        val key: String by lazy {
            val a = artist?.lowercase(Locale.ROOT) ?: ""
            val t = title?.lowercase(Locale.ROOT) ?: ""
            "$a\u0001$t\u0001$durationSec"
        }

        fun isValid(): Boolean = !title.isNullOrBlank()

        companion object {
            fun create(artist: String?, title: String?, album: String?, durationMs: Long): Query {
                var cleanArtist = clean(artist)
                var cleanTitle = clean(title)

                if (cleanTitle != null) {
                    cleanTitle = AUDIO_EXT.matcher(cleanTitle).replaceFirst("").replace('_', ' ').trim()
                }

                // If artist is empty and title contains " - ", split them
                if (cleanArtist.isNullOrEmpty() && cleanTitle != null) {
                    val dashIndex = cleanTitle.indexOf(" - ")
                    if (dashIndex > 0) {
                        cleanArtist = cleanTitle.substring(0, dashIndex).trim()
                        cleanTitle = cleanTitle.substring(dashIndex + 3).trim()
                    }
                }

                val strippedTitle = stripDecorations(cleanTitle)
                val durationSec = if (durationMs > 0) (durationMs / 1000L).toInt() else 0

                return Query(
                    artist = cleanArtist,
                    title = strippedTitle,
                    album = clean(album),
                    durationSec = durationSec
                )
            }

            private fun clean(s: String?): String? {
                if (s == null) return null
                val trimmed = s.trim()
                return if (trimmed.isEmpty()) null else trimmed
            }

            fun stripDecorations(s: String?): String? {
                if (s == null) return null
                val out = DECORATION.matcher(s).replaceAll("").trim()
                return if (out.isEmpty()) s else out
            }
        }
    }

    fun isKnownMissing(query: Query): Boolean {
        return negativeCache.contains(query.key)
    }

    fun markKnownMissing(query: Query) {
        negativeCache.add(query.key)
    }

    fun clearNegativeCache() {
        negativeCache.clear()
    }

    class HttpErrorException(val statusCode: Int, message: String) : java.io.IOException(message)

    sealed class LrclibResult {
        data class Success(val lyrics: LyricsData) : LrclibResult()
        object NotFound : LrclibResult()
        data class NetworkError(val message: String, val statusCode: Int? = null) : LrclibResult()
    }

    suspend fun fetchLyricsWithResult(
        context: Context,
        track: AudioFileMetadata,
        forceRefresh: Boolean = false
    ): LrclibResult = withContext(Dispatchers.IO) {
        val query = Query.create(
            artist = track.artist,
            title = track.title,
            album = track.album,
            durationMs = track.durationMs
        )

        if (!query.isValid()) {
            Log.d(TAG, "Query invalid for track '${track.title}'")
            return@withContext LrclibResult.NotFound
        }

        val key = query.key

        // 1. Check negative cache
        if (!forceRefresh && negativeCache.contains(key)) {
            Log.d(TAG, "Skipping fetch for '${query.title}' (known missing in negative cache)")
            return@withContext LrclibResult.NotFound
        }

        // 2. Check disk cache
        if (!forceRefresh) {
            val cachedDisk = readDisk(context, key)
            if (cachedDisk != null) {
                Log.d(TAG, "Found disk-cached LRCLIB lyrics for '${query.title}'")
                return@withContext LrclibResult.Success(cachedDisk)
            }
        }

        // 3. Retry loop (max 3 attempts: 1 initial + 2 retries)
        val maxAttempts = 3
        var lastError: Exception? = null

        for (attempt in 1..maxAttempts) {
            if (!isActive) {
                return@withContext LrclibResult.NetworkError("Запрос отменен")
            }

            try {
                // First: exact match via /api/get
                var result = requestLrclibGet(query)

                // Second: search match via /api/search
                if (result == null) {
                    result = requestLrclibSearch(query)
                }

                if (result != null) {
                    negativeCache.remove(key)
                    writeDisk(context, key, result)
                    Log.d(TAG, "Successfully fetched LRCLIB lyrics for '${query.title}' (synced=${result.isSynced}, instrumental=${result.isInstrumental})")
                    return@withContext LrclibResult.Success(result)
                } else {
                    // Clean 404 or empty search results: definite Not Found
                    negativeCache.add(key)
                    Log.d(TAG, "Lyrics not found on LRCLIB for '${query.title}' -> added to negative cache")
                    return@withContext LrclibResult.NotFound
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Attempt $attempt/$maxAttempts failed for '${query.title}': ${e.message}")

                val isRetryable = when (e) {
                    is java.net.SocketTimeoutException,
                    is java.net.ConnectException,
                    is java.net.UnknownHostException -> true
                    is HttpErrorException -> e.statusCode == 429 || e.statusCode in 500..599
                    else -> false
                }

                if (!isRetryable || attempt == maxAttempts) {
                    break
                }

                val backoffMs = if (attempt == 1) 500L else 1000L
                kotlinx.coroutines.delay(backoffMs)
            }
        }

        // Network error after retries: DO NOT add to negativeCache
        val statusCode = (lastError as? HttpErrorException)?.statusCode
        val errMsg = when (lastError) {
            is java.net.SocketTimeoutException -> "Превышено время ожидания ответа сервера"
            is java.net.UnknownHostException, is java.net.ConnectException -> "Нет соединения с сервером LRCLIB"
            is HttpErrorException -> "Ошибка сервера LRCLIB (${lastError.statusCode})"
            else -> lastError?.message ?: "Сетевая ошибка при загрузке текста"
        }
        LrclibResult.NetworkError(errMsg, statusCode)
    }

    suspend fun fetchLyrics(
        context: Context,
        track: AudioFileMetadata,
        forceRefresh: Boolean = false
    ): LyricsData? {
        return when (val res = fetchLyricsWithResult(context, track, forceRefresh)) {
            is LrclibResult.Success -> res.lyrics
            else -> null
        }
    }

    private fun requestLrclibGet(q: Query): LyricsData? {
        if (q.artist.isNullOrBlank() || q.title.isNullOrBlank()) {
            return null
        }

        val urlBuilder = StringBuilder(LRCLIB_API)
            .append("get?artist_name=").append(urlEncode(q.artist))
            .append("&track_name=").append(urlEncode(q.title))

        if (!q.album.isNullOrBlank()) {
            urlBuilder.append("&album_name=").append(urlEncode(q.album))
        }
        if (q.durationSec > 0) {
            urlBuilder.append("&duration=").append(q.durationSec)
        }

        val body = executeHttpGet(urlBuilder.toString()) ?: return null
        return parseLrclibJson(JSONObject(body))
    }

    private fun requestLrclibSearch(q: Query): LyricsData? {
        if (q.title.isNullOrBlank()) return null

        val urlBuilder = StringBuilder(LRCLIB_API)
            .append("search?track_name=").append(urlEncode(q.title))

        if (!q.artist.isNullOrBlank()) {
            urlBuilder.append("&artist_name=").append(urlEncode(q.artist))
        }

        val body = executeHttpGet(urlBuilder.toString()) ?: return null
        val jsonArray = JSONArray(body)
        if (jsonArray.length() == 0) return null

        var bestScore = Int.MIN_VALUE
        var bestLyrics: LyricsData? = null

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val parsed = parseLrclibJson(obj) ?: continue

            var score = if (parsed.isSynced) 40 else 0
            if (q.durationSec > 0 && obj.has("duration")) {
                val candidateDuration = obj.optDouble("duration", 0.0).roundToInt()
                val diff = abs(candidateDuration - q.durationSec)
                score -= if (diff <= MAX_DURATION_DIFF) diff else 20 + min(diff, 60)
            }

            if (score > bestScore) {
                bestScore = score
                bestLyrics = parsed
            }
        }

        return bestLyrics
    }

    fun parseLrclibJson(obj: JSONObject): LyricsData? {
        val isInstrumental = obj.optBoolean("instrumental", false)
        if (isInstrumental) {
            return LyricsData.instrumental(LyricsSource.LRCLIB)
        }

        val synced = optString(obj, "syncedLyrics")
        if (!synced.isNullOrBlank()) {
            val parsed = LyricsParser.parse(synced, LyricsSource.LRCLIB)
            if (parsed.isSynced) {
                return parsed
            }
        }

        val plain = optString(obj, "plainLyrics")
        if (!plain.isNullOrBlank()) {
            return LyricsParser.parse(plain, LyricsSource.LRCLIB)
        }

        return if (!synced.isNullOrBlank()) {
            LyricsParser.parse(synced, LyricsSource.LRCLIB)
        } else {
            null
        }
    }

    private fun optString(obj: JSONObject, key: String): String? {
        if (obj.isNull(key)) return null
        val str = obj.optString(key, "")
        return if (str.isBlank()) null else str
    }

    private fun executeHttpGet(urlString: String): String? {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 6_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "FlacPlayer/1.17 (Android; +https://github.com/exteraless/exteraless)")
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            if (code == 404) {
                return null
            }
            if (code !in 200..299) {
                throw HttpErrorException(code, "HTTP error $code from $urlString")
            }

            connection.inputStream.use { readStreamToString(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readStreamToString(inputStream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } > 0) {
            output.write(buffer, 0, bytesRead)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun urlEncode(s: String): String {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DISK CACHE
    // ─────────────────────────────────────────────────────────────────────────

    private fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "lrclib")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getCacheFile(context: Context, key: String): File {
        val md5Key = md5(key)
        return File(getCacheDir(context), "$md5Key.json")
    }

    fun readDisk(context: Context, key: String): LyricsData? {
        return try {
            val file = getCacheFile(context, key)
            if (!file.exists()) return null

            val jsonStr = file.inputStream().use { readStreamToString(it) }
            val obj = JSONObject(jsonStr)

            val isInstrumental = obj.optBoolean("instrumental", false)
            if (isInstrumental) {
                return LyricsData.instrumental(LyricsSource.LRCLIB)
            }

            val text = optString(obj, "text") ?: return null
            LyricsParser.parse(text, LyricsSource.LRCLIB)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading disk cache for key: $key", e)
            null
        }
    }

    fun writeDisk(context: Context, key: String, lyrics: LyricsData) {
        try {
            val file = getCacheFile(context, key)
            val obj = JSONObject().apply {
                put("v", CACHE_VERSION)
                put("provider", LRCLIB_PROVIDER_NAME)
                put("instrumental", lyrics.isInstrumental)
                put("text", if (lyrics.isSynced) {
                    lyrics.lines.joinToString("\n") { line ->
                        val t = line.timestampMs.coerceAtLeast(0L)
                        val min = t / 60000
                        val sec = (t / 1000) % 60
                        val frac = (t % 1000) / 10
                        String.format(Locale.US, "[%02d:%02d.%02d]%s", min, sec, frac, line.text)
                    }
                } else {
                    lyrics.plainText
                })
            }

            file.outputStream().use {
                it.write(obj.toString().toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing disk cache for key: $key", e)
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
