package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
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

        // Loading Behavior Toggle
        root.findViewById<View>(R.id.container_loading_behavior)?.apply {
            val sw = findViewById<SwitchCompat>(R.id.switch_loading_behavior)
            sw.isChecked = settings.popupLoadingBehavior == 1
            setOnClickListener { sw.toggle() }
            sw.setOnCheckedChangeListener { _, isChecked ->
                settings.popupLoadingBehavior = if (isChecked) 1 else 0
                onSettingsChanged(false)
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
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
