package nl.rogro82.pipup.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.R

/**
 * Submenu for managing optional PiPup modules.
 */
@UnstableApi
class ModulesSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        // Power Control Module
        root.findViewById<View>(R.id.container_power_module)?.apply {
            val sw = findViewById<SwitchCompat>(R.id.switch_power_module)
            sw.isChecked = settings.powerModuleEnabled
            setOnClickListener {
                val newState = !settings.powerModuleEnabled
                sw.isChecked = newState
                settings.powerModuleEnabled = newState
                notifySettingsChanged()
                if (newState && nl.rogro82.pipup.core.PowerController.getSleepMethod(context) == null) {
                    Permissions.showFixDialog(context, Permissions.KEY_POWER)
                }
                onSettingsChanged(false)
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }

        // Network Discovery Module
        root.findViewById<View>(R.id.container_discovery_module)?.apply {
            val sw = findViewById<SwitchCompat>(R.id.switch_discovery_module)
            sw.isChecked = settings.discoveryModuleEnabled
            setOnClickListener {
                val newState = !settings.discoveryModuleEnabled
                sw.isChecked = newState
                settings.discoveryModuleEnabled = newState
                notifySettingsChanged()
                onSettingsChanged(false)
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }

        // Rich Media Module
        root.findViewById<View>(R.id.container_media_module)?.apply {
            val sw = findViewById<SwitchCompat>(R.id.switch_media_module)
            sw.isChecked = settings.mediaModuleEnabled
            setOnClickListener {
                val newState = !settings.mediaModuleEnabled
                sw.isChecked = newState
                settings.mediaModuleEnabled = newState
                notifySettingsChanged()
                onSettingsChanged(false)
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }
    }

    private fun notifySettingsChanged() {
        val intent = Intent("nl.rogro82.pipup.SETTINGS_CHANGED").apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}
