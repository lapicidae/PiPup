package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R

@UnstableApi
class TextSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        val colors = (context as SettingsActivity).materialColors

        // Title Color
        val titleAdapter = SettingsActivity.ColorSpinnerAdapter(context, colors, AppSettings.DEFAULT_TITLE_COLOR)
        setupSpinner(root, R.id.spinner_title_color, titleAdapter, 0) {
            val color = titleAdapter.colors[it].hex
            settings.titleColor = color
            root.findViewById<Button>(R.id.btn_edit_title_hex)?.text = color
        }
        setSelectedColorInSpinner(root, R.id.spinner_title_color, titleAdapter, settings.titleColor)

        root.findViewById<Button>(R.id.btn_edit_title_hex)?.apply {
            text = settings.titleColor
            visibility = if (settings.advancedMode) View.VISIBLE else View.GONE
            setOnClickListener { showHexInputDialog(this) { settings.titleColor = it; onSettingsChanged(false) } }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }

        // Message Color
        val msgAdapter = SettingsActivity.ColorSpinnerAdapter(context, colors, AppSettings.DEFAULT_MSG_COLOR)
        setupSpinner(root, R.id.spinner_message_color, msgAdapter, 0) {
            val color = msgAdapter.colors[it].hex
            settings.messageColor = color
            root.findViewById<Button>(R.id.btn_edit_message_hex)?.text = color
        }
        setSelectedColorInSpinner(root, R.id.spinner_message_color, msgAdapter, settings.messageColor)

        root.findViewById<Button>(R.id.btn_edit_message_hex)?.apply {
            text = settings.messageColor
            visibility = if (settings.advancedMode) View.VISIBLE else View.GONE
            setOnClickListener { showHexInputDialog(this) { settings.messageColor = it; onSettingsChanged(false) } }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }

        setupSeekBar(root, R.id.seekbar_title_size, R.id.text_title_size_value, settings.titleSize.toInt()) { settings.titleSize = it.toFloat() }
        setupSeekBar(root, R.id.seekbar_message_size, R.id.text_message_size_value, settings.messageSize.toInt()) { settings.messageSize = it.toFloat() }

        val alignmentItems = context.resources.getStringArray(R.array.alignment_options).toList()
        setupSpinner(root, R.id.spinner_title_alignment, ArrayAdapter(context, android.R.layout.simple_spinner_item, alignmentItems).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }, settings.titleAlignment) { settings.titleAlignment = it }
        setupSpinner(root, R.id.spinner_message_alignment, ArrayAdapter(context, android.R.layout.simple_spinner_item, alignmentItems).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }, settings.messageAlignment) { settings.messageAlignment = it }
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
