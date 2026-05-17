package nl.rogro82.pipup

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class PiPupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply theme as early as possible
        val prefs = getSharedPreferences("pipup_settings", MODE_PRIVATE)
        val appTheme = prefs.getInt("app_theme", 0)
        val mode = if (appTheme == 0) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
