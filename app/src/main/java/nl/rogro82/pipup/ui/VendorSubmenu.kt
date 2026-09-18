package nl.rogro82.pipup.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.PiPupModule
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.service.PiPupAccessibilityService

/**
 * Specialized submenu for the Vendor module.
 * Displays vendor-specific settings and status information like the Keep-Alive state.
 */
@OptIn(UnstableApi::class)
class VendorSubmenu(
    activity: SettingsActivity,
    settings: AppSettings,
    private val module: PiPupModule,
    onPreviewUpdate: (Boolean) -> Unit,
    previewContainer: ViewGroup
) : SubmenuBase(activity, settings, onPreviewUpdate, previewContainer as FrameLayout) {

    private lateinit var rootContainer: LinearLayout

    private val refreshTask = object : Runnable {
        override fun run() {
            if (::rootContainer.isInitialized && rootContainer.isAttachedToWindow) {
                updateStatus()
                handler.postDelayed(this, 3000)
            }
        }
    }

    override fun onBind(root: View) {
        rootContainer = root.findViewById(R.id.module_settings_container) ?: return
        render()
        handler.postDelayed(refreshTask, 1000)
    }

    override fun onBackPress(): Boolean {
        handler.removeCallbacks(refreshTask)
        return super.onBackPress()
    }

    private fun render() {
        rootContainer.removeAllViews()

        // 1. Settings Section
        val metadata = module.getSettingsMetadata()
        metadata.forEach { def ->
            renderSetting(rootContainer, module, def)
        }

        // 2. Status Section
        addSectionHeader(rootContainer, context.getString(R.string.server_landing_status))

        val statusView = LayoutInflater.from(context).inflate(R.layout.item_setting_toggle, rootContainer, false)
        statusView.id = R.id.vendor_status_row
        statusView.findViewById<View>(R.id.setting_switch)?.visibility = View.GONE
        statusView.findViewById<TextView>(R.id.setting_label)?.text = context.getString(R.string.settings_vendor_tcl_keepalive)
        statusView.isFocusable = false

        rootContainer.addView(statusView)
        updateStatus()

        settingsActivity?.setupSubmenuFocus()
    }

    private fun updateStatus() {
        val row = rootContainer.findViewById<View>(R.id.vendor_status_row) ?: return
        val label = row.findViewById<TextView>(R.id.setting_label) ?: return

        val isTcl = Permissions.autoStart(context) != null
        val accEnabled = PiPupAccessibilityService.isEnabledInSettings(context)
        val accRunning = PiPupAccessibilityService.isAvailable()

        val statusText = when {
            !isTcl -> context.getString(R.string.vendor_status_not_tcl)
            accRunning -> context.getString(R.string.vendor_status_active)
            accEnabled -> context.getString(R.string.vendor_status_waiting)
            else -> context.getString(R.string.vendor_status_inactive)
        }

        val color = when {
            !isTcl -> R.color.colorOnSurfaceVariant
            accRunning -> R.color.status_green
            accEnabled -> R.color.status_orange
            else -> R.color.status_red
        }

        label.text = context.getString(R.string.settings_module_setting_format, context.getString(R.string.settings_vendor_tcl_keepalive), statusText)
        label.setTextColor(ContextCompat.getColor(context, color))
    }

    private fun addSectionHeader(container: LinearLayout, title: String) {
        container.addView(
            TextView(context).apply {
                text = title.uppercase()
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.colorOnSurfaceVariant))
                setPadding(context.dpToPx(16), context.dpToPx(24), 0, context.dpToPx(8))
            }
        )
    }
}
