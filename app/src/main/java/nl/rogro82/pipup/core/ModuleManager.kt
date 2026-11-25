package nl.rogro82.pipup.core

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.PiPupApp

/**
 * Manages the lifecycle and request dispatching for optional PiPup modules.
 */
class ModuleManager {

    /** Companion constants for [ModuleManager]. */
    companion object {
        private const val TAG = "ModuleManager"
        private const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
        private const val IDLE_WATCHDOG_INTERVAL_MS = 60_000L
    }

    private val modules = mutableMapOf<String, PiPupModule>()
    private val activeModules = mutableSetOf<String>()
    private val lastActivityMap = mutableMapOf<String, Long>()

    /**
     * The timeout in milliseconds before an Eco module is considered idle and released.
     */
    var idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS

    private var moduleContext: ModuleContext? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val idleWatchdog = object : Runnable {
        override fun run() {
            checkIdleModules()
            handler.postDelayed(this, IDLE_WATCHDOG_INTERVAL_MS) // Check every minute
        }
    }

    /**
     * Registers a module with the manager.
     */
    fun registerModule(module: PiPupModule) {
        modules[module.id] = module
        Log.d(TAG, "Module registered: ${module.id}")
    }

    /**
     * Initializes all modules based on user settings.
     */
    fun initialize(context: ModuleContext) {
        this.moduleContext = context
        handler.removeCallbacks(idleWatchdog)
        modules.values.forEach { module ->
            val mode = context.settings.getModuleMode(module.id)
            updateModuleState(module.id, mode)
        }
        handler.postDelayed(idleWatchdog, IDLE_WATCHDOG_INTERVAL_MS)
    }

    /**
     * Updates the state of a module based on the requested mode.
     */
    fun updateModuleState(id: String, mode: ModuleMode) {
        val module = modules[id] ?: return
        val context = moduleContext ?: return

        when (mode) {
            ModuleMode.OFF -> {
                if (activeModules.remove(id)) {
                    Log.i(TAG, "Disabling module: $id")
                    module.onDisable()
                }
                lastActivityMap.remove(id)
            }
            ModuleMode.ECO -> {
                // Eco modules start in inactive state; they are activated on demand.
            }
            ModuleMode.ON -> {
                if (activeModules.add(id)) {
                    Log.i(TAG, "Activating module (Always On): $id")
                    module.onEnable(context)
                }
                lastActivityMap.remove(id) // On modules don't idle
            }
        }
    }

    /**
     * Forces a module to activate (onEnable).
     */
    fun activateModule(id: String) {
        val module = modules[id] ?: return
        val context = moduleContext ?: return

        if (activeModules.add(id)) {
            Log.i(TAG, "Activating module: $id")
            module.onEnable(context)
        }
        lastActivityMap[id] = android.os.SystemClock.elapsedRealtime()
    }

    private fun checkIdleModules() {
        val now = android.os.SystemClock.elapsedRealtime()
        val context = moduleContext ?: return

        activeModules.toList().forEach { id ->
            val module = modules[id] ?: return@forEach
            // Only Eco modules are eligible for idle cleanup
            if (context.settings.getModuleMode(id) == ModuleMode.ECO) {
                val lastActivity = lastActivityMap[id] ?: 0L
                if (now - lastActivity > idleTimeoutMs) {
                    Log.i(TAG, "Module $id is idle, releasing heavy resources")
                    module.onIdle()
                }
            }
        }
    }

    /**
     * Returns a registered module by its ID.
     */
    fun getModule(id: String): PiPupModule? = modules[id]

    /**
     * Returns all registered modules.
     */
    fun getAllModules(): List<PiPupModule> = modules.values.toList()

    /**
     * Returns all modules that are logically enabled (not OFF).
     */
    fun getEnabledModules(): List<PiPupModule> {
        val s = PiPupApp.settings
        return modules.values.filter { s.getModuleMode(it.id) != ModuleMode.OFF }
    }

    /**
     * Returns all permissions required by enabled modules that are currently missing.
     */
    fun getEnabledModulesMissingPermissions(context: Context): List<String> {
        return getEnabledModules().flatMap { module ->
            module.getRequiredPermissions().filter { key ->
                Permissions.granted(context, key) == false
            }
        }.distinct()
    }

    /**
     * Dispatches an HTTP request to enabled modules.
     */
    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri.lowercase()
        val context = moduleContext ?: return null

        return modules.values.firstNotNullOfOrNull { module ->
            if (!ensureActiveForRequest(module, uri, context)) {
                null
            } else {
                module.handleRequest(session)?.also {
                    lastActivityMap[module.id] = android.os.SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private fun ensureActiveForRequest(module: PiPupModule, uri: String, context: ModuleContext): Boolean = when {
        context.settings.getModuleMode(module.id) == ModuleMode.OFF -> false
        activeModules.contains(module.id) -> true
        else -> module.supportedRoutes.any { uri.startsWith(it.lowercase()) }.also { matches ->
            if (matches) activateModule(module.id)
        }
    }

    /**
     * Notifies modules about changes in the notification display state.
     */
    fun notifyNotificationDisplayStateChanged(isDisplaying: Boolean) {
        activeModules.forEach { id ->
            modules[id]?.onNotificationDisplayStateChanged(isDisplaying)
        }
    }

    /**
     * Allows enabled modules to add their own information to the global state report.
     */
    fun augmentState(state: MutableMap<String, Any?>) {
        activeModules.forEach { id ->
            modules[id]?.augmentState(state)
        }
    }

    /**
     * Gracefully shuts down all active modules and resets the context.
     * Note: The module registry (modules map) is preserved for service restarts.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down all active modules...")
        handler.removeCallbacks(idleWatchdog)
        activeModules.toList().forEach { id ->
            modules[id]?.onDisable()
        }
        activeModules.clear()
        lastActivityMap.clear()
        moduleContext = null
    }

    /**
     * Returns the first active module implementing the requested capability type [T], or null if none is active.
     */
    inline fun <reified T> getActiveCapability(): T? {
        val s = PiPupApp.settings
        for (module in getAllModules()) {
            if (module is T && s.getModuleMode(module.id) != ModuleMode.OFF) {
                return module
            }
        }
        return null
    }

    /**
     * Legacy/Debug: Triggered by DebugModule to clean up memory.
     */
    @OptIn(UnstableApi::class)
    fun forceIdleCleanup() {
        Log.i(TAG, "Forced module cleanup triggered")
        activeModules.toList().forEach { id ->
            val module = modules[id] ?: return@forEach
            // Only modules supporting OFF can be disabled/unloaded
            if (ModuleMode.OFF in module.supportedModes) {
                Log.i(TAG, "Module $id forced to disable to save RAM")
                module.onDisable()
                activeModules.remove(id)
            }
        }

        // Deep cleanup: View pool removal handled by NotificationManager
        moduleContext?.notificationManager?.cancelAll()
    }
}
