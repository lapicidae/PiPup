package nl.rogro82.pipup.core.modules

import android.util.Log
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.ModuleMode
import nl.rogro82.pipup.core.ModuleContext
import nl.rogro82.pipup.core.ModuleMenuDefinition
import nl.rogro82.pipup.core.ModuleSettingDefinition
import nl.rogro82.pipup.core.PiPupModule

/**
 * Module responsible for rich media support (WebView, WHEP).
 * Manages the engine lifecycle and provides configuration metadata.
 */
class MediaModule : PiPupModule {

    companion object {
        private const val TAG = "MediaModule"
    }

    override val id: String = "media"
    override val nameRes: Int = R.string.settings_module_media
    override val descriptionRes: Int = R.string.settings_module_media_desc
    override val supportedModes: List<ModuleMode> = listOf(ModuleMode.OFF, ModuleMode.ECO, ModuleMode.ON)
    override val defaultMode: ModuleMode = ModuleMode.ECO
    override val supportedRoutes: List<String> = emptyList()

    private var moduleContext: ModuleContext? = null

    override fun onNotificationDisplayStateChanged(isDisplaying: Boolean) {
        if (!isDisplaying) {
            // Engine warming: Re-activate module when idle to ensure it stays in memory (if in ON mode)
            val settings = PiPupApp.settings
            if (settings.getModuleMode(id) == ModuleMode.ON) {
                moduleContext?.let { (it.androidContext.applicationContext as? PiPupApp)?.moduleManager?.activateModule(id) }
            }
        }
    }

    override fun onEnable(context: ModuleContext) {
        Log.d(TAG, "Media module enabled")
        this.moduleContext = context
    }

    override fun onDisable() {
        Log.d(TAG, "Media module disabled")
        moduleContext = null
    }

    override fun getSettingsMetadata(): List<ModuleSettingDefinition> = listOf(
        ModuleSettingDefinition(
            key = "resource_mode",
            type = nl.rogro82.pipup.core.SettingType.STRING_SELECT,
            labelRes = R.string.settings_resource_mode,
            defaultValue = "eco",
            options = mapOf(
                "eco" to R.string.resource_mode_eco,
                "performance" to R.string.resource_mode_performance
            ),
            category = nl.rogro82.pipup.core.SettingCategory.PERFORMANCE
        )
    )

    override fun getSettingsMenu(): ModuleMenuDefinition? {
        if (getSettingsMetadata().none { it.category == null }) return null
        return ModuleMenuDefinition(
            iconRes = R.drawable.ic_module_rmedia,
            labelRes = R.string.settings_module_media,
            priority = 75
        )
    }

    override fun augmentState(state: MutableMap<String, Any?>) {
        state["media_engine"] = mapOf(
            "active" to (moduleContext != null)
        )
    }
}
