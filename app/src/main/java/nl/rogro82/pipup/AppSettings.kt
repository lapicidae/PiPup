package nl.rogro82.pipup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AppSettings(context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs = context.getSharedPreferences("pipup_settings", Context.MODE_PRIVATE)

    @Volatile
    private var isUpdating = false

    private var _positionIndex = 0
    private var _backgroundColor = DEFAULT_BG_COLOR
    private var _backgroundAlpha = DEFAULT_BG_ALPHA
    private var _titleColor = DEFAULT_TITLE_COLOR
    private var _titleSize = DEFAULT_TITLE_SIZE
    private var _messageColor = DEFAULT_MSG_COLOR
    private var _messageSize = DEFAULT_MSG_SIZE
    private var _borderRadius = DEFAULT_RADIUS
    private var _borderWidth = DEFAULT_BORDER_WIDTH
    private var _borderColor = DEFAULT_BORDER_COLOR
    private var _contentPadding = DEFAULT_PADDING
    private var _titleAlignment = 0
    private var _messageAlignment = 0
    private var _mediaPosition = 0
    private var _dismissBatteryOptimization = false
    private var _advancedMode = false
    private var _appTheme = 0 // 0 = Dark, 1 = Light

    init {
        reload()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (!isUpdating) {
            reload()
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
        val appTheme: Int,
        val advancedMode: Boolean
    )

    fun getAll(): SettingsData {
        return SettingsData(
            _positionIndex, _backgroundColor, _backgroundAlpha, _titleColor, _titleSize,
            _messageColor, _messageSize, _borderRadius, _borderWidth, _borderColor,
            _contentPadding, _titleAlignment, _messageAlignment, _mediaPosition, _appTheme,
            _advancedMode
        )
    }

    /**
     * Atomically applies a full set of settings and persists them synchronously.
     * This ensures that the local memory cache and SharedPreferences are in sync
     * before the method returns, preventing race conditions during UI reloads.
     */
    fun apply(data: SettingsData) {
        isUpdating = true
        try {
            _positionIndex = data.positionIndex
            _backgroundColor = data.backgroundColor
            _backgroundAlpha = data.backgroundAlpha
            _titleColor = data.titleColor
            _titleSize = data.titleSize
            _messageColor = data.messageColor
            _messageSize = data.messageSize
            _borderRadius = data.borderRadius
            _borderWidth = data.borderWidth
            _borderColor = data.borderColor
            _contentPadding = data.contentPadding
            _titleAlignment = data.titleAlignment
            _messageAlignment = data.messageAlignment
            _mediaPosition = data.mediaPosition
            _appTheme = data.appTheme
            _advancedMode = data.advancedMode
            
            // Note: _dismissBatteryOptimization is preserved as it is not part of SettingsData
            save(sync = true)
        } finally {
            isUpdating = false
        }
    }

    /**
     * Persists current in-memory settings to disk.
     * @param sync If true, uses commit() to write synchronously. Otherwise uses apply().
     */
    fun save(sync: Boolean = false) {
        // Prevent re-entry from listeners if we are already in an update block
        val alreadyUpdating = isUpdating
        if (!alreadyUpdating) isUpdating = true
        
        try {
            prefs.edit(commit = sync) {
                putInt("position_index", _positionIndex)
                putString("background_color", _backgroundColor)
                putInt("background_alpha", _backgroundAlpha)
                putString("title_color", _titleColor)
                putFloat("title_size", _titleSize)
                putString("message_color", _messageColor)
                putFloat("message_size", _messageSize)
                putInt("border_radius", _borderRadius)
                putInt("border_width", _borderWidth)
                putString("border_color", _borderColor)
                putInt("content_padding", _contentPadding)
                putInt("title_alignment", _titleAlignment)
                putInt("message_alignment", _messageAlignment)
                putInt("media_position", _mediaPosition)
                putInt("app_theme", _appTheme)
                putBoolean("advanced_mode", _advancedMode)
                putBoolean("dismiss_battery_optimization", _dismissBatteryOptimization)
            }
        } finally {
            if (!alreadyUpdating) isUpdating = false
        }
    }

    /**
     * Refreshes the in-memory cache from the persistent SharedPreferences.
     */
    fun reload() {
        _positionIndex = prefs.getInt("position_index", 0)
        _backgroundColor = prefs.getString("background_color", DEFAULT_BG_COLOR) ?: DEFAULT_BG_COLOR
        _backgroundAlpha = prefs.getInt("background_alpha", DEFAULT_BG_ALPHA)
        _titleColor = prefs.getString("title_color", DEFAULT_TITLE_COLOR) ?: DEFAULT_TITLE_COLOR
        _titleSize = prefs.getFloat("title_size", DEFAULT_TITLE_SIZE)
        _messageColor = prefs.getString("message_color", DEFAULT_MSG_COLOR) ?: DEFAULT_MSG_COLOR
        _messageSize = prefs.getFloat("message_size", DEFAULT_MSG_SIZE)
        _borderRadius = prefs.getInt("border_radius", DEFAULT_RADIUS)
        _borderWidth = prefs.getInt("border_width", DEFAULT_BORDER_WIDTH)
        _borderColor = prefs.getString("border_color", DEFAULT_BORDER_COLOR) ?: DEFAULT_BORDER_COLOR
        _contentPadding = prefs.getInt("content_padding", DEFAULT_PADDING)
        _titleAlignment = prefs.getInt("title_alignment", 0)
        _messageAlignment = prefs.getInt("message_alignment", 0)
        _mediaPosition = prefs.getInt("media_position", 0)
        _dismissBatteryOptimization = prefs.getBoolean("dismiss_battery_optimization", false)
        _appTheme = prefs.getInt("app_theme", 0)
        _advancedMode = prefs.getBoolean("advanced_mode", false)
    }

    companion object {
        const val DEFAULT_BG_COLOR = "#0F1417"
        const val DEFAULT_BG_ALPHA = 225
        const val DEFAULT_TITLE_COLOR = "#DFE3E7"
        const val DEFAULT_TITLE_SIZE = 22f
        const val DEFAULT_MSG_COLOR = "#C0C7CD"
        const val DEFAULT_MSG_SIZE = 16f
        const val DEFAULT_RADIUS = 16
        const val DEFAULT_BORDER_WIDTH = 0
        const val DEFAULT_BORDER_COLOR = "#625B71"
        const val DEFAULT_PADDING = 20
    }

    // High-performance getters and setters (Memory Only)
    
    var positionIndex: Int
        get() = _positionIndex
        set(value) { _positionIndex = value }

    var backgroundColor: String
        get() = _backgroundColor
        set(value) { _backgroundColor = value }

    var backgroundAlpha: Int
        get() = _backgroundAlpha
        set(value) { _backgroundAlpha = value }

    var titleColor: String
        get() = _titleColor
        set(value) { _titleColor = value }

    var titleSize: Float
        get() = _titleSize
        set(value) { _titleSize = value }

    var messageColor: String
        get() = _messageColor
        set(value) { _messageColor = value }

    var messageSize: Float
        get() = _messageSize
        set(value) { _messageSize = value }

    var borderRadius: Int
        get() = _borderRadius
        set(value) { _borderRadius = value }

    var borderWidth: Int
        get() = _borderWidth
        set(value) { _borderWidth = value }

    var borderColor: String
        get() = _borderColor
        set(value) { _borderColor = value }

    var contentPadding: Int
        get() = _contentPadding
        set(value) { _contentPadding = value }

    var titleAlignment: Int
        get() = _titleAlignment
        set(value) { _titleAlignment = value }

    var messageAlignment: Int
        get() = _messageAlignment
        set(value) { _messageAlignment = value }

    var mediaPosition: Int
        get() = _mediaPosition
        set(value) { _mediaPosition = value }

    var dismissBatteryOptimization: Boolean
        get() = _dismissBatteryOptimization
        set(value) { 
            _dismissBatteryOptimization = value
            // Battery optimization dismissal is usually a one-time thing, save immediately
            prefs.edit { putBoolean("dismiss_battery_optimization", value) }
        }
        
    var advancedMode: Boolean
        get() = _advancedMode
        set(value) { _advancedMode = value }

    var appTheme: Int
        get() = _appTheme
        set(value) { _appTheme = value }

    fun getFullBackgroundColor(): String {
        val clean = backgroundColor.replace("#", "").let { if (it.length == 8) it.substring(2) else it }
        val alphaHex = String.format("%02X", backgroundAlpha)
        return "#$alphaHex$clean"
    }

    fun resetToDefaults() {
        prefs.edit { clear() }
        _dismissBatteryOptimization = false
        reload()
    }
}
