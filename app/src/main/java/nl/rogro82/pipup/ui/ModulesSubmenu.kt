package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.ActivationStrategy
import nl.rogro82.pipup.core.PowerController

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
        bindModuleToggle(
            root,
            R.id.container_power_module,
            R.id.switch_power_module,
            { settings.getActivationStrategy("power") != ActivationStrategy.OFF },
            { settings.setActivationStrategy("power", if (it) settings.getPreferredStrategy("power") else ActivationStrategy.OFF) },
            { if (it && PowerController.getSleepMethod(context) == null) Permissions.showFixDialog(context, Permissions.KEY_POWER) }
        )

        // Network Discovery Module
        bindModuleToggle(
            root,
            R.id.container_discovery_module,
            R.id.switch_discovery_module,
            { settings.getActivationStrategy("discovery") != ActivationStrategy.OFF },
            { settings.setActivationStrategy("discovery", if (it) settings.getPreferredStrategy("discovery") else ActivationStrategy.OFF) }
        )

        // Rich Media Module
        bindModuleToggle(
            root,
            R.id.container_media_module,
            R.id.switch_media_module,
            { settings.getActivationStrategy("media") != ActivationStrategy.OFF },
            { settings.setActivationStrategy("media", if (it) settings.getPreferredStrategy("media") else ActivationStrategy.OFF) }
        )
    }

    private fun bindModuleToggle(
        root: View,
        containerId: Int,
        switchId: Int,
        getter: () -> Boolean,
        onToggle: (Boolean) -> Unit,
        afterToggle: ((Boolean) -> Unit)? = null
    ) {
        root.findViewById<View>(containerId)?.apply {
            val sw = findViewById<SwitchCompat>(switchId)
            sw.isChecked = getter()

            // Disable direct interaction with the switch to avoid double-toggles
            sw.isClickable = false
            sw.isFocusable = false

            setOnClickListener {
                val newState = !getter()
                sw.isChecked = newState
                onToggle(newState)
                // Notify ModuleManager about the change
                val moduleId = when (containerId) {
                    R.id.container_power_module -> "power"
                    R.id.container_discovery_module -> "discovery"
                    R.id.container_media_module -> "media"
                    else -> ""
                }
                (context.applicationContext as PiPupApp).moduleManager.updateModuleState(
                    moduleId,
                    if (newState) settings.getActivationStrategy(moduleId) else ActivationStrategy.OFF
                )
                notifySettingsChanged()
                afterToggle?.invoke(newState)
                onSettingsChanged(false)
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }
    }

}
