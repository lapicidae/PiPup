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

    /**
     * List of HTTP routes supported by this module.
     * Used for on-demand activation in Eco mode.
     */
    val supportedRoutes: List<String> get() = emptyList()

    /**
     * Called when the module is enabled.
     *
     * @param context The module context providing access to settings and services.
     */
    fun onEnable(context: ModuleContext)

    /** Called when the module is disabled or the service is destroyed. */
    fun onDisable()

    /**
     * Called when the module is in Eco mode and has been inactive for a while.
     * Use this to release heavy resources (like WebViews) while remaining logically enabled.
     */
    fun onIdle() {}

    /**
     * Handles an incoming HTTP request.
     *
     * @param session The NanoHTTPD session.
     * @return A [NanoHTTPD.Response] if the module handles this request, null otherwise.
     */
    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response?

    /**
     * Allows enabled modules to add their own information to the global state report.
     *
     * @param state The mutable state map to add information to.
     */
    fun augmentState(state: MutableMap<String, Any?>)

    /**
     * Returns a list of permission keys required by this module.
     * These will be shown in the central Permissions menu when the module is enabled.
     *
     * @return A list of permission keys.
     */
    fun getRequiredPermissions(): List<String> = emptyList()

    /**
     * Returns metadata for dynamically rendered settings in the UI.
     */
    fun getSettingsMetadata(): List<ModuleSettingDefinition> = emptyList()

    /**
     * Returns the menu definition for integration into the Settings UI.
     */
    fun getSettingsMenu(): ModuleMenuDefinition? = null
}
