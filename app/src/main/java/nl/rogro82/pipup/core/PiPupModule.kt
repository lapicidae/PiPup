package nl.rogro82.pipup.core

import fi.iki.elonen.NanoHTTPD

/**
 * Interface for optional PiPup modules.
 *
 * Modules can be enabled/disabled by the user and can hook into the WebServer
 * request flow and the application state reporting.
 */
interface PiPupModule {
    /** Unique identifier for the module (e.g., "power"). */
    val id: String
    /** User-friendly name of the module. */
    val name: String

    /** Called when the module is enabled. */
    fun onEnable()

    /** Called when the module is disabled or the service is destroyed. */
    fun onDisable()

    /**
     * Handles an incoming HTTP request.
     *
     * @return A [NanoHTTPD.Response] if the module handles this request, null otherwise.
     */
    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response?

    /**
     * Augments the application state with module-specific information.
     *
     * @param state The mutable state map to add information to.
     */
    fun augmentState(state: MutableMap<String, Any?>)

    /**
     * Returns a list of permission keys required by this module.
     * These will be shown in the central Permissions menu when the module is enabled.
     */
    fun getRequiredPermissions(): List<String> = emptyList()
}
