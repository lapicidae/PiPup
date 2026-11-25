package nl.rogro82.pipup

import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import nl.rogro82.pipup.core.PowerController
import nl.rogro82.pipup.service.AdminReceiver
import nl.rogro82.pipup.service.PiPupAccessibilityService
import nl.rogro82.pipup.ui.SettingsActivity

/**
 * Centralized and robust permission management for PiPup.
 *
 * All permission checks, system intent launchers, and diagnostics are centralized here.
 * UI labels and guided fix dialogs live in [nl.rogro82.pipup.ui.PermissionsUi].
 */
@OptIn(UnstableApi::class)
object Permissions {

    const val LOG_TAG = "Permissions"

    const val KEY_OVERLAY = "overlay"
    const val KEY_INSTALL = "install"
    const val KEY_ADMIN = "admin"
    const val KEY_ACCESSIBILITY = "accessibility"
    const val KEY_AUTO_START = "autoStart"
    const val KEY_ENERGY = "energy"
    const val KEY_POWER = "power" // Virtual key for grouped power permissions

    /** List of permissions that can potentially be "fixed" via a system settings screen. */
    val FIXABLE_KEYS = listOf(KEY_OVERLAY, KEY_INSTALL, KEY_ADMIN, KEY_ACCESSIBILITY, KEY_ENERGY, KEY_AUTO_START)

    private const val OP_TCL_AUTO_START = "android:auto_start"
    private const val MS_PER_SECOND = 1000L
    private const val ENERGY_FALLBACK_DELAY_MS = 1500L
    private val PLACEHOLDER_MARKERS = listOf("CTSDummy", "frameworkpackagestubs")

    const val BLOCKED_ERROR =
        "Android blocks starting an activity from the background unless the overlay " +
            "permission is granted; open PiPup on the TV (or tap its notification) " +
            "and use the button on its status screen"

    @Volatile
    private var mInstallCheckError: String? = null

    @Volatile
    private var mLastFix: Map<String, Any?>? = null

    private val resolvedActivityCache = ConcurrentHashMap<String, String>()

    private val mVisibleActivities = AtomicInteger(0)

    val activityVisible: Boolean
        get() = mVisibleActivities.get() > 0

    /** Tracks that a PiPup activity entered the resumed state. */
    fun onActivityResumed() {
        Log.d(LOG_TAG, "Activity resumed, visibility counter: ${mVisibleActivities.incrementAndGet()}")
    }

    /** Tracks that a PiPup activity left the resumed state. */
    fun onActivityPaused() {
        val count = mVisibleActivities.updateAndGet { if (it > 0) it - 1 else 0 }
        Log.d(LOG_TAG, "Activity paused, visibility counter: $count")
    }

