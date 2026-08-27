package nl.rogro82.pipup

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread
import nl.rogro82.pipup.service.PipUpService

/**
 * Data class representing a release on GitHub.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubRelease(
    @get:JsonProperty("tag_name") val tagName: String,
    val name: String?,
    val prerelease: Boolean,
    val body: String?,
    val assets: List<GitHubAsset>
)

/**
 * Data class representing an asset within a GitHub release.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubAsset(
    val name: String,
    @get:JsonProperty("browser_download_url") val browserDownloadUrl: String,
    @get:JsonProperty("content_type") val contentType: String,
    val digest: String? = null
)

/**
 * Manages the application update process, including checking for new releases,
 * downloading APKs, and triggering the installation.
 */
class UpdateManager(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Interface for update check callbacks.
     */
    interface UpdateCallback {
        /**
         * Called when a newer version is available.
         * @param release The information about the available release.
         */
        fun onUpdateAvailable(release: GitHubRelease)
        /**
         * Called when no update is available (already on the latest version).
         */
        fun onNoUpdate()
        /**
         * Called when an error occurs during the update check.
         * @param message The error message.
         */
        fun onError(message: String)
    }

    /**
     * Checks for new updates asynchronously.
     * @param includeBeta Whether to include pre-release (beta) versions in the check.
     * @param callback The callback to handle the results.
     */
    fun checkForUpdates(includeBeta: Boolean, callback: UpdateCallback) {
        thread {
            try {
                val connection = URL(REPO_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "PiPup-App")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val json = connection.inputStream.bufferedReader().use { it.readText() }
                    val rootNode = Json.mapper.readTree(json)
                    if (!rootNode.isArray) {
                        callback.onError(appContext.getString(R.string.update_error_invalid_api))
                        return@thread
                    }

                    val releases = mutableListOf<GitHubRelease>()
                    for (node in rootNode) {
                        val assets = mutableListOf<GitHubAsset>()
                        node.get("assets")?.forEach { assetNode ->
                            assets.add(GitHubAsset(
                                name = assetNode.get("name")?.asText() ?: "",
                                browserDownloadUrl = assetNode.get("browser_download_url")?.asText() ?: "",
                                contentType = assetNode.get("content_type")?.asText() ?: "",
                                digest = assetNode.get("digest")?.asText()
                            ))
                        }

                        releases.add(GitHubRelease(
                            tagName = node.get("tag_name")?.asText() ?: "",
                            name = node.get("name")?.asText(),
                            prerelease = node.get("prerelease")?.asBoolean() ?: false,
                            body = node.get("body")?.asText(),
                            assets = assets
                        ))
                    }

                    val latest = if (includeBeta) {
                        releases.firstOrNull()
                    } else {
                        releases.firstOrNull { !it.prerelease }
                    }

                    if (latest != null) {
                        Log.d("UpdateManager", "Comparing remote: ${latest.tagName} with beta channel: $includeBeta")
                        if (isNewer(latest.tagName)) {
                            Log.i("UpdateManager", "New version available: ${latest.tagName}")
                            callback.onUpdateAvailable(latest)
                        } else {
                            Log.i("UpdateManager", "No update available. Current version matches or is newer than ${latest.tagName}")
                            callback.onNoUpdate()
                        }
                    } else {
                        Log.w("UpdateManager", "No releases found on GitHub for selected channel (beta=$includeBeta)")
                        callback.onNoUpdate()
                    }
                } else {
                    callback.onError("HTTP ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e("UpdateManager", "Error checking for updates", e)
                callback.onError(e.localizedMessage ?: appContext.getString(R.string.update_error_network))
            }
        }
    }

    /**
     * Displays an update notification according to the user's preference (Popup or Toast).
     */
    fun showUpdateNotification(release: GitHubRelease) {
        val appSettings = PiPupApp.settings
        when (appSettings.updateNotificationStyle) {
            1 -> showPiPupPopup(release)
            2 -> showToastNotification(release)
        }
    }



    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun showPiPupPopup(release: GitHubRelease) {
        val appSettings = PiPupApp.settings
        val props = PopupProps(
            title = appContext.getString(R.string.notification_update_title),
            message = appContext.getString(R.string.notification_update_msg, release.tagName),
            duration = 10,
            position = appSettings.positionIndex,
            backgroundColor = appSettings.getFullBackgroundColor(),
            titleSize = appSettings.titleSize,
            titleColor = appSettings.titleColor,
            messageSize = appSettings.messageSize,
            messageColor = appSettings.messageColor,
            borderRadius = appSettings.borderRadius,
            borderWidth = appSettings.borderWidth,
            borderColor = appSettings.borderColor,
            titleAlignment = appSettings.titleAlignment,
            messageAlignment = appSettings.messageAlignment,
            mediaPosition = appSettings.mediaPosition,
            animationType = appSettings.animationType,
            animationDuration = appSettings.animationDuration,
            animationExit = appSettings.animationExit
        )

        val serviceIntent = Intent(appContext, PipUpService::class.java).apply {
            action = "DISPLAY_NOTIFICATION"
            putExtra("props", Json.mapper.writeValueAsString(props))
        }
        appContext.startService(serviceIntent)
    }

    private fun showToastNotification(release: GitHubRelease) {
        appContext.showToast(appContext.getString(R.string.notification_update_msg, release.tagName), android.widget.Toast.LENGTH_LONG)
    }

    /**
     * Checks if a remote tag version is newer than the currently installed version.
     */
    fun isNewer(remoteTag: String): Boolean {
        val currentVersion = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        } catch (_: Exception) {
            "0.0.0"
        }

        Log.d("UpdateManager", "Comparing remote: $remoteTag with local: $currentVersion")
        val result = compareVersions(remoteTag.replace("v", ""), currentVersion?.replace("v", "") ?: "0.0.0")
        return result > 0
    }

    /**
     * Compares two version strings.
     * Returns > 0 if v1 > v2, < 0 if v1 < v2, 0 if equal.
     * Handles semantic versioning and suffixes like -beta, -prerelease.
     */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split("-")
        val parts2 = v2.split("-")

        val main1 = parts1[0].split(".").mapNotNull { it.toIntOrNull() }
        val main2 = parts2[0].split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(main1.size, main2.size)
        for (i in 0 until length) {
            val n1 = main1.getOrElse(i) { 0 }
            val n2 = main2.getOrElse(i) { 0 }
            if (n1 != n2) return n1.compareTo(n2)
        }

        // Main version is same, compare suffixes
        val suffix1 = parts1.getOrNull(1)
        val suffix2 = parts2.getOrNull(1)

        return when {
            suffix1 == null && suffix2 == null -> 0
            suffix1 == null -> 1  // v1 is stable, v2 is beta -> v1 is newer
            suffix2 == null -> -1 // v1 is beta, v2 is stable -> v2 is newer
            else -> suffix1.compareTo(suffix2) // Both are beta, compare strings (simple)
        }
    }

    /**
     * Enqueues a download for the suitable APK from the release and prepares for installation.
     */
    fun downloadAndInstall(release: GitHubRelease) {
        val appSettings = PiPupApp.settings
        val isBetaChannel = appSettings.updateChannel == 1
        val isCurrentDebug = BuildConfig.DEBUG

        val asset = if (isCurrentDebug) {
            release.assets.find { it.name.contains("debug", true) && it.name.endsWith(".apk", true) }
        } else {
            val possibleApks = release.assets.filter {
                it.name.endsWith(".apk", true) && !it.name.contains("debug", true)
            }

            if (isBetaChannel) {
                possibleApks.firstOrNull()
            } else {
                possibleApks.find {
                    !it.name.contains("prerelease", true) && !it.name.contains("beta", true)
                }
            }
        }

        if (asset == null) {
            Log.e("UpdateManager", "No suitable APK found in release ${release.tagName}")
            return
        }

        // Clean up old download if it exists
        val oldFile = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "pipup-update.apk")
        if (oldFile.exists()) {
            oldFile.delete()
        }

        val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(asset.browserDownloadUrl.toUri())
            .setTitle(appContext.getString(R.string.update_download_title, release.tagName))
            .setDescription(appContext.getString(R.string.update_download_desc))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, "pipup-update.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadId = downloadManager.enqueue(request)

        // Persist state for recovery
        appSettings.pendingUpdateId = downloadId
        appSettings.pendingUpdateDigest = asset.digest ?: ""
        appSettings.pendingUpdateTagName = release.tagName

        Log.i("UpdateManager", "Update download enqueued. ID: $downloadId, Asset: ${asset.name}")
    }

    /**
     * Resumes a pending update if a download was previously enqueued.
     * This checks the status and proceeds to installation if successful.
     */
    fun resumePendingUpdate() {
        val appSettings = PiPupApp.settings
        val downloadId = appSettings.pendingUpdateId
        if (downloadId == -1L) return

        Log.d("UpdateManager", "Checking pending update status for ID: $downloadId")
        handleDownloadComplete(downloadId)
    }

    /**
     * Handles the completion of a download, verifying and installing if successful.
     * @param downloadId The ID of the completed download.
     */
    fun handleDownloadComplete(downloadId: Long) {
        val appSettings = PiPupApp.settings
        if (appSettings.pendingUpdateId != downloadId) {
            Log.d("UpdateManager", "Download ID mismatch (got $downloadId, expected ${appSettings.pendingUpdateId}). Ignoring.")
            return
        }

        val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)

        if (cursor.moveToFirst()) {
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = if (statusIdx != -1) cursor.getInt(statusIdx) else -1

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    Log.i("UpdateManager", "Download $downloadId successful.")
                    val digest = appSettings.pendingUpdateDigest
                    if (digest.isNotEmpty()) {
                        verifyAndInstall(digest)
                    } else {
                        Log.w("UpdateManager", "No digest stored for verification, proceeding with installation.")
                        installApk(appContext)
                    }
                    // Clear pending state after processing
                    appSettings.pendingUpdateId = -1L
                    appSettings.pendingUpdateDigest = ""
                    appSettings.pendingUpdateTagName = ""
                }
                DownloadManager.STATUS_FAILED -> {
                    val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val reason = if (reasonIdx != -1) cursor.getInt(reasonIdx) else -1
                    Log.e("UpdateManager", "Download failed. Reason: $reason")
                    appContext.showToast(appContext.getString(R.string.update_download_failed, reason), android.widget.Toast.LENGTH_LONG)
                    // Clear pending state on failure
                    appSettings.pendingUpdateId = -1L
                    appSettings.pendingUpdateDigest = ""
                    appSettings.pendingUpdateTagName = ""
                }
                else -> {
                    Log.d("UpdateManager", "Download $downloadId still in progress. Status: $status")
                }
            }
        }
        cursor.close()
    }

    private fun verifyAndInstall(digest: String) {
        thread {
            try {
                val expectedHash = digest.substringAfter("sha256:").trim()
                val apkFile = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "pipup-update.apk")
                val actualHash = calculateSha256(apkFile)

                Log.d("UpdateManager", "Verification: expected=$expectedHash, actual=$actualHash")

                if (expectedHash.equals(actualHash, ignoreCase = true)) {
                    Log.i("UpdateManager", "SHA-256 verification successful.")
                    Handler(Looper.getMainLooper()).post { installApk(appContext) }
                } else {
                    Log.e("UpdateManager", "SHA-256 mismatch!")
                    appContext.showToast(appContext.getString(R.string.update_verification_failed), android.widget.Toast.LENGTH_LONG)
                }
            } catch (e: Exception) {
                Log.e("UpdateManager", "Error during checksum verification", e)
                Handler(Looper.getMainLooper()).post { installApk(appContext) }
            }
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installApk(installContext: Context) {
        val file = File(installContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "pipup-update.apk")
        if (!file.exists()) {
            Log.e("UpdateManager", "APK file not found at: ${file.absolutePath}")
            return
        }

        val size = file.length()
        Log.i("UpdateManager", "Installing APK. Size: $size bytes, Path: ${file.absolutePath}")

        if (size < 1024 * 100) {
            Log.e("UpdateManager", "Downloaded file is too small ($size bytes). Likely a failed download.")
            return
        }

        try {
            val ctx = installContext.applicationContext
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            Log.d("UpdateManager", "Generated FileProvider URI: $uri")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addCategory(Intent.CATEGORY_DEFAULT)
            }

            ctx.startActivity(intent)
            Log.i("UpdateManager", "Installer intent started successfully.")
        } catch (e: Exception) {
            Log.e("UpdateManager", "Error launching APK installer", e)
            val ctx = installContext.applicationContext
            ctx.showToast(ctx.getString(R.string.update_installer_failed, e.message), android.widget.Toast.LENGTH_LONG)
        }
    }

    companion object {
        private const val REPO_URL = "https://api.github.com/repos/lapicidae/PiPup/releases"
    }
}
