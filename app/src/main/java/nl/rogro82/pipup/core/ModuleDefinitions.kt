package nl.rogro82.pipup.core

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

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
 * Definition of a single setting exposed by a module.
 */
data class ModuleSettingDefinition(
    val key: String,
    val type: SettingType,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int? = null,
    val defaultValue: Any,
    val options: Map<String, Int>? = null // For STRING_SELECT: Map of value to labelRes
)

/**
 * Definition of a menu entry in the settings UI.
 */
data class ModuleMenuDefinition(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    val priority: Int = 100,
    val showInMainRail: Boolean = false
)

/**
 * Activation strategies for modules.
 */
object ActivationStrategy {
    const val OFF = 0
    const val ECO = 1
    const val PERFORMANCE = 2
}
