package nl.rogro82.pipup.ui

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
import nl.rogro82.pipup.BuildConfig
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.PiPupModule
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
        handler.postDelayed(refreshTask, INITIAL_REFRESH_DELAY_MS)
    }

    override fun onBackPress(): Boolean {
        handler.removeCallbacks(refreshTask)
        return super.onBackPress()
    }

    private fun render() {
        rootContainer.removeAllViews()

        // 1. Settings Section
        val metadata = module.getSettingsMetadata()
        if (metadata.isNotEmpty()) {
            val settingsCard = addSectionCard(rootContainer, context.getString(module.nameRes))
            metadata.forEach { def ->
                renderSetting(settingsCard, module, def)
            }
        }

        // 2. Status Section
        val statusCard = addSectionCard(rootContainer, context.getString(R.string.settings_vendor_status))

        val statusView = LayoutInflater.from(context).inflate(R.layout.item_setting_toggle, statusCard, false)
        statusView.id = R.id.vendor_status_row
        statusView.findViewById<View>(R.id.setting_switch)?.visibility = View.GONE
        statusView.findViewById<TextView>(R.id.setting_label)?.text =
            context.getString(R.string.settings_vendor_tcl_keepalive)
        statusView.isFocusable = false

        statusCard.addView(statusView)
        updateStatus()

        settingsActivity?.setupSubmenuFocus()
    }

    private fun updateStatus() {
        val row = rootContainer.findViewById<View>(R.id.vendor_status_row) ?: return
        val label = row.findViewById<TextView>(R.id.setting_label) ?: return

        val isTcl = Permissions.autoStart(context) != null || BuildConfig.DEBUG
        val isEnabled = settings.getModuleSetting(module.id, "tcl_optimization", false)

        if (isTcl && isEnabled) {
            PiPupAccessibilityService.triggerSystemScan(context)
        }

        val accEnabled = PiPupAccessibilityService.isEnabledInSettings(context)
        val accRunning = PiPupAccessibilityService.isAvailable()

        val (statusTextRes, colorRes) = when {
            !isTcl -> R.string.vendor_status_not_tcl to R.color.colorOnSurfaceVariant
            !isEnabled -> R.string.vendor_status_disabled to R.color.status_red
            accRunning -> R.string.vendor_status_active to R.color.status_green
            accEnabled -> R.string.vendor_status_waiting to R.color.status_orange
            else -> R.string.vendor_status_inactive to R.color.status_red
        }

        label.text = context.getString(
            R.string.settings_module_setting_format,
            context.getString(R.string.settings_vendor_tcl_keepalive),
            context.getString(statusTextRes),
        )
        label.setTextColor(ContextCompat.getColor(context, colorRes))
    }

    /** Timing constants for [VendorSubmenu]. */
    companion object {
        private const val INITIAL_REFRESH_DELAY_MS = 1000L
    }
}
