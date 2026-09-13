package nl.rogro82.pipup.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import nl.rogro82.pipup.Permissions

/**
 * A transparent activity used to force the screen to wake up.
 * This is particularly effective for HDMI-CEC devices.
 */
class WakeActivity : Activity() {

    override fun onResume() {
        super.onResume()
        // Counts towards the background-activity-launch exemption just like any other
        // window of this app.
        Permissions.onActivityResumed()
    }

    override fun onPause() {
        super.onPause()
        Permissions.onActivityPaused()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set flags to wake the device and dismiss keyguard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // The activity itself is transparent (via theme).
        // We finish it after enough time for the panel and CEC link to wake.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                finish()
            }
        }, 1500)
    }
}
