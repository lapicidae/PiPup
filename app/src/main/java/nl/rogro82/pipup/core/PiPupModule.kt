package nl.rogro82.pipup.core

import fi.iki.elonen.NanoHTTPD

/**
 * Interface for optional PiPup modules.
 */
interface PiPupModule {
    /** Unique identifier for the module (e.g., "power"). */
    val id: String
    /** User-friendly name of the module. */
    val name: String
    /** Description of the module functionality (Resource ID). */
    val descriptionRes: Int

    /**
     * List of modes supported by this module.
     */
    val supportedModes: List<ModuleMode>

    /**
     * The default mode for this module when no setting is saved.
     */
    val defaultMode: ModuleMode

    /**
     * List of HTTP routes supported by this module.
     * Used for on-demand activation in Eco mode.
     */
    val supportedRoutes: List<String> get() = emptyList()

    /**
     * Called when the notification display state changes.
     */
    fun onNotificationDisplayStateChanged(isDisplaying: Boolean) {}

    /**
     * Called when the module is enabled.
     *
     * @param context The module context providing access to settings and services.
     */
    fun onEnable(context: ModuleContext)

    /** Called when the module is disabled or the service is destroyed. */
    fun onDisable()

    /**
     * Called when the module has been inactive for a while (Eco mode).
     * Use this to release heavy resources like WebViews.
     */
    @Suppress("EmptyMethod")
    fun onIdle() {
        // Optional hook for releasing resources
    }

    /**
     * Handles an incoming HTTP request.
     *
     * @param session The NanoHTTPD session.
     * @return A [NanoHTTPD.Response] if the module handles this request, null otherwise.
     */
    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? = null

    /**
     * Allows enabled modules to add their own information to the global state report.
     *
     * @param state The mutable state map to add information to.
     */
    fun augmentState(state: MutableMap<String, Any?>) {
        // Optional hook for adding state info
    }

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
