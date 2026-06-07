package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R

@UnstableApi
class BackgroundSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        val colors = settingsActivity?.materialColors ?: emptyList()
        val adapter = SettingsActivity.ColorSpinnerAdapter(context, colors, AppSettings.DEFAULT_BG_COLOR)
        setupSpinner(root, R.id.spinner_bg_color, adapter, 0) {
            val color = adapter.colors[it].hex
            settings.backgroundColor = color
            root.findViewById<Button>(R.id.btn_edit_bg_hex)?.text = color
        }
        setSelectedColorInSpinner(root, R.id.spinner_bg_color, adapter, settings.backgroundColor)

        root.findViewById<Button>(R.id.btn_edit_bg_hex)?.apply {
            text = settings.backgroundColor
            visibility = if (settings.advancedMode) View.VISIBLE else View.GONE
            setOnClickListener { showHexInputDialog(this) { settings.backgroundColor = it; onSettingsChanged(false) } }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }

        setupSeekBar(root, R.id.seekbar_bg_alpha, R.id.text_bg_alpha_value, settings.backgroundAlpha) {
            settings.backgroundAlpha = it
        }
    }

    private fun setSelectedColorInSpinner(root: View, spinnerId: Int, adapter: SettingsActivity.ColorSpinnerAdapter, hex: String) {
        val clean = hex.replace("#", "").let { if (it.length == 8) it.substring(2) else it }
        val idx = adapter.colors.indexOfFirst { it.hex.equals("#$clean", true) }
        if (idx != -1) root.findViewById<android.widget.Spinner>(spinnerId)?.setSelection(idx)
    }
}
