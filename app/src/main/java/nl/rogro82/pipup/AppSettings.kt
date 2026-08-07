package nl.rogro82.pipup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Manages application-wide settings using [SharedPreferences].
 */
class AppSettings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("pipup_settings", Context.MODE_PRIVATE)

    // Styling
    var positionIndex by IntPref("position_index", 0)
    var backgroundColor by ColorPref("background_color", R.color.preset_deep_slate) { cachedFullBgColor = null }
    var backgroundAlpha by IntPref("background_alpha", DEFAULT_BG_ALPHA) { cachedFullBgColor = null }
    var titleColor by ColorPref("title_color", R.color.preset_platinum)
    var titleSize by FloatPref("title_size", DEFAULT_TITLE_SIZE)
    var messageColor by ColorPref("message_color", R.color.preset_silver)
    var messageSize by FloatPref("message_size", DEFAULT_MSG_SIZE)
    var borderRadius by IntPref("border_radius", DEFAULT_RADIUS)
    var borderWidth by IntPref("border_width", DEFAULT_BORDER_WIDTH)
    var borderColor by ColorPref("border_color", R.color.preset_gunmetal)
    var contentPadding by IntPref("content_padding", DEFAULT_PADDING)
    var titleAlignment by IntPref("title_alignment", 0)
    var messageAlignment by IntPref("message_alignment", 0)
    var mediaPosition by IntPref("media_position", 0)
    var animationType by IntPref("animation_type", 0)
    var animationDuration by IntPref("animation_duration", 500)
    var animationExit by BooleanPref("animation_exit", false)
    var mediaTimeout by IntPref("media_timeout", 10)
    var mediaRetries by IntPref("media_retries", 3)
    var preWarmWebView by BooleanPref("pre_warm_webview", false)

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

    // Pending Update State
    var pendingUpdateId by LongPref("pending_update_id", -1L)
    var pendingUpdateDigest by StringPref("pending_update_digest", "")
    var pendingUpdateTagName by StringPref("pending_update_tag_name", "")

    val isBetaBuild: Boolean by lazy {
        val versionName = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        } catch (_: Exception) { null }

        versionName?.let { v ->
            listOf("prerelease", "beta", "rc").any { v.contains(it, true) } || v.contains("-")
        } == true ||
                BuildConfig.APP_STATUS.contains("beta", true) ||
                BuildConfig.APP_STATUS.contains("prerelease", true) ||
                BuildConfig.DEBUG
    }

    init {
        if (updateChannel == -1) {
            updateChannel = if (isBetaBuild) 1 else 0
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
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
        val mediaTimeout: Int,
        val mediaRetries: Int,
        val preWarmWebView: Boolean,
        val appTheme: Int,
        val advancedMode: Boolean,
        val updateChannel: Int,
        val updateInterval: Int,
        val updateNotificationStyle: Int,
        val lastUpdateCheck: Long,
        val updateAvailableTag: String,
        val updateRepeat: Boolean,
        val lastNotifiedTag: String,
        val pendingUpdateId: Long,
        val pendingUpdateDigest: String,
        val pendingUpdateTagName: String,
        val language: String
    )

    fun getAll() = SettingsData(
        positionIndex, backgroundColor, backgroundAlpha, titleColor, titleSize,
        messageColor, messageSize, borderRadius, borderWidth, borderColor,
        contentPadding, titleAlignment, messageAlignment, mediaPosition,
        animationType, animationDuration, animationExit, mediaTimeout, mediaRetries, preWarmWebView, appTheme, advancedMode,
        updateChannel, updateInterval, updateNotificationStyle, lastUpdateCheck,
        updateAvailableTag, updateRepeat, lastNotifiedTag,
        pendingUpdateId, pendingUpdateDigest, pendingUpdateTagName,
        language
    )

    fun apply(data: SettingsData) {
        prefs.edit {
            putInt("position_index", data.positionIndex)
            putString("background_color", validateHexColor(data.backgroundColor, appContext.colorToHex(R.color.preset_deep_slate)))
            putInt("background_alpha", data.backgroundAlpha.coerceIn(0, 255))
            putString("title_color", validateHexColor(data.titleColor, appContext.colorToHex(R.color.preset_platinum)))
            putFloat("title_size", data.titleSize.coerceIn(10f, 100f))
            putString("message_color", validateHexColor(data.messageColor, appContext.colorToHex(R.color.preset_silver)))
            putFloat("message_size", data.messageSize.coerceIn(8f, 80f))
            putInt("border_radius", data.borderRadius.coerceIn(0, 200))
            putInt("border_width", data.borderWidth.coerceIn(0, 50))
            putString("border_color", validateHexColor(data.borderColor, appContext.colorToHex(R.color.preset_gunmetal)))
            putInt("content_padding", data.contentPadding.coerceIn(0, 200))
            putInt("title_alignment", data.titleAlignment.coerceIn(0, 2))
            putInt("message_alignment", data.messageAlignment.coerceIn(0, 2))
            putInt("media_position", data.mediaPosition.coerceIn(0, 3))
            putInt("animation_type", data.animationType.coerceIn(0, 10))
            putInt("animation_duration", data.animationDuration.coerceIn(0, 5000))
            putBoolean("animation_exit", data.animationExit)
            putInt("media_timeout", data.mediaTimeout.coerceIn(1, 60))
            putInt("media_retries", data.mediaRetries.coerceIn(0, 10))
            putBoolean("pre_warm_webview", data.preWarmWebView)
            putInt("app_theme", data.appTheme.coerceIn(0, 1))
            putBoolean("advanced_mode", data.advancedMode)
            putInt("update_channel", data.updateChannel.coerceIn(-1, 1))
            putInt("update_interval", data.updateInterval.coerceIn(0, 4))
            putInt("update_notification_style", data.updateNotificationStyle.coerceIn(0, 2))
            putLong("last_update_check", data.lastUpdateCheck)
            putString("update_available_tag", data.updateAvailableTag)
            putBoolean("update_repeat", data.updateRepeat)
            putString("last_notified_tag", data.lastNotifiedTag)
            putLong("pending_update_id", data.pendingUpdateId)
            putString("pending_update_digest", data.pendingUpdateDigest)
            putString("pending_update_tag_name", data.pendingUpdateTagName)
            putString("language", data.language)
        }
        cachedFullBgColor = null
    }

    private fun validateHexColor(hex: String, fallback: String): String {
        return try {
            val clean = if (hex.startsWith("#")) hex else "#$hex"
            clean.toColorInt()
            if (clean.length != 4 && clean.length != 7 && clean.length != 9) return fallback
            clean
        } catch (_: Exception) {
            fallback
        }
    }

    private var cachedFullBgColor: String? = null

    fun getFullBackgroundColor(): String {
        cachedFullBgColor?.let { return it }

        val clean = backgroundColor.replace("#", "").let { if (it.length == 8) it.substring(2) else it }
        val alphaHex = String.format("%02X", backgroundAlpha)
        val result = "#$alphaHex$clean"

        cachedFullBgColor = result
        return result
    }

    fun resetToDefaults() {
        prefs.edit {
            clear()
            // Explicitly reset non-styling flag for parity and clarity
            putBoolean("dismiss_battery_optimization", false)
        }
    }

    private class StringPref(val key: String, val defaultValue: String, val onSet: (() -> Unit)? = null) : ReadWriteProperty<AppSettings, String> {
        override fun getValue(thisRef: AppSettings, property: KProperty<*>): String {
            return thisRef.prefs.getString(key, defaultValue) ?: defaultValue
        }
        override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: String) {
            thisRef.prefs.edit { putString(key, value) }
            onSet?.invoke()
        }
    }

    private class ColorPref(val key: String, val defaultValueRes: Int, val onSet: (() -> Unit)? = null) : ReadWriteProperty<AppSettings, String> {
        override fun getValue(thisRef: AppSettings, property: KProperty<*>): String {
            val default = thisRef.appContext.colorToHex(defaultValueRes)
            return thisRef.prefs.getString(key, default) ?: default
        }
        override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: String) {
            thisRef.prefs.edit { putString(key, value) }
            onSet?.invoke()
        }
    }

    private class IntPref(val key: String, val defaultValue: Int, val onSet: (() -> Unit)? = null) : ReadWriteProperty<AppSettings, Int> {
        override fun getValue(thisRef: AppSettings, property: KProperty<*>): Int {
            return thisRef.prefs.getInt(key, defaultValue)
        }
        @Suppress("unused", "RedundantSuppression")
        override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: Int) {
            thisRef.prefs.edit { putInt(key, value) }
            onSet?.invoke()
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
        const val DEFAULT_BG_ALPHA = 225
        const val DEFAULT_TITLE_SIZE = 22f
        const val DEFAULT_MSG_SIZE = 16f
        const val DEFAULT_RADIUS = 16
        const val DEFAULT_BORDER_WIDTH = 0
        const val DEFAULT_PADDING = 20
    }
}
