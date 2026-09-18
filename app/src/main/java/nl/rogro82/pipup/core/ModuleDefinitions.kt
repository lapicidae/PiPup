package nl.rogro82.pipup.core

import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import nl.rogro82.pipup.R

/**
 * Supported types for modular settings.
 */
@Suppress("unused")
enum class SettingType {
    BOOLEAN,
    INT,
    FLOAT,
    STRING,
    COLOR,
    STRING_SELECT
}

/**
 * Target categories for settings to be placed in global UI tabs.
 */
enum class SettingCategory {
    /** The central performance settings tab. */
    PERFORMANCE,
    /** The general settings tab. */
    GENERAL,
    /** The background settings tab. */
    BACKGROUND,
    /** The text styling settings tab. */
    TEXT,
    /** The border styling settings tab. */
    BORDER,
    /** The animation settings tab. */
    ANIMATION,
    /** The advanced settings tab. */
    ADVANCED,
    /** The updates settings tab. */
    UPDATES,
    /** The permissions settings tab. */
    PERMISSIONS
}

/**
 * Definition of a single setting exposed by a module.
 */
data class ModuleSettingDefinition(
    val key: String,
    val type: SettingType,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int? = null,
    val defaultValue: Any,
    val options: Map<String, Int>? = null, // For STRING_SELECT: Map of value to labelRes
    val category: SettingCategory? = null
)

/**
 * Definition of a menu entry in the settings UI.
 */
data class ModuleMenuDefinition(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    val priority: Int = 100,
    val showInMainRail: Boolean = false,
    @LayoutRes val layoutRes: Int = R.layout.submenu_module_dynamic
)

/**
 * Internal modes for modules.
 * OFF: Module is disabled and released from memory.
 * ON: Module is active and ready.
 * ECO: Module is active on-demand and idles when not in use.
 */
enum class ModuleMode(val value: Int) {
    OFF(0),
    ON(1),
    ECO(2)
}
