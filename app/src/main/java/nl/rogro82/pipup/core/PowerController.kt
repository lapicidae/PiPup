package nl.rogro82.pipup.core

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import nl.rogro82.pipup.service.AdminReceiver
import nl.rogro82.pipup.service.PiPupAccessibilityService
import nl.rogro82.pipup.ui.WakeActivity

/**
 * Utility for managing the power state (screen on/off) of the device.
 */
object PowerController {

    private const val TAG = "PowerController"

    const val METHOD_DEVICE_ADMIN = "device_admin"
    const val METHOD_ACCESSIBILITY = "accessibility"

    private const val WAKE_LOCK_TIMEOUT_MS = 3_000L

    /** Checks if the screen is currently interactive (on). */
    fun isScreenOn(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    /** Checks if the device claims to support Device Administration. */
    fun isDeviceAdminSupported(context: Context): Boolean = try {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)
    } catch (_: Exception) {
        false
    }

    /** Checks if PiPup is currently an active Device Administrator. */
    fun isDeviceAdminActive(context: Context): Boolean = try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.isAdminActive(AdminReceiver.getComponentName(context))
    } catch (_: Exception) {
        false
    }

    /** Determines which method (if any) is available to remotely put the screen to sleep. */
    fun getSleepMethod(context: Context): String? = when {
        isDeviceAdminActive(context) -> METHOD_DEVICE_ADMIN
        PiPupAccessibilityService.isAvailable() -> METHOD_ACCESSIBILITY
        else -> null
    }

    /**
     * Attempts to wake the screen.
     * Uses both a WakeLock and starting a transparent Activity for maximum reliability.
     */
    fun wake(context: Context): Boolean {
        var success = false
        try {
            // CPU WakeLock: Ensure the processor stays awake while we launch the Activity.
            // Screen wakeup is handled by WakeActivity's WindowManager flags (M3 standard).
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PiPup:wake")
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            success = true
        } catch (e: Exception) {
            Log.e(TAG, "CPU wake lock failed", e)
        }

        try {
            context.startActivity(
                Intent(context, WakeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_NO_HISTORY)
                }
            )
            success = true
        } catch (e: Exception) {
            Log.e(TAG, "WakeActivity launch failed", e)
        }
        return success
    }

    /**
     * Attempts to put the screen to sleep (standby).
     * Tries Device Administrator first, then Accessibility Service.
     */
    fun sleep(context: Context): Boolean {
        if (isDeviceAdminActive(context)) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.lockNow()
                return true
            } catch (e: Exception) {
                Log.e(TAG, "lockNow failed, falling back to accessibility", e)
            }
        }

        if (PiPupAccessibilityService.lockScreen()) {
            return true
        }

        Log.w(TAG, "No way to put screen to sleep. Grant Device Admin or Accessibility permissions.")
        return false
    }
}
