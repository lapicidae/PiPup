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
        val installGranted = Permissions.granted(context, Permissions.KEY_INSTALL) == true
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
        val requiredCard = addSectionCard(submenuRoot, context.getString(R.string.permission_header_required))
        addPermissionRow(requiredCard, Permissions.KEY_OVERLAY, isOptional = false)

        modulePermissions.forEach { key ->
            addPermissionRow(requiredCard, key, isOptional = false)
        }

        // 3. OPTIONAL SECTION
        val optionalCard = addSectionCard(submenuRoot, context.getString(R.string.permission_header_optional))
        addPermissionRow(optionalCard, Permissions.KEY_INSTALL, isOptional = true)
        addPermissionRow(optionalCard, Permissions.KEY_ENERGY, isOptional = true)

        renderModuleSettings(submenuRoot, SettingCategory.PERMISSIONS)

        settingsActivity?.setupSubmenuFocus()
    }

    private fun addPermissionRow(container: LinearLayout, key: String, isOptional: Boolean = false) {
        val granted = Permissions.granted(context, key)
        val isGranted = granted == true
        val isNotSupported = granted == null

        // UX Refinement: Hide N/A permissions in Release builds.
        // Developers can still see them (grayed out) in Debug builds for integration testing.
        if (isNotSupported && !BuildConfig.DEBUG) return

        val row = buildPermissionRowContainer(key, isGranted)
        val label = PermissionsUi.getLabel(context, key)
        row.addView(buildPermissionTitleView(label, isNotSupported, isGranted, isOptional))

        val why = PermissionsUi.getWhyText(context, key)
        if (why != null && shouldExplainPermission(key, isNotSupported, isGranted)) {
            row.addView(buildWhyTextView(if (isNotSupported) UNSUPPORTED_TEXT else why))
        }

        if (!isGranted && !isNotSupported) {
            val adb = Permissions.adbCommand(key, context)
            if (adb.isNotEmpty()) {
                row.addView(buildAdbHintView(adb))
            }
        }

        container.addView(row)
    }

    private fun shouldExplainPermission(key: String, isNotSupported: Boolean, isGranted: Boolean): Boolean =
        isNotSupported || !isGranted || key in ALWAYS_EXPLAIN_KEYS

    private fun buildPermissionRowContainer(key: String, isGranted: Boolean): LinearLayout =
        LinearLayout(context).apply {
            id = key.hashCode() and POSITIVE_INT_MASK
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.dpToPx(PAD_LG_DP),
                context.dpToPx(PAD_MD_DP),
                context.dpToPx(PAD_LG_DP),
                context.dpToPx(PAD_LG_DP),
            )
            background = ContextCompat.getDrawable(context, R.drawable.focus_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = context.dpToPx(PAD_SM_DP) }
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { if (!isGranted) PermissionsUi.showFixDialog(context, key) }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
            setOnKeyListener { _, keyCode, event ->
                event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            }
        }

    private fun buildPermissionTitleView(
        label: String,
        isNotSupported: Boolean,
        isGranted: Boolean,
        isOptional: Boolean,
    ): TextView = TextView(context).apply {
        text = when {
            isNotSupported -> context.getString(R.string.permission_granted, label) + " (N/A)"
            isGranted -> context.getString(R.string.permission_granted, label)
            else -> context.getString(R.string.permission_missing, label)
        }
        textSize = TITLE_TEXT_SIZE_SP
        val statusColor = when {
            isNotSupported -> R.color.colorOnSurfaceVariant
            isGranted -> R.color.status_green
            isOptional -> R.color.status_orange
            else -> R.color.status_red
        }
        setTextColor(ContextCompat.getColor(context, statusColor))
        if (!isGranted && !isNotSupported) setTypeface(null, Typeface.BOLD)
    }

    private fun buildWhyTextView(textValue: String): TextView = TextView(context).apply {
        text = textValue
        textSize = WHY_TEXT_SIZE_SP
        alpha = WHY_TEXT_ALPHA
        setTextColor(ContextCompat.getColor(this@PermissionsSubmenu.context, R.color.colorOnSurfaceVariant))
        setPadding(0, context.dpToPx(PAD_XS_DP), 0, 0)
    }

    private fun buildAdbHintView(adb: String): TextView = TextView(context).apply {
        text = context.getString(R.string.permission_adb_format, adb)
        textSize = ADB_TEXT_SIZE_SP
        typeface = Typeface.MONOSPACE
        setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
        setPadding(0, context.dpToPx(PAD_MD_DP), 0, 0)
    }

    /** Timing and layout constants for [PermissionsSubmenu]. */
    companion object {
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val POSITIVE_INT_MASK = 0x7FFFFFFF
        private const val TITLE_TEXT_SIZE_SP = 18f
        private const val WHY_TEXT_SIZE_SP = 14f
        private const val WHY_TEXT_ALPHA = 0.8f
        private const val ADB_TEXT_SIZE_SP = 11f
        private const val PAD_XS_DP = 4
        private const val PAD_SM_DP = 8
        private const val PAD_MD_DP = 12
        private const val PAD_LG_DP = 16
        private const val UNSUPPORTED_TEXT = "This permission is not applicable to your hardware."
        private val ALWAYS_EXPLAIN_KEYS = setOf(
            Permissions.KEY_OVERLAY,
            Permissions.KEY_ENERGY,
            Permissions.KEY_INSTALL,
            Permissions.KEY_ADMIN,
            Permissions.KEY_ACCESSIBILITY,
            Permissions.KEY_POWER,
        )
    }
}
