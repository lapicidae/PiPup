package nl.rogro82.pipup

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.bumptech.glide.Glide

class PiPupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply theme as early as possible
        val prefs = getSharedPreferences("pipup_settings", MODE_PRIVATE)
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
