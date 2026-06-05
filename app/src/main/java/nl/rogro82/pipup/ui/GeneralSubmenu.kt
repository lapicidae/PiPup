package nl.rogro82.pipup.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.os.LocaleListCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.MainActivity
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

        val langCodes = context.resources.getStringArray(R.array.language_codes)
        val langItems = context.resources.getStringArray(R.array.language_options)
        val currentLangIndex = langCodes.indexOf(settings.language).coerceAtLeast(0)

        setupSpinner(root, R.id.spinner_language, ArrayAdapter(context, android.R.layout.simple_spinner_item, langItems).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }, currentLangIndex) {
            val newLang = langCodes[it]
            if (settings.language != newLang) {
                settings.language = newLang

                // Use official AppCompat API for language switching
                val appLocale: LocaleListCompat = if (newLang == "default") {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(newLang)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)

                // The above might already recreate activities, but we ensure a clean state
                (context as? SettingsActivity)?.let { activity ->
                    val intent = Intent(activity, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                    activity.finish()
                }
            }
        }

        // App Theme
        val themeItems = listOf(context.getString(R.string.settings_theme_dark), context.getString(R.string.settings_theme_light))
        setupSpinner(root, R.id.spinner_app_theme, ArrayAdapter(context, android.R.layout.simple_spinner_item, themeItems).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }, settings.appTheme) {
            if (settings.appTheme != it) {
                settings.appTheme = it
                val mode = if (it == 0) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                AppCompatDelegate.setDefaultNightMode(mode)
                (context as? SettingsActivity)?.recreate()
            }
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