    /**
     * Checks if the overlay permission is granted.
     */
    @Suppress("TooGenericExceptionCaught") // OEM firmware may throw vendor runtime/linkage errors on Settings APIs
    fun overlay(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (ex: Throwable) {
        Log.e(LOG_TAG, "Cannot query overlay permission: ${ex.message}")
        false
    }

    /**
     * Checks if a vendor-specific auto-start permission is granted.
     * Implements a dispatcher to handle different manufacturer guards (TCL, Xiaomi, etc.).
     */
    fun autoStart(context: Context): Boolean? {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        val tclAllowed = try {
            checkAppOp(ops, OP_TCL_AUTO_START, context) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            null
        }

        val isXiaomi = Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
            Build.BRAND.contains("Xiaomi", ignoreCase = true)

        return tclAllowed ?: if (isXiaomi) false else null
    }

    /**
     * Returns whether a specific permission is granted.
     */
    fun granted(context: Context, key: String): Boolean? = when (key) {
        KEY_OVERLAY -> overlay(context)
        KEY_INSTALL -> installPackages(context)
        KEY_ADMIN -> when {
            PowerController.isDeviceAdminActive(context) -> true
            PowerController.isDeviceAdminSupported(context) -> false
            else -> null
        }
        KEY_ACCESSIBILITY -> PiPupAccessibilityService.isEnabledInSettings(context)
        KEY_AUTO_START -> autoStart(context)
        KEY_ENERGY -> {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        KEY_POWER -> PowerController.getSleepMethod(context) != null
        else -> null
    }

    /** Builds a summary map of current permission grant and fixability states for `/state`. */
    fun asMap(context: Context): Map<String, Any?> {
        val overlayGranted = overlay(context)
        return mapOf(
            "overlay" to overlayGranted,
            "installPackages" to installPackages(context),
            "autoStart" to autoStart(context),
            "deviceAdmin" to granted(context, KEY_ADMIN),
            "accessibility" to granted(context, KEY_ACCESSIBILITY),
            "complete" to overlayGranted,
            "fixable" to FIXABLE_KEYS.associateWith { fixIntent(context, it) != null },
        )
    }

    /**
     * Returns an ADB command string to grant the given permission.
     */
    fun adbCommand(key: String, context: Context): String {
        val pkg = context.packageName
        return when (key) {
            KEY_OVERLAY -> "adb shell appops set --user current $pkg SYSTEM_ALERT_WINDOW allow"
            KEY_INSTALL -> "adb shell appops set --user current $pkg REQUEST_INSTALL_PACKAGES allow"
            KEY_ADMIN -> "adb shell dpm set-active-admin --user current $pkg/.service.AdminReceiver"
            KEY_ACCESSIBILITY -> "adb shell settings put secure --user current " +
                "enabled_accessibility_services $pkg/.service.PiPupAccessibilityService && " +
                "adb shell settings put secure accessibility_enabled 1"
            KEY_AUTO_START -> {
                if (autoStart(context) != null && !Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true)) {
                    "adb shell cmd appops set --user current $pkg $OP_TCL_AUTO_START allow"
                } else {
                    ""
                }
            }
            KEY_ENERGY -> "adb shell dumpsys deviceidle whitelist +$pkg"
            else -> ""
        }
    }

    /** Returns true if the system AppOps mode explicitly blocks [key] from being granted via UI. */
    fun opBlocked(context: Context, key: String): Boolean {
        val op = when (key) {
            KEY_OVERLAY -> "android:system_alert_window"
            KEY_INSTALL -> "android:request_install_packages"
            else -> return false
        }
        return opMode(context, op) in setOf("errored", "ignored")
    }

    /** Returns true when a non-placeholder system settings screen exists to grant [key]. */
    internal fun isFixable(context: Context, key: String): Boolean = fixIntent(context, key) != null

    /** Returns true when PiPup is allowed to start an activity (foreground or overlay granted). */
    fun canLaunchActivity(context: Context): Boolean = activityVisible || overlay(context)

    /** Attempts to launch the system settings screen for the given permission key. */
    fun launchFix(context: Context, key: String): Boolean {
        Log.d(LOG_TAG, "launchFix($key) called")

        if (context is SettingsActivity) {
            context.focusRail()
        }

        return when (key) {
            KEY_ENERGY -> launchEnergyFix(context)
            KEY_ACCESSIBILITY -> openTvAccessibilitySettings(context)
            KEY_ADMIN -> launchAdminFix(context)
            else -> launchDefaultFix(context, key)
        }
    }

    /** Launches [MainActivity] when background activity starts are permitted. */
    fun launchApp(context: Context): Boolean {
        if (!canLaunchActivity(context)) {
            record("app", ok = false, activity = "MainActivity", error = BLOCKED_ERROR)
            return false
        }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val error = startActivityOrError(context, intent)
        record("app", ok = error == null, activity = "MainActivity", error = error)
        return error == null
    }

    /** Returns the first missing fixable permission key, or null when all fixable ones are granted. */
    fun firstMissing(context: Context): String? = FIXABLE_KEYS.firstOrNull {
        granted(context, it) == false && fixIntent(context, it) != null
    }

    /** Builds a full diagnostic report of device permissions, AppOps modes, and fix screens. */
    fun diagnose(context: Context): Map<String, Any?> = mapOf(
        "sdk" to Build.VERSION.SDK_INT,
        "version" to BuildConfig.VERSION_NAME,
        "device" to mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "android" to Build.VERSION.RELEASE,
        ),
        "permissions" to asMap(context),
        "opModes" to mapOf(
            "installPackages" to opMode(context, "android:request_install_packages"),
            "overlay" to opMode(context, "android:system_alert_window"),
        ),
        "installCheckError" to mInstallCheckError,
        "backgroundLaunchExempt" to canLaunchActivity(context),
        "activityVisible" to activityVisible,
        "screens" to FIXABLE_KEYS.associateWith { key ->
            val intent = rawIntent(context, key)
            val resolved = intent?.let { resolvedActivity(context, it, useCache = false) }
            mapOf(
                "granted" to granted(context, key),
                "action" to intent?.action,
                "resolvedActivity" to resolved,
                "placeholder" to isPlaceholder(resolved),
                "fixable" to (fixIntent(context, key) != null),
                "adb" to adbCommand(key, context),
            )
        },
        "lastFix" to mLastFix?.let { fix ->
            fix + mapOf("secondsAgo" to (SystemClock.elapsedRealtime() - (fix["at"] as Long)) / MS_PER_SECOND) - "at"
        },
    )

