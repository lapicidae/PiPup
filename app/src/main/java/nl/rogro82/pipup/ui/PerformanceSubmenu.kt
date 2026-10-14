package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.SettingCategory

/**
 * Centralized settings screen for all performance-related configurations.
 */
@UnstableApi
class PerformanceSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    private lateinit var rootContainer: LinearLayout

    override fun onBind(root: View) {
        rootContainer = root.findViewById(R.id.performance_settings_container) ?: return

        // Max Popups (SeekBar 0..4 maps to values 1..5)
        setupSeekBar(root, R.id.seekbar_max_popups, R.id.text_max_popups_value, (settings.maxPopups - 1).coerceIn(0, 4)) {
            settings.maxPopups = (it + 1).coerceIn(1, 5)
        }

        render()
    }

    private fun render() {
        rootContainer.removeAllViews()
        renderModuleSettings(rootContainer, SettingCategory.PERFORMANCE)
        settingsActivity?.setupSubmenuFocus()
    }

    companion object {
        /**
         * Determines if this submenu has any visible settings to show.
         */
        fun hasContent(context: Context, settings: AppSettings): Boolean {
            return context.applicationContext != null && settings.maxPopups > 0
        }
    }
}
