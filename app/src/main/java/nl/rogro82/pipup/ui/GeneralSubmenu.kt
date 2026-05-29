package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R

@UnstableApi
class GeneralSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        val suffix = context.getString(R.string.settings_default_suffix)

        val posItems = context.resources.getStringArray(R.array.position_options).mapIndexed { i, s -> if (i == 0) "$s$suffix" else s }
        setupSpinner(root, R.id.spinner_position, ArrayAdapter(context, android.R.layout.simple_spinner_item, posItems).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }, settings.positionIndex) {
            settings.positionIndex = it
        }

        val mediaPosItems = context.resources.getStringArray(R.array.media_position_options).mapIndexed { i, s -> if (i == 0) "$s$suffix" else s }
        setupSpinner(root, R.id.spinner_media_position, ArrayAdapter(context, android.R.layout.simple_spinner_item, mediaPosItems).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }, settings.mediaPosition) {
            settings.mediaPosition = it
        }

        setupSeekBar(root, R.id.seekbar_padding, R.id.text_padding_value, settings.contentPadding) {
            settings.contentPadding = it
        }

        root.findViewById<SwitchCompat>(R.id.switch_advanced)?.apply {
            isChecked = settings.advancedMode
            setOnCheckedChangeListener { _, isChecked ->
                settings.advancedMode = isChecked
                onSettingsChanged(false)
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }
    }
}
