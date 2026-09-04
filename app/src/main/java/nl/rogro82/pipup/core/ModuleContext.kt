package nl.rogro82.pipup.core

import android.content.Context
import nl.rogro82.pipup.AppSettings

/**
 * Interface providing restricted access to system services and settings for modules.
 * Decouples modules from the raw Android Context for better testability and safety.
 */
interface ModuleContext {
    /** The application-wide settings. */
    val settings: AppSettings

    /** The underlying Android context (for components that strictly require it). */
    val androidContext: Context

    /**
     * Retrieves a system service by name.
     * @param name The name of the service.
     * @return The service object, or null if not found.
     */
    fun getSystemService(name: String): Any?

    /**
     * Returns the application package name.
     */
    fun getPackageName(): String

    /**
     * Displays a toast message on the screen.
     */
    fun showToast(message: String)

    /**
     * Returns a string resource.
     */
    fun getString(resId: Int): String

    /**
     * Returns a string resource with arguments.
     */
    fun getString(resId: Int, vararg formatArgs: Any): String
}
