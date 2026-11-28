package nl.rogro82.pipup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import org.json.JSONObject
import nl.rogro82.pipup.core.ModuleMode
import java.util.Locale
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
        val idPrefs = appContext.createDeviceProtectedStorageContext()
            .getSharedPreferences("pipup_id", Context.MODE_PRIVATE)
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

    var webhook: String?
        get() = prefs.getString("webhook", null)
        set(value) {
            prefs.edit { putString("webhook", value) }
        }

    /** Periodic webhook heartbeat interval in seconds (`0` = disabled). */
    var webhookHeartbeat: Int by IntPref("webhook_heartbeat", 0)

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

    // Default Button Styling
    var defaultButtonSize by FloatPref("default_button_size", 14f)
    var defaultButtonColor by StringPref("default_button_color", appContext.colorToHex(R.color.preset_gunmetal))
    var defaultButtonFocusColor by StringPref(
        "default_button_focus_color",
        appContext.colorToHex(R.color.preset_sky_blue),
    )
    var defaultButtonTextColor by StringPref(
        "default_button_text_color",
        appContext.colorToHex(R.color.preset_platinum),
    )
    var defaultButtonFocusTextColor by StringPref(
        "default_button_focus_text_color",
        appContext.colorToHex(R.color.preset_deep_slate),
    )
    var defaultButtonBorderColor by StringPref(
        "default_button_border_color",
        appContext.colorToHex(R.color.preset_gunmetal),
    )
    var defaultButtonCornerRadius by FloatPref("default_button_corner_radius", 20f)
    /** The timeout in seconds for loading remote media. */
    var mediaTimeout by IntPref("media_timeout", 10)
    /** The number of retries for failed media loads. */
    var mediaRetries by IntPref("media_retries", 3)
    /** The maximum number of simultaneous popups displayed on screen. */
    var maxPopups by IntPref("max_popups", 1)
    /** The loading & stacking behavior mode (0: Strict Sequential, 1: Fast-Track Dynamic). */
    var popupLoadingBehavior by IntPref("popup_loading_behavior", 0)
    /** Default buffer duration in ms for video playback (0 = ExoPlayer default ~2500ms). */
    var defaultBufferMs by IntPref("default_buffer_ms", 0)

    // Media Display Defaults
    /** Whether to display a loading placeholder while streams are connecting. */
    var mediaLoadingPlaceholder by BooleanPref("media_loading_placeholder", false)
    /** Whether media runs edge-to-edge across the top of the popup by default. */
    var mediaFullBleed by BooleanPref("media_full_bleed", false)
    /** Whether smaller media is upscaled to fill the available area in fullscreen popups by default. */
    var fullscreenUpscale by BooleanPref("fullscreen_upscale", false)
    /** Whether a Live indicator pill is shown on live streams by default. */
    var liveBadge by BooleanPref("live_badge", false)
    /** Default corner position for the Live indicator pill. */
    var liveBadgePosition by StringPref("live_badge_position", "bottom_right")
    /** Default placement for optional header icons (`"left"` or `"right"`). */
    var iconPosition by StringPref("icon_position", PopupProps.DEFAULT_ICON_POSITION)
    /** Default width in pixels for optional header icons (`16..512`). */
    var iconWidth by IntPref("icon_width", PopupProps.DEFAULT_ICON_WIDTH)
    /** Default overlay close button corner position ("none" = disabled). */
    var closeButton by StringPref("close_button", "none")
    /** Default auto-hide delay in seconds for the overlay close button (0 = always visible). */
    var closeButtonTimeout by IntPref("close_button_timeout", PopupProps.CLOSE_BUTTON_TIMEOUT_DEFAULT)
    /** Default diameter in dp for the overlay close button (24..72). */
    var closeButtonSize by IntPref("close_button_size", PopupProps.CLOSE_BUTTON_SIZE_DEFAULT)

    // System / App
    /** Enables additional technical settings and information. */
    var advancedMode by BooleanPref("advanced_mode", false)
    /** The application theme (0: Dark, 1: Light). */
    var appTheme by IntPref("app_theme", 0)

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
    /** Whether the pending update is silent. */
    var pendingUpdateSilent by BooleanPref("pending_update_silent", true)

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
        val maxPopups: Int,
        val popupLoadingBehavior: Int = 0,
        val defaultBufferMs: Int = 0,
        val mediaLoadingPlaceholder: Boolean = false,
        val mediaFullBleed: Boolean = false,
        val fullscreenUpscale: Boolean = false,
        val liveBadge: Boolean = false,
        val liveBadgePosition: String = "bottom_right",
        val iconPosition: String = PopupProps.DEFAULT_ICON_POSITION,
        val iconWidth: Int = PopupProps.DEFAULT_ICON_WIDTH,
        val closeButton: String = "none",
        val closeButtonTimeout: Int = PopupProps.CLOSE_BUTTON_TIMEOUT_DEFAULT,
        val closeButtonSize: Int = PopupProps.CLOSE_BUTTON_SIZE_DEFAULT,
        val appTheme: Int,
        val advancedMode: Boolean,
        val moduleModes: Map<String, Int>,
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
        val moduleModeMedia: Int? = null,
        val webhookHeartbeat: Int = 0
    ) {
        /** Serializes this settings snapshot into a [JSONObject]. */
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
                put("animation", animationType)
                put("animationDuration", animationDuration)
                put("animationExit", animationExit)
                put("mediaTimeout", mediaTimeout)
                put("mediaRetries", mediaRetries)
                put("maxPopups", maxPopups)
                put("popupLoadingBehavior", popupLoadingBehavior)
                put("defaultBufferMs", defaultBufferMs)
                put("mediaLoadingPlaceholder", mediaLoadingPlaceholder)
                put("mediaFullBleed", mediaFullBleed)
                put("fullscreenUpscale", fullscreenUpscale)
                put("liveBadge", liveBadge)
                put("liveBadgePosition", liveBadgePosition)
                put("iconPosition", iconPosition)
                put("iconWidth", iconWidth)
                put("closeButton", closeButton)
                put("closeButtonTimeout", closeButtonTimeout)
                put("closeButtonSize", closeButtonSize)
                put("appTheme", appTheme)
                put("advancedMode", advancedMode)
                put("moduleModes", JSONObject(moduleModes))
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
                put("webhookHeartbeat", webhookHeartbeat)
            }
        }

        /** JSON deserialization helpers for [SettingsData]. */
        companion object {
            private val DEFAULT_SNAPSHOT = SettingsData(
                positionIndex = 0,
                backgroundColor = "",
                backgroundAlpha = DEFAULT_BG_ALPHA,
                titleColor = "",
                titleSize = DEFAULT_TITLE_SIZE,
                messageColor = "",
                messageSize = DEFAULT_MSG_SIZE,
                borderRadius = DEFAULT_RADIUS,
                borderWidth = DEFAULT_BORDER_WIDTH,
                borderColor = "",
                contentPadding = DEFAULT_PADDING,
                titleAlignment = 0,
                messageAlignment = 0,
                mediaPosition = 0,
                animationType = 0,
                animationDuration = DEFAULT_ANIMATION_DURATION_MS,
                animationExit = false,
                mediaTimeout = DEFAULT_MEDIA_TIMEOUT_SEC,
                mediaRetries = DEFAULT_MEDIA_RETRIES,
                maxPopups = 1,
                appTheme = 0,
                advancedMode = false,
                moduleModes = emptyMap(),
                updateChannel = 0,
                updateInterval = DEFAULT_UPDATE_INTERVAL,
                updateNotificationStyle = 1,
                lastUpdateCheck = 0L,
                updateAvailableTag = "",
                updateRepeat = false,
                lastNotifiedTag = "",
                pendingUpdateId = -1L,
                pendingUpdateDigest = "",
                pendingUpdateTagName = "",
                language = "default",
            )

            private fun <T> JSONObject.optParsed(key: String, fallback: T, parser: (Any) -> T?): T {
                return if (has(key) && !isNull(key)) parser(get(key)) ?: fallback else fallback
            }

            /**
             * Parses a JSON object into [SettingsData], preserving any omitted fields from
             * [defaults] when provided (partial merge).
             */
            fun fromJson(jsonStr: String, defaults: SettingsData? = null): SettingsData {
                val j = JSONObject(jsonStr)
                val base = defaults ?: DEFAULT_SNAPSHOT
                val mergedModes = base.moduleModes.toMutableMap()
                j.optJSONObject("moduleModes")?.let { modes ->
                    modes.keys().forEach { key -> mergedModes[key] = modes.getInt(key) }
                }
                return SettingsData(
                    positionIndex = j.optInt("positionIndex", base.positionIndex),
                    backgroundColor = j.optString("backgroundColor", base.backgroundColor),
                    backgroundAlpha = j.optInt("backgroundAlpha", base.backgroundAlpha),
                    titleColor = j.optString("titleColor", base.titleColor),
                    titleSize = j.optDouble("titleSize", base.titleSize.toDouble()).toFloat(),
                    messageColor = j.optString("messageColor", base.messageColor),
                    messageSize = j.optDouble("messageSize", base.messageSize.toDouble()).toFloat(),
                    borderRadius = j.optInt("borderRadius", base.borderRadius),
                    borderWidth = j.optInt("borderWidth", base.borderWidth),
                    borderColor = j.optString("borderColor", base.borderColor),
                    contentPadding = j.optInt("contentPadding", base.contentPadding),
                    titleAlignment = j.optInt("titleAlignment", base.titleAlignment),
                    messageAlignment = j.optInt("messageAlignment", base.messageAlignment),
                    mediaPosition = j.optInt("mediaPosition", base.mediaPosition),
                    animationType = PopupProps.parseAnimationFromJson(j) ?: base.animationType,
                    animationDuration = j.optInt("animationDuration", base.animationDuration),
                    animationExit = j.optBoolean("animationExit", base.animationExit),
                    mediaTimeout = j.optInt("mediaTimeout", base.mediaTimeout),
                    mediaRetries = j.optInt("mediaRetries", base.mediaRetries),
                    maxPopups = j.optInt("maxPopups", base.maxPopups),
                    popupLoadingBehavior = j.optInt("popupLoadingBehavior", base.popupLoadingBehavior),
                    defaultBufferMs = j.optInt("defaultBufferMs", base.defaultBufferMs),
                    mediaLoadingPlaceholder = j.optBoolean("mediaLoadingPlaceholder", base.mediaLoadingPlaceholder),
                    mediaFullBleed = j.optBoolean("mediaFullBleed", base.mediaFullBleed),
                    fullscreenUpscale = j.optBoolean("fullscreenUpscale", base.fullscreenUpscale),
                    liveBadge = j.optBoolean("liveBadge", base.liveBadge),
                    liveBadgePosition = j.optString("liveBadgePosition", base.liveBadgePosition),
                    appTheme = j.optInt("appTheme", base.appTheme),
                    advancedMode = j.optBoolean("advancedMode", base.advancedMode),
                    moduleModes = mergedModes,
                    updateChannel = j.optInt("updateChannel", base.updateChannel),
                    updateInterval = j.optInt("updateInterval", base.updateInterval),
                    updateNotificationStyle = j.optInt("updateNotificationStyle", base.updateNotificationStyle),
                    lastUpdateCheck = j.optLong("lastUpdateCheck", base.lastUpdateCheck),
                    updateAvailableTag = j.optString("updateAvailableTag", base.updateAvailableTag),
                    updateRepeat = j.optBoolean("updateRepeat", base.updateRepeat),
                    lastNotifiedTag = j.optString("lastNotifiedTag", base.lastNotifiedTag),
                    pendingUpdateId = j.optLong("pendingUpdateId", base.pendingUpdateId),
                    pendingUpdateDigest = j.optString("pendingUpdateDigest", base.pendingUpdateDigest),
                    pendingUpdateTagName = j.optString("pendingUpdateTagName", base.pendingUpdateTagName),
                    language = j.optString("language", base.language),
                ).withExtraJsonFields(j, base)
            }

            private fun SettingsData.withExtraJsonFields(j: JSONObject, base: SettingsData): SettingsData = copy(
                iconPosition = j.optParsed("iconPosition", base.iconPosition) {
                    PopupProps.parseIconPosition(it.toString())
                },
                iconWidth = j.optParsed("iconWidth", base.iconWidth) {
                    PopupProps.parseIconWidth(it)
                },
                closeButton = j.optParsed("closeButton", base.closeButton) {
                    PopupProps.parseCloseButton(it) ?: "none"
                },
                closeButtonTimeout = j.optParsed("closeButtonTimeout", base.closeButtonTimeout) {
                    PopupProps.parseCloseButtonTimeout(it) ?: PopupProps.CLOSE_BUTTON_TIMEOUT_DEFAULT
                },
                closeButtonSize = j.optParsed("closeButtonSize", base.closeButtonSize) {
                    PopupProps.parseCloseButtonSize(it) ?: PopupProps.CLOSE_BUTTON_SIZE_DEFAULT
                },
                moduleModeMedia = if (j.has("moduleModeMedia")) {
                    j.getInt("moduleModeMedia")
                } else {
                    base.moduleModeMedia
                },
                webhookHeartbeat = j.optInt("webhookHeartbeat", base.webhookHeartbeat),
            )
        }
    }

    /**
     * Retrieves all current settings as a [SettingsData] object.
     */
    fun getAll(): SettingsData {
        val mm = (appContext as? PiPupApp)?.moduleManager
        val modes = mm?.getAllModules()?.associate { it.id to getModuleMode(it.id).value } ?: emptyMap()

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
            maxPopups = maxPopups,
            popupLoadingBehavior = popupLoadingBehavior,
            defaultBufferMs = defaultBufferMs,
            mediaLoadingPlaceholder = mediaLoadingPlaceholder,
            mediaFullBleed = mediaFullBleed,
            fullscreenUpscale = fullscreenUpscale,
            liveBadge = liveBadge,
            liveBadgePosition = liveBadgePosition,
            iconPosition = iconPosition,
            iconWidth = iconWidth,
            closeButton = closeButton,
            closeButtonTimeout = closeButtonTimeout,
            closeButtonSize = closeButtonSize,
            appTheme = appTheme,
            advancedMode = advancedMode,
            moduleModes = modes,
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
            webhookHeartbeat = webhookHeartbeat
        )
    }

    /**
     * Applies new settings from a [SettingsData] object.
     */
    fun apply(data: SettingsData) {
        positionIndex = data.positionIndex
        backgroundColor = validateHexColor(data.backgroundColor, appContext.colorToHex(R.color.preset_deep_slate))
        backgroundAlpha = data.backgroundAlpha.coerceIn(0, MAX_BG_ALPHA)
        titleColor = validateHexColor(data.titleColor, appContext.colorToHex(R.color.preset_platinum))
        titleSize = data.titleSize.coerceIn(MIN_TITLE_SIZE, MAX_TITLE_SIZE)
        messageColor = validateHexColor(data.messageColor, appContext.colorToHex(R.color.preset_silver))
        messageSize = data.messageSize.coerceIn(MIN_MSG_SIZE, MAX_MSG_SIZE)
        borderRadius = data.borderRadius.coerceIn(0, MAX_BORDER_RADIUS)
        borderWidth = data.borderWidth.coerceIn(0, MAX_BORDER_WIDTH)
        borderColor = validateHexColor(data.borderColor, appContext.colorToHex(R.color.preset_gunmetal))
        contentPadding = data.contentPadding.coerceIn(0, MAX_CONTENT_PADDING)
        titleAlignment = data.titleAlignment.coerceIn(0, 2)
        messageAlignment = data.messageAlignment.coerceIn(0, 2)
        mediaPosition = data.mediaPosition.coerceIn(0, MAX_MEDIA_POSITION)
        animationType = data.animationType.coerceIn(PopupProps.ANIM_NONE, PopupProps.MAX_ANIMATION_TYPE)
        animationDuration = data.animationDuration.coerceIn(0, MAX_ANIMATION_DURATION_MS)
        animationExit = data.animationExit
        mediaTimeout = data.mediaTimeout.coerceIn(1, MAX_MEDIA_TIMEOUT_SEC)
        mediaRetries = data.mediaRetries.coerceIn(0, MAX_MEDIA_RETRIES)
        maxPopups = data.maxPopups.coerceIn(1, MAX_POPUPS_LIMIT)
        popupLoadingBehavior = data.popupLoadingBehavior.coerceIn(0, 1)
        defaultBufferMs = data.defaultBufferMs.coerceIn(0, MAX_BUFFER_MS)
        mediaLoadingPlaceholder = data.mediaLoadingPlaceholder
        mediaFullBleed = data.mediaFullBleed
        fullscreenUpscale = data.fullscreenUpscale
        liveBadge = data.liveBadge
        liveBadgePosition = data.liveBadgePosition.takeIf {
            it in listOf("top_left", "top_right", "bottom_left", "bottom_right")
        } ?: "bottom_right"
        iconPosition = PopupProps.parseIconPosition(data.iconPosition) ?: PopupProps.DEFAULT_ICON_POSITION
        iconWidth = data.iconWidth.coerceIn(PopupProps.ICON_WIDTH_MIN, PopupProps.ICON_WIDTH_MAX)
        closeButton = PopupProps.normalizeCloseButtonPosition(data.closeButton)
        closeButtonTimeout = data.closeButtonTimeout.coerceIn(0, PopupProps.CLOSE_BUTTON_TIMEOUT_MAX)
        closeButtonSize = data.closeButtonSize.coerceIn(
            PopupProps.CLOSE_BUTTON_SIZE_MIN,
            PopupProps.CLOSE_BUTTON_SIZE_MAX
        )
        applySystemAndUpdateSettings(data)
        cachedFullBgColor = null
    }

    private fun applySystemAndUpdateSettings(data: SettingsData) {
        appTheme = data.appTheme.coerceIn(0, 1)
        advancedMode = data.advancedMode

        val mm = (appContext as? PiPupApp)?.moduleManager
        data.moduleModes.forEach { (id, modeValue) ->
            ModuleMode.entries.find { it.value == modeValue }?.let { mode ->
                setModuleMode(id, mode)
                mm?.updateModuleState(id, mode)
            }
        }

        updateChannel = data.updateChannel.coerceIn(-1, 1)
        updateInterval = data.updateInterval.coerceIn(0, MAX_UPDATE_INTERVAL)
        updateNotificationStyle = data.updateNotificationStyle.coerceIn(0, 2)
        lastUpdateCheck = data.lastUpdateCheck
        updateAvailableTag = data.updateAvailableTag
        updateRepeat = data.updateRepeat
        lastNotifiedTag = data.lastNotifiedTag
        pendingUpdateId = data.pendingUpdateId
        pendingUpdateDigest = data.pendingUpdateDigest
        pendingUpdateTagName = data.pendingUpdateTagName
        language = data.language
        webhookHeartbeat = normalizeWebhookHeartbeat(data.webhookHeartbeat) ?: 0
    }

    private fun validateHexColor(hex: String, fallback: String): String {
        return try {
            val clean = if (hex.startsWith("#")) hex else "#$hex"
            clean.toColorInt()
            if (clean.length != HEX_LEN_SHORT && clean.length != HEX_LEN_RGB && clean.length != HEX_LEN_ARGB) {
                return fallback
            }
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

        val clean = backgroundColor.replace("#", "").let { if (it.length == HEX_DIGITS_ARGB) it.substring(2) else it }
        val alphaHex = String.format(Locale.US, "%02X", backgroundAlpha)
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
        val (resolvedBorderWidth, resolvedBorderColor, resolvedBorderRadius) = resolveBorderDefaults(props)
        return props.copy(
            backgroundColor = props.backgroundColor.orIfDefault("#CC000000", getFullBackgroundColor()),
            borderColor = resolvedBorderColor,
            borderRadius = resolvedBorderRadius,
            borderWidth = resolvedBorderWidth,
            contentPadding = props.contentPadding ?: contentPadding,
            titleColor = props.titleColor.orIfDefault("#FFFFFF", titleColor),
            titleSize = props.titleSize.orIfDefault(PopupProps.DEFAULT_TITLE_SIZE_SP, titleSize),
            messageColor = props.messageColor.orIfDefault("#FFFFFF", messageColor),
            messageSize = props.messageSize.orIfDefault(PopupProps.DEFAULT_MESSAGE_SIZE_SP, messageSize),
            titleAlignment = props.titleAlignment.orIfDefault(0, titleAlignment),
            messageAlignment = props.messageAlignment.orIfDefault(0, messageAlignment),
            media = applyMediaDefaults(props.media),
            mediaPosition = props.mediaPosition ?: mediaPosition,
            animationType = props.animationType ?: animationType,
            animationDuration = props.animationDuration.orIfDefault(
                PopupProps.DEFAULT_ANIMATION_DURATION_MS,
                animationDuration
            ),
            animationExit = props.animationExit || animationExit,
        ).applyOverlayDefaults(props)
    }

    private fun resolveBorderDefaults(props: PopupProps): Triple<Int, String, Int> {
        val urgencyPreset = props.urgency?.let { PopupProps.URGENCY_PRESETS[it] }
        val resolvedWidth = props.borderWidth
            ?: urgencyPreset?.first
            ?: if (props.borderColor != null && borderWidth <= 0) {
                PopupProps.DEFAULT_EXPLICIT_BORDER_WIDTH
            } else {
                borderWidth
            }
        val urgencyHex = urgencyPreset?.second?.let { colorRes ->
            appContext.getLocalizedContext(language, appTheme).colorToHex(colorRes)
        }
        val resolvedColor = props.borderColor ?: urgencyHex ?: borderColor
        val resolvedRadius = props.borderRadius
            ?: if (urgencyPreset != null && borderRadius <= 0) {
                PopupProps.DEFAULT_URGENCY_CORNER_RADIUS
            } else {
                borderRadius
            }
        return Triple(resolvedWidth, resolvedColor, resolvedRadius)
    }

    private fun PopupProps.applyOverlayDefaults(original: PopupProps): PopupProps = copy(
        mediaFullBleed = original.mediaFullBleed ?: mediaFullBleed,
        fullscreenUpscale = original.fullscreenUpscale ?: fullscreenUpscale,
        liveBadge = original.liveBadge ?: liveBadge,
        liveBadgePosition = original.liveBadgePosition ?: liveBadgePosition,
        iconPosition = original.iconPosition ?: iconPosition,
        iconWidth = original.iconWidth ?: iconWidth,
        closeButton = original.closeButton ?: closeButton,
        closeButtonTimeout = original.closeButtonTimeout ?: closeButtonTimeout,
        closeButtonSize = original.closeButtonSize ?: closeButtonSize,
    )

    private fun <T> T.orIfDefault(sentinel: T, fallback: T): T = if (this == sentinel) fallback else this

    private fun applyMediaDefaults(media: PopupProps.Media?): PopupProps.Media? =
        if (media is PopupProps.Media.Video && media.bufferMs == null && defaultBufferMs > 0) {
            media.copy(bufferMs = defaultBufferMs.coerceIn(PopupProps.BUFFER_MS_MIN, PopupProps.BUFFER_MS_MAX))
        } else {
            media
        }

    private class StringPref(
        val key: String,
        val defaultValue: String,
        val onSet: (() -> Unit)? = null,
    ) : ReadWriteProperty<AppSettings, String> {
        override fun getValue(thisRef: AppSettings, property: KProperty<*>): String {
            return thisRef.prefs.getString(key, defaultValue) ?: defaultValue
        }
        override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: String) {
            thisRef.prefs.edit { putString(key, value) }
            onSet?.invoke()
        }
    }

    private class ColorPref(
        val key: String,
        val defaultValueRes: Int,
        val onSet: (() -> Unit)? = null,
    ) : ReadWriteProperty<AppSettings, String> {
        override fun getValue(thisRef: AppSettings, property: KProperty<*>): String {
            val default = thisRef.appContext.colorToHex(defaultValueRes)
            return thisRef.prefs.getString(key, default) ?: default
        }
        override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: String) {
            thisRef.prefs.edit { putString(key, value) }
            onSet?.invoke()
        }
    }

    private class IntPref(
        val key: String,
        val defaultValue: Int,
        val onSet: (() -> Unit)? = null,
    ) : ReadWriteProperty<AppSettings, Int> {
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

    /** Default settings constants and normalization helpers. */
    companion object {
        const val PREFS_NAME = "pipup_settings"
        const val DEFAULT_BG_ALPHA = 225
        const val DEFAULT_TITLE_SIZE = 22f
        const val DEFAULT_MSG_SIZE = 16f
        const val DEFAULT_RADIUS = 16
        const val DEFAULT_BORDER_WIDTH = 0
        const val DEFAULT_PADDING = 20
        const val DEFAULT_ANIMATION_DURATION_MS = 500
        const val DEFAULT_MEDIA_TIMEOUT_SEC = 10
        const val DEFAULT_MEDIA_RETRIES = 3
        const val DEFAULT_UPDATE_INTERVAL = 4
        /** Default update release source identifier. */
        const val DEFAULT_UPDATE_SOURCE = "github"
        const val MAX_POPUPS_LIMIT = 5
        const val MIN_WEBHOOK_HEARTBEAT_SEC = 10
        const val MAX_WEBHOOK_HEARTBEAT_SEC = 86_400

        private const val MAX_BG_ALPHA = 255
        private const val MIN_TITLE_SIZE = 10f
        private const val MAX_TITLE_SIZE = 100f
        private const val MIN_MSG_SIZE = 8f
        private const val MAX_MSG_SIZE = 80f
        private const val MAX_BORDER_RADIUS = 200
        private const val MAX_BORDER_WIDTH = 50
        private const val MAX_CONTENT_PADDING = 200
        private const val MAX_MEDIA_POSITION = 3
        private const val MAX_ANIMATION_DURATION_MS = 5000
        private const val MAX_MEDIA_TIMEOUT_SEC = 60
        private const val MAX_MEDIA_RETRIES = 10
        private const val MAX_BUFFER_MS = 10_000
        private const val MAX_UPDATE_INTERVAL = 4
        private const val HEX_LEN_SHORT = 4
        private const val HEX_LEN_RGB = 7
        private const val HEX_LEN_ARGB = 9
        private const val HEX_DIGITS_ARGB = 8

        /**
         * Normalizes a webhook heartbeat interval in seconds (`0` disables the heartbeat; positive
         * values are clamped to `[MIN_WEBHOOK_HEARTBEAT_SEC, MAX_WEBHOOK_HEARTBEAT_SEC]`).
         * Returns `null` when [seconds] is negative.
         */
        fun normalizeWebhookHeartbeat(seconds: Int): Int? = when {
            seconds < 0 -> null
            seconds == 0 -> 0
            else -> seconds.coerceIn(MIN_WEBHOOK_HEARTBEAT_SEC, MAX_WEBHOOK_HEARTBEAT_SEC)
        }
    }
}
