package nl.rogro82.pipup.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * Optional accessibility service to provide remote sleep (lock) functionality
 * without requiring Device Administrator privileges.
 */
@SuppressLint("AccessibilityPolicy")
class PiPupAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onServiceConnected() {
        Log.i(LOG_TAG, "Accessibility service connected")
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(LOG_TAG, "Accessibility service unbinding")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(LOG_TAG, "Accessibility service destroying")
        instance = null
        super.onDestroy()
    }

    companion object {
        const val LOG_TAG = "PiPupAccessibility"
        private var instance: PiPupAccessibilityService? = null

        /** Checks if the service is currently running. */
        fun isAvailable(): Boolean = instance != null

        /** Attempts to lock the screen using a global accessibility action. */
        fun lockScreen(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
            } else false
        }

        /** Checks if the service is enabled in the system accessibility settings. */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabledServices.split(':').any { it.substringBefore('/') == context.packageName }
        }

        /**
         * Triggers a system scan of accessibility services by querying the manager.
         * This can help the OS decide to bind a service that is enabled but dormant.
         */
        fun triggerSystemScan(context: Context) {
            try {
                val am = context.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
                // Querying the enabled services list often triggers an internal refresh in the system.
                am.getEnabledAccessibilityServiceList(AccessibilityEvent.TYPES_ALL_MASK)
                Log.d(LOG_TAG, "Triggered passive system scan for accessibility services")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to trigger system scan: ${e.message}")
            }
        }
    }
}
