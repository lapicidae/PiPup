package nl.rogro82.pipup.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

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
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
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
            val expectedComponentName = ComponentName(context, PiPupAccessibilityService::class.java).flattenToString()
            val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }
}
