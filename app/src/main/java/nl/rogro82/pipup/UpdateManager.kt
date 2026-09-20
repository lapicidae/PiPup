package nl.rogro82.pipup

import android.app.DownloadManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import nl.rogro82.pipup.service.PipUpService

/**
 * Data class representing a release on GitHub.
 */
data class GitHubRelease(
    val tagName: String,
    val name: String?,
    val prerelease: Boolean,
    val body: String?,
    val assets: List<GitHubAsset>
) {
    companion object {
        fun fromJsonObject(j: JSONObject): GitHubRelease {
            val assetList = mutableListOf<GitHubAsset>()
            val assetsArray = j.optJSONArray("assets")
            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    assetList.add(GitHubAsset.fromJsonObject(assetsArray.getJSONObject(i)))
                }
            }
            return GitHubRelease(
                tagName = j.optString("tag_name", ""),
                name = if (j.has("name") && !j.isNull("name")) j.getString("name") else null,
                prerelease = j.optBoolean("prerelease", false),
                body = if (j.has("body") && !j.isNull("body")) j.getString("body") else null,
                assets = assetList
            )
        }
    }
}

/**
 * Data class representing an asset within a GitHub release.
 */
data class GitHubAsset(
    val name: String,
    val browserDownloadUrl: String,
    val contentType: String,
    val digest: String? = null
) {
    companion object {
        fun fromJsonObject(j: JSONObject): GitHubAsset {
            return GitHubAsset(
                name = j.optString("name", ""),
                browserDownloadUrl = j.optString("browser_download_url", ""),
                contentType = j.optString("content_type", ""),
                digest = if (j.has("digest") && !j.isNull("digest")) j.getString("digest") else null
            )
        }
    }
}

/**
 * Manages the application update process, including checking for new releases,
 * downloading APKs, and triggering the installation.
 */
