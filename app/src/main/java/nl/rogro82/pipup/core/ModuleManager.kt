package nl.rogro82.pipup.core

import android.util.Log
import fi.iki.elonen.NanoHTTPD

/**
 * Manages the lifecycle and request dispatching for optional PiPup modules.
 */
class ModuleManager {

    companion object {
        private const val TAG = "ModuleManager"
    }

    private val modules = mutableMapOf<String, PiPupModule>()
    private val enabledModules = mutableSetOf<String>()

    /**
     * Registers a module with the manager.
     */
    fun registerModule(module: PiPupModule) {
        modules[module.id] = module
        Log.d(TAG, "Module registered: ${module.id}")
    }

    /**
     * Toggles the enabled state of a module.
     */
    fun setModuleEnabled(id: String, enabled: Boolean) {
        val module = modules[id] ?: return
        if (enabled) {
            if (enabledModules.add(id)) {
                Log.i(TAG, "Enabling module: $id")
                try {
                    module.onEnable()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enable module $id", e)
                    enabledModules.remove(id)
                }
            }
        } else {
            if (enabledModules.remove(id)) {
                Log.i(TAG, "Disabling module: $id")
                try {
                    module.onDisable()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable module $id", e)
                }
            }
        }
    }

    /**
     * Returns a registered module by its ID.
     */
    fun getModule(id: String): PiPupModule? = modules[id]

    /**
     * Dispatches an HTTP request to all enabled modules.
     * The first module to return a non-null response wins.
     */
    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        for (id in enabledModules) {
            val response = modules[id]?.handleRequest(session)
            if (response != null) return response
        }
        return null
    }

    /**
     * Allows enabled modules to add their own information to the global state report.
     */
    fun augmentState(state: MutableMap<String, Any?>) {
        for (id in enabledModules) {
            modules[id]?.augmentState(state)
        }
    }

    /**
     * Gracefully shuts down all enabled modules.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down all modules...")
        for (id in enabledModules.toList()) {
            setModuleEnabled(id, false)
        }
    }
}
