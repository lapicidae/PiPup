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

                // Notify service
                val settingsIntent = Intent("nl.rogro82.pipup.SETTINGS_CHANGED")
                settingsIntent.setPackage(context.packageName)
                context.sendBroadcast(settingsIntent)

                if (newState && nl.rogro82.pipup.core.PowerController.getSleepMethod(context) == null) {
                    Permissions.showFixDialog(context, Permissions.KEY_POWER)
                }

                onSettingsChanged(false)
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    updatePreviewPosition(v)
                }
            }
        }
    }
}
