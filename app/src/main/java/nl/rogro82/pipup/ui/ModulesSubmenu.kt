package nl.rogro82.pipup.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.ModuleMode
import nl.rogro82.pipup.core.PiPupModule
import nl.rogro82.pipup.core.PowerController

/**
 * Submenu for managing optional PiPup modules.
 * Dynamically renders toggles for all registered modules.
 */
@UnstableApi
class ModulesSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    private lateinit var rootContainer: LinearLayout

    override fun onBind(root: View) {
        rootContainer = root as? LinearLayout ?: return
        render()
    }

    private fun render() {
        // Clear all except the title
        val titleView = rootContainer.getChildAt(0)
        rootContainer.removeAllViews()
        rootContainer.addView(titleView)

        val mm = (context.applicationContext as PiPupApp).moduleManager
        val modules = mm.getAllModules().filter { it.id != "system" && it.id != "debug" }

        modules.forEach { module ->
            renderModuleToggle(module)
        }

        settingsActivity?.setupSubmenuFocus()
    }

    private fun renderModuleToggle(module: PiPupModule) {
        val view = LayoutInflater.from(context).inflate(R.layout.item_setting_toggle_with_desc, rootContainer, false)

        view.findViewById<TextView>(R.id.setting_label)?.text = module.name
        view.findViewById<TextView>(R.id.setting_desc)?.apply {
            setText(module.descriptionRes)
            visibility = View.VISIBLE
        }

        val sw = view.findViewById<SwitchCompat>(R.id.setting_switch)
        val currentMode = settings.getModuleMode(module.id)
        sw?.isChecked = currentMode != ModuleMode.OFF

        view.setOnClickListener {
            val next = if (settings.getModuleMode(module.id) == ModuleMode.OFF) {
                // When enabling, try to restore preferred mode (ON or ECO)
                settings.getPreferredMode(module.id).takeIf { it != ModuleMode.OFF } ?: ModuleMode.ON
            } else {
                ModuleMode.OFF
            }

            settings.setModuleMode(module.id, next)
            (context.applicationContext as PiPupApp).moduleManager.updateModuleState(module.id, next)
            sw?.isChecked = next != ModuleMode.OFF

            notifySettingsChanged()

            // Special handling for Power Module permission nag
            if (module.id == "power" && next != ModuleMode.OFF && PowerController.getSleepMethod(context) == null) {
                Permissions.showFixDialog(context, Permissions.KEY_POWER)
            }

            onSettingsChanged(false)
        }

        rootContainer.addView(view)
    }
}
