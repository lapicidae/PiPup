package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R
import nl.rogro82.pipup.colorToHex

@UnstableApi
class ButtonSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        setupSeekBar(root, R.id.seekbar_button_size, R.id.text_button_size_value, (settings.defaultButtonSize - 10).toInt().coerceIn(0, 30)) {
            settings.defaultButtonSize = (it + 10).toFloat()
        }
        setupSeekBar(root, R.id.seekbar_button_radius, R.id.text_button_radius_value, settings.defaultButtonCornerRadius.toInt()) {
            settings.defaultButtonCornerRadius = it.toFloat()
        }

        val colors = settingsActivity?.materialColors ?: emptyList()
        val defaultFillHex = context.colorToHex(R.color.preset_gunmetal)
        val defaultFocusHex = context.colorToHex(R.color.preset_sky_blue)

        val adapterColor = SettingsActivity.ColorSpinnerAdapter(context, colors, defaultFillHex)
        setupSpinner(root, R.id.spinner_button_color, adapterColor, 0) {
            val color = adapterColor.colors[it].hex
            settings.defaultButtonColor = color
            root.findViewById<Button>(R.id.btn_edit_button_color_hex)?.text = color
        }
        setSelectedColorInSpinner(root, R.id.spinner_button_color, adapterColor, settings.defaultButtonColor)

        val adapterFocus = SettingsActivity.ColorSpinnerAdapter(context, colors, defaultFocusHex)
        setupSpinner(root, R.id.spinner_button_focus_color, adapterFocus, 0) {
            val color = adapterFocus.colors[it].hex
            settings.defaultButtonFocusColor = color
            root.findViewById<Button>(R.id.btn_edit_button_focus_color_hex)?.text = color
        }
        setSelectedColorInSpinner(root, R.id.spinner_button_focus_color, adapterFocus, settings.defaultButtonFocusColor)

        val defaultTextHex = context.colorToHex(R.color.preset_platinum)
        val defaultFocusTextHex = context.colorToHex(R.color.preset_deep_slate)

        val adapterText = SettingsActivity.ColorSpinnerAdapter(context, colors, defaultTextHex)
        setupSpinner(root, R.id.spinner_button_text_color, adapterText, 0) {
            val color = adapterText.colors[it].hex
            settings.defaultButtonTextColor = color
            root.findViewById<Button>(R.id.btn_edit_button_text_color_hex)?.text = color
        }
        setSelectedColorInSpinner(root, R.id.spinner_button_text_color, adapterText, settings.defaultButtonTextColor)

        val adapterFocusText = SettingsActivity.ColorSpinnerAdapter(context, colors, defaultFocusTextHex)
        setupSpinner(root, R.id.spinner_button_focus_text_color, adapterFocusText, 0) {
            val color = adapterFocusText.colors[it].hex
            settings.defaultButtonFocusTextColor = color
            root.findViewById<Button>(R.id.btn_edit_button_focus_text_color_hex)?.text = color
        }
        setSelectedColorInSpinner(root, R.id.spinner_button_focus_text_color, adapterFocusText, settings.defaultButtonFocusTextColor)

        val defaultBorderHex = context.colorToHex(R.color.preset_gunmetal)
        val adapterBorder = SettingsActivity.ColorSpinnerAdapter(context, colors, defaultBorderHex)
        setupSpinner(root, R.id.spinner_button_border_color, adapterBorder, 0) {
            val color = adapterBorder.colors[it].hex
            settings.defaultButtonBorderColor = color
            root.findViewById<Button>(R.id.btn_edit_button_border_color_hex)?.text = color
        }
        setSelectedColorInSpinner(root, R.id.spinner_button_border_color, adapterBorder, settings.defaultButtonBorderColor)

        setupHexColorButton(root, R.id.btn_edit_button_color_hex, settings.defaultButtonColor) {
            settings.defaultButtonColor = it
            setSelectedColorInSpinner(root, R.id.spinner_button_color, adapterColor, it)
        }
        setupHexColorButton(root, R.id.btn_edit_button_focus_color_hex, settings.defaultButtonFocusColor) {
            settings.defaultButtonFocusColor = it
            setSelectedColorInSpinner(root, R.id.spinner_button_focus_color, adapterFocus, it)
        }
        setupHexColorButton(root, R.id.btn_edit_button_text_color_hex, settings.defaultButtonTextColor) {
            settings.defaultButtonTextColor = it
            setSelectedColorInSpinner(root, R.id.spinner_button_text_color, adapterText, it)
        }
        setupHexColorButton(root, R.id.btn_edit_button_focus_text_color_hex, settings.defaultButtonFocusTextColor) {
            settings.defaultButtonFocusTextColor = it
            setSelectedColorInSpinner(root, R.id.spinner_button_focus_text_color, adapterFocusText, it)
        }
        setupHexColorButton(root, R.id.btn_edit_button_border_color_hex, settings.defaultButtonBorderColor) {
            settings.defaultButtonBorderColor = it
            setSelectedColorInSpinner(root, R.id.spinner_button_border_color, adapterBorder, it)
        }
    }
}
