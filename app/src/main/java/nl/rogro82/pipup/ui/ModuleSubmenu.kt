package nl.rogro82.pipup.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.ModuleSettingDefinition
import nl.rogro82.pipup.core.PiPupModule
import nl.rogro82.pipup.core.SettingCategory
import nl.rogro82.pipup.core.SettingType

/**
 * Dynamically rendered settings screen for a specific module.
 * Performance-related settings are excluded as they are managed centrally.
 */
@OptIn(UnstableApi::class)
class ModuleSubmenu(
    activity: SettingsActivity,
    settings: AppSettings,
    private val module: PiPupModule,
    onPreviewUpdate: (Boolean) -> Unit,
    previewContainer: ViewGroup
) : SubmenuBase(activity, settings, onPreviewUpdate, previewContainer as FrameLayout) {

    private lateinit var rootContainer: LinearLayout

    override fun onBind(root: View) {
        rootContainer = root.findViewById(R.id.module_settings_container) ?: return
        render()
    }

    private fun render() {
        rootContainer.removeAllViews()

        // Module-specific settings (Protected Area: category == null)
        val metadata = module.getSettingsMetadata().filter { it.category == null }
        metadata.forEach { def ->
            renderSetting(rootContainer, module, def)
        }

        settingsActivity?.setupSubmenuFocus()
    }
}
