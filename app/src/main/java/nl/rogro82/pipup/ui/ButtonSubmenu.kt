package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.colorToHex

/**
 * Settings submenu controller for action button appearance, corner radius, focus highlights, and color states.
 */
@UnstableApi
class ButtonSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        setupSeekBar(
            root,
            R.id.seekbar_button_size,
            R.id.text_button_size_value,
            (settings.defaultButtonSize - PopupProps.TEXT_SIZE_UI_MIN).toInt()
                .coerceIn(0, PopupProps.TEXT_SIZE_UI_SPAN),
        ) {
            settings.defaultButtonSize = (it + PopupProps.TEXT_SIZE_UI_MIN).toFloat()
        }
        setupSeekBar(
            root,
            R.id.seekbar_button_radius,
            R.id.text_button_radius_value,
            settings.defaultButtonCornerRadius.toInt(),
        ) {
            settings.defaultButtonCornerRadius = it.toFloat()
        }

        bindButtonColorControls(root)
        bindCloseButtonControls(root)
    }

    private data class ColorControlSpec(
        val defaultColorRes: Int,
        val spinnerId: Int,
        val hexButtonId: Int,
        val currentColor: String,
        val onColorSelected: (String) -> Unit,
    )

    private fun bindButtonColorControls(root: View) {
        val colors = settingsActivity?.materialColors ?: emptyList()
        val specs = listOf(
            ColorControlSpec(
                R.color.preset_gunmetal,
                R.id.spinner_button_color,
                R.id.btn_edit_button_color_hex,
                settings.defaultButtonColor,
            ) { settings.defaultButtonColor = it },
            ColorControlSpec(
                R.color.preset_sky_blue,
                R.id.spinner_button_focus_color,
                R.id.btn_edit_button_focus_color_hex,
                settings.defaultButtonFocusColor,
            ) { settings.defaultButtonFocusColor = it },
            ColorControlSpec(
                R.color.preset_platinum,
                R.id.spinner_button_text_color,
                R.id.btn_edit_button_text_color_hex,
                settings.defaultButtonTextColor,
            ) { settings.defaultButtonTextColor = it },
            ColorControlSpec(
                R.color.preset_deep_slate,
                R.id.spinner_button_focus_text_color,
                R.id.btn_edit_button_focus_text_color_hex,
                settings.defaultButtonFocusTextColor,
            ) { settings.defaultButtonFocusTextColor = it },
            ColorControlSpec(
                R.color.preset_gunmetal,
                R.id.spinner_button_border_color,
                R.id.btn_edit_button_border_color_hex,
                settings.defaultButtonBorderColor,
            ) { settings.defaultButtonBorderColor = it },
        )
        specs.forEach { bindColorPair(root, colors, it) }
    }

    private fun bindColorPair(
        root: View,
        colors: List<SettingsActivity.ColorEntry>,
        spec: ColorControlSpec,
    ) {
        val adapter = SettingsActivity.ColorSpinnerAdapter(context, colors, context.colorToHex(spec.defaultColorRes))
        setupSpinner(root, spec.spinnerId, adapter, 0) {
            val color = adapter.colors[it].hex
            spec.onColorSelected(color)
            root.findViewById<Button>(spec.hexButtonId)?.text = color
        }
        setSelectedColorInSpinner(root, spec.spinnerId, adapter, spec.currentColor)
        setupHexColorButton(root, spec.hexButtonId, spec.currentColor) {
            spec.onColorSelected(it)
            setSelectedColorInSpinner(root, spec.spinnerId, adapter, it)
        }
    }

    private fun bindCloseButtonControls(root: View) {
        val suffix = context.getString(R.string.settings_default_suffix)
        val closeButtonItems = context.resources.getStringArray(R.array.close_button_position_options)
            .mapIndexed { i, s -> if (i == 0) "$s $suffix" else s }
        val closeButtonKeys = listOf("none", "top_right", "top_left", "bottom_right", "bottom_left")
        val currentCloseButtonIdx = closeButtonKeys.indexOf(settings.closeButton).coerceAtLeast(0)
        val closeButtonAdapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            closeButtonItems
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setupSpinner(root, R.id.spinner_close_button, closeButtonAdapter, currentCloseButtonIdx) {
            settings.closeButton = closeButtonKeys.getOrElse(it) { "none" }
        }

        val sizeSpan = PopupProps.CLOSE_BUTTON_SIZE_MAX - PopupProps.CLOSE_BUTTON_SIZE_MIN
        setupSeekBar(
            root,
            R.id.seekbar_close_button_size,
            R.id.text_close_button_size_value,
            (settings.closeButtonSize - PopupProps.CLOSE_BUTTON_SIZE_MIN).coerceIn(0, sizeSpan),
            max = sizeSpan
        ) {
            settings.closeButtonSize = it + PopupProps.CLOSE_BUTTON_SIZE_MIN
        }

        setupSeekBar(
            root,
            R.id.seekbar_close_button_timeout,
            R.id.text_close_button_timeout_value,
            settings.closeButtonTimeout.coerceIn(0, PopupProps.CLOSE_BUTTON_TIMEOUT_UI_MAX),
            max = PopupProps.CLOSE_BUTTON_TIMEOUT_UI_MAX
        ) {
            settings.closeButtonTimeout = it
        }
    }
}
