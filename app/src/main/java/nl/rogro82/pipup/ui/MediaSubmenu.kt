package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R

/**
 * Settings submenu controller for visual media display preferences including media position,
 * full-bleed layout, fullscreen upscaling, live indicator badge, and loading placeholder.
 */
@UnstableApi
class MediaSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout,
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        val suffix = context.getString(R.string.settings_default_suffix)

        val mediaPosItems = context.resources.getStringArray(R.array.media_position_options)
            .mapIndexed { i, s -> if (i == 0) "$s $suffix" else s }
        setupSpinner(
            root,
            R.id.spinner_media_position,
            ArrayAdapter(context, android.R.layout.simple_spinner_item, mediaPosItems).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            },
            settings.mediaPosition
        ) {
            settings.mediaPosition = it
        }

        setupSwitch(root, R.id.container_media_full_bleed, R.id.switch_media_full_bleed, settings.mediaFullBleed) {
            settings.mediaFullBleed = it
        }

        setupSwitch(root, R.id.container_fullscreen_upscale, R.id.switch_fullscreen_upscale, settings.fullscreenUpscale) {
            settings.fullscreenUpscale = it
        }

        val liveBadgePosItems = context.resources.getStringArray(R.array.live_badge_position_options)
            .mapIndexed { i, s -> if (i == 0) "$s $suffix" else s }
        val liveBadgePosKeys = listOf("bottom_right", "top_right", "top_left", "bottom_left")
        val currentLiveBadgePosIndex = liveBadgePosKeys.indexOf(settings.liveBadgePosition).coerceAtLeast(0)

        setupSwitch(root, R.id.container_live_badge, R.id.switch_live_badge, settings.liveBadge) {
            settings.liveBadge = it
        }

        setupSpinner(
            root,
            R.id.spinner_live_badge_position,
            ArrayAdapter(context, android.R.layout.simple_spinner_item, liveBadgePosItems).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            },
            currentLiveBadgePosIndex
        ) {
            settings.liveBadgePosition = liveBadgePosKeys.getOrElse(it) { "bottom_right" }
        }

        setupSwitch(root, R.id.container_media_placeholder, R.id.switch_media_placeholder, settings.mediaLoadingPlaceholder) {
            settings.mediaLoadingPlaceholder = it
        }
    }
}
