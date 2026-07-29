package nl.rogro82.pipup

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bumptech.glide.Glide

class PiPupApp : Application() {

    companion object {
        lateinit var settings: AppSettings
            private set
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)

        // Apply language as early as possible using AppCompat API
        val lang = settings.language
        val appLocale: LocaleListCompat = if (lang == "default") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(lang)
        }
        AppCompatDelegate.setApplicationLocales(appLocale)

        // Apply theme as early as possible
        val appTheme = settings.appTheme
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
