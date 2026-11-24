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
        setupSeekBar(
            root,
            R.id.seekbar_max_popups,
            R.id.text_max_popups_value,
            (settings.maxPopups - 1).coerceIn(0, MAX_POPUPS_SEEKBAR_MAX),
        ) {
            settings.maxPopups = (it + 1).coerceIn(1, AppSettings.MAX_POPUPS_LIMIT)
        }

        // Loading Behavior Toggle
        setupSwitch(
            root,
            R.id.container_loading_behavior,
            R.id.switch_loading_behavior,
            settings.popupLoadingBehavior == 1,
        ) {
            settings.popupLoadingBehavior = if (it) 1 else 0
        }

        // Buffer Ms (SeekBar 0..30 maps to 0..3000ms in 100ms steps)
        val bufferDescText = root.findViewById<android.widget.TextView>(R.id.text_buffer_ms_desc)

        fun updateBufferDesc(ms: Int) {
            bufferDescText?.text = if (ms == 0) {
                context.getString(R.string.settings_buffer_ms_default)
            } else {
                when {
                    ms <= BUFFER_LOW_MAX_MS -> context.getString(R.string.settings_buffer_ms_low, ms)
                    ms <= BUFFER_MEDIUM_MAX_MS -> context.getString(R.string.settings_buffer_ms_medium, ms)
                    else -> context.getString(R.string.settings_buffer_ms_high, ms)
                }
            }
        }

        val initialBufferProgress = (settings.defaultBufferMs / BUFFER_STEP_MS).coerceIn(0, BUFFER_SEEKBAR_MAX)
        updateBufferDesc(initialBufferProgress * BUFFER_STEP_MS)

        setupSeekBar(root, R.id.seekbar_buffer_ms, R.id.text_buffer_ms_value, initialBufferProgress) { progress ->
            val ms = progress * BUFFER_STEP_MS
            settings.defaultBufferMs = ms
            updateBufferDesc(ms)
        }

        render()
    }

    private fun render() {
        rootContainer.removeAllViews()
        renderModuleSettings(rootContainer, SettingCategory.PERFORMANCE)
        settingsActivity?.setupSubmenuFocus()
    }

    /** Visibility helpers and slider bounds for [PerformanceSubmenu]. */
    companion object {
        private const val MAX_POPUPS_SEEKBAR_MAX = AppSettings.MAX_POPUPS_LIMIT - 1
        private const val BUFFER_SEEKBAR_MAX = 30
        private const val BUFFER_LOW_MAX_MS = 400
        private const val BUFFER_MEDIUM_MAX_MS = 1200

        /**
         * Determines if this submenu has any visible settings to show.
         */
        fun hasContent(context: Context, settings: AppSettings): Boolean {
            return context.applicationContext != null && settings.maxPopups > 0
        }
    }
}