    @Suppress("TooGenericExceptionCaught") // OEM firmware may throw vendor runtime/linkage errors on PackageManager
    private fun installPackages(context: Context): Boolean = try {
        context.packageManager.canRequestPackageInstalls().also {
            mInstallCheckError = null
        }
    } catch (ex: Throwable) {
        mInstallCheckError = "${ex.javaClass.simpleName}: ${ex.message}"
        Log.e(LOG_TAG, "Cannot query install permission: ${ex.message}")
        false
    }

    @Suppress("TooGenericExceptionCaught") // OEM AppOps implementations may throw arbitrary runtime errors
    private fun opMode(context: Context, op: String): String = try {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        when (val mode = checkAppOp(ops, op, context)) {
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_IGNORED -> "ignored"
            AppOpsManager.MODE_ERRORED -> "errored"
            AppOpsManager.MODE_DEFAULT -> "default"
            else -> "mode $mode"
        }
    } catch (ex: Throwable) {
        "unavailable: ${ex.message}"
    }

    private fun checkAppOp(ops: AppOpsManager, op: String, context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            ops.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(op, Process.myUid(), context.packageName)
        }
    }

    private fun rawIntent(context: Context, key: String): Intent? = when (key) {
        KEY_OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
        KEY_INSTALL -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        KEY_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminReceiver.getComponentName(context))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.permission_admin_explanation),
            )
        }
        KEY_ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        KEY_AUTO_START -> {
            if (Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true)) {
                Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    )
                }
            } else {
                null
            }
        }
        KEY_ENERGY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else -> null
    }

    @Suppress("TooGenericExceptionCaught") // OEM PackageManager resolvers can throw unexpected runtime exceptions
    private fun resolvedActivity(context: Context, intent: Intent, useCache: Boolean = true): String? {
        val cacheKey = "${intent.action.orEmpty()}|${intent.component?.flattenToShortString().orEmpty()}"
        if (useCache) {
            resolvedActivityCache[cacheKey]?.let { return it.ifEmpty { null } }
        }
        val resolved = try {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, 0)?.activityInfo?.name
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "Cannot resolve ${intent.action}: ${ex.message}")
            null
        }
        resolvedActivityCache[cacheKey] = resolved.orEmpty()
        return resolved
    }

    private fun isPlaceholder(activity: String?): Boolean =
        (activity != null) && PLACEHOLDER_MARKERS.any { activity.contains(it, ignoreCase = true) }

    private fun fixIntent(context: Context, key: String): Intent? {
        val intent = if (key != KEY_OVERLAY && opBlocked(context, key)) null else rawIntent(context, key)
        val activity = intent?.let { resolvedActivity(context, it) }
        return if (intent != null && isPlaceholder(activity)) {
            Log.w(LOG_TAG, "Detected placeholder activity for $key: $activity")
            null
        } else {
            intent
        }
    }

    private fun launchAdminFix(context: Context): Boolean {
        val intent = rawIntent(context, KEY_ADMIN) ?: return false
        return if (context is SettingsActivity) {
            context.requestAdminRights(intent)
            true
        } else {
            openTvAdminSettings(context)
        }
    }

    private fun launchDefaultFix(context: Context, key: String): Boolean {
        val intent = rawIntent(context, key) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val error = startActivityOrError(context, intent)
        return if (error == null) {
            record(key, ok = true, activity = null, error = null)
            true
        } else {
            Log.e(LOG_TAG, "Failed to start fix activity for $key: $error")
            record(key, ok = false, activity = null, error = error)
            false
        }
    }

    private fun startActivityOrError(context: Context, intent: Intent): String? = try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        "${e.javaClass.simpleName}: ${e.message}"
    } catch (e: SecurityException) {
        "${e.javaClass.simpleName}: ${e.message}"
    } catch (e: IllegalStateException) {
        "${e.javaClass.simpleName}: ${e.message}"
    }

    private fun openTvAdminSettings(context: Context): Boolean {
        val intents = listOf(
            rawIntent(context, KEY_ADMIN),
            Intent("android.settings.DEVICE_ADMIN_SETTINGS"),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )

        for (intent in intents) {
            if (intent == null) continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Log.d(LOG_TAG, "Attempting to launch admin settings: ${intent.action}")
            val error = startActivityOrError(context, intent)
            if (error == null) {
                val name = resolvedActivity(context, intent) ?: intent.action
                record(KEY_ADMIN, ok = true, activity = name, error = null)
                return true
            }
            Log.w(LOG_TAG, "Failed to launch admin intent ${intent.action}: $error")
        }
        record(KEY_ADMIN, ok = false, activity = null, error = "All admin intents failed")
        return false
    }

    private fun openTvAccessibilitySettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return when {
            context is SettingsActivity -> {
                context.requestAdminRights(intent)
                true
            }
            startActivityOrError(context, intent) == null -> {
                record(KEY_ACCESSIBILITY, ok = true, activity = "AccessibilitySettings", error = null)
                true
            }
            else -> {
                Log.w(LOG_TAG, "Accessibility settings not found, falling back to general settings")
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val fallbackError = startActivityOrError(context, fallback)
                val ok = fallbackError == null
                record(
                    KEY_ACCESSIBILITY,
                    ok = ok,
                    activity = if (ok) "GeneralSettings" else null,
                    error = fallbackError,
                )
                ok
            }
        }
    }

    private fun launchEnergyFix(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            if (context is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            } else {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val error = startActivityOrError(context, intent)
        if (error != null) {
            Log.e(LOG_TAG, "Failed to launch energy settings: $error")
            return false
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val isStillResumed = (context as? AppCompatActivity)?.lifecycle?.currentState
                ?.isAtLeast(Lifecycle.State.RESUMED) ?: false
            if (isStillResumed) {
                Log.w(LOG_TAG, "Energy menu likely blocked, falling back to App Info")
                startActivityOrError(context, Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            }
        }, ENERGY_FALLBACK_DELAY_MS)
        return true
    }

    private fun record(what: String, ok: Boolean, activity: String?, error: String?) {
        mLastFix = mapOf(
            "what" to what,
            "ok" to ok,
            "activity" to activity,
            "error" to error,
            "at" to SystemClock.elapsedRealtime(),
        )
    }
}
