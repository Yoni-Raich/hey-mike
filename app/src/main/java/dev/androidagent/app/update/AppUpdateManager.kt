/*
 * Hey Mike - an on-device Android AI agent.
 * Copyright (C) 2025-2026 Yoni Raich
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * This file is part of Hey Mike, which is dual-licensed. You may use it under
 * the terms of the GNU Affero General Public License, version 3, as published
 * by the Free Software Foundation, or under a commercial license from the
 * copyright holder. See LICENSE, LICENSE-COMMERCIAL.md and NOTICE.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.androidagent.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.androidagent.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val latestVersionName: String,
    val latestTag: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkName: String,
    val apkSize: Long,
    val isUpdateAvailable: Boolean,
)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class Available(val info: AppUpdateInfo) : UpdateStatus
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateStatus
    data class ReadyToInstall(val apkFile: File, val info: AppUpdateInfo) : UpdateStatus
    data class UpToDate(val currentVersion: String) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}

class AppUpdateManager(
    private val context: Context,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val repoOwner: String = "Yoni-Raich",
    private val repoName: String = "hey-mike",
) {
    suspend fun checkForUpdates(): AppUpdateInfo = withContext(Dispatchers.IO) {
        val endpoint = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "AndroidAgent-App/$currentVersion")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("GitHub API returned HTTP $responseCode: $err")
            }
            val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
            parseReleaseJson(jsonString, currentVersion)
                ?: error("No compatible APK asset found in latest GitHub release")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadUpdate(
        info: AppUpdateInfo,
        onProgress: (progress: Float, downloaded: Long, total: Long) -> Unit = { _, _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val safeName = info.apkName.substringAfterLast('/').substringAfterLast('\\').replace("..", "").trim()
        val apkFile = File(updateDir, safeName.ifBlank { "android-agent-update.apk" })
        if (apkFile.exists()) apkFile.delete()

        var downloadUrl = info.apkDownloadUrl
        var redirects = 0
        try {
            while (redirects < 5) {
                require(downloadUrl.startsWith("https://", ignoreCase = true)) {
                    "Refusing non-HTTPS download URL: $downloadUrl"
                }
                val host = URL(downloadUrl).host.lowercase()
                val isTrustedHost = host == "github.com" || host.endsWith(".github.com") ||
                    host == "githubusercontent.com" || host.endsWith(".githubusercontent.com")
                require(isTrustedHost) {
                    "Untrusted download host: $host"
                }

                val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "AndroidAgent-App/$currentVersion")
                }
                try {
                    val code = connection.responseCode
                    if (code in listOf(
                            HttpURLConnection.HTTP_MOVED_PERM,
                            HttpURLConnection.HTTP_MOVED_TEMP,
                            HttpURLConnection.HTTP_SEE_OTHER,
                            307,
                            308,
                        )
                    ) {
                        val location = connection.getHeaderField("Location")
                            ?: error("Redirect without Location header")
                        downloadUrl = URL(URL(downloadUrl), location).toString()
                        redirects++
                        continue
                    }
                    if (code !in 200..299) {
                        error("Download failed with HTTP $code")
                    }

                    val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                    var downloadedBytes = 0L
                    var lastReportedTime = 0L
                    var lastReportedProgress = 0f

                    connection.inputStream.use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(16384)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val progress = if (totalBytes > 0) {
                                    (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                } else 0f
                                val now = System.currentTimeMillis()
                                if (now - lastReportedTime >= 200L || progress - lastReportedProgress >= 0.01f || downloadedBytes == totalBytes) {
                                    lastReportedTime = now
                                    lastReportedProgress = progress
                                    onProgress(progress, downloadedBytes, totalBytes)
                                }
                            }
                        }
                    }
                    require(apkFile.length() > 0) { "Downloaded APK file is empty" }
                    return@withContext apkFile
                } finally {
                    connection.disconnect()
                }
            }
            error("Too many redirects while downloading update")
        } catch (e: Exception) {
            if (apkFile.exists()) apkFile.delete()
            throw e
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createInstallIntent(apkFile: File): Intent {
        require(apkFile.exists() && apkFile.length() > 0) { "APK file does not exist or is empty" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createPermissionIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        fun parseReleaseJson(jsonString: String, currentVersion: String): AppUpdateInfo? {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val tagName = root["tag_name"]?.jsonPrimitive?.content.orEmpty().trim()
            if (tagName.isBlank()) return null
            val rawVersionName = tagName.removePrefix("v").removePrefix("V")
            val releaseNotes = root["body"]?.jsonPrimitive?.content.orEmpty().trim()

            val assets = root["assets"]?.jsonArray.orEmpty()
            val apkAsset = assets.mapNotNull { it.jsonObject }.firstOrNull { asset ->
                asset["name"]?.jsonPrimitive?.content.orEmpty().endsWith(".apk", ignoreCase = true)
            } ?: return null

            val apkName = apkAsset["name"]?.jsonPrimitive?.content.orEmpty()
            val downloadUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
            val apkSize = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L

            if (downloadUrl.isBlank()) return null

            val isAvailable = compareVersions(rawVersionName, currentVersion) > 0

            return AppUpdateInfo(
                latestVersionName = rawVersionName,
                latestTag = tagName,
                releaseNotes = releaseNotes,
                apkDownloadUrl = downloadUrl,
                apkName = apkName,
                apkSize = apkSize,
                isUpdateAvailable = isAvailable,
            )
        }

        /**
         * Compare two semver strings like "0.1.2" and "0.1.1".
         * Returns positive if v1 > v2, 0 if equal, negative if v1 < v2.
         */
        fun compareVersions(v1: String, v2: String): Int {
            val clean1 = v1.trim().removePrefix("v").removePrefix("V")
            val clean2 = v2.trim().removePrefix("v").removePrefix("V")

            val base1 = clean1.split("-", "_", "+")[0]
            val base2 = clean2.split("-", "_", "+")[0]

            val parts1 = base1.split(".").mapNotNull { it.toIntOrNull() }
            val parts2 = base2.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(parts1.size, parts2.size)
            for (i in 0 until maxLen) {
                val num1 = parts1.getOrElse(i) { 0 }
                val num2 = parts2.getOrElse(i) { 0 }
                if (num1 != num2) return num1.compareTo(num2)
            }

            // If base numeric parts are equal, check if one has pre-release metadata
            val hasSuffix1 = clean1.contains("-") || clean1.contains("+")
            val hasSuffix2 = clean2.contains("-") || clean2.contains("+")
            if (hasSuffix1 && !hasSuffix2) return -1 // 1.0.0-beta < 1.0.0
            if (!hasSuffix1 && hasSuffix2) return 1  // 1.0.0 > 1.0.0-beta

            return 0
        }
    }
}
