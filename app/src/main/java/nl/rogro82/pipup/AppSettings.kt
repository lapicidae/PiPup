package nl.rogro82.pipup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Manages application-wide settings using [SharedPreferences].
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("pipup_settings", Context.MODE_PRIVATE)

    // Styling
    var positionIndex by IntPref("position_index", 0)
    var backgroundColor by StringPref("background_color", DEFAULT_BG_COLOR)
    var backgroundAlpha by IntPref("background_alpha", DEFAULT_BG_ALPHA)
    var titleColor by StringPref("title_color", DEFAULT_TITLE_COLOR)
    var titleSize by FloatPref("title_size", DEFAULT_TITLE_SIZE)
    var messageColor by StringPref("message_color", DEFAULT_MSG_COLOR)
    var messageSize by FloatPref("message_size", DEFAULT_MSG_SIZE)
    var borderRadius by IntPref("border_radius", DEFAULT_RADIUS)
    var borderWidth by IntPref("border_width", DEFAULT_BORDER_WIDTH)
    var borderColor by StringPref("border_color", DEFAULT_BORDER_COLOR)
    var contentPadding by IntPref("content_padding", DEFAULT_PADDING)
    var titleAlignment by IntPref("title_alignment", 0)
    var messageAlignment by IntPref("message_alignment", 0)
    var mediaPosition by IntPref("media_position", 0)
    var animationType by IntPref("animation_type", 0)
    var animationDuration by IntPref("animation_duration", 500)
    var animationExit by BooleanPref("animation_exit", false)

    // System / App
    var dismissBatteryOptimization by BooleanPref("dismiss_battery_optimization", false)
    var advancedMode by BooleanPref("advanced_mode", false)
    var appTheme by IntPref("app_theme", 0)
    var language by StringPref("language", "default")

    // Updates
    var updateChannel by IntPref("update_channel", -1)
    var updateInterval by IntPref("update_interval", 4)
    var updateNotificationStyle by IntPref("update_notification_style", 1)
    var lastUpdateCheck by LongPref("last_update_check", 0L)
    var updateAvailableTag by StringPref("update_available_tag", "")
    var updateRepeat by BooleanPref("update_repeat", false)
    var lastNotifiedTag by StringPref("last_notified_tag", "")

    init {
        if (updateChannel == -1) {
            val versionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) { null }

            val isBetaBuild = versionName?.let { v ->
                listOf("prerelease", "beta", "rc").any { v.contains(it, true) }
            } == true ||
                    BuildConfig.APP_STATUS.contains("beta", true) ||
                    BuildConfig.APP_STATUS.contains("prerelease", true) ||
                    BuildConfig.DEBUG

            updateChannel = if (isBetaBuild) 1 else 0
        }
    }

    data class SettingsData(
        val positionIndex: Int,
        val backgroundColor: String,
        val backgroundAlpha: Int,
        val titleColor: String,
        val titleSize: Float,
        val messageColor: String,
        val messageSize: Float,
        val borderRadius: Int,
        val borderWidth: Int,
        val borderColor: String,
        val contentPadding: Int,
        val titleAlignment: Int,
        val messageAlignment: Int,
        val mediaPosition: Int,
        val animationType: Int,
        val animationDuration: Int,
        val animationExit: Boolean,
        val appTheme: Int,
        val advancedMode: Boolean,
        val updateChannel: Int,
        val updateInterval: Int,
        val updateNotificationStyle: Int,
        val lastUpdateCheck: Long,
        val updateAvailableTag: String,
        val updateRepeat: Boolean,
        val lastNotifiedTag: String,
        val language: String
    )

    fun getAll() = SettingsData(
        positionIndex, backgroundColor, backgroundAlpha, titleColor, titleSize,
        messageColor, messageSize, borderRadius, borderWidth, borderColor,
        contentPadding, titleAlignment, messageAlignment, mediaPosition,
        animationType, animationDuration, animationExit, appTheme, advancedMode,
        updateChannel, updateInterval, updateNotificationStyle, lastUpdateCheck,
        updateAvailableTag, updateRepeat, lastNotifiedTag, language
    )

    fun apply(data: SettingsData) {
        prefs.edit {
            putInt("position_index", data.positionIndex)
            putString("background_color", data.backgroundColor)
            putInt("background_alpha", data.backgroundAlpha)
            putString("title_color", data.titleColor)
            putFloat("title_size", data.titleSize)
            putString("message_color", data.messageColor)
            putFloat("message_size", data.messageSize)
            putInt("border_radius", data.borderRadius)
            putInt("border_width", data.borderWidth)
            putString("border_color", data.borderColor)
            putInt("content_padding", data.contentPadding)
            putInt("title_alignment", data.titleAlignment)
            putInt("message_alignment", data.messageAlignment)
            putInt("media_position", data.mediaPosition)
            putInt("animation_type", data.animationType)
            putInt("animation_duration", data.animationDuration)
            putBoolean("animation_exit", data.animationExit)
            putInt("app_theme", data.appTheme)
            putBoolean("advanced_mode", data.advancedMode)
            putInt("update_channel", data.updateChannel)
            putInt("update_interval", data.updateInterval)
            putInt("update_notification_style", data.updateNotificationStyle)
            putLong("last_update_check", data.lastUpdateCheck)
            putString("update_available_tag", data.updateAvailableTag)
            putBoolean("update_repeat", data.updateRepeat)
            putString("last_notified_tag", data.lastNotifiedTag)
            putString("language", data.language)
        }
    }

    fun getFullBackgroundColor(): String {
        val clean = backgroundColor.replace("#", "").let { if (it.length == 8) it.substring(2) else it }
        val alphaHex = String.format("%02X", backgroundAlpha)
        return "#$alphaHex$clean"
    }

    fun resetToDefaults() {
        prefs.edit {
            clear()
            // Explicitly reset non-styling flag for parity and clarity
            putBoolean("dismiss_battery_optimization", false)
        }
    }

    private class StringPref(val key: String, val defaultValue: String) : ReadWriteProperty<AppSettings, String> {
        override fun getValue(thisRef: AppSettings, property: KProperty<*>): String {
            return thisRef.prefs.getString(key, defaultValue) ?: defaultValue
        }
        override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: String) {
            thisRef.prefs.edit { putString(key, value) }
        }
    }

    private class IntPref(val key: String, val defaultValue: Int) : ReadWriteProperty<AppSettings, Int> {
        override fun getValue(thisRef: AppSettings, property: KProperty<*>): Int {
            return thisRef.prefs.getInt(key, defaultValue)
        }
        @Suppress("unused", "RedundantSuppression")
        override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: Int) {
            thisRef.prefs.edit { putInt(key, value) }
        }
    }

    private class FloatPref(val key: String, val defaultValue: Float) : ReadWriteProperty<AppSettings, Float> {
        override fun getValue(thisRef: AppSettings, property: KProperty<*>): Float {
            return thisRef.prefs.getFloat(key, defaultValue)
        }
        @Suppress("unused", "RedundantSuppression")
        override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: Float) {
            thisRef.prefs.edit { putFloat(key, value) }
        }
    }

    private class BooleanPref(val key: String, val defaultValue: Boolean) : ReadWriteProperty<AppSettings, Boolean> {
        override fun getValue(thisRef: AppSettings, property: KProperty<*>): Boolean {
            return thisRef.prefs.getBoolean(key, defaultValue)
        }
        @Suppress("unused", "RedundantSuppression")
        override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: Boolean) {
            thisRef.prefs.edit { putBoolean(key, value) }
        }
    }

    private class LongPref(val key: String, val defaultValue: Long) : ReadWriteProperty<AppSettings, Long> {
        override fun getValue(thisRef: AppSettings, property: KProperty<*>): Long {
            return thisRef.prefs.getLong(key, defaultValue)
        }
        @Suppress("unused", "RedundantSuppression")
        override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: Long) {
            thisRef.prefs.edit { putLong(key, value) }
        }
    }

    companion object {
        const val DEFAULT_BG_COLOR = "#0F1417"
        const val DEFAULT_BG_ALPHA = 225
        const val DEFAULT_TITLE_COLOR = "#DFE3E7"
        const val DEFAULT_TITLE_SIZE = 22f
        const val DEFAULT_MSG_COLOR = "#C6C6C9" // Silver instead of old #C0C7CD
        const val DEFAULT_MSG_SIZE = 16f
        const val DEFAULT_RADIUS = 16
        const val DEFAULT_BORDER_WIDTH = 0
        const val DEFAULT_BORDER_COLOR = "#2F3033" // Gunmetal instead of old Teal-ish #625B71
        const val DEFAULT_PADDING = 20
    }
}
