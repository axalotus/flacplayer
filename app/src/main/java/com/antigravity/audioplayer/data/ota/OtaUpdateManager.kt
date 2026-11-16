package com.antigravity.audioplayer.data.ota

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.antigravity.audioplayer.BuildConfig
import com.antigravity.audioplayer.data.storage.LibraryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val versionName: String,
    val tag: String,
    val title: String,
    val description: String,
    val downloadUrl: String,
    val apiUrl: String,
    val assetName: String,
    val fileSize: Long,
    val isPrerelease: Boolean
)

sealed class OtaStatus {
    object Idle : OtaStatus()
    object Checking : OtaStatus()
    object UpToDate : OtaStatus()
    data class UpdateAvailable(val release: AppReleaseInfo) : OtaStatus()
    data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : OtaStatus()
    data class ReadyToInstall(val apkFile: File, val release: AppReleaseInfo) : OtaStatus()
    data class Error(val message: String) : OtaStatus()
}

object OtaUpdateManager {
    private const val TAG = "OtaUpdateManager"

    private val _status = MutableStateFlow<OtaStatus>(OtaStatus.Idle)
    val status = _status.asStateFlow()

    private var currentApkFile: File? = null

    suspend fun checkForUpdates(context: Context): OtaStatus = withContext(Dispatchers.IO) {
        _status.value = OtaStatus.Checking
        val prefs = LibraryPreferences.getInstance(context)
        val repo = prefs.otaGithubRepo.value.trim()
        val token = prefs.otaGithubToken.value.trim()

        if (repo.isBlank() || !repo.contains("/")) {
            val err = OtaStatus.Error("Укажите репозиторий в формате владелец/название (например, axalotus/flacplayer)")
            _status.value = err
            return@withContext err
        }

        try {
            val apiUrl = "https://api.github.com/repos/$repo/releases"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 12000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "FlacPlayer-OTA/${BuildConfig.VERSION_NAME}")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            val responseCode = conn.responseCode
            if (responseCode == 404) {
                val msg = if (token.isBlank()) {
                    "Репозиторий '$repo' не найден или является приватным. Укажите Personal Access Token (PAT) в настройках."
                } else {
                    "Репозиторий '$repo' не найден или у токена нет доступа к релизам."
                }
                val err = OtaStatus.Error(msg)
                _status.value = err
                return@withContext err
            } else if (responseCode == 401) {
                val err = OtaStatus.Error("Неверный токен доступа GitHub (401 Unauthorized)")
                _status.value = err
                return@withContext err
            } else if (responseCode !in 200..299) {
                val err = OtaStatus.Error("Ошибка GitHub API: HTTP $responseCode")
                _status.value = err
                return@withContext err
            }

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val releasesJson = JSONArray(responseText)

            if (releasesJson.length() == 0) {
                _status.value = OtaStatus.UpToDate
                return@withContext OtaStatus.UpToDate
            }

            var latestReleaseWithApk: AppReleaseInfo? = null

            for (i in 0 until releasesJson.length()) {
                val item = releasesJson.getJSONObject(i)
                val isDraft = item.optBoolean("draft", false)
                if (isDraft) continue

                val isPrerelease = item.optBoolean("prerelease", false)
                val tagName = item.optString("tag_name", "").trim()
                val title = item.optString("name", tagName)
                val body = item.optString("body", "")

                val assets = item.optJSONArray("assets") ?: JSONArray()
                var apkAsset: JSONObject? = null
                for (a in 0 until assets.length()) {
                    val asset = assets.getJSONObject(a)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        apkAsset = asset
                        break
                    }
                }

                if (apkAsset != null) {
                    val downloadUrl = apkAsset.optString("browser_download_url", "")
                    val assetApiUrl = apkAsset.optString("url", "")
                    val assetName = apkAsset.optString("name", "update.apk")
                    val fileSize = apkAsset.optLong("size", 0L)
                    val cleanVersion = tagName.removePrefix("v").removePrefix("V")

                    latestReleaseWithApk = AppReleaseInfo(
                        versionName = cleanVersion,
                        tag = tagName,
                        title = title,
                        description = body,
                        downloadUrl = downloadUrl,
                        apiUrl = assetApiUrl,
                        assetName = assetName,
                        fileSize = fileSize,
                        isPrerelease = isPrerelease
                    )
                    break
                }
            }

