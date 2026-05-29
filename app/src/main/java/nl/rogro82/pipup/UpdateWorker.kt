package nl.rogro82.pipup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appSettings = AppSettings(applicationContext)
        val includeBeta = appSettings.updateChannel == 1
        val updateManager = UpdateManager(applicationContext)

        return try {
            suspendCancellableCoroutine { continuation ->
                updateManager.checkForUpdates(includeBeta, object : UpdateManager.UpdateCallback {
                    override fun onUpdateAvailable(release: GitHubRelease) {
                        appSettings.updateAvailableTag = release.tagName
                        appSettings.lastUpdateCheck = System.currentTimeMillis()

                        if (appSettings.updateRepeat || appSettings.lastNotifiedTag != release.tagName) {
                            updateManager.showUpdateNotification(release)
                            appSettings.lastNotifiedTag = release.tagName
                        }

                        if (continuation.isActive) continuation.resume(Result.success())
                    }

                    override fun onNoUpdate() {
                        appSettings.updateAvailableTag = ""
                        appSettings.lastUpdateCheck = System.currentTimeMillis()
                        if (continuation.isActive) continuation.resume(Result.success())
                    }

                    override fun onError(message: String) {
                        if (continuation.isActive) continuation.resume(Result.retry())
                    }
                })
            }
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "periodic_update_check"

        fun schedule(context: Context, intervalMode: Int) {
            val workManager = WorkManager.getInstance(context)

            // If interval is Off (0) or On App Open (1), cancel any existing background work
            if (intervalMode <= 1) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val repeatInterval = when (intervalMode) {
                2 -> 1L to TimeUnit.DAYS
                3 -> 7L to TimeUnit.DAYS
                4 -> 30L to TimeUnit.DAYS
                else -> return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateWorker>(repeatInterval.first, repeatInterval.second)
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