class UpdateManager(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Checks for new updates asynchronously using coroutines.
     * @param includeBeta Whether to include pre-release (beta) versions in the check.
     * @return A [Result] containing the latest [GitHubRelease] if available, null if no update.
     */
    suspend fun checkForUpdates(includeBeta: Boolean): Result<GitHubRelease?> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(REPO_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "PiPup-App")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == 200) {
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                lastCheckedAt = System.currentTimeMillis()
                lastError = null

                val releases = mutableListOf<GitHubRelease>()
                try {
                    val rootArray = JSONArray(json)
                    for (i in 0 until rootArray.length()) {
                        releases.add(GitHubRelease.fromJsonObject(rootArray.getJSONObject(i)))
                    }
                } catch (_: Exception) {
                    return@withContext Result.failure(Exception(appContext.getString(R.string.update_error_invalid_api)))
                }

                val latest = if (includeBeta) {
                    releases.firstOrNull()
                } else {
                    releases.firstOrNull { !it.prerelease }
                }

                latestVersion = latest?.tagName?.removePrefix("v")

                if (latest != null) {
                    Log.d("UpdateManager", "Comparing remote: ${latest.tagName} with beta channel: $includeBeta")
                    if (isNewer(appContext, latest.tagName)) {
                        Log.i("UpdateManager", "New version available: ${latest.tagName}")
                        return@withContext Result.success(latest)
                    } else {
                        Log.i("UpdateManager", "No update available. Current version matches or is newer than ${latest.tagName}")
                        return@withContext Result.success(null)
                    }
                } else {
                    Log.w("UpdateManager", "No releases found on GitHub for selected channel (beta=$includeBeta)")
                    return@withContext Result.success(null)
                }
            } else {
                lastError = "HTTP ${connection.responseCode}"
                return@withContext Result.failure(Exception(lastError))
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Error checking for updates", e)
            lastError = e.localizedMessage ?: appContext.getString(R.string.update_error_network)
            Result.failure(Exception(lastError))
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
        val appName = appContext.getString(R.string.app_name)
        val props = PopupProps(
            title = appContext.getString(R.string.notification_update_title, appName),
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
            putExtra("props", props.toJson())
        }
        appContext.startService(serviceIntent)
    }

    private fun showToastNotification(release: GitHubRelease) {
        appContext.showToast(appContext.getString(R.string.notification_update_msg, release.tagName), true)
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
        val appName = appContext.getString(R.string.app_name)
        val request = DownloadManager.Request(asset.browserDownloadUrl.toUri())
            .setTitle(appContext.getString(R.string.update_download_title, appName, release.tagName))
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
                        val scope = (appContext as? PiPupApp)?.applicationScope ?: kotlinx.coroutines.MainScope()
                        scope.launch { verifyAndInstall(digest) }
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
                    appContext.showToast(appContext.getString(R.string.update_download_failed, reason), true)
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

    private suspend fun verifyAndInstall(digest: String) = withContext(Dispatchers.IO) {
        try {
            val expectedHash = digest.substringAfter("sha256:").trim()
            val apkFile = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "pipup-update.apk")
            val actualHash = calculateSha256(apkFile)

            Log.d("UpdateManager", "Verification: expected=$expectedHash, actual=$actualHash")

            withContext(Dispatchers.Main) {
                if (expectedHash.equals(actualHash, ignoreCase = true)) {
                    Log.i("UpdateManager", "SHA-256 verification successful.")
                    installApk(appContext)
                } else {
                    Log.e("UpdateManager", "SHA-256 mismatch!")
                    appContext.showToast(appContext.getString(R.string.update_verification_failed), true)
                }
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Error during checksum verification", e)
            withContext(Dispatchers.Main) { installApk(appContext) }
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

    @SuppressLint("RequestInstallPackagesPolicy")
    private fun installApk(installContext: Context) {
        val file = File(installContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "pipup-update.apk")
        if (!file.exists()) {
            Log.e("UpdateManager", "APK file not found at: ${file.absolutePath}")
            return
        }

        val size = file.length()
        Log.i("UpdateManager", "Installing APK via PackageInstaller. Size: $size bytes")

        if (size < 1024 * 100) {
            Log.e("UpdateManager", "Downloaded file is too small ($size bytes). Likely a failed download.")
            return
        }

        val ctx = installContext.applicationContext
        try {
            isInstalling = true
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, size).use { out ->
                    FileInputStream(file).use { fis ->
                        fis.copyTo(out)
                    }
                    session.fsync(out)
                }
                registerResultReceiver(ctx)
                val intent = Intent(INSTALL_ACTION).setPackage(ctx.packageName)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(ctx, sessionId, intent, flags)
                session.commit(pendingIntent.intentSender)
            }
            Log.i("UpdateManager", "Update session $sessionId committed.")
        } catch (e: Exception) {
            Log.e("UpdateManager", "Error during PackageInstaller session", e)
            isInstalling = false
            ctx.showToast(ctx.getString(R.string.update_installer_failed, e.message), true)
        }
    }

    private var receiverRegistered = false

    @Synchronized
    @SuppressLint("AndroidLintUnsafeIntentLaunch")
    private fun registerResultReceiver(context: Context) {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.d("UpdateManager", "Installation result received: status=$status, message=$msg")

                    when (status) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(Intent.EXTRA_INTENT)
                            }
                            confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            confirmIntent?.let { ctx.startActivity(it) }
                        }
                        PackageInstaller.STATUS_SUCCESS -> {
                            Log.i("UpdateManager", "Update successful")
                            isInstalling = false
                            ctx.showToast(ctx.getString(R.string.update_done_title, latestVersion ?: ""), true)
                        }
                        else -> {
                            Log.e("UpdateManager", "Update failed ($status): $msg")
                            isInstalling = false
                            ctx.showToast(ctx.getString(R.string.update_installer_failed, msg ?: "Unknown error"), true)
                        }
                    }
                }
            },
            IntentFilter(INSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    companion object {
        private const val REPO_URL = "https://api.github.com/repos/lapicidae/PiPup/releases"
        private const val INSTALL_ACTION = "${PiPupApp.APP_PACKAGE}.INSTALL_RESULT"

        @Volatile var latestVersion: String? = null
            private set
        @Volatile var lastCheckedAt: Long = 0L
            private set
        @Volatile var lastError: String? = null
            private set
        @Volatile private var installStartedAt: Long = 0L

        var isInstalling: Boolean
            get() = installStartedAt != 0L && (SystemClock.elapsedRealtime() - installStartedAt < 15 * 60 * 1000L) // 15 min timeout
            internal set(value) {
                installStartedAt = if (value) SystemClock.elapsedRealtime() else 0L
            }

        /**
         * Whether a self-update can run without an on-screen confirmation (Android 12+).
         */
        val silentInstall: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        /**
         * Checks if an update is available based on the last check.
         */
        fun updateAvailable(context: Context): Boolean {
            val version = latestVersion ?: return false
            return isNewer(context, "v$version")
        }

        /**
         * Checks if a remote tag version is newer than the currently installed version.
         */
        fun isNewer(context: Context, remoteTag: String): Boolean {
            val currentVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
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

            val suffix1 = parts1.getOrNull(1)
            val suffix2 = parts2.getOrNull(1)

            return when {
                suffix1 == null && suffix2 == null -> 0
                suffix1 == null -> 1
                suffix2 == null -> -1
                else -> suffix1.compareTo(suffix2)
            }
        }
    }
}
