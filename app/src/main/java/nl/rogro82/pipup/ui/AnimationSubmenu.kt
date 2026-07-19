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
class AnimationSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        val suffix = context.getString(R.string.settings_default_suffix)
        val animItems = context.resources.getStringArray(R.array.animation_options).mapIndexed { i, s -> if (i == 0) "$s $suffix" else s }
        setupSpinner(root, R.id.spinner_animation_type, ArrayAdapter(context, R.layout.spinner_item, animItems).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }, settings.animationType) {
            settings.animationType = it
        }

        setupSeekBar(root, R.id.seekbar_animation_duration, R.id.text_animation_duration_value, settings.animationDuration) {
            settings.animationDuration = it
        }

        val sw = root.findViewById<SwitchCompat>(R.id.switch_animation_exit)
        sw?.isChecked = settings.animationExit
        sw?.setOnCheckedChangeListener { _, isChecked ->
            settings.animationExit = isChecked
            onSettingsChanged(false)
        }

        root.findViewById<View>(R.id.container_exit_animation)?.apply {
            setOnClickListener { sw?.toggle() }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }
    }
}
