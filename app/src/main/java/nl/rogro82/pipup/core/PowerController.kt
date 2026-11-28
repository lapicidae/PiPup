package nl.rogro82.pipup.core

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
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
    /** Default timeout in ms to wait for the display power state to settle after wake/sleep. */
    const val SCREEN_WAIT_MS = 3_000L
    private const val SCREEN_POLL_MS = 100L

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
     *
     * @param context The application context.
     * @return True if wake lock was acquired or activity was started.
     */
    fun wake(context: Context): Boolean {
        var success = false
        try {
            // CPU & Screen WakeLock: Force the screen to turn on immediately.
            // SCREEN_BRIGHT_WAKE_LOCK is deprecated but remains the most reliable way
            // for sideloaded apps to force a hardware wake on many TV OEMs.
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "PiPup:wake"
            )
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            success = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Wake lock failed", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Wake lock failed", e)
        }

        try {
            context.startActivity(
                Intent(context, WakeActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_NO_HISTORY
                    )
                }
            )
            success = true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "WakeActivity launch failed", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "WakeActivity launch failed", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "WakeActivity launch failed", e)
        }
        return success
    }

    /**
     * Blocks the calling worker thread up to [timeoutMs] until [isScreenOn] matches [targetOn].
     * Never call on the main thread.
     *
     * @return True if the screen reached [targetOn] before [timeoutMs] elapsed.
     */
    fun waitForScreen(context: Context, targetOn: Boolean, timeoutMs: Long = SCREEN_WAIT_MS): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var reached = isScreenOn(context) == targetOn
        while (!reached && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(SCREEN_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            reached = isScreenOn(context) == targetOn
        }
        return reached
    }

    /**
     * Attempts to put the screen to sleep (standby).
     * Tries Device Administrator first, then Accessibility Service.
     *
     * @param context The application context.
     * @return True if a sleep command was successfully executed.
     */
    fun sleep(context: Context): Boolean {
        val locked = tryDeviceAdminLock(context) || PiPupAccessibilityService.lockScreen()
        if (!locked) {
            Log.w(TAG, "No way to put screen to sleep. Grant Device Admin or Accessibility permissions.")
        }
        return locked
    }

    private fun tryDeviceAdminLock(context: Context): Boolean {
        return isDeviceAdminActive(context) && try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "lockNow failed, falling back to accessibility", e)
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "lockNow failed, falling back to accessibility", e)
            false
        }
    }
}
