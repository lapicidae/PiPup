package nl.rogro82.pipup

import android.graphics.Color
import androidx.core.graphics.toColorInt
import org.json.JSONObject

/**
 * Data class representing all properties of a notification popup.
 * Optimized for RAM by using native org.json for parsing instead of Jackson.
 */
data class PopupProps(
    val duration: Int = 10,
    val id: String? = null,
    val position: Int = 0,
    val title: String? = null,
    val titleSize: Float = 24f,
    val titleColor: String = DEFAULT_TEXT_COLOR,
    val message: String? = null,
    val messageSize: Float = 16f,
    val messageColor: String = DEFAULT_TEXT_COLOR,
    val backgroundColor: String = DEFAULT_BG_COLOR,

    val media: Media? = null,
    val mediaPosition: Int? = null, // 0: Top, 1: Bottom, 2: Left, 3: Right
    val animationType: Int = 0, // 0: None, 1: Fade, 2: Slide
    val animationDuration: Int = 500,
    val animationExit: Boolean = false,
    val overwrite: Boolean = false,

    // Advanced Styling (from Settings)
    val borderRadius: Int = 0,
    val borderWidth: Int = 0,
    val borderColor: String = DEFAULT_BORDER_COLOR,

    // Internal/Extra
    val contentPadding: Int? = null,
    val titleAlignment: Int = 0, // 0: Left, 1: Center, 2: Right
    val messageAlignment: Int = 0,
    val image: String? = null,
    val imageWidth: Int? = null,
    val cache: Boolean = true,
    val scale: Boolean = true,

    // Buttons & Callbacks
    val buttons: List<Button> = emptyList(),
    val callback: String? = null,
    val buttonSize: Float? = null,
    val buttonColor: String? = null,
    val buttonFocusColor: String? = null,
    val buttonTextColor: String? = null,
    val buttonFocusTextColor: String? = null,
    val buttonBorderColor: String? = null,
    val buttonCornerRadius: Float? = null,
    val buttonsInline: Boolean = false,
    val isPreview: Boolean = false,
    val isInternalUpdateAction: Boolean = false,
    val stack: String? = null,
    val liveBadge: Boolean? = null,
    val liveBadgeColor: String? = null,
    val liveBadgePosition: String? = null,
    val mediaFullBleed: Boolean? = null,
    val fullscreen: Boolean = false,
    val fullscreenUpscale: Boolean? = null,
    val dismissOnBack: Boolean = false,
    val closeButton: String? = null,
    val closeButtonTimeout: Int? = null,
    val closeButtonSize: Int? = null
) {
    /**
     * Whether this popup covers the entire display (including system bars and cutouts).
     */
    val coversScreen: Boolean
        get() = fullscreen

    /**
     * Whether this popup displays an overlaid close button in a corner.
     */
    val hasCloseButton: Boolean
        get() = !closeButton.isNullOrBlank() && closeButton != "none"

    /**
     * Whether this popup requires overlay input focus (for interactive buttons,
     * an overlaid close button, or hardware Back key dismissal via [dismissOnBack]).
     */
    val requiresInputFocus: Boolean
        get() = buttons.isNotEmpty() || dismissOnBack || hasCloseButton

    /**
     * Whether pressing the hardware Back key should dismiss this popup.
     * Popups with only [closeButton] require explicit [dismissOnBack] to close on Back.
     */
    val dismissibleByBack: Boolean
        get() = buttons.isNotEmpty() || dismissOnBack

    /**
     * Whether this popup contains a media object or legacy image URI.
     */
    val hasMedia: Boolean
        get() = media != null || !image.isNullOrEmpty()

    /**
     * Effective stacking axis direction ("vertical" or "horizontal"), or null if stacking is not set
     * or disabled by [coversScreen].
     */
    val effectiveStack: String?
        get() = if (coversScreen) null else stack

    /**
     * Resolves the effective [Media] object, wrapping legacy top-level [image]/[imageWidth] fields
     * when [media] is null.
     */
    fun resolveEffectiveMedia(): Media? {
        return media ?: image?.let {
            val explicitW = imageWidth?.takeIf { w -> w > 0 }
            Media.Image(
                uri = it,
                width = explicitW ?: DEFAULT_MEDIA_WIDTH,
                cache = cache,
                scale = scale,
                explicitWidth = explicitW != null
            )
        }
    }

    @Transient
    var measuredSize: Pair<Int, Int>? = null

    /** Action button definition displayed at the bottom of a popup. */
    data class Button(val id: String, val label: String)

    /** Constants, defaults, and JSON/field parsing helpers for [PopupProps]. */
    companion object {
        private const val DEFAULT_TEXT_COLOR = "#FFFFFF"
        private const val DEFAULT_BG_COLOR = "#CC000000"
        private const val DEFAULT_BORDER_COLOR = "#00000000"
        /** Default popup display duration in seconds. */
        const val DEFAULT_DURATION_SEC = 10
        /** Default title font size in sp. */
        const val DEFAULT_TITLE_SIZE_SP = 24f
        /** Default message font size in sp. */
        const val DEFAULT_MESSAGE_SIZE_SP = 16f
        /** Default popup entrance/exit animation duration in milliseconds. */
        const val DEFAULT_ANIMATION_DURATION_MS = 500
        /** Default media width in pixels for image and video payloads. */
        const val DEFAULT_MEDIA_WIDTH = 480
        /** Default media width in pixels for web and WHEP payloads. */
        const val DEFAULT_WEB_WIDTH = 640
        /** Minimum allowed video buffer duration in milliseconds. */
        const val BUFFER_MS_MIN = 100
        /** Maximum allowed video buffer duration in milliseconds. */
        const val BUFFER_MS_MAX = 10_000
        /** Numerator of standard 16:9 media aspect ratio height calculation. */
        const val ASPECT_16_9_HEIGHT = 9
        /** Denominator of standard 16:9 media aspect ratio height calculation. */
        const val ASPECT_16_9_WIDTH = 16
        /** Minimum text/button size in sp supported by settings sliders. */
        const val TEXT_SIZE_UI_MIN = 10
        /** SeekBar span above [TEXT_SIZE_UI_MIN] for text/button size sliders. */
        const val TEXT_SIZE_UI_SPAN = 30
        private const val MAX_BUTTONS = 5
        private const val MAX_BUTTON_LABEL_LENGTH = 30
        private const val BUTTON_SIZE_MIN_SP = 10f
        private const val BUTTON_SIZE_MAX_SP = 40f
        private const val BUTTON_CORNER_RADIUS_MAX_DP = 50f
        private const val POS_TOP_RIGHT = 0
        private const val POS_TOP_LEFT = 1
        private const val POS_BOTTOM_RIGHT = 2
        private const val POS_BOTTOM_LEFT = 3
        private const val POS_CENTER = 4
        private const val POS_TOP_CENTER = 5
        private const val POS_BOTTOM_CENTER = 6
        private const val POS_CENTER_LEFT = 7
        private const val POS_CENTER_RIGHT = 8
        /** Maximum allowed auto-hide delay in seconds for the overlay close button. */
        const val CLOSE_BUTTON_TIMEOUT_MAX = 300
        /** Maximum auto-hide delay in seconds exposed by the Button Settings slider. */
        const val CLOSE_BUTTON_TIMEOUT_UI_MAX = 30
        /** Default auto-hide delay in seconds for the overlay close button. */
        const val CLOSE_BUTTON_TIMEOUT_DEFAULT = 5
        /** Minimum allowed diameter in dp for the overlay close button. */
        const val CLOSE_BUTTON_SIZE_MIN = 24
        /** Maximum allowed diameter in dp for the overlay close button. */
        const val CLOSE_BUTTON_SIZE_MAX = 72
        /** Default diameter in dp for the overlay close button. */
        const val CLOSE_BUTTON_SIZE_DEFAULT = 32

        /**
         * Manual JSON parser for PopupProps to avoid reflection overhead.
         */
        fun fromJson(jsonStr: String): PopupProps {
            val j = JSONObject(jsonStr)
            return PopupProps(
                duration = j.optInt("duration", DEFAULT_DURATION_SEC),
                id = JsonFields.optNullableString(j, "id"),
                position = parsePositionIndex(JsonFields.optRaw(j, "position")),
                title = JsonFields.optNullableString(j, "title"),
                titleSize = j.optDouble("titleSize", DEFAULT_TITLE_SIZE_SP.toDouble()).toFloat(),
                titleColor = j.optString("titleColor", DEFAULT_TEXT_COLOR) ?: DEFAULT_TEXT_COLOR,
                message = JsonFields.optNullableString(j, "message"),
                messageSize = j.optDouble("messageSize", DEFAULT_MESSAGE_SIZE_SP.toDouble()).toFloat(),
                messageColor = j.optString("messageColor", DEFAULT_TEXT_COLOR) ?: DEFAULT_TEXT_COLOR,
                backgroundColor = j.optString("backgroundColor", DEFAULT_BG_COLOR) ?: DEFAULT_BG_COLOR,
                media = MediaParser.parseMedia(j),
                mediaPosition = JsonFields.optNullableInt(j, "mediaPosition"),
                animationType = j.optInt("animationType", 0),
                animationDuration = j.optInt("animationDuration", DEFAULT_ANIMATION_DURATION_MS),
                animationExit = j.optBoolean("animationExit", false),
                overwrite = j.optBoolean("overwrite", false),
                borderRadius = JsonFields.optDoubleAsInt(j, "borderRadius", "cornerRadius"),
                borderWidth = j.optInt("borderWidth", 0),
                borderColor = j.optString("borderColor", DEFAULT_BORDER_COLOR) ?: DEFAULT_BORDER_COLOR,
                contentPadding = JsonFields.optNullableInt(j, "contentPadding")
                    ?: JsonFields.optNullableInt(j, "padding"),
                titleAlignment = j.optInt("titleAlignment", 0),
                messageAlignment = j.optInt("messageAlignment", 0),
                image = JsonFields.optNullableString(j, "image"),
                imageWidth = JsonFields.optNullableInt(j, "imageWidth"),
                cache = j.optBoolean("cache", true),
                scale = j.optBoolean("scale", true),
                buttons = MediaParser.parseButtons(j),
                callback = JsonFields.optNullableString(j, "callback"),
                buttonSize = JsonFields.optNullableFloat(j, "buttonSize")
                    ?.coerceIn(BUTTON_SIZE_MIN_SP, BUTTON_SIZE_MAX_SP),
                buttonColor = JsonFields.optNullableString(j, "buttonColor"),
                buttonFocusColor = JsonFields.optNullableString(j, "buttonFocusColor"),
                buttonTextColor = JsonFields.optNullableString(j, "buttonTextColor"),
                buttonFocusTextColor = JsonFields.optNullableString(j, "buttonFocusTextColor"),
                buttonBorderColor = JsonFields.optNullableString(j, "buttonBorderColor"),
                buttonCornerRadius = JsonFields.optNullableFloat(j, "buttonCornerRadius")
                    ?.coerceIn(0f, BUTTON_CORNER_RADIUS_MAX_DP),
                buttonsInline = j.optBoolean("buttonsInline", false),
                isInternalUpdateAction = false,
                stack = JsonFields.optNullableString(j, "stack")?.trim()?.lowercase()
                    ?.takeIf { it == "vertical" || it == "horizontal" },
                liveBadge = JsonFields.optNullableBoolean(j, "liveBadge"),
                liveBadgeColor = JsonFields.optNullableString(j, "liveBadgeColor"),
                liveBadgePosition = JsonFields.optNullableString(j, "liveBadgePosition"),
                mediaFullBleed = JsonFields.optNullableBoolean(j, "mediaFullBleed"),
                fullscreen = j.optBoolean("fullscreen", false),
                fullscreenUpscale = JsonFields.optNullableBoolean(j, "fullscreenUpscale")
                    ?: JsonFields.optNullableBoolean(j, "upscale"),
                dismissOnBack = j.optBoolean("dismissOnBack", false),
                closeButton = JsonFields.optRaw(j, "closeButton")?.let { parseCloseButton(it) },
                closeButtonTimeout = JsonFields.optRaw(j, "closeButtonTimeout")?.let { parseCloseButtonTimeout(it) },
                closeButtonSize = JsonFields.optRaw(j, "closeButtonSize")?.let { parseCloseButtonSize(it) },
            )
        }

        private object JsonFields {
            fun optRaw(obj: JSONObject, key: String): Any? =
                if (obj.has(key) && !obj.isNull(key)) obj.get(key) else null

            fun optNullableString(obj: JSONObject, key: String): String? =
                if (obj.has(key) && !obj.isNull(key)) obj.getString(key) else null

            fun optNullableInt(obj: JSONObject, key: String): Int? =
                if (obj.has(key) && !obj.isNull(key)) obj.getInt(key) else null

            fun optNullableFloat(obj: JSONObject, key: String): Float? =
                if (obj.has(key) && !obj.isNull(key)) obj.getDouble(key).toFloat() else null

            fun optNullableBoolean(obj: JSONObject, key: String): Boolean? =
                if (obj.has(key) && !obj.isNull(key)) obj.getBoolean(key) else null

            fun optPositiveInt(obj: JSONObject, key: String): Int? =
                if (obj.has(key) && !obj.isNull(key)) obj.optInt(key, 0).takeIf { it > 0 } else null

            fun optDoubleAsInt(obj: JSONObject, primary: String, alias: String): Int = when {
                obj.has(primary) && !obj.isNull(primary) -> obj.optDouble(primary, 0.0).toInt()
                obj.has(alias) && !obj.isNull(alias) -> obj.optDouble(alias, 0.0).toInt()
                else -> 0
            }
        }

        private object MediaParser {
            fun parseMedia(j: JSONObject): Media? {
                if (!j.has("media") || j.isNull("media")) return null
                val m = j.getJSONObject("media")
                return when {
                    m.has("video") -> parseVideoMedia(m.getJSONObject("video"))
                    m.has("image") -> parseImageMedia(m.getJSONObject("image"))
                    m.has("web") -> parseWebMedia(m.getJSONObject("web"))
                    m.has("whep") -> parseWhepMedia(m.getJSONObject("whep"))
                    else -> null
                }
            }

            private fun parseVideoMedia(v: JSONObject): Media.Video {
                val explicitW = JsonFields.optPositiveInt(v, "width")
                return Media.Video(
                    uri = v.getString("uri"),
                    width = explicitW ?: DEFAULT_MEDIA_WIDTH,
                    height = JsonFields.optPositiveInt(v, "height"),
                    scale = v.optBoolean("scale", true),
                    muted = v.optBoolean("muted", true),
                    udp = v.optBoolean("udp", false),
                    poster = JsonFields.optNullableString(v, "poster"),
                    posterMode = parsePosterMode(JsonFields.optNullableString(v, "posterMode"))?.name?.lowercase(),
                    bufferMs = JsonFields.optPositiveInt(v, "bufferMs")?.coerceIn(BUFFER_MS_MIN, BUFFER_MS_MAX),
                    explicitWidth = explicitW != null,
                )
            }

            private fun parseImageMedia(i: JSONObject): Media.Image {
                val explicitW = JsonFields.optPositiveInt(i, "width")
                return Media.Image(
                    uri = i.getString("uri"),
                    width = explicitW ?: DEFAULT_MEDIA_WIDTH,
                    cache = i.optBoolean("cache", true),
                    scale = i.optBoolean("scale", true),
                    explicitWidth = explicitW != null,
                )
            }

            private fun parseWebMedia(w: JSONObject): Media.Web {
                val explicitW = JsonFields.optPositiveInt(w, "width")
                val width = explicitW ?: DEFAULT_WEB_WIDTH
                val explicitH = JsonFields.optPositiveInt(w, "height")
                return Media.Web(
                    uri = w.getString("uri"),
                    width = width,
                    height = explicitH ?: ((width * ASPECT_16_9_HEIGHT) / ASPECT_16_9_WIDTH),
                    cache = w.optBoolean("cache", true),
                    scale = w.optBoolean("scale", true),
                    muted = w.optBoolean("muted", true),
                    poster = JsonFields.optNullableString(w, "poster"),
                    posterMode = parsePosterMode(JsonFields.optNullableString(w, "posterMode"))?.name?.lowercase(),
                    explicitWidth = explicitW != null,
                    explicitHeight = explicitH != null,
                )
            }

            private fun parseWhepMedia(w: JSONObject): Media.Whep {
                val explicitW = JsonFields.optPositiveInt(w, "width")
                return Media.Whep(
                    uri = w.getString("uri"),
                    width = explicitW ?: DEFAULT_WEB_WIDTH,
                    height = JsonFields.optPositiveInt(w, "height"),
                    scale = w.optBoolean("scale", true),
                    videoFit = w.optString("videoFit", "cover"),
                    poster = JsonFields.optNullableString(w, "poster"),
                    posterMode = parsePosterMode(JsonFields.optNullableString(w, "posterMode"))?.name?.lowercase(),
                    muted = w.optBoolean("muted", true),
                    explicitWidth = explicitW != null,
                )
            }

            private fun isValidButtonField(value: String): Boolean = value.isNotEmpty() && value != "null"

            fun parseButtons(j: JSONObject): List<Button> {
                if (!j.has("buttons") || j.isNull("buttons")) return emptyList()
                val bArr = j.getJSONArray("buttons")
                return (0 until bArr.length()).asSequence()
                    .mapNotNull { idx -> bArr.optJSONObject(idx) }
                    .mapNotNull { bObj ->
                        val bId = bObj.optString("id", "").trim()
                        val bLabel = bObj.optString("label", "").trim()
                        if (isValidButtonField(bId) && isValidButtonField(bLabel)) {
                            Button(bId, bLabel.take(MAX_BUTTON_LABEL_LENGTH))
                        } else {
                            null
                        }
                    }
                    .take(MAX_BUTTONS)
                    .toList()
            }
        }

        /**
         * Parses a raw close button setting (boolean or corner name) into a canonical position
         * ("top_right", "top_left", "bottom_right", "bottom_left", "none") or null if invalid/unset.
         */
        fun parseCloseButton(raw: Any?): String? = when (raw) {
            is Boolean -> if (raw) "top_right" else "none"
            is String -> when (raw.trim().lowercase().replace("-", "_")) {
                "true", "on", "1" -> "top_right"
                "false", "off", "none", "0", "" -> "none"
                "top_right", "topright" -> "top_right"
                "top_left", "topleft" -> "top_left"
                "bottom_right", "bottomright" -> "bottom_right"
                "bottom_left", "bottomleft" -> "bottom_left"
                else -> null
            }
            else -> null
        }

        /**
         * Normalizes a persisted close button position to a canonical corner or "none".
         */
        fun normalizeCloseButtonPosition(raw: String?): String {
            return parseCloseButton(raw)?.takeIf {
                it in setOf("top_right", "top_left", "bottom_right", "bottom_left")
            } ?: "none"
        }

        /**
         * Parses a close button auto-hide timeout in seconds (0 = always visible, clamped 0..300).
         */
        fun parseCloseButtonTimeout(raw: Any?): Int? = when (raw) {
            is Number -> raw.toInt().coerceIn(0, CLOSE_BUTTON_TIMEOUT_MAX)
            is String -> raw.trim().toIntOrNull()?.coerceIn(0, CLOSE_BUTTON_TIMEOUT_MAX)
            else -> null
        }

        /**
         * Parses a close button diameter in dp (clamped 24..72).
         */
        fun parseCloseButtonSize(raw: Any?): Int? = when (raw) {
            is Number -> raw.toInt().coerceIn(CLOSE_BUTTON_SIZE_MIN, CLOSE_BUTTON_SIZE_MAX)
            is String -> raw.trim().toIntOrNull()?.coerceIn(CLOSE_BUTTON_SIZE_MIN, CLOSE_BUTTON_SIZE_MAX)
            else -> null
        }

        /**
         * Parses a position value (integer index 0..8 or position name string) into a numeric position index.
         */
        fun parsePositionIndex(raw: Any?): Int {
            return when (raw) {
                is Number -> raw.toInt().takeIf { it in POS_TOP_RIGHT..POS_CENTER_RIGHT } ?: POS_TOP_RIGHT
                is String -> {
                    val trimmed = raw.trim()
                    trimmed.toIntOrNull()?.takeIf { it in POS_TOP_RIGHT..POS_CENTER_RIGHT } ?: run {
                        val normalized = trimmed.replace("_", "").replace("-", "").lowercase()
                        Position.entries.find { it.name.lowercase() == normalized }?.index ?: POS_TOP_RIGHT
                    }
                }
                else -> POS_TOP_RIGHT
            }
        }

        /**
         * Parses a raw stack direction string ("horizontal" or "vertical") into a [StackDirection].
         */
        fun parseStackDirection(raw: String?): StackDirection {
            return if (raw?.trim()?.lowercase() == "horizontal") StackDirection.HORIZONTAL else StackDirection.VERTICAL
        }

        /**
         * Parses a raw poster scaling mode string ("fit", "fill", "stretch", "center") into a [PosterMode].
         */
        fun parsePosterMode(raw: String?): PosterMode? = when (raw?.trim()?.lowercase()) {
            "fit" -> PosterMode.FIT
            "fill" -> PosterMode.FILL
            "stretch" -> PosterMode.STRETCH
            "center" -> PosterMode.CENTER
            else -> null
        }
    }

    /**
     * Converts properties to a JSON string.
     */
    fun toJson(): String {
        val j = JSONObject()
        putCoreFields(j)
        putButtonFields(j)
        putOverlayFields(j)
        media?.let { j.put("media", serializeMedia(it)) }
        return j.toString()
    }

    private fun putCoreFields(j: JSONObject) {
        j.put("duration", duration)
        j.put("id", id ?: JSONObject.NULL)
        j.put("position", position)
        j.put("title", title ?: JSONObject.NULL)
        j.put("titleSize", titleSize.toDouble())
        j.put("titleColor", titleColor)
        j.put("message", message ?: JSONObject.NULL)
        j.put("messageSize", messageSize.toDouble())
        j.put("messageColor", messageColor)
        j.put("backgroundColor", backgroundColor)
        j.put("mediaPosition", mediaPosition ?: JSONObject.NULL)
        j.put("animationType", animationType)
        j.put("animationDuration", animationDuration)
        j.put("animationExit", animationExit)
        j.put("overwrite", overwrite)
        j.put("borderRadius", borderRadius)
        j.put("borderWidth", borderWidth)
        j.put("borderColor", borderColor)
        j.put("contentPadding", contentPadding ?: JSONObject.NULL)
        j.put("titleAlignment", titleAlignment)
        j.put("messageAlignment", messageAlignment)
        j.put("image", image ?: JSONObject.NULL)
        j.put("imageWidth", imageWidth ?: JSONObject.NULL)
        j.put("cache", cache)
        j.put("scale", scale)
        j.put("isPreview", isPreview)
    }

    private fun putButtonFields(j: JSONObject) {
        if (buttons.isNotEmpty()) {
            val bArr = org.json.JSONArray()
            for ((btnId, btnLabel) in buttons) {
                bArr.put(JSONObject().apply {
                    put("id", btnId)
                    put("label", btnLabel)
                })
            }
            j.put("buttons", bArr)
        }
        j.put("callback", callback ?: JSONObject.NULL)
        j.put("buttonSize", buttonSize ?: JSONObject.NULL)
        j.put("buttonColor", buttonColor ?: JSONObject.NULL)
        j.put("buttonFocusColor", buttonFocusColor ?: JSONObject.NULL)
        j.put("buttonTextColor", buttonTextColor ?: JSONObject.NULL)
        j.put("buttonFocusTextColor", buttonFocusTextColor ?: JSONObject.NULL)
        j.put("buttonBorderColor", buttonBorderColor ?: JSONObject.NULL)
        j.put("buttonCornerRadius", buttonCornerRadius ?: JSONObject.NULL)
        j.put("buttonsInline", buttonsInline)
        j.put("isInternalUpdateAction", isInternalUpdateAction)
        j.put("stack", stack ?: JSONObject.NULL)
    }

    private fun putOverlayFields(j: JSONObject) {
        liveBadge?.let { j.put("liveBadge", it) }
        liveBadgeColor?.let { j.put("liveBadgeColor", it) }
        liveBadgePosition?.let { j.put("liveBadgePosition", it) }
        mediaFullBleed?.let { j.put("mediaFullBleed", it) }
        j.put("fullscreen", fullscreen)
        fullscreenUpscale?.let { j.put("fullscreenUpscale", it) }
        j.put("dismissOnBack", dismissOnBack)
        closeButton?.let { j.put("closeButton", it) }
        closeButtonTimeout?.let { j.put("closeButtonTimeout", it) }
        closeButtonSize?.let { j.put("closeButtonSize", it) }
    }

    private fun serializeMedia(m: Media): JSONObject {
        val mj = JSONObject()
        when (m) {
            is Media.Video -> mj.put("video", serializeVideoMedia(m))
            is Media.Image -> mj.put("image", JSONObject().apply {
                put("uri", m.uri); put("cache", m.cache); put("scale", m.scale)
                if (m.explicitWidth) put("width", m.width)
            })
            is Media.Web -> mj.put("web", serializeWebMedia(m))
            is Media.Whep -> mj.put("whep", serializeWhepMedia(m))
            else -> {} // LocalFile and Bitmap are not serialized back to JSON for external use
        }
        return mj
    }

    private fun serializeVideoMedia(m: Media.Video): JSONObject = JSONObject().apply {
        put("uri", m.uri); put("scale", m.scale); put("muted", m.muted); put("udp", m.udp)
        if (m.explicitWidth) put("width", m.width)
        m.height?.let { put("height", it) }
        m.poster?.let { put("poster", it) }
        m.posterMode?.let { put("posterMode", it) }
        m.bufferMs?.let { put("bufferMs", it) }
    }

    private fun serializeWebMedia(m: Media.Web): JSONObject = JSONObject().apply {
        put("uri", m.uri); put("cache", m.cache); put("scale", m.scale); put("muted", m.muted)
        if (m.explicitWidth) put("width", m.width)
        if (m.explicitHeight) put("height", m.height)
        m.poster?.let { put("poster", it) }
        m.posterMode?.let { put("posterMode", it) }
    }

    private fun serializeWhepMedia(m: Media.Whep): JSONObject = JSONObject().apply {
        put("uri", m.uri)
        if (m.explicitWidth) put("width", m.width)
        m.height?.let { put("height", it) }
        put("scale", m.scale); put("videoFit", m.videoFit); put("muted", m.muted)
        m.poster?.let { put("poster", it) }
        m.posterMode?.let { put("posterMode", it) }
    }

    /**
     * Scaling mode for poster snapshot images before live stream playback begins.
     */
    enum class PosterMode {
        FIT, FILL, STRETCH, CENTER
    }

    /** Polymorphic media attachment displayed inside a popup notification. */
    sealed class Media {
        /** Video stream media (RTSP, HLS, DASH, HTTP) rendered via Media3 ExoPlayer. */
        data class Video(
            val uri: String,
            val width: Int = 480,
            val height: Int? = null,
            val scale: Boolean = true,
            val muted: Boolean = true,
            val udp: Boolean = false,
            val poster: String? = null,
            val bufferMs: Int? = null,
            val posterMode: String? = null,
            val explicitWidth: Boolean = false
        ) : Media()

        /** Remote image media loaded and cached via Glide. */
        data class Image(
            val uri: String,
            val width: Int = 480,
            val cache: Boolean = true,
            val scale: Boolean = true,
            val explicitWidth: Boolean = false
        ) : Media()

        /** Web page media rendered inside an embedded WebView. */
        data class Web(
            val uri: String,
            val width: Int = 640,
            val height: Int = 480,
            val cache: Boolean = true,
            val scale: Boolean = true,
            val muted: Boolean = true,
            val poster: String? = null,
            val posterMode: String? = null,
            val explicitWidth: Boolean = false,
            val explicitHeight: Boolean = false
        ) : Media()

        /** WebRTC WHEP live stream rendered via WebView bridge. */
        data class Whep(
            val uri: String,
            val width: Int = 640,
            val height: Int? = null,
            val scale: Boolean = true,
            val videoFit: String = "cover", // cover, contain, fill
            val poster: String? = null,
            val posterMode: String? = null,
            val muted: Boolean = true,
            val explicitWidth: Boolean = false
        ) : Media()

        /** Local cached image file uploaded via multipart request. */
        data class LocalFile(
            val path: String,
            val width: Int = 480,
            val scale: Boolean = true,
            val explicitWidth: Boolean = false
        ) : Media()

        /** In-memory bitmap attachment (used for live settings previews). */
        data class Bitmap(
            val bitmap: android.graphics.Bitmap,
            val width: Int = 480,
            val scale: Boolean = true
        ) : Media()
    }

    /**
     * Enum defining supported screen positions across the full 3x3 grid.
     */
    enum class Position(val index: Int) {
        TopRight(POS_TOP_RIGHT),
        TopLeft(POS_TOP_LEFT),
        BottomRight(POS_BOTTOM_RIGHT),
        BottomLeft(POS_BOTTOM_LEFT),
        Center(POS_CENTER),
        TopCenter(POS_TOP_CENTER),
        BottomCenter(POS_BOTTOM_CENTER),
        CenterLeft(POS_CENTER_LEFT),
        CenterRight(POS_CENTER_RIGHT);

        /**
         * Returns the horizontal screen side corresponding to this position.
         */
        fun getScreenSide(): ScreenSide {
            return when (this) {
                TopLeft, BottomLeft, CenterLeft -> ScreenSide.LEFT
                TopRight, BottomRight, CenterRight -> ScreenSide.RIGHT
                TopCenter, BottomCenter, Center -> ScreenSide.CENTER
            }
        }

        /**
         * Returns the vertical screen anchor corresponding to this position.
         */
        fun getVerticalAnchor(): VerticalAnchor {
            return when (this) {
                TopLeft, TopRight, TopCenter -> VerticalAnchor.TOP
                BottomLeft, BottomRight, BottomCenter -> VerticalAnchor.BOTTOM
                CenterLeft, CenterRight, Center -> VerticalAnchor.CENTER
            }
        }
    }

    /**
     * Horizontal screen alignment bucket for a popup position.
     */
    enum class ScreenSide {
        LEFT, RIGHT, CENTER
    }

    /**
     * Vertical screen alignment anchor for a popup position.
     */
    enum class VerticalAnchor {
        TOP, BOTTOM, CENTER
    }

    /**
     * Stacking growth axis for multiple popups at the same position.
     */
    enum class StackDirection {
        VERTICAL, HORIZONTAL
    }

    /**
     * Resolves the numeric [position] index into a [Position] enum value.
     */
    fun getPositionEnum(): Position {
        if (coversScreen) return Position.Center
        return Position.entries.find { it.index == position } ?: Position.TopRight
    }

    /**
     * Resolves the [stack] property into a [StackDirection] enum value.
     */
    fun getStackDirectionEnum(): StackDirection {
        return parseStackDirection(effectiveStack)
    }

    /**
     * Returns the unscaled media width in pixels if media or legacy image is configured, or null otherwise.
     */
    fun rawMediaWidth(): Int? {
        return when (val m = media) {
            is Media.Image -> m.width
            is Media.Video -> m.width
            is Media.Web -> m.width
            is Media.Whep -> m.width
            is Media.LocalFile -> m.width
            is Media.Bitmap -> m.width
            null -> imageWidth ?: if (!image.isNullOrEmpty()) DEFAULT_MEDIA_WIDTH else null
        }
    }

    /**
     * Scales [px] according to the current screen resolution if [scale] is enabled on this popup.
     */
    fun scaleValue(context: android.content.Context, px: Int): Int {
        return if (scale) context.getScaledPixels(px) else px
    }

    /** Returns the Android [android.view.Gravity] corresponding to [titleAlignment]. */
    fun getTitleGravity(): Int = mapAlignmentToGravity(titleAlignment)

    /** Returns the Android [android.view.Gravity] corresponding to [messageAlignment]. */
    fun getMessageGravity(): Int = mapAlignmentToGravity(messageAlignment)

    private fun mapAlignmentToGravity(alignment: Int): Int {
        return when (alignment) {
            1 -> android.view.Gravity.CENTER_HORIZONTAL
            2 -> android.view.Gravity.END
            else -> android.view.Gravity.START
        }
    }

    /** Parses [backgroundColor] to an ARGB color int, falling back to the default background. */
    fun getBackgroundColorInt(): Int = safeParseColor(backgroundColor, DEFAULT_BG_COLOR.toColorInt())

    /** Parses [borderColor] to an ARGB color int, falling back to transparent. */
    fun getBorderColorInt(): Int = safeParseColor(borderColor, Color.TRANSPARENT)

    /** Parses [titleColor] to an ARGB color int, falling back to white. */
    fun getTitleColorInt(): Int = safeParseColor(titleColor, Color.WHITE)

    /** Parses [messageColor] to an ARGB color int, falling back to white. */
    fun getMessageColorInt(): Int = safeParseColor(messageColor, Color.WHITE)

    /**
     * Computes pre-inflation 2D layout metrics for this popup.
     */
    fun calculateMetrics(
        context: android.content.Context,
        settings: AppSettings = PiPupApp.settings,
    ): PopupLayoutMetrics {
        return PopupLayoutMetrics.calculate(context, this, settings)
    }

    private fun safeParseColor(hex: String, fallback: Int): Int {
        return try {
            val clean = if (hex.startsWith("#")) hex else "#$hex"
            clean.toColorInt()
        } catch (_: Exception) { fallback }
    }

    /**
     * Pure data calculation component for popup width, height, and 2D bounding boxes using
     * StaticLayout without UI inflation.
     */
    data class PopupLayoutMetrics(
        val width: Int,
        val height: Int,
        val boundingBox: android.graphics.Rect
    ) {
        /** Pure layout estimation helpers for [PopupLayoutMetrics]. */
        companion object {
            private const val FALLBACK_LINE_HEIGHT_FACTOR = 1.2f
            private const val FALLBACK_CHARS_PER_LINE = 30
            private const val BASE_MARGIN_DP = 20
            private const val MAX_TEXT_WIDTH_PX = 500
            private const val MIN_TEXT_WIDTH_DP = 180
            private const val TITLE_MSG_GAP_DP = 4
            private const val BUTTON_ROW_HEIGHT_DP = 60
            private const val HORIZONTAL_MEDIA_GAP_DP = 12
            private const val VERTICAL_MEDIA_GAP_DP = 8
            private const val MIN_POPUP_WIDTH_DP = 240
            private const val MAX_HEIGHT_SCREEN_RATIO = 0.75
            private const val MIN_CONTENT_HEIGHT_EXTRA_DP = 36
            private const val MEDIA_POS_LEFT = 2
            private const val MEDIA_POS_RIGHT = 3

            private data class TextEstimate(val width: Int, val contentHeight: Int, val hasContent: Boolean)

            private data class LayoutDimensionsInput(
                val scaledPadding: Int,
                val mediaPad: Int,
                val fullBleed: Boolean,
                val isHorizontal: Boolean,
                val mediaW: Int,
                val mediaH: Int,
                val textEst: TextEstimate,
                val mediaTextGap: Int,
            )

            /**
             * Estimates multiline text height in pixels for a given target [width].
             */
            fun calculateTextHeight(text: String, paint: android.text.TextPaint, width: Int): Int {
                if (text.isEmpty() || width <= 0) return 0
                return try {
                    android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                        .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(true)
                        .build()
                        .height
                } catch (_: Exception) {
                    (paint.textSize * FALLBACK_LINE_HEIGHT_FACTOR * (text.length / FALLBACK_CHARS_PER_LINE + 1)).toInt()
                }
            }

            /**
             * Computes estimated dimensions and initial bounding box for [props] on the given [context] display.
             */
            fun calculate(
                context: android.content.Context,
                props: PopupProps,
                settings: AppSettings,
            ): PopupLayoutMetrics {
                val dm = context.resources.displayMetrics
                if (props.coversScreen) {
                    return PopupLayoutMetrics(
                        width = dm.widthPixels,
                        height = dm.heightPixels,
                        boundingBox = android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels),
                    )
                }

                val baseMargin = context.dpToPx(BASE_MARGIN_DP)
                val scaledPadding = props.scaleValue(context, props.contentPadding ?: settings.contentPadding)
                val hasMedia = props.hasMedia
                val fullBleed = (props.mediaFullBleed ?: settings.mediaFullBleed) && hasMedia
                val borderInset = if (props.borderWidth > 0) props.scaleValue(context, props.borderWidth) else 0
                val mediaPad = if (fullBleed) borderInset else scaledPadding

                val (mediaW, mediaH) = estimateMediaDimensions(context, props)
                val textEst = estimateTextContent(context, props)
                val isHorizontal = hasMedia && (props.mediaPosition == MEDIA_POS_LEFT ||
                    props.mediaPosition == MEDIA_POS_RIGHT)
                val mediaTextGap = if (hasMedia && textEst.hasContent) {
                    context.dpToPx(if (isHorizontal) HORIZONTAL_MEDIA_GAP_DP else VERTICAL_MEDIA_GAP_DP)
                } else {
                    0
                }

                val input = LayoutDimensionsInput(
                    scaledPadding = scaledPadding,
                    mediaPad = mediaPad,
                    fullBleed = fullBleed,
                    isHorizontal = isHorizontal,
                    mediaW = mediaW,
                    mediaH = mediaH,
                    textEst = textEst,
                    mediaTextGap = mediaTextGap,
                )
                val (width, height) = resolvePopupDimensions(context, dm, baseMargin, input)
                val box = computeBoundingBox(props.getPositionEnum(), width, height, dm, baseMargin)
                return PopupLayoutMetrics(width, height, box)
            }

            private fun resolvePopupDimensions(
                context: android.content.Context,
                dm: android.util.DisplayMetrics,
                baseMargin: Int,
                input: LayoutDimensionsInput,
            ): Pair<Int, Int> {
                val rawWidth = if (input.isHorizontal) {
                    input.textEst.width + input.mediaTextGap + input.mediaW + input.scaledPadding + input.mediaPad
                } else {
                    maxOf(input.textEst.width + input.scaledPadding * 2, input.mediaW + input.mediaPad * 2)
                }
                val width = rawWidth
                    .coerceAtLeast(context.dpToPx(MIN_POPUP_WIDTH_DP))
                    .coerceAtMost(dm.widthPixels - baseMargin * 2)

                val minContentHeight = if (input.fullBleed && !input.textEst.hasContent) {
                    input.mediaH + input.mediaPad * 2
                } else {
                    input.scaledPadding * 2 + context.dpToPx(MIN_CONTENT_HEIGHT_EXTRA_DP)
                }
                val verticalPad = when {
                    !input.fullBleed -> input.scaledPadding * 2
                    input.textEst.hasContent -> input.mediaPad + input.scaledPadding
                    else -> input.mediaPad * 2
                }
                val rawHeight = if (input.isHorizontal) {
                    maxOf(input.textEst.contentHeight + input.scaledPadding * 2, input.mediaH + input.mediaPad * 2)
                } else {
                    input.textEst.contentHeight + input.mediaTextGap + input.mediaH + verticalPad
                }
                val height = rawHeight
                    .coerceAtLeast(minContentHeight)
                    .coerceAtMost((dm.heightPixels * MAX_HEIGHT_SCREEN_RATIO).toInt())
                return width to height
            }

            private fun estimateMediaDimensions(context: android.content.Context, props: PopupProps): Pair<Int, Int> {
                val rawWidth = props.rawMediaWidth() ?: return 0 to 0
                val scaledWidth = props.scaleValue(context, rawWidth)
                val default16By9 = (scaledWidth * ASPECT_16_9_HEIGHT) / ASPECT_16_9_WIDTH
                val height = when (val m = props.media) {
                    is Media.Video -> m.height?.let { props.scaleValue(context, it) } ?: default16By9
                    is Media.Web -> props.scaleValue(context, m.height)
                    is Media.Whep -> m.height?.let { props.scaleValue(context, it) } ?: default16By9
                    else -> default16By9
                }
                return scaledWidth to height
            }

            private fun estimateTextContent(context: android.content.Context, props: PopupProps): TextEstimate {
                val dm = context.resources.displayMetrics
                val titlePaint = buildTextPaint(props.titleSize, android.graphics.Typeface.DEFAULT_BOLD, dm)
                val msgPaint = buildTextPaint(props.messageSize, android.graphics.Typeface.DEFAULT, dm)

                val titleText = props.title?.takeIf { it.isNotEmpty() }
                val msgText = props.message?.takeIf { it.isNotEmpty() }
                val hasAnyText = titleText != null || msgText != null

                val titleW = titleText?.lines()?.maxOf { titlePaint.measureText(it).toInt() } ?: 0
                val msgW = msgText?.lines()?.maxOf { msgPaint.measureText(it).toInt() } ?: 0
                val textWidth = if (hasAnyText) {
                    maxOf(titleW, msgW).coerceIn(
                        context.dpToPx(MIN_TEXT_WIDTH_DP),
                        props.scaleValue(context, MAX_TEXT_WIDTH_PX),
                    )
                } else {
                    0
                }

                val titleH = titleText?.let { calculateTextHeight(it, titlePaint, textWidth) } ?: 0
                val msgH = msgText?.let { calculateTextHeight(it, msgPaint, textWidth) } ?: 0
                val titleMsgGap = if (titleText != null && msgText != null) context.dpToPx(TITLE_MSG_GAP_DP) else 0
                val buttonRowH = if (props.buttons.isNotEmpty()) context.dpToPx(BUTTON_ROW_HEIGHT_DP) else 0

                return TextEstimate(
                    width = textWidth,
                    contentHeight = titleH + titleMsgGap + msgH + buttonRowH,
                    hasContent = hasAnyText || buttonRowH > 0,
                )
            }

            private fun buildTextPaint(
                sizeSp: Float,
                typeface: android.graphics.Typeface,
                dm: android.util.DisplayMetrics,
            ): android.text.TextPaint = android.text.TextPaint().apply {
                textSize = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    sizeSp,
                    dm,
                )
                this.typeface = typeface
            }

            private fun computeBoundingBox(
                pos: Position,
                width: Int,
                height: Int,
                dm: android.util.DisplayMetrics,
                baseMargin: Int,
            ): android.graphics.Rect {
                val left = when (pos.getScreenSide()) {
                    ScreenSide.LEFT -> baseMargin
                    ScreenSide.RIGHT -> dm.widthPixels - baseMargin - width
                    ScreenSide.CENTER -> (dm.widthPixels - width) / 2
                }
                val top = when (pos.getVerticalAnchor()) {
                    VerticalAnchor.TOP -> baseMargin
                    VerticalAnchor.BOTTOM -> dm.heightPixels - baseMargin - height
                    VerticalAnchor.CENTER -> (dm.heightPixels - height) / 2
                }
                return android.graphics.Rect(left, top, left + width, top + height)
            }

            /**
             * Checks whether the display is too small to render a minimum viable popup.
             */
            fun isFundamentallyUnplaceable(context: android.content.Context): Boolean {
                val screenWidth = context.resources.displayMetrics.widthPixels
                val screenHeight = context.resources.displayMetrics.heightPixels
                val maxAllowedWidth = screenWidth - context.dpToPx(40)
                val maxAllowedHeight = screenHeight - context.dpToPx(40)

                val minRenderableWidth = context.dpToPx(200)
                val minRenderableHeight = context.dpToPx(100)

                return minRenderableWidth > maxAllowedWidth || minRenderableHeight > maxAllowedHeight
            }
        }
    }
}
