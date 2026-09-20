package nl.rogro82.pipup

import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
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
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicInteger
import nl.rogro82.pipup.core.PowerController
import nl.rogro82.pipup.service.AdminReceiver
import nl.rogro82.pipup.service.PiPupAccessibilityService
import nl.rogro82.pipup.ui.SettingsActivity

/**
 * Centralized and robust permission management for PiPup.
 *
 * All permission metadata and implementations are centralized here.
 * Modules simply request these permissions by key.
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
    private val PLACEHOLDER_MARKERS = listOf("CTSDummy", "frameworkpackagestubs")

    const val BLOCKED_ERROR =
        "Android blocks starting an activity from the background unless the overlay " +
                "permission is granted; open PiPup on the TV (or tap its notification) " +
                "and use the button on its status screen"

    @Volatile
    private var mInstallCheckError: String? = null

    @Volatile
    private var mLastFix: Map<String, Any?>? = null

    private val mVisibleActivities = AtomicInteger(0)

    val activityVisible: Boolean
        get() = mVisibleActivities.get() > 0

    fun onActivityResumed() {
        Log.d(LOG_TAG, "Activity resumed, visibility counter: ${mVisibleActivities.incrementAndGet()}")
    }

    fun onActivityPaused() {
        val count = mVisibleActivities.updateAndGet { if (it > 0) it - 1 else 0 }
        Log.d(LOG_TAG, "Activity paused, visibility counter: $count")
    }

    /**
     * Checks if the overlay permission is granted.
     */
    fun overlay(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (ex: Throwable) {
        Log.e(LOG_TAG, "Cannot query overlay permission: ${ex.message}")
        false
    }

    /**
     * Checks if the package install permission is granted.
     */
    fun installPackages(context: Context): Boolean = try {
        context.packageManager.canRequestPackageInstalls().also {
            mInstallCheckError = null
        }
    } catch (ex: Throwable) {
        mInstallCheckError = "${ex.javaClass.simpleName}: ${ex.message}"
        Log.e(LOG_TAG, "Cannot query install permission: ${ex.message}")
        false
    }

    /**
     * Checks if a vendor-specific auto-start permission is granted.
     * Implements a dispatcher to handle different manufacturer guards (TCL, Xiaomi, etc.).
     */
    fun autoStart(context: Context): Boolean? {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        // 1. TCL Optimization (AppOp-based)
        try {
            // We use checkOpNoThrow to see if the op is even known to the system
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(OP_TCL_AUTO_START, Process.myUid(), context.packageName)
            val mode = checkAppOp(ops, OP_TCL_AUTO_START, context)
            return mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {}

        // 2. Xiaomi / MIUI (Intent-based)
        // On Xiaomi devices, background activity is strictly controlled.
        // We show the row (return false) so users can reach the MIUI management screen.
        if (Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
            Build.BRAND.contains("Xiaomi", ignoreCase = true)) {
            return false
        }

        // Return null for hardware without known specific auto-start restrictions
        // to gracefully hide the row in modular UIs.
        return null
    }

    fun opMode(context: Context, op: String): String = try {
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

    fun asMap(context: Context): Map<String, Any?> = mapOf(
        "overlay" to overlay(context),
        "installPackages" to installPackages(context),
        "autoStart" to autoStart(context),
        "deviceAdmin" to granted(context, KEY_ADMIN),
        "accessibility" to granted(context, KEY_ACCESSIBILITY),
        "complete" to overlay(context),
        "fixable" to FIXABLE_KEYS.associateWith { fixIntent(context, it) != null },
    )

    /**
     * Returns an ADB command string to grant the given permission.
     */
    fun adbCommand(key: String, context: Context): String {
        val pkg = context.packageName
        return when (key) {
            KEY_OVERLAY -> "adb shell appops set --user current $pkg SYSTEM_ALERT_WINDOW allow"
            KEY_INSTALL -> "adb shell appops set --user current $pkg REQUEST_INSTALL_PACKAGES allow"
            KEY_ADMIN -> "adb shell dpm set-active-admin --user current $pkg/.service.AdminReceiver"
            KEY_ACCESSIBILITY -> "adb shell settings put secure --user current enabled_accessibility_services $pkg/.service.PiPupAccessibilityService && adb shell settings put secure accessibility_enabled 1"
            KEY_AUTO_START -> {
                if (autoStart(context) != null && !Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true)) {
                    "adb shell cmd appops set --user current $pkg $OP_TCL_AUTO_START allow"
                } else ""
            }
            KEY_ENERGY -> "adb shell dumpsys deviceidle whitelist +$pkg"
            else -> ""
        }
    }

    /**
     * Returns the raw system intent to open the settings screen for a permission.
     */
    fun rawIntent(context: Context, key: String): Intent? = when (key) {
        KEY_OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
        KEY_INSTALL -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        KEY_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminReceiver.getComponentName(context))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.permission_admin_explanation))
        }
        KEY_ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        KEY_AUTO_START -> {
            if (Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true)) {
                Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
            } else null
        }
        KEY_ENERGY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else -> null
    }

    fun resolvedActivity(context: Context, intent: Intent): String? = try {
        @Suppress("DEPRECATION")
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.name
    } catch (ex: Throwable) {
        Log.e(LOG_TAG, "Cannot resolve ${intent.action}: ${ex.message}")
        null
    }

    fun isPlaceholder(activity: String?): Boolean =
        (activity != null) && PLACEHOLDER_MARKERS.any { activity.contains(it, ignoreCase = true) }

    fun opBlocked(context: Context, key: String): Boolean {
        val op = when (key) {
            KEY_OVERLAY -> "android:system_alert_window"
            KEY_INSTALL -> "android:request_install_packages"
            else -> return false
        }
        return opMode(context, op) in setOf("errored", "ignored")
    }

    /** Returns an intent to fix the permission, or null if the device explicitly blocks it or has no screen. */
    fun fixIntent(context: Context, key: String): Intent? {
        if (key != KEY_OVERLAY && opBlocked(context, key)) return null

        val intent = rawIntent(context, key) ?: return null
        val activity = resolvedActivity(context, intent)

        if (isPlaceholder(activity)) {
            Log.w(LOG_TAG, "Detected placeholder activity for $key: $activity")
            return null
        }

        return intent
    }

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
            KEY_ADMIN -> {
                val intent = rawIntent(context, KEY_ADMIN) ?: return false
                if (context is SettingsActivity) {
                    context.requestAdminRights(intent)
                    true
                } else {
                    openTvAdminSettings(context)
                }
            }
            else -> {
                val intent = rawIntent(context, key) ?: return false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                    record(key, ok = true, activity = null, error = null)
                    true
                } catch (ex: Throwable) {
                    Log.e(LOG_TAG, "Failed to start fix activity for $key: ${ex.message}")
                    record(key, ok = false, activity = null, error = ex.message)
                    false
                }
            }
        }
    }

    private fun openTvAdminSettings(context: Context): Boolean {
        val intents = listOf(
            rawIntent(context, KEY_ADMIN),
            Intent("android.settings.DEVICE_ADMIN_SETTINGS"),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            if (intent == null) continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                Log.d(LOG_TAG, "Attempting to launch admin settings: ${intent.action}")
                context.startActivity(intent)
                val name = resolvedActivity(context, intent) ?: intent.action
                record(KEY_ADMIN, ok = true, activity = name, error = null)
                return true
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to launch admin intent ${intent.action}: ${e.message}")
            }
        }
        record(KEY_ADMIN, ok = false, activity = null, error = "All admin intents failed")
        return false
    }

    private fun openTvAccessibilitySettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (context is SettingsActivity) {
            context.requestAdminRights(intent)
            return true
        }

        return try {
            context.startActivity(intent)
            record(KEY_ACCESSIBILITY, ok = true, activity = "AccessibilitySettings", error = null)
            true
        } catch (_: Exception) {
            Log.w(LOG_TAG, "Accessibility settings not found, falling back to general settings")
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                record(KEY_ACCESSIBILITY, ok = true, activity = "GeneralSettings", error = null)
                true
            } catch (e2: Exception) {
                record(KEY_ACCESSIBILITY, ok = false, activity = null, error = e2.message)
                false
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

        try {
            context.startActivity(intent)

            Handler(Looper.getMainLooper()).postDelayed({
                val isStillResumed = (context as? AppCompatActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: false
                if (isStillResumed) {
                    Log.w(LOG_TAG, "Energy menu likely blocked, falling back to App Info")
                    try {
                        val fallbackIntent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                        context.startActivity(fallbackIntent)
                    } catch (_: Exception) {}
                }
            }, 1500)
            return true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to launch energy settings", e)
            return false
        }
    }

    /**
     * Returns a human-readable label for the given permission key.
     */
    fun getLabel(context: Context, key: String): String {
        return when (key) {
            KEY_OVERLAY -> context.getString(R.string.permission_overlay)
            KEY_INSTALL -> context.getString(R.string.permission_install)
            KEY_ENERGY -> context.getString(R.string.energy_optimization_title)
            KEY_ADMIN -> context.getString(R.string.permission_admin)
            KEY_ACCESSIBILITY -> context.getString(R.string.permission_accessibility)
            KEY_AUTO_START -> context.getString(R.string.permission_autostart)
            KEY_POWER -> {
                val sleepMethod = PowerController.getSleepMethod(context)
                if (sleepMethod != null) {
                    val methodLabel = if (sleepMethod == PowerController.METHOD_DEVICE_ADMIN) context.getString(R.string.permission_admin) else context.getString(R.string.permission_accessibility)
                    context.getString(R.string.settings_module_power) + " ($methodLabel)"
                } else {
                    context.getString(R.string.settings_module_power)
                }
            }
            else -> key
        }
    }

    /**
     * Returns a human-readable reason/why-text for the given permission key.
     */
    fun getWhyText(context: Context, key: String): String? {
        val resId = when (key) {
            KEY_OVERLAY -> R.string.permission_overlay_why
            KEY_INSTALL -> R.string.permission_install_why
            KEY_AUTO_START -> R.string.permission_autostart_why
            KEY_ENERGY -> R.string.permission_energy_why
            KEY_ACCESSIBILITY -> R.string.permission_accessibility_why
            KEY_ADMIN, KEY_POWER -> R.string.permission_power_why
            else -> 0
        }
        return if (resId != 0) context.getString(resId) else null
    }

    /**
     * Shows a guided instruction dialog for any permission.
     */
    fun showFixDialog(context: Context, key: String) {
        if (key == KEY_POWER) {
            showPowerChoiceDialog(context)
            return
        }

        val label = getLabel(context, key)
        val appName = context.getString(R.string.app_name)
        val why = getWhyText(context, key)

        val fixIntentAvailable = fixIntent(context, key) != null

        val message = SpannableStringBuilder()
        if (why != null) {
            message.append(why)
        }

        if (key == KEY_ENERGY) {
            message.append("\n\n").append(context.getString(R.string.energy_optimization_manual, appName))
        }

        if (key == KEY_ADMIN || key == KEY_ACCESSIBILITY) {
            message.append("\n\n").append(context.getString(R.string.permission_restricted_hint))
        }

        appendAdbHint(context, message, key)

        if (fixIntentAvailable) {
            message.append("\n\n").append(context.getString(R.string.permission_ask_open_settings))
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(label)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)

        if (fixIntentAvailable) {
            builder.setPositiveButton(R.string.settings_open) { _, _ ->
                if (!launchFix(context, key)) {
                    context.showToast(context.getString(R.string.permission_blocked_device))
                }
            }
        }

        val dialog = builder.create()
        dialog.show()

        if (fixIntentAvailable) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
    }

    private fun appendAdbHint(context: Context, message: SpannableStringBuilder, key: String) {
        val adb = adbCommand(key, context)
        if (adb.isNotEmpty()) {
            message.append("\n\n").append(context.getString(R.string.permission_adb_hint))
            val start = message.length
            message.append("\n").append(adb)
            val end = message.length
            message.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun showPowerChoiceDialog(context: Context) {
        val adminFixable = fixIntent(context, KEY_ADMIN) != null
        val accFixable = fixIntent(context, KEY_ACCESSIBILITY) != null

        if (!adminFixable && !accFixable) {
            context.showToast(context.getString(R.string.permission_blocked_device))
            return
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = context.dpToPx(16)
            setPadding(p, p, p, p)
        }

        val msg = TextView(context).apply {
            text = context.getString(R.string.permission_power_why)
            textSize = 16f
            setPadding(0, 0, 0, context.dpToPx(16))
            setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
        }
        container.addView(msg)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_module_power)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        if (adminFixable) {
            container.addView(createOptionRow(context,
                context.getString(R.string.permission_admin),
                context.getString(R.string.permission_admin_desc)) {
                dialog.dismiss()
                showFixDialog(context, KEY_ADMIN)
            })
        }

        if (accFixable) {
            container.addView(createOptionRow(context,
                context.getString(R.string.permission_accessibility),
                context.getString(R.string.permission_accessibility_desc)) {
                dialog.dismiss()
                showFixDialog(context, KEY_ACCESSIBILITY)
            })
        }

        dialog.show()
        handler.post { container.getChildAt(1)?.requestFocus() }
    }

    private fun createOptionRow(context: Context, title: String, desc: String, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dpToPx(16), context.dpToPx(12), context.dpToPx(16), context.dpToPx(12))
            background = ContextCompat.getDrawable(context, R.drawable.focus_background)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = context.dpToPx(8)
            layoutParams = params
        }

        row.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
        })

        row.addView(TextView(context).apply {
            text = desc
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.colorOnSurfaceVariant))
            setPadding(0, context.dpToPx(2), 0, 0)
        })

        return row
    }

    private val handler = Handler(Looper.getMainLooper())

    fun launchApp(context: Context): Boolean {
        if (!canLaunchActivity(context)) {
            record("app", ok = false, activity = "MainActivity", error = BLOCKED_ERROR)
            return false
        }
        return try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            record("app", ok = true, activity = "MainActivity", error = null)
            true
        } catch (ex: Throwable) {
            record("app", ok = false, activity = "MainActivity", error = "${ex.javaClass.simpleName}: ${ex.message}")
            false
        }
    }

    fun firstMissing(context: Context): String? = FIXABLE_KEYS.firstOrNull {
        granted(context, it) == false && fixIntent(context, it) != null
    }

    private fun record(what: String, ok: Boolean, activity: String?, error: String?) {
        mLastFix = mapOf(
            "what" to what,
            "ok" to ok,
            "activity" to activity,
            "error" to error,
            "at" to SystemClock.elapsedRealtime()
        )
    }

    fun diagnose(context: Context): Map<String, Any?> = mapOf(
        "sdk" to Build.VERSION.SDK_INT,
        "version" to BuildConfig.VERSION_NAME,
        "device" to mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "android" to Build.VERSION.RELEASE
        ),
        "permissions" to asMap(context),
        "opModes" to mapOf(
            "installPackages" to opMode(context, "android:request_install_packages"),
            "overlay" to opMode(context, "android:system_alert_window")
        ),
        "installCheckError" to mInstallCheckError,
        "backgroundLaunchExempt" to canLaunchActivity(context),
        "activityVisible" to activityVisible,
        "screens" to FIXABLE_KEYS.associateWith { key ->
            val intent = rawIntent(context, key)
            val resolved = intent?.let { resolvedActivity(context, it) }
            mapOf(
                "granted" to granted(context, key),
                "action" to intent?.action,
                "resolvedActivity" to resolved,
                "placeholder" to isPlaceholder(resolved),
                "fixable" to (fixIntent(context, key) != null),
                "adb" to adbCommand(key, context)
            )
        },
        "lastFix" to mLastFix?.let { fix ->
            fix + mapOf("secondsAgo" to (SystemClock.elapsedRealtime() - (fix["at"] as Long)) / 1000) - "at"
        }
    )
}
