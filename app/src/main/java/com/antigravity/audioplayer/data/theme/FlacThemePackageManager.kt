package com.antigravity.audioplayer.data.theme

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.antigravity.audioplayer.data.storage.FlacThemeConfig
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Import/export manager for Desktop-compatible `.flactheme` ZIP bundles (`theme.json` + `assets/`)
 * and local custom background media files (images, GIFs, MP4/WebM/MKV videos).
 */
object FlacThemePackageManager {

    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov", "mkv", "m4v")

    fun getThemesAssetsDir(context: Context): File {
        return File(context.filesDir, "themes-assets").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isVideoExtension(ext: String): Boolean {
        return VIDEO_EXTENSIONS.contains(ext.lowercase().trimStart('.'))
    }

    suspend fun copyBackgroundUriToAssets(
        context: Context,
        uri: Uri
    ): Result<Pair<String, Boolean>> = withContext(Dispatchers.IO) {
        try {
            val mime = context.contentResolver.getType(uri) ?: ""
            val extFromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            val extFromName = uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            val ext = (extFromMime ?: extFromName ?: if (mime.startsWith("video/")) "mp4" else "jpg").lowercase()
            val isVideo = mime.startsWith("video/") || isVideoExtension(ext)

            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(IllegalStateException("Не удалось прочитать файл"))

            val outFile = File(getThemesAssetsDir(context), "${sha1Hex(bytes)}.$ext")
            if (!outFile.exists()) {
                outFile.writeBytes(bytes)
            }
            Result.success(outFile.absolutePath to isVideo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportThemeToUri(
        context: Context,
        targetUri: Uri,
        config: FlacThemeConfig,
        themeName: String = "FlacTheme"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val rootJson = JSONObject().apply {
                put("schemaVersion", 1)
                put("name", themeName)
                put("exportedAt", System.currentTimeMillis())
                put("theme", config.toThemeJson())
            }

            val assetsObj = JSONObject()
            val mediaFiles = mutableListOf<Pair<String, File>>()
            val addedSourcePaths = mutableMapOf<String, String>()

            fun bundleMedia(slotKey: String, path: String) {
                if (path.isBlank()) return
                val f = File(path)
                if (!f.exists() || !f.isFile) return
                val existingZipName = addedSourcePaths[path]
                if (existingZipName != null) {
                    assetsObj.put(slotKey, existingZipName)
                    return
                }
                val ext = f.extension.lowercase().ifBlank { "bin" }
                val zipName = "assets/$slotKey.$ext"
                addedSourcePaths[path] = zipName
                mediaFiles.add(zipName to f)
                assetsObj.put(slotKey, zipName)
            }

            if (config.appBgMode == "custom" && config.appBgCustomPath.isNotBlank()) {
                bundleMedia("appBg", config.appBgCustomPath)
            }
            if (config.fsBgMode == "custom" && config.fsBgCustomPath.isNotBlank()) {
                bundleMedia("fsBg", config.fsBgCustomPath)
            }

            rootJson.put("assets", assetsObj)

            context.contentResolver.openOutputStream(targetUri)?.use { rawOut ->
                ZipOutputStream(BufferedOutputStream(rawOut)).use { zos ->
                    zos.putNextEntry(ZipEntry("theme.json"))
                    zos.write(rootJson.toString(2).toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    for ((zipName, srcFile) in mediaFiles) {
                        zos.putNextEntry(ZipEntry(zipName))
                        srcFile.inputStream().use { input -> input.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            } ?: return@withContext Result.failure(IllegalStateException("Не удалось открыть файл для записи"))

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importThemeFromUri(
        context: Context,
        sourceUri: Uri,
        prefs: LibraryPreferences
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val rawBytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(IllegalStateException("Не удалось открыть файл темы"))

            val parsedRoot: JSONObject
            val extractedAssets = mutableMapOf<String, ByteArray>()

            if (rawBytes.isNotEmpty() && rawBytes[0] == '{'.code.toByte()) {
                parsedRoot = JSONObject(String(rawBytes, Charsets.UTF_8))
            } else {
                ZipInputStream(BufferedInputStream(rawBytes.inputStream())).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val buf = ByteArrayOutputStream()
                            zis.copyTo(buf)
                            extractedAssets[entry.name] = buf.toByteArray()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                val themeBytes = extractedAssets["theme.json"]
                    ?: return@withContext Result.failure(IllegalStateException("Внутри темы отсутствует theme.json"))
                parsedRoot = JSONObject(String(themeBytes, Charsets.UTF_8))
            }

            val themeJson = parsedRoot.optJSONObject("theme") ?: parsedRoot
            val assetsObj = parsedRoot.optJSONObject("assets") ?: JSONObject()
            val assetsDir = getThemesAssetsDir(context)

            var nextConfig = FlacThemeConfig.fromJson(themeJson, prefs.themeConfig.value)

            fun saveAsset(slotKey: String): Pair<String, Boolean>? {
                val zipName = assetsObj.optString(slotKey, "").takeIf { it.isNotBlank() } ?: return null
                val data = extractedAssets[zipName] ?: return null
                val ext = zipName.substringAfterLast('.', "bin").lowercase()
                val outFile = File(assetsDir, "${sha1Hex(data)}.$ext")
                if (!outFile.exists()) {
                    FileOutputStream(outFile).use { it.write(data) }
                }
                return outFile.absolutePath to isVideoExtension(ext)
            }

            saveAsset("appBg")?.let { (path, isVideo) ->
                nextConfig = nextConfig.copy(appBgCustomPath = path, appBgIsVideo = isVideo)
            }
            saveAsset("fsBg")?.let { (path, isVideo) ->
                nextConfig = nextConfig.copy(fsBgCustomPath = path, fsBgIsVideo = isVideo)
            }

            prefs.setThemeConfig(nextConfig)
            val themeName = parsedRoot.optString("name", "Импортированная тема")
            Result.success(themeName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
