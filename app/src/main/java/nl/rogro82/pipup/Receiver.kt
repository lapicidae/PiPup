package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * BroadcastReceiver responsible for starting the [PipUpService] on system events.
 * 
 * It listens for boot completion and package updates to ensure the background
 * service is running without manual user intervention.
 */
@OptIn(UnstableApi::class)
class Receiver : BroadcastReceiver() {

    /**
     * Called when the BroadcastReceiver is receiving an Intent broadcast.
     * 
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received.
     */
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast action: $action")

        with(context) {
            val serviceIntent = Intent(this, PipUpService::class.java)
            try {
                Log.i(TAG, "Starting PipUpService as foreground service")
                startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start PipUpService from receiver", e)
            }
        }
    }

    companion object {
        private const val TAG = "PiPupReceiver"
    }
}
