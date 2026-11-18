package com.zuna.player.data.theme

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.zuna.player.data.storage.FlacThemeConfig
import com.zuna.player.data.storage.LibraryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
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

    private fun isVideoExtension(ext: String): Boolean {
        return VIDEO_EXTENSIONS.contains(ext.lowercase().trimStart('.'))
    }

    private fun streamToHashedAssetFile(
        input: InputStream,
        assetsDir: File,
        ext: String
    ): File {
        val digest = MessageDigest.getInstance("SHA-1")
        val tmpFile = File.createTempFile("asset_tmp_", ".$ext", assetsDir)
        try {
            FileOutputStream(tmpFile).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    digest.update(buf, 0, read)
                    out.write(buf, 0, read)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            val finalFile = File(assetsDir, "$hex.$ext")
            if (finalFile.exists()) {
                tmpFile.delete()
            } else if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
            return finalFile
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    private fun readBoundedUtf8(input: InputStream, maxBytes: Int = 2 * 1024 * 1024): String {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalStateException("theme.json превышает допустимый размер")
            }
            baos.write(buf, 0, read)
        }
        return String(baos.toByteArray(), Charsets.UTF_8)
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

            val assetsDir = getThemesAssetsDir(context)
            val outFile = context.contentResolver.openInputStream(uri)?.use { input ->
                streamToHashedAssetFile(input, assetsDir, ext)
            } ?: return@withContext Result.failure(IllegalStateException("Не удалось прочитать файл"))

            Result.success(outFile.absolutePath to isVideo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportThemeToUri(
        context: Context,
        targetUri: Uri,
        config: FlacThemeConfig,
        themeName: String = "ZunaTheme"
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
        val tempPackage = File.createTempFile("theme_pkg_", ".tmp", context.cacheDir)
        val extractedFiles = mutableMapOf<String, File>()
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempPackage).use { out ->
                    input.copyTo(out, 64 * 1024)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Не удалось открыть файл темы"))

            val firstByte = FileInputStream(tempPackage).use { it.read() }
            val assetsDir = getThemesAssetsDir(context)
            val parsedRoot: JSONObject

            if (firstByte == '{'.code) {
                val jsonText = FileInputStream(tempPackage).use { readBoundedUtf8(it) }
                parsedRoot = JSONObject(jsonText)
            } else {
                var themeJsonText: String? = null
                ZipInputStream(BufferedInputStream(FileInputStream(tempPackage))).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val isSafeEntry = !entry.isDirectory &&
                            !name.startsWith("/") &&
                            !name.startsWith("\\") &&
                            !name.contains("..")
                        if (isSafeEntry) {
                            if (name == "theme.json") {
                                themeJsonText = readBoundedUtf8(zis)
                            } else {
                                val ext = name.substringAfterLast('.', "bin")
                                    .lowercase()
                                    .takeIf { it.length in 1..6 && it.all(Char::isLetterOrDigit) } ?: "bin"
                                extractedFiles[name] = streamToHashedAssetFile(zis, assetsDir, ext)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                val rawTheme = themeJsonText
                    ?: return@withContext Result.failure(IllegalStateException("Внутри темы отсутствует theme.json"))
                parsedRoot = JSONObject(rawTheme)
            }

            val themeJson = parsedRoot.optJSONObject("theme") ?: parsedRoot
            val assetsObj = parsedRoot.optJSONObject("assets") ?: JSONObject()

            var nextConfig = FlacThemeConfig.fromJson(themeJson, prefs.themeConfig.value)

            fun saveAsset(slotKey: String): Pair<String, Boolean>? {
                val zipName = assetsObj.optString(slotKey, "").takeIf { it.isNotBlank() } ?: return null
                val outFile = extractedFiles[zipName] ?: return null
                val ext = outFile.extension.lowercase()
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
        } finally {
            tempPackage.delete()
        }
    }
}
