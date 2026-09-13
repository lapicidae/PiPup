package nl.rogro82.pipup.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.R
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.core.PowerController

/**
 * Submenu for central permission management.
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
            // Only continue if we are still the active submenu.
            // We wait for attachment if it hasn't happened yet.
            if (root != null && (settingsActivity?.getCurrentSubmenuLayout() == R.layout.submenu_permissions)) {
                if (root.isAttachedToWindow) {
                    refreshPermissionList(root)
                }
                handler.postDelayed(this, 2000)
            } else {
                handler.removeCallbacks(this)
            }
        }
    }

    private var rootView: View? = null

    override fun onBind(root: View) {
        rootView = root

        // Stop any previous task instances to avoid parallel loops
        handler.removeCallbacks(refreshTask)

        // Initial render logic
        refreshPermissionList(root)
        handler.postDelayed(refreshTask, 2000)
    }

    override fun onBackPress(): Boolean {
        handler.removeCallbacks(refreshTask)
        return super.onBackPress()
    }

    private fun refreshPermissionList(root: View) {
        // Robustness: Handle both the direct root (from ViewStub) or its container
        val submenuRoot: LinearLayout? = root.findViewById(R.id.permissions_root) ?: (root as? LinearLayout)
        if (submenuRoot == null || (submenuRoot.id != R.id.permissions_root && root.id != R.id.permissions_root)) return

        // Use a signature to avoid unnecessary UI rebuilds
        val sleepMethod = PowerController.getSleepMethod(context)
        val powerModuleEnabled = settings.powerModuleEnabled
        val overlayGranted = Permissions.overlay(context)
        val installGranted = Permissions.installPackages(context)
        val energyGranted = (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName)

        val currentSignature = "pow:$powerModuleEnabled:$sleepMethod|ov:$overlayGranted|in:$installGranted|en:$energyGranted"

        if (submenuRoot.tag == currentSignature) return
        submenuRoot.tag = currentSignature

        // 1. Clear everything except the first child (the title)
        val childCount = submenuRoot.childCount
        if (childCount > 1) {
            submenuRoot.removeViews(1, childCount - 1)
        }

        // 2. REQUIRED SECTION
        addSectionHeader(submenuRoot, context.getString(R.string.permission_header_required))
        addPermissionRow(submenuRoot, Permissions.KEY_OVERLAY, isOptional = false)

        // Dynamically add module-required permissions
        val mm = (context.applicationContext as nl.rogro82.pipup.PiPupApp).moduleManager
        val modulePermissions = mm.getEnabledModules().flatMap { it.getRequiredPermissions() }.toSet()

        modulePermissions.forEach { key ->
            addPermissionRow(
                submenuRoot,
                key,
                isOptional = false
            )
        }

        // 3. OPTIONAL SECTION
        addSectionHeader(submenuRoot, context.getString(R.string.permission_header_optional), marginTop = 32)
        addPermissionRow(submenuRoot, Permissions.KEY_INSTALL, isOptional = true)
        addPermissionRow(submenuRoot, Permissions.KEY_ENERGY, isOptional = true)

        renderModuleSettings(submenuRoot, nl.rogro82.pipup.core.SettingCategory.PERMISSIONS)

        // 4. Trigger focus recalculation
        settingsActivity?.setupSubmenuFocus()
    }

    private fun addSectionHeader(container: LinearLayout, title: String, marginTop: Int = 16) {
        container.addView(
            TextView(context).apply {
                text = title.uppercase()
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.colorOnSurfaceVariant))
                setPadding(context.dpToPx(16), context.dpToPx(marginTop), 0, context.dpToPx(8))
            }
        )
    }

    private fun addPermissionRow(container: LinearLayout, key: String, isOptional: Boolean = false) {
        val granted = Permissions.granted(context, key) ?: false
        val label = when(key) {
            Permissions.KEY_OVERLAY -> context.getString(R.string.permission_overlay)
            Permissions.KEY_ENERGY -> context.getString(R.string.energy_optimization_title)
            Permissions.KEY_INSTALL -> context.getString(R.string.permission_install)
            Permissions.KEY_ADMIN -> context.getString(R.string.permission_admin)
            Permissions.KEY_ACCESSIBILITY -> context.getString(R.string.permission_accessibility)
            Permissions.KEY_POWER -> {
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

        val row = LinearLayout(context).apply {
            // Stable ID is crucial: Android uses it to restore focus after a list refresh.
            id = key.hashCode() and 0x7FFFFFFF
            orientation = LinearLayout.VERTICAL
            setPadding(context.dpToPx(16), context.dpToPx(12), context.dpToPx(16), context.dpToPx(16))
            background = ContextCompat.getDrawable(context, R.drawable.focus_background)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = context.dpToPx(16)
            layoutParams = params

            isFocusable = true
            isFocusableInTouchMode = true

            setOnClickListener {
                if (!granted) {
                    Permissions.showFixDialog(context, key)
                }
            }

            onFocusChangeListener = View.OnFocusChangeListener { v, f ->
                if (f) {
                    updatePreviewPosition(v)
                }
            }

            // Trap focus on the right side to prevent it from disappearing
            setOnKeyListener { _, keyCode, event ->
                event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            }
        }

        val title = TextView(context).apply {
            text = if (granted) context.getString(R.string.permission_granted, label) else context.getString(R.string.permission_missing, label)
            textSize = 18f

            val statusColor = when {
                granted -> R.color.status_green
                isOptional -> R.color.status_orange
                else -> R.color.status_red
            }
            setTextColor(ContextCompat.getColor(context, statusColor))
            if (!granted) setTypeface(null, Typeface.BOLD)
        }
        row.addView(title)

        val whyRes = when(key) {
            Permissions.KEY_OVERLAY -> R.string.permission_overlay_why
            Permissions.KEY_ENERGY -> R.string.permission_energy_why
            Permissions.KEY_INSTALL -> R.string.permission_install_why
            Permissions.KEY_ADMIN, Permissions.KEY_ACCESSIBILITY, Permissions.KEY_POWER -> R.string.permission_power_why
            else -> null
        }

        if (whyRes != null && (!granted || key in setOf(Permissions.KEY_OVERLAY, Permissions.KEY_ENERGY, Permissions.KEY_INSTALL, Permissions.KEY_ADMIN, Permissions.KEY_ACCESSIBILITY, Permissions.KEY_POWER))) {
            row.addView(TextView(context).apply {
                text = context.getString(whyRes)
                textSize = 14f
                alpha = 0.8f
                setTextColor(ContextCompat.getColor(this@PermissionsSubmenu.context, R.color.colorOnSurfaceVariant))
                setPadding(0, context.dpToPx(4), 0, 0)
            })
        }

        if (!granted) {
            val adb = Permissions.adbCommand(key, context)
            if (adb.isNotEmpty()) {
                row.addView(TextView(context).apply {
                    text = context.getString(R.string.permission_adb_format, adb)
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
                    setPadding(0, context.dpToPx(12), 0, 0)
                })
            }
        }

        container.addView(row)
    }
}
