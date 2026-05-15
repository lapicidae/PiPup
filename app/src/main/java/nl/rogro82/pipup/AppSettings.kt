package nl.rogro82.pipup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists global notification styling and behavior settings.
 * Optimized with in-memory caching to avoid synchronous disk reads during runtime.
 */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pipup_settings", Context.MODE_PRIVATE)

    // In-memory cache fields
    private var _positionIndex: Int
    private var _backgroundColor: String
    private var _backgroundAlpha: Int
    private var _titleColor: String
    private var _titleSize: Float
    private var _messageColor: String
    private var _messageSize: Float
    private var _borderRadius: Int
    private var _borderWidth: Int
    private var _borderColor: String
    private var _contentPadding: Int
    private var _titleAlignment: Int
    private var _messageAlignment: Int

    init {
        // Initialize cache from disk once
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
    }

    companion object {
        const val DEFAULT_BG_COLOR = "#1C1B1F" // M3 Neutral 10
        const val DEFAULT_BG_ALPHA = 204
        const val DEFAULT_TITLE_COLOR = "#FFFBFE" // M3 Neutral 99
        const val DEFAULT_TITLE_SIZE = 24f
        const val DEFAULT_MSG_COLOR = "#F4EFF4" // M3 Neutral variant 90
        const val DEFAULT_MSG_SIZE = 16f
        const val DEFAULT_RADIUS = 0
        const val DEFAULT_BORDER_WIDTH = 0
        const val DEFAULT_BORDER_COLOR = "#625B71" // M3 Secondary
        const val DEFAULT_PADDING = 16
    }

    var positionIndex: Int
        get() = _positionIndex
        set(value) {
            _positionIndex = value
            prefs.edit { putInt("position_index", value) }
        }

    var backgroundColor: String
        get() = _backgroundColor
        set(value) {
            _backgroundColor = value
            prefs.edit { putString("background_color", value) }
        }

    var backgroundAlpha: Int
        get() = _backgroundAlpha
        set(value) {
            _backgroundAlpha = value
            prefs.edit { putInt("background_alpha", value) }
        }

    var titleColor: String
        get() = _titleColor
        set(value) {
            _titleColor = value
            prefs.edit { putString("title_color", value) }
        }

    var titleSize: Float
        get() = _titleSize
        set(value) {
            _titleSize = value
            prefs.edit { putFloat("title_size", value) }
        }

    var messageColor: String
        get() = _messageColor
        set(value) {
            _messageColor = value
            prefs.edit { putString("message_color", value) }
        }

    var messageSize: Float
        get() = _messageSize
        set(value) {
            _messageSize = value
            prefs.edit { putFloat("message_size", value) }
        }

    var borderRadius: Int
        get() = _borderRadius
        set(value) {
            _borderRadius = value
            prefs.edit { putInt("border_radius", value) }
        }

    var borderWidth: Int
        get() = _borderWidth
        set(value) {
            _borderWidth = value
            prefs.edit { putInt("border_width", value) }
        }

    var borderColor: String
        get() = _borderColor
        set(value) {
            _borderColor = value
            prefs.edit { putString("border_color", value) }
        }

    var contentPadding: Int
        get() = _contentPadding
        set(value) {
            _contentPadding = value
            prefs.edit { putInt("content_padding", value) }
        }

    var titleAlignment: Int
        get() = _titleAlignment
        set(value) {
            _titleAlignment = value
            prefs.edit { putInt("title_alignment", value) }
        }

    var messageAlignment: Int
        get() = _messageAlignment
        set(value) {
            _messageAlignment = value
            prefs.edit { putInt("message_alignment", value) }
        }

    fun getFullBackgroundColor(): String {
        val alphaHex = String.format("%02X", _backgroundAlpha)
        val cleanColor = _backgroundColor.replace("#", "")
        return "#$alphaHex$cleanColor"
    }

    fun resetToDefaults() {
        prefs.edit { clear() }
        
        _positionIndex = 0
        _backgroundColor = DEFAULT_BG_COLOR
        _backgroundAlpha = DEFAULT_BG_ALPHA
        _titleColor = DEFAULT_TITLE_COLOR
        _titleSize = DEFAULT_TITLE_SIZE
        _messageColor = DEFAULT_MSG_COLOR
        _messageSize = DEFAULT_MSG_SIZE
        _borderRadius = DEFAULT_RADIUS
        _borderWidth = DEFAULT_BORDER_WIDTH
        _borderColor = DEFAULT_BORDER_COLOR
        _contentPadding = DEFAULT_PADDING
        _titleAlignment = 0
        _messageAlignment = 0
        
        // Re-write defaults to disk
        prefs.edit {
            putString("background_color", DEFAULT_BG_COLOR)
            putInt("background_alpha", DEFAULT_BG_ALPHA)
            putString("title_color", DEFAULT_TITLE_COLOR)
            putFloat("title_size", DEFAULT_TITLE_SIZE)
            putString("message_color", DEFAULT_MSG_COLOR)
            putFloat("message_size", DEFAULT_MSG_SIZE)
            putInt("border_radius", DEFAULT_RADIUS)
            putInt("border_width", DEFAULT_BORDER_WIDTH)
            putString("border_color", DEFAULT_BORDER_COLOR)
            putInt("content_padding", DEFAULT_PADDING)
            putInt("title_alignment", 0)
            putInt("message_alignment", 0)
        }
    }
}
