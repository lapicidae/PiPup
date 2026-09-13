package nl.rogro82.pipup.core

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import fi.iki.elonen.NanoHTTPD

/**
 * Manages the lifecycle and request dispatching for optional PiPup modules.
 */
class ModuleManager {

    companion object {
        private const val TAG = "ModuleManager"
        private const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
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
            handler.postDelayed(this, 60000) // Check every minute
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
        handler.postDelayed(idleWatchdog, 60000)
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
        val context = moduleContext ?: return emptyList()
        return modules.values.filter { context.settings.getModuleMode(it.id) != ModuleMode.OFF }
    }

    /**
     * Dispatches an HTTP request to enabled modules.
     */
    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri.lowercase()
        val context = moduleContext ?: return null

        // Check all modules that are NOT turned OFF
        for (module in modules.values) {
            val mode = context.settings.getModuleMode(module.id)
            if (mode == ModuleMode.OFF) continue

            // If not active yet, check if it should be activated by this request
            if (!activeModules.contains(module.id)) {
                if (module.supportedRoutes.any { uri.startsWith(it.lowercase()) }) {
                    activateModule(module.id)
                } else {
                    continue
                }
            }

            val response = module.handleRequest(session)
            if (response != null) {
                lastActivityMap[module.id] = android.os.SystemClock.elapsedRealtime()
                return response
            }
        }

        return null
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
     * Legacy/Debug: Triggered by DebugModule to clean up memory.
     */
    @OptIn(UnstableApi::class)
    fun forceIdleCleanup() {
        Log.i(TAG, "Forced module cleanup triggered")
        activeModules.toList().forEach { id ->
            val module = modules[id] ?: return@forEach
            // Only non-system modules can be unloaded
            if (id != "system") {
                Log.i(TAG, "Module $id forced to disable to save RAM")
                module.onDisable()
                activeModules.remove(id)
            }
        }

        // Deep cleanup: View pool removal handled by NotificationManager
        moduleContext?.notificationManager?.cancelAll()
    }
}
