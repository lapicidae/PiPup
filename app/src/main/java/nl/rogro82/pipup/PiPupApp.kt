package nl.rogro82.pipup

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bumptech.glide.Glide

class PiPupApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Apply language as early as possible using AppCompat API
        val prefs = getSharedPreferences("pipup_settings", MODE_PRIVATE)
        val lang = prefs.getString("language", "default") ?: "default"
        val appLocale: LocaleListCompat = if (lang == "default") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(lang)
        }
        AppCompatDelegate.setApplicationLocales(appLocale)

        // Apply theme as early as possible
        val appTheme = prefs.getInt("app_theme", 0)
        val mode = if (appTheme == 0) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Global memory management for Glide
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            Glide.get(this).clearMemory()
        }
    }
}
