package nl.rogro82.pipup

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Static receiver to handle completed update downloads.
 * This ensures the update continues even if the app process was stopped.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("UpdateDownloadReceiver", "Received broadcast action: $action")

        if (action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId != -1L) {
                Log.i("UpdateDownloadReceiver", "Processing completed download ID: $downloadId")
                UpdateManager(context).handleDownloadComplete(downloadId)
            } else {
                Log.w("UpdateDownloadReceiver", "Received DOWNLOAD_COMPLETE but EXTRA_DOWNLOAD_ID is missing.")
            }
        }
    }
}
