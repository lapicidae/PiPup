package nl.rogro82.pipup.core

import android.util.Log
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
    private val dormantModules = mutableSetOf<String>() // Eco mode: logically on, but not initialized

    /**
     * The timeout in milliseconds before an Eco module is considered idle and released.
     */
    var idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS

    private var moduleContext: ModuleContext? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val lastActivityMap = mutableMapOf<String, Long>()

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
        modules.values.forEach { module ->
            val strategy = context.settings.getActivationStrategy(module.id)
            updateModuleState(module.id, strategy)
        }
        handler.postDelayed(idleWatchdog, 60000)
    }

    /**
     * Updates the state of a module based on the requested strategy.
     */
    fun updateModuleState(id: String, strategy: Int) {
        val module = modules[id] ?: return
        val context = moduleContext ?: return

        when (strategy) {
            ActivationStrategy.OFF -> {
                if (activeModules.remove(id)) {
                    Log.i(TAG, "Disabling module: $id")
                    module.onDisable()
                }
                dormantModules.remove(id)
                lastActivityMap.remove(id)
            }
            ActivationStrategy.ECO -> {
                if (activeModules.contains(id)) {
                    // Stay active if already active, but mark as eligible for idle cleanup
                } else {
                    dormantModules.add(id)
                }
            }
            ActivationStrategy.PERFORMANCE -> {
                dormantModules.remove(id)
                if (activeModules.add(id)) {
                    Log.i(TAG, "Activating module (Performance): $id")
                    module.onEnable(context)
                }
            }
        }
    }

    /**
     * Forces a dormant module to activate (onEnable).
     * Useful for cross-module activation triggers.
     */
    fun activateModule(id: String) {
        val module = modules[id] ?: return
        val context = moduleContext ?: return

        if (dormantModules.remove(id)) {
            if (activeModules.add(id)) {
                Log.i(TAG, "Activating dormant module (Manual Trigger): $id")
                module.onEnable(context)
            }
        }
        lastActivityMap[id] = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * Forces an immediate check and cleanup of idle modules, regardless of the timeout.
     */
    fun forceIdleCleanup() {
        Log.i(TAG, "Forced idle cleanup triggered")
        val context = moduleContext ?: return

        activeModules.toList().forEach { id ->
            val module = modules[id] ?: return@forEach
            // Only Eco modules are eligible for idle cleanup
            if (context.settings.getActivationStrategy(id) == ActivationStrategy.ECO) {
                Log.i(TAG, "Module $id forced to idle, releasing resources")
                module.onIdle()
                activeModules.remove(id)
                dormantModules.add(id)
                lastActivityMap.remove(id)
            }
        }
    }

    private fun checkIdleModules() {
        val now = android.os.SystemClock.elapsedRealtime()
        val context = moduleContext ?: return

        activeModules.toList().forEach { id ->
            val module = modules[id] ?: return@forEach
            // Only Eco modules are eligible for idle cleanup
            if (context.settings.getActivationStrategy(id) == ActivationStrategy.ECO) {
                val lastActivity = lastActivityMap[id] ?: 0L
                if (now - lastActivity > idleTimeoutMs) {
                    Log.i(TAG, "Module $id is idle, releasing resources")
                    module.onIdle()
                    activeModules.remove(id)
                    dormantModules.add(id)
                    lastActivityMap.remove(id)
                }
            }
        }
    }

    /**
     * Returns a registered module by its ID.
     */
    fun getModule(id: String): PiPupModule? = modules[id]

    /**
     * Returns all modules that are logically enabled (not OFF).
     */
    fun getEnabledModules(): List<PiPupModule> {
        val enabledIds = activeModules + dormantModules
        return enabledIds.mapNotNull { modules[it] }
    }

    /**
     * Dispatches an HTTP request to all enabled modules.
     */
    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri.lowercase()

        // 1. Check active modules first
        for (id in activeModules) {
            val module = modules[id] ?: continue
            val response = module.handleRequest(session)
            if (response != null) {
                lastActivityMap[id] = android.os.SystemClock.elapsedRealtime()
                return response
            }
        }

        // 2. Check dormant modules for route matching
        for (id in dormantModules) {
            val module = modules[id] ?: continue
            if (module.supportedRoutes.any { uri.startsWith(it.lowercase()) }) {
                Log.d(TAG, "Route $uri matches dormant module $id, triggering activation")
                activateModule(id)
                val response = module.handleRequest(session)
                if (response != null) return response
            }
        }

        return null
    }

    /**
     * Allows enabled modules to add their own information to the global state report.
     */
    fun augmentState(state: MutableMap<String, Any?>) {
        // Both active and dormant (eco) modules can report state
        (activeModules + dormantModules).forEach { id ->
            modules[id]?.augmentState(state)
        }
    }

    /**
     * Gracefully shuts down all enabled modules and clears the module registry.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down all modules...")
        handler.removeCallbacks(idleWatchdog)
        activeModules.toList().forEach { id ->
            modules[id]?.onDisable()
        }
        activeModules.clear()
        dormantModules.clear()
        modules.clear()
        moduleContext = null
    }
}
