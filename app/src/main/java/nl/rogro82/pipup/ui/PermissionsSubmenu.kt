package nl.rogro82.pipup.ui

import android.content.Context
import android.graphics.Typeface
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.BuildConfig
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.R
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.core.PowerController
import nl.rogro82.pipup.core.SettingCategory

/**
 * Submenu for central permission management.
 * Adheres to modular architecture: asks ModuleManager for permissions instead of hardcoding.
 */
@UnstableApi
class PermissionsSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    private val refreshTask = object : Runnable {
        override fun run() {
            val root = rootView
            if (root != null && (settingsActivity?.getCurrentSubmenuLayout() == R.layout.submenu_permissions)) {
                if (root.isAttachedToWindow) {
                    refreshPermissionList(root)
                }
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
            } else {
                handler.removeCallbacks(this)
            }
        }
    }

    private var rootView: View? = null

    override fun onBind(root: View) {
        rootView = root
        handler.removeCallbacks(refreshTask)
        refreshPermissionList(root)
        handler.postDelayed(refreshTask, REFRESH_INTERVAL_MS)
    }

    override fun onBackPress(): Boolean {
        handler.removeCallbacks(refreshTask)
        return super.onBackPress()
    }

    private fun refreshPermissionList(root: View) {
        val submenuRoot: LinearLayout? = root.findViewById(R.id.permissions_root) ?: (root as? LinearLayout)
        if (submenuRoot == null || (submenuRoot.id != R.id.permissions_root && root.id != R.id.permissions_root)) return

        val sleepMethod = PowerController.getSleepMethod(context)
        val overlayGranted = Permissions.overlay(context)
        val installGranted = Permissions.installPackages(context)
        val energyGranted = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
        val accGranted = Permissions.granted(context, Permissions.KEY_ACCESSIBILITY) == true

        // Modular integration: Get permissions required by currently enabled modules
        val mm = (context.applicationContext as PiPupApp).moduleManager
        val modulePermissions = mm.getEnabledModules().flatMap { it.getRequiredPermissions() }.toSet()

        val currentSignature =
            "pow:$sleepMethod|acc:$accGranted|ov:$overlayGranted|in:$installGranted|" +
                "en:$energyGranted|mods:${modulePermissions.joinToString(",")}"

        if (submenuRoot.tag == currentSignature) return
        submenuRoot.tag = currentSignature

        // 1. Clear dynamic content
        val childCount = submenuRoot.childCount
        if (childCount > 1) {
            submenuRoot.removeViews(1, childCount - 1)
        }

        // 2. REQUIRED SECTION
        addSectionHeader(submenuRoot, context.getString(R.string.permission_header_required))
        addPermissionRow(submenuRoot, Permissions.KEY_OVERLAY, isOptional = false)

        modulePermissions.forEach { key ->
            addPermissionRow(submenuRoot, key, isOptional = false)
        }

        // 3. OPTIONAL SECTION
        addSectionHeader(submenuRoot, context.getString(R.string.permission_header_optional), marginTop = 32)
        addPermissionRow(submenuRoot, Permissions.KEY_INSTALL, isOptional = true)
        addPermissionRow(submenuRoot, Permissions.KEY_ENERGY, isOptional = true)

        renderModuleSettings(submenuRoot, SettingCategory.PERMISSIONS)

        settingsActivity?.setupSubmenuFocus()
    }

    private fun addSectionHeader(container: LinearLayout, title: String, marginTop: Int = PAD_LG_DP) {
        container.addView(
            TextView(context).apply {
                text = title.uppercase()
                textSize = HEADER_TEXT_SIZE_SP
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.colorOnSurfaceVariant))
                setPadding(context.dpToPx(PAD_LG_DP), context.dpToPx(marginTop), 0, context.dpToPx(PAD_SM_DP))
            }
        )
    }

    private fun addPermissionRow(container: LinearLayout, key: String, isOptional: Boolean = false) {
        val granted = Permissions.granted(context, key)
        val isGranted = granted == true
        val isNotSupported = granted == null

        // UX Refinement: Hide N/A permissions in Release builds.
        // Developers can still see them (grayed out) in Debug builds for integration testing.
        if (isNotSupported && !BuildConfig.DEBUG) return

        val label = Permissions.getLabel(context, key)

        val row = LinearLayout(context).apply {
            id = key.hashCode() and 0x7FFFFFFF
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.dpToPx(PAD_LG_DP),
                context.dpToPx(PAD_MD_DP),
                context.dpToPx(PAD_LG_DP),
                context.dpToPx(PAD_LG_DP),
            )
            background = ContextCompat.getDrawable(context, R.drawable.focus_background)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.bottomMargin = context.dpToPx(PAD_LG_DP)
            layoutParams = params

            isFocusable = true
            isFocusableInTouchMode = true

            setOnClickListener {
                if (!isGranted) {
                    Permissions.showFixDialog(context, key)
                }
            }

            onFocusChangeListener = View.OnFocusChangeListener { v, f ->
                if (f) updatePreviewPosition(v)
            }

            setOnKeyListener { _, keyCode, event ->
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            }
        }

        val title = TextView(context).apply {
            text = when {
                isNotSupported -> context.getString(R.string.permission_granted, label) + " (N/A)"
                isGranted -> context.getString(R.string.permission_granted, label)
                else -> context.getString(R.string.permission_missing, label)
            }
            textSize = 18f

            val statusColor = when {
                isNotSupported -> R.color.colorOnSurfaceVariant
                isGranted -> R.color.status_green
                isOptional -> R.color.status_orange
                else -> R.color.status_red
            }
            setTextColor(ContextCompat.getColor(context, statusColor))
            if (!isGranted && !isNotSupported) setTypeface(null, Typeface.BOLD)
        }
        row.addView(title)

        val why = Permissions.getWhyText(context, key)

        val alwaysExplainKeys = setOf(
            Permissions.KEY_OVERLAY,
            Permissions.KEY_ENERGY,
            Permissions.KEY_INSTALL,
            Permissions.KEY_ADMIN,
            Permissions.KEY_ACCESSIBILITY,
            Permissions.KEY_POWER,
        )
        if (why != null && (isNotSupported || !isGranted || key in alwaysExplainKeys)) {
            row.addView(TextView(context).apply {
                text = if (isNotSupported) "This permission is not applicable to your hardware." else why
                textSize = WHY_TEXT_SIZE_SP
                alpha = WHY_TEXT_ALPHA
                setTextColor(ContextCompat.getColor(this@PermissionsSubmenu.context, R.color.colorOnSurfaceVariant))
                setPadding(0, context.dpToPx(PAD_XS_DP), 0, 0)
            })
        }

        if (!isGranted && !isNotSupported) {
            val adb = Permissions.adbCommand(key, context)
            if (adb.isNotEmpty()) {
                row.addView(TextView(context).apply {
                    text = context.getString(R.string.permission_adb_format, adb)
                    textSize = ADB_TEXT_SIZE_SP
                    typeface = Typeface.MONOSPACE
                    setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
                    setPadding(0, context.dpToPx(PAD_MD_DP), 0, 0)
                })
            }
        }

        container.addView(row)
    }

    /** Timing and layout constants for [PermissionsSubmenu]. */
    companion object {
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val HEADER_TEXT_SIZE_SP = 14f
        private const val WHY_TEXT_SIZE_SP = 14f
        private const val WHY_TEXT_ALPHA = 0.8f
        private const val ADB_TEXT_SIZE_SP = 11f
        private const val PAD_XS_DP = 4
        private const val PAD_SM_DP = 8
        private const val PAD_MD_DP = 12
        private const val PAD_LG_DP = 16
    }
}
