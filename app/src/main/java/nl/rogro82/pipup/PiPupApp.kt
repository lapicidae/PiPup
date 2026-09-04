package nl.rogro82.pipup

import android.app.Application
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class PiPupApp : Application() {

    /**
     * Application-wide coroutine scope that follows the application lifecycle.
     */
    val applicationScope = CoroutineScope(SupervisorJob())

    companion object {
        const val ACTION_SETTINGS_CHANGED = "nl.rogro82.pipup.SETTINGS_CHANGED"

        lateinit var settings: AppSettings
            private set
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)

        // Apply language and theme as early as possible using optimized unified logic
        applyAppLocaleAndTheme(settings.language, settings.appTheme)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Global memory management for Glide
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            Glide.get(this).clearMemory()
        }
    }
}
