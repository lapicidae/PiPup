package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R

@UnstableApi
class BorderSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        setupSeekBar(root, R.id.seekbar_radius, R.id.text_radius_value, settings.borderRadius) { settings.borderRadius = it }
        setupSeekBar(root, R.id.seekbar_border_width, R.id.text_border_width_value, settings.borderWidth) { settings.borderWidth = it }

        val adapter = SettingsActivity.ColorSpinnerAdapter(context, (context as SettingsActivity).materialColors, AppSettings.DEFAULT_BORDER_COLOR)
        setupSpinner(root, R.id.spinner_border_color, adapter, 0) {
            val color = adapter.colors[it].hex
            settings.borderColor = color
            root.findViewById<Button>(R.id.btn_edit_border_hex)?.text = color
        }
        setSelectedColorInSpinner(root, R.id.spinner_border_color, adapter, settings.borderColor)

        root.findViewById<Button>(R.id.btn_edit_border_hex)?.apply {
            text = settings.borderColor
            visibility = if (settings.advancedMode) View.VISIBLE else View.GONE
            setOnClickListener { showHexInputDialog(this) { settings.borderColor = it; onSettingsChanged(false) } }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }
    }

    private fun setSelectedColorInSpinner(root: View, spinnerId: Int, adapter: SettingsActivity.ColorSpinnerAdapter, hex: String) {
        val clean = hex.replace("#", "").let { if (it.length == 8) it.substring(2) else it }
        val idx = adapter.colors.indexOfFirst { it.hex.equals("#$clean", true) }
        if (idx != -1) root.findViewById<android.widget.Spinner>(spinnerId)?.setSelection(idx)
    }

    private fun showHexInputDialog(btn: Button, onSet: (String) -> Unit) {
        val input = EditText(context).apply { setText(btn.text.toString().replace("#", "")); isSingleLine = true }
        AlertDialog.Builder(context)
            .setTitle(R.string.settings_edit_hex_title).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val h = "#${input.text.toString().uppercase()}"
                try {
                    h.toColorInt()
                    btn.text = h
                    onSet(h)
                } catch (_: Exception) {}
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
