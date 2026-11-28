package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R

/**
 * Settings submenu controller for visual media display preferences including media position,
 * full-bleed layout, fullscreen upscaling, live indicator badge, loading placeholder, and
 * default header icon position and size.
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
            createSpinnerAdapter(mediaPosItems),
            settings.mediaPosition,
        ) {
            settings.mediaPosition = it
        }

        setupSwitch(root, R.id.container_media_full_bleed, R.id.switch_media_full_bleed, settings.mediaFullBleed) {
            settings.mediaFullBleed = it
        }

        setupSwitch(
            root,
            R.id.container_fullscreen_upscale,
            R.id.switch_fullscreen_upscale,
            settings.fullscreenUpscale,
        ) {
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
            createSpinnerAdapter(liveBadgePosItems),
            currentLiveBadgePosIndex,
        ) {
            settings.liveBadgePosition = liveBadgePosKeys.getOrElse(it) { "bottom_right" }
        }

        setupSwitch(
            root,
            R.id.container_media_placeholder,
            R.id.switch_media_placeholder,
            settings.mediaLoadingPlaceholder,
        ) {
            settings.mediaLoadingPlaceholder = it
        }

        bindIconControls(root, suffix)
    }

    private fun bindIconControls(root: View, suffix: String) {
        val iconPosItems = context.resources.getStringArray(R.array.icon_position_options)
            .mapIndexed { i, s -> if (i == 0) "$s $suffix" else s }
        val iconPosKeys = listOf(PopupProps.ICON_POSITION_LEFT, PopupProps.ICON_POSITION_RIGHT)
        val currentIconPosIndex = iconPosKeys.indexOf(settings.iconPosition).coerceAtLeast(0)
        setupSpinner(
            root,
            R.id.spinner_icon_position,
            createSpinnerAdapter(iconPosItems),
            currentIconPosIndex,
        ) {
            settings.iconPosition = iconPosKeys.getOrElse(it) { PopupProps.DEFAULT_ICON_POSITION }
        }

        val iconSteps = (PopupProps.ICON_WIDTH_MAX - PopupProps.ICON_WIDTH_MIN) / PopupProps.ICON_WIDTH_STEP_PX
        val initialStep = ((settings.iconWidth - PopupProps.ICON_WIDTH_MIN) / PopupProps.ICON_WIDTH_STEP_PX)
            .coerceIn(0, iconSteps)
        setupSeekBar(
            root,
            R.id.seekbar_icon_width,
            R.id.text_icon_width_value,
            initialStep,
            max = iconSteps,
        ) { step ->
            settings.iconWidth = PopupProps.ICON_WIDTH_MIN + step * PopupProps.ICON_WIDTH_STEP_PX
        }
    }

    private fun createSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(context, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
}
