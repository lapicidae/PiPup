package nl.rogro82.pipup

import android.app.Application
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import nl.rogro82.pipup.core.ModuleManager
import nl.rogro82.pipup.core.modules.DiscoveryModule
import nl.rogro82.pipup.core.modules.MediaModule
import nl.rogro82.pipup.core.modules.PowerModule
import nl.rogro82.pipup.core.modules.SystemModule
import nl.rogro82.pipup.core.modules.VendorModule
import nl.rogro82.pipup.core.modules.DebugModule

class PiPupApp : Application() {

    /**
     * Application-wide coroutine scope that follows the application lifecycle.
     */
    val applicationScope = CoroutineScope(SupervisorJob())

    val moduleManager = ModuleManager()

    companion object {
        const val ACTION_SETTINGS_CHANGED = "nl.rogro82.pipup.SETTINGS_CHANGED"

        lateinit var settings: AppSettings
            private set
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)

        moduleManager.registerModule(SystemModule())
        moduleManager.registerModule(PowerModule())
        moduleManager.registerModule(DiscoveryModule())
        moduleManager.registerModule(MediaModule())
        moduleManager.registerModule(VendorModule())

        if (BuildConfig.DEBUG) {
            moduleManager.registerModule(DebugModule())
        }

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
