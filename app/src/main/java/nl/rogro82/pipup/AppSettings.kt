package nl.rogro82.pipup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import org.json.JSONObject
import nl.rogro82.pipup.core.ModuleMode
import java.util.UUID
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Manages application-wide settings using [SharedPreferences].
 * Uses device-protected storage to ensure background service accessibility.
 */
class AppSettings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = run {
        val deviceProtectedContext = appContext.createDeviceProtectedStorageContext()
        val p = deviceProtectedContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!p.contains("migration_done")) {
            Log.i("AppSettings", "Checking for legacy settings migration...")
            val legacyPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Check if there is anything to migrate
            if (legacyPrefs.all.isNotEmpty()) {
                Log.i("AppSettings", "Migrating settings from legacy storage...")
                runCatching {
                    // 1. Move file at OS level
                    deviceProtectedContext.moveSharedPreferencesFrom(appContext, PREFS_NAME)
                    // 2. Hard-clear the old instance to prevent any ghost data
                    legacyPrefs.edit(commit = true) { clear() }
                    Log.i("AppSettings", "Migration successful and legacy storage cleared.")
                }.onFailure {
                    Log.e("AppSettings", "Migration failed: ${it.message}")
                }
            }
            p.edit(commit = true) { putBoolean("migration_done", true) }
        }
        p
    }

    /**
     * Retrieves the unique device ID, migrating from old storage if necessary.
     */
    val deviceId: String by lazy {
        val idPrefs = appContext.createDeviceProtectedStorageContext().getSharedPreferences("pipup_id", Context.MODE_PRIVATE)
        idPrefs.getString("device_id", null) ?: run {
            val oldPrefs = appContext.getSharedPreferences("pipup_id", Context.MODE_PRIVATE)
            val oldId = oldPrefs.getString("device_id", null)
            if (oldId != null) {
                idPrefs.edit { putString("device_id", oldId) }
                oldPrefs.edit { remove("device_id") }
                oldId
            } else {
                UUID.randomUUID().toString().also { newId ->
                    idPrefs.edit { putString("device_id", newId) }
                }
            }
        }
    }

    /**
     * Retrieves the mode for a specific module.
     */
    fun getModuleMode(moduleId: String): ModuleMode {
        val mm = (appContext as? PiPupApp)?.moduleManager
        val module = mm?.getModule(moduleId)
        val default = module?.defaultMode ?: ModuleMode.OFF

        val savedValue = prefs.getInt("module_mode_$moduleId", -1)
        if (savedValue != -1) {
            return ModuleMode.entries.find { it.value == savedValue } ?: default
        }
        return default
    }

    /**
     * Sets the mode for a specific module.
     * Automatically handles fallbacks when enabling a module without a preferred mode.
     */
    fun setModuleMode(moduleId: String, mode: ModuleMode) {
        val finalMode = if (mode != ModuleMode.OFF) mode else ModuleMode.OFF
        prefs.edit {
            putInt("module_mode_$moduleId", finalMode.value)
            if (finalMode != ModuleMode.OFF) {
                putInt("module_preferred_mode_$moduleId", finalMode.value)
            }
        }
    }

    /**
     * Retrieves the last used active mode (Eco/On) for a module.
     */
    fun getPreferredMode(moduleId: String): ModuleMode {
        val mm = (appContext as? PiPupApp)?.moduleManager
        val module = mm?.getModule(moduleId)
        val default = module?.defaultMode ?: ModuleMode.OFF

        val savedValue = prefs.getInt("module_preferred_mode_$moduleId", -1)
        if (savedValue != -1) {
            return ModuleMode.entries.find { it.value == savedValue } ?: default
        }
        return default
    }

    /**
     * Generic method to retrieve a module-specific setting.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getModuleSetting(moduleId: String, key: String, defaultValue: T): T {
        val fullKey = "mod_${moduleId}_$key"
        return when (defaultValue) {
            is Boolean -> prefs.getBoolean(fullKey, defaultValue) as T
            is Int -> prefs.getInt(fullKey, defaultValue) as T
            is Float -> prefs.getFloat(fullKey, defaultValue) as T
            is Long -> prefs.getLong(fullKey, defaultValue) as T
            is String -> (prefs.getString(fullKey, defaultValue) ?: defaultValue) as T
            else -> defaultValue
        }
    }

    /**
     * Generic method to store a module-specific setting.
     */
    fun setModuleSetting(moduleId: String, key: String, value: Any) {
        val fullKey = "mod_${moduleId}_$key"
        prefs.edit {
            when (value) {
                is Boolean -> putBoolean(fullKey, value)
                is Int -> putInt(fullKey, value)
                is Float -> putFloat(fullKey, value)
                is Long -> putLong(fullKey, value)
                is String -> putString(fullKey, value)
            }
        }
    }

    // Styling
    /** The index of the popup position on the screen. */
    var positionIndex by IntPref("position_index", 0)
    /** The background color of the popup in hex format. */
    var backgroundColor by ColorPref("background_color", R.color.preset_deep_slate) { cachedFullBgColor = null }
    /** The transparency level of the popup background (0-255). */
    var backgroundAlpha by IntPref("background_alpha", DEFAULT_BG_ALPHA) { cachedFullBgColor = null }
    /** The text color of the popup title in hex format. */
    var titleColor by ColorPref("title_color", R.color.preset_platinum)
    /** The font size of the popup title. */
    var titleSize by FloatPref("title_size", DEFAULT_TITLE_SIZE)
    /** The text color of the popup message in hex format. */
    var messageColor by ColorPref("message_color", R.color.preset_silver)
    /** The font size of the popup message. */
    var messageSize by FloatPref("message_size", DEFAULT_MSG_SIZE)
    /** The corner radius of the popup background. */
    var borderRadius by IntPref("border_radius", DEFAULT_RADIUS)
    /** The width of the popup border. */
    var borderWidth by IntPref("border_width", DEFAULT_BORDER_WIDTH)
    /** The color of the popup border in hex format. */
    var borderColor by ColorPref("border_color", R.color.preset_gunmetal)
    /** The internal padding of the popup content. */
    var contentPadding by IntPref("content_padding", DEFAULT_PADDING)
    /** The alignment of the title text (0: Start, 1: Center, 2: End). */
    var titleAlignment by IntPref("title_alignment", 0)
    /** The alignment of the message text (0: Start, 1: Center, 2: End). */
    var messageAlignment by IntPref("message_alignment", 0)
    /** The position of the media relative to the text. */
    var mediaPosition by IntPref("media_position", 0)
    /** The type of entrance animation. */
    var animationType by IntPref("animation_type", 0)
    /** The duration of the entrance and exit animations in milliseconds. */
    var animationDuration by IntPref("animation_duration", 500)
    /** Whether to play an exit animation when the popup is dismissed. */
    var animationExit by BooleanPref("animation_exit", false)
    /** The timeout in seconds for loading remote media. */
    var mediaTimeout by IntPref("media_timeout", 10)
    /** The number of retries for failed media loads. */
    var mediaRetries by IntPref("media_retries", 3)

    // System / App
    /** Enables additional technical settings and information. */
    var advancedMode by BooleanPref("advanced_mode", false)
    /** The application theme (0: Dark, 1: Light). */
    var appTheme by IntPref("app_theme", 0)

    /** Whether the power control module is enabled. */
    var powerModuleEnabled: Boolean
        get() = getModuleMode("power") != ModuleMode.OFF
        set(value) = setModuleMode("power", if (value) getPreferredMode("power").takeIf { it != ModuleMode.OFF } ?: ModuleMode.ON else ModuleMode.OFF)

    /** Whether the network discovery module is enabled. */
    var discoveryModuleEnabled: Boolean
        get() = getModuleMode("discovery") != ModuleMode.OFF
        set(value) = setModuleMode("discovery", if (value) ModuleMode.ON else ModuleMode.OFF)

    /** Whether the rich media (WebView/WHEP) module is enabled. */
    var mediaModuleEnabled: Boolean
        get() = getModuleMode("media") != ModuleMode.OFF
        set(value) = setModuleMode("media", if (value) getPreferredMode("media").takeIf { it != ModuleMode.OFF } ?: ModuleMode.ECO else ModuleMode.OFF)

    /** The preferred language for the application UI. */
    var language by StringPref("language", "default")
    /** The timestamp of the last daily permission nag. */
    var lastPermissionNagDate by LongPref("last_permission_nag_date", 0L)

    // Updates
    /** The update channel (0: Stable, 1: Beta). */
    var updateChannel by IntPref("update_channel", -1)
    /** The frequency of update checks. */
    var updateInterval by IntPref("update_interval", 4)
    /** The style of update notifications. */
    var updateNotificationStyle by IntPref("update_notification_style", 1)
    /** The timestamp of the last successful update check. */
    var lastUpdateCheck by LongPref("last_update_check", 0L)
    /** The tag name of the latest available update found. */
    var updateAvailableTag by StringPref("update_available_tag", "")
    /** Whether to re-notify the user about an available update. */
    var updateRepeat by BooleanPref("update_repeat", false)
    /** The tag name for which the user was last notified. */
    var lastNotifiedTag by StringPref("last_notified_tag", "")

    // Pending Update State
    /** The ID of a currently downloading or pending update. */
    var pendingUpdateId by LongPref("pending_update_id", -1L)
    /** The expected digest (SHA) of a pending update file. */
    var pendingUpdateDigest by StringPref("pending_update_digest", "")
    /** The tag name associated with a pending update. */
    var pendingUpdateTagName by StringPref("pending_update_tag_name", "")

    /**
     * Determines if the current application is a beta or pre-release build.
     */
    val isBetaBuild: Boolean by lazy {
        if (BuildConfig.DEBUG) return@lazy true
        if (BuildConfig.APP_STATUS.contains("beta", true)) return@lazy true
        if (BuildConfig.APP_STATUS.contains("prerelease", true)) return@lazy true

        val versionName = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        } catch (_: Exception) { null }

        versionName?.let {
            listOf("beta", "prerelease", "rc").any { tag -> it.contains(tag, true) } || it.contains("-")
        } ?: false
    }

    init {
        if (updateChannel == -1) {
            updateChannel = if (isBetaBuild) 1 else 0
        }
    }

    /**
     * Data class representing a snapshot of all application settings.
     */
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
        val appTheme: Int,
        val advancedMode: Boolean,
        val powerModuleEnabled: Boolean,
        val discoveryModuleEnabled: Boolean,
        val mediaModuleEnabled: Boolean,
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
        val language: String,
        val moduleModeMedia: Int? = null
    ) {
        fun toJSONObject(): JSONObject {
            return JSONObject().apply {
                put("positionIndex", positionIndex)
                put("backgroundColor", backgroundColor)
                put("backgroundAlpha", backgroundAlpha)
                put("titleColor", titleColor)
                put("titleSize", titleSize.toDouble())
                put("messageColor", messageColor)
                put("messageSize", messageSize.toDouble())
                put("borderRadius", borderRadius)
                put("borderWidth", borderWidth)
                put("borderColor", borderColor)
                put("contentPadding", contentPadding)
                put("titleAlignment", titleAlignment)
                put("messageAlignment", messageAlignment)
                put("mediaPosition", mediaPosition)
                put("animationType", animationType)
                put("animationDuration", animationDuration)
                put("animationExit", animationExit)
                put("mediaTimeout", mediaTimeout)
                put("mediaRetries", mediaRetries)
                put("appTheme", appTheme)
                put("advancedMode", advancedMode)
                put("powerModuleEnabled", powerModuleEnabled)
                put("discoveryModuleEnabled", discoveryModuleEnabled)
                put("mediaModuleEnabled", mediaModuleEnabled)
                put("updateChannel", updateChannel)
                put("updateInterval", updateInterval)
                put("updateNotificationStyle", updateNotificationStyle)
                put("lastUpdateCheck", lastUpdateCheck)
                put("updateAvailableTag", updateAvailableTag)
                put("updateRepeat", updateRepeat)
                put("lastNotifiedTag", lastNotifiedTag)
                put("pendingUpdateId", pendingUpdateId)
                put("pendingUpdateDigest", pendingUpdateDigest)
                put("pendingUpdateTagName", pendingUpdateTagName)
                put("language", language)
                moduleModeMedia?.let { put("moduleModeMedia", it) }
            }
        }

        companion object {
            fun fromJson(jsonStr: String): SettingsData {
                val j = JSONObject(jsonStr)
                return SettingsData(
                    positionIndex = j.optInt("positionIndex", 0),
                    backgroundColor = j.optString("backgroundColor", ""),
                    backgroundAlpha = j.optInt("backgroundAlpha", 225),
                    titleColor = j.optString("titleColor", ""),
                    titleSize = j.optDouble("titleSize", 22.0).toFloat(),
                    messageColor = j.optString("messageColor", ""),
                    messageSize = j.optDouble("messageSize", 16.0).toFloat(),
                    borderRadius = j.optInt("borderRadius", 16),
                    borderWidth = j.optInt("borderWidth", 0),
                    borderColor = j.optString("borderColor", ""),
                    contentPadding = j.optInt("contentPadding", 20),
                    titleAlignment = j.optInt("titleAlignment", 0),
                    messageAlignment = j.optInt("messageAlignment", 0),
                    mediaPosition = j.optInt("mediaPosition", 0),
                    animationType = j.optInt("animationType", 0),
                    animationDuration = j.optInt("animationDuration", 500),
                    animationExit = j.optBoolean("animationExit", false),
                    mediaTimeout = j.optInt("mediaTimeout", 10),
                    mediaRetries = j.optInt("mediaRetries", 3),
                    appTheme = j.optInt("appTheme", 0),
                    advancedMode = j.optBoolean("advancedMode", false),
                    powerModuleEnabled = j.optBoolean("powerModuleEnabled", false),
                    discoveryModuleEnabled = j.optBoolean("discoveryModuleEnabled", false),
                    mediaModuleEnabled = j.optBoolean("mediaModuleEnabled", false),
                    updateChannel = j.optInt("updateChannel", 0),
                    updateInterval = j.optInt("updateInterval", 4),
                    updateNotificationStyle = j.optInt("updateNotificationStyle", 1),
                    lastUpdateCheck = j.optLong("lastUpdateCheck", 0L),
                    updateAvailableTag = j.optString("updateAvailableTag", ""),
                    updateRepeat = j.optBoolean("updateRepeat", false),
                    lastNotifiedTag = j.optString("lastNotifiedTag", ""),
                    pendingUpdateId = j.optLong("pendingUpdateId", -1L),
                    pendingUpdateDigest = j.optString("pendingUpdateDigest", ""),
                    pendingUpdateTagName = j.optString("pendingUpdateTagName", ""),
                    language = j.optString("language", "default"),
                    moduleModeMedia = if (j.has("moduleModeMedia")) j.getInt("moduleModeMedia") else null
                )
            }
        }
    }

    /**
     * Retrieves all current settings as a [SettingsData] object.
     */
    fun getAll(): SettingsData {
        return SettingsData(
            positionIndex = positionIndex,
            backgroundColor = backgroundColor,
            backgroundAlpha = backgroundAlpha,
            titleColor = titleColor,
            titleSize = titleSize,
            messageColor = messageColor,
            messageSize = messageSize,
            borderRadius = borderRadius,
            borderWidth = borderWidth,
            borderColor = borderColor,
            contentPadding = contentPadding,
            titleAlignment = titleAlignment,
            messageAlignment = messageAlignment,
            mediaPosition = mediaPosition,
            animationType = animationType,
            animationDuration = animationDuration,
            animationExit = animationExit,
            mediaTimeout = mediaTimeout,
            mediaRetries = mediaRetries,
            appTheme = appTheme,
            advancedMode = advancedMode,
            powerModuleEnabled = powerModuleEnabled,
            discoveryModuleEnabled = discoveryModuleEnabled,
            mediaModuleEnabled = mediaModuleEnabled,
            updateChannel = updateChannel,
            updateInterval = updateInterval,
            updateNotificationStyle = updateNotificationStyle,
            lastUpdateCheck = lastUpdateCheck,
            updateAvailableTag = updateAvailableTag,
            updateRepeat = updateRepeat,
            lastNotifiedTag = lastNotifiedTag,
            pendingUpdateId = pendingUpdateId,
            pendingUpdateDigest = pendingUpdateDigest,
            pendingUpdateTagName = pendingUpdateTagName,
            language = language,
            moduleModeMedia = getModuleMode("media").value
        )
    }

    /**
     * Applies new settings from a [SettingsData] object.
     */
    fun apply(data: SettingsData) {
        positionIndex = data.positionIndex
        backgroundColor = validateHexColor(data.backgroundColor, appContext.colorToHex(R.color.preset_deep_slate))
        backgroundAlpha = data.backgroundAlpha.coerceIn(0, 255)
        titleColor = validateHexColor(data.titleColor, appContext.colorToHex(R.color.preset_platinum))
        titleSize = data.titleSize.coerceIn(10f, 100f)
        messageColor = validateHexColor(data.messageColor, appContext.colorToHex(R.color.preset_silver))
        messageSize = data.messageSize.coerceIn(8f, 80f)
        borderRadius = data.borderRadius.coerceIn(0, 200)
        borderWidth = data.borderWidth.coerceIn(0, 50)
        borderColor = validateHexColor(data.borderColor, appContext.colorToHex(R.color.preset_gunmetal))
        contentPadding = data.contentPadding.coerceIn(0, 200)
        titleAlignment = data.titleAlignment.coerceIn(0, 2)
        messageAlignment = data.messageAlignment.coerceIn(0, 2)
        mediaPosition = data.mediaPosition.coerceIn(0, 3)
        animationType = data.animationType.coerceIn(0, 10)
        animationDuration = data.animationDuration.coerceIn(0, 5000)
        animationExit = data.animationExit
        mediaTimeout = data.mediaTimeout.coerceIn(1, 60)
        mediaRetries = data.mediaRetries.coerceIn(0, 10)
        appTheme = data.appTheme.coerceIn(0, 1)
        advancedMode = data.advancedMode
        powerModuleEnabled = data.powerModuleEnabled
        discoveryModuleEnabled = data.discoveryModuleEnabled
        mediaModuleEnabled = data.mediaModuleEnabled
        updateChannel = data.updateChannel.coerceIn(-1, 1)
        updateInterval = data.updateInterval.coerceIn(0, 4)
        updateNotificationStyle = data.updateNotificationStyle.coerceIn(0, 2)
        lastUpdateCheck = data.lastUpdateCheck
        updateAvailableTag = data.updateAvailableTag
        updateRepeat = data.updateRepeat
        lastNotifiedTag = data.lastNotifiedTag
        pendingUpdateId = data.pendingUpdateId
        pendingUpdateDigest = data.pendingUpdateDigest
        pendingUpdateTagName = data.pendingUpdateTagName
        language = data.language

        data.moduleModeMedia?.let {
            ModuleMode.entries.find { m -> m.value == it }?.let { m -> setModuleMode("media", m) }
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

    /**
     * Calculates the full background color including the alpha channel.
     * Returns a hex string in #AARRGGBB format.
     */
    fun getFullBackgroundColor(): String {
        cachedFullBgColor?.let { return it }

        val clean = backgroundColor.replace("#", "").let { if (it.length == 8) it.substring(2) else it }
        val alphaHex = String.format("%02X", backgroundAlpha)
        val result = "#$alphaHex$clean"

        cachedFullBgColor = result
        return result
    }

    /**
     * Resets all settings to their default values in the active storage.
     * Sets the migration flag to prevent restoring old data after reset.
     */
    fun resetToDefaults() {
        // 1. Clear active device-protected storage and ensure migration flag is set
        prefs.edit(commit = true) {
            clear()
            putBoolean("migration_done", true)
        }

        // 2. Also clear legacy storage just in case it wasn't cleared during migration
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            clear()
        }

        cachedFullBgColor = null
        // Re-initialize default values that were set in init
        updateChannel = if (isBetaBuild) 1 else 0
    }

    /**
     * Applies user-configured default settings to a PopupProps instance.
     */
    fun applyDefaults(props: PopupProps): PopupProps {
        return props.copy(
            backgroundColor = if (props.backgroundColor == "#CC000000") getFullBackgroundColor() else props.backgroundColor,
            borderColor = if (props.borderColor == "#00000000") borderColor else props.borderColor,
            borderRadius = if (props.borderRadius == 0) borderRadius else props.borderRadius,
            borderWidth = if (props.borderWidth == 0) borderWidth else props.borderWidth,
            titleColor = if (props.titleColor == "#FFFFFF") titleColor else props.titleColor,
            titleSize = if (props.titleSize == 24f) titleSize else props.titleSize,
            messageColor = if (props.messageColor == "#FFFFFF") messageColor else props.messageColor,
            messageSize = if (props.messageSize == 16f) messageSize else props.messageSize,
            titleAlignment = if (props.titleAlignment == 0) titleAlignment else props.titleAlignment,
            messageAlignment = if (props.messageAlignment == 0) messageAlignment else props.messageAlignment,
            mediaPosition = props.mediaPosition ?: mediaPosition,
            animationType = if (props.animationType == 0) animationType else props.animationType,
            animationDuration = if (props.animationDuration == 500) animationDuration else props.animationDuration,
            animationExit = props.animationExit || animationExit
        )
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
        const val PREFS_NAME = "pipup_settings"
        const val DEFAULT_BG_ALPHA = 225
        const val DEFAULT_TITLE_SIZE = 22f
        const val DEFAULT_MSG_SIZE = 16f
        const val DEFAULT_RADIUS = 16
        const val DEFAULT_BORDER_WIDTH = 0
        const val DEFAULT_PADDING = 20
    }
}