            if (latestReleaseWithApk == null) {
                val err = OtaStatus.Error("В последних релизах '$repo' не найден готовый .apk файл")
                _status.value = err
                return@withContext err
            }

            val currentVersion = BuildConfig.VERSION_NAME
            if (isNewerVersion(latestReleaseWithApk.versionName, currentVersion)) {
                val available = OtaStatus.UpdateAvailable(latestReleaseWithApk)
                _status.value = available
                available
            } else {
                _status.value = OtaStatus.UpToDate
                OtaStatus.UpToDate
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking OTA updates", e)
            val err = OtaStatus.Error("Ошибка проверки: ${e.localizedMessage ?: e.message}")
            _status.value = err
            err
        }
    }

    suspend fun downloadUpdate(
        context: Context,
        release: AppReleaseInfo
    ): Result<File> = withContext(Dispatchers.IO) {
        val prefs = LibraryPreferences.getInstance(context)
        val token = prefs.otaGithubToken.value.trim()

        try {
            val destinationFile = File(context.cacheDir, "FlacPlayer-${release.versionName}.apk")
            if (destinationFile.exists() && destinationFile.length() == release.fileSize && release.fileSize > 0) {
                currentApkFile = destinationFile
                _status.value = OtaStatus.ReadyToInstall(destinationFile, release)
                return@withContext Result.success(destinationFile)
            }

            _status.value = OtaStatus.Downloading(0, 0L, release.fileSize)

            // For private repo releases, download using asset api url
            val targetUrl = if (token.isNotBlank() && release.apiUrl.isNotBlank()) {
                release.apiUrl
            } else {
                release.downloadUrl
            }

            var conn = openConnectionWithRedirects(targetUrl, token)
            val totalBytes = if (release.fileSize > 0) release.fileSize else conn.contentLengthLong

            conn.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    var downloaded = 0L
                    var lastPercent = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _status.value = OtaStatus.Downloading(percent, downloaded, totalBytes)
                            }
                        }
                    }
                }
            }

            currentApkFile = destinationFile
            val ready = OtaStatus.ReadyToInstall(destinationFile, release)
            _status.value = ready
            Result.success(destinationFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading update", e)
            val err = OtaStatus.Error("Сбой скачивания: ${e.localizedMessage ?: e.message}")
            _status.value = err
            Result.failure(e)
        }
    }

    private fun openConnectionWithRedirects(initialUrl: String, token: String?): HttpURLConnection {
        var currentUrl = initialUrl
        var isInitial = true

        for (redirectCount in 0..5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 25000
                setRequestProperty("User-Agent", "FlacPlayer-OTA/${BuildConfig.VERSION_NAME}")
                // GitHub API asset download requires Accept: application/octet-stream
                if (!token.isNullOrBlank() && isInitial) {
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/octet-stream")
                }
            }

            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirect missing Location")
                conn.disconnect()
                currentUrl = location
                isInitial = false // S3 URL does not accept GitHub Authorization header
                continue
            }

            if (code !in 200..299) {
                throw IllegalStateException("HTTP error $code from $currentUrl")
            }

            return conn
        }

        throw IllegalStateException("Too many redirects")
    }

    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file does not exist: ${apkFile.absolutePath}")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permissionIntent)
                    return false
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installer", e)
            false
        }
    }

    fun resetStatus() {
        _status.value = OtaStatus.Idle
    }

    fun isNewerVersion(remoteTag: String, localVersion: String): Boolean {
        try {
            val rParts = remoteTag.removePrefix("v").removePrefix("V").split("-", ".").mapNotNull { it.toIntOrNull() }
            val lParts = localVersion.removePrefix("v").removePrefix("V").split("-", ".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(rParts.size, lParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
        } catch (_: Exception) {}
        return remoteTag.trim() != localVersion.trim()
    }
}
