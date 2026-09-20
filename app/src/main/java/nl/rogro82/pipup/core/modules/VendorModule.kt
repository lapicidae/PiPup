package nl.rogro82.pipup.core.modules

import android.util.Log
import nl.rogro82.pipup.BuildConfig
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.ModuleContext
import nl.rogro82.pipup.core.ModuleMenuDefinition
import nl.rogro82.pipup.core.ModuleMode
import nl.rogro82.pipup.core.PiPupModule
import nl.rogro82.pipup.core.SettingType
import nl.rogro82.pipup.core.ModuleSettingDefinition

/**
 * Module for vendor-specific optimizations and workarounds.
 * Primarily addresses background freezing issues on TCL devices.
 */
class VendorModule : PiPupModule {

    companion object {
        private const val TAG = "VendorModule"
    }

    override val id: String = "vendor"
    override val nameRes: Int = R.string.settings_module_vendor
    override val descriptionRes: Int = R.string.settings_module_vendor_desc

    override val supportedModes: List<ModuleMode> = listOf(ModuleMode.OFF, ModuleMode.ON)
    override val defaultMode: ModuleMode = ModuleMode.OFF

    private var moduleContext: ModuleContext? = null

    override fun onEnable(context: ModuleContext) {
        Log.d(TAG, "Vendor module enabled")
        this.moduleContext = context
    }

    override fun onDisable() {
        Log.d(TAG, "Vendor module disabled")
        moduleContext = null
    }

    override fun getRequiredPermissions(): List<String> {
        // Return KEY_AUTO_START and KEY_ACCESSIBILITY based on module activation.
        // Accessibility is the primary "Keep-Alive" anchor for TCL devices.
        return listOf(Permissions.KEY_AUTO_START, Permissions.KEY_ACCESSIBILITY)
    }

    override fun getSettingsMenu(): ModuleMenuDefinition {
        return ModuleMenuDefinition(
            iconRes = R.drawable.ic_module_vendor,
            labelRes = R.string.settings_module_vendor,
            priority = 85, // After Modules, before Permissions
            layoutRes = R.layout.submenu_vendor
        )
    }

    override fun getSettingsMetadata(): List<ModuleSettingDefinition> {
        return listOf(
            ModuleSettingDefinition(
                key = "tcl_optimization",
                type = SettingType.BOOLEAN,
                labelRes = R.string.settings_vendor_tcl_keepalive,
                descriptionRes = R.string.settings_vendor_tcl_keepalive_desc,
                defaultValue = false
            )
        )
    }

    override fun augmentState(state: MutableMap<String, Any?>) {
        val context = moduleContext?.androidContext ?: return
        val vendorState = mutableMapOf<String, Any?>(
            "isTcl" to (Permissions.autoStart(context) != null || BuildConfig.DEBUG)
        )
        state["vendor"] = vendorState
    }
}
