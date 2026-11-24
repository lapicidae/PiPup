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

            fun optNullableString(key: String): String? = if (j.has(key) && !j.isNull(key)) j.getString(key) else null
            fun optNullableStringIn(obj: JSONObject, key: String): String? =
                if (obj.has(key) && !obj.isNull(key)) obj.getString(key) else null
            fun optPositiveInt(obj: JSONObject, key: String): Int? =
                if (obj.has(key) && !obj.isNull(key)) obj.optInt(key, 0).takeIf { it > 0 } else null

            // Handle polymorphic media structure
            var media: Media? = null
            if (j.has("media") && !j.isNull("media")) {
                val m = j.getJSONObject("media")
                when {
                    m.has("video") -> {
                        val v = m.getJSONObject("video")
                        val explicitW = optPositiveInt(v, "width")
                        media = Media.Video(
                            uri = v.getString("uri"),
                            width = explicitW ?: DEFAULT_MEDIA_WIDTH,
                            height = optPositiveInt(v, "height"),
                            scale = v.optBoolean("scale", true),
                            muted = v.optBoolean("muted", true),
                            udp = v.optBoolean("udp", false),
                            poster = optNullableStringIn(v, "poster"),
                            posterMode = parsePosterMode(optNullableStringIn(v, "posterMode"))?.name?.lowercase(),
                            bufferMs = optPositiveInt(v, "bufferMs")?.coerceIn(BUFFER_MS_MIN, BUFFER_MS_MAX),
                            explicitWidth = explicitW != null
                        )
                    }
                    m.has("image") -> {
                        val i = m.getJSONObject("image")
                        val explicitW = optPositiveInt(i, "width")
                        media = Media.Image(
                            uri = i.getString("uri"),
                            width = explicitW ?: DEFAULT_MEDIA_WIDTH,
                            cache = i.optBoolean("cache", true),
                            scale = i.optBoolean("scale", true),
                            explicitWidth = explicitW != null
                        )
                    }
                    m.has("web") -> {
                        val w = m.getJSONObject("web")
                        val explicitW = optPositiveInt(w, "width")
                        val width = explicitW ?: DEFAULT_WEB_WIDTH
                        val explicitH = optPositiveInt(w, "height")
                        media = Media.Web(
                            uri = w.getString("uri"),
                            width = width,
                            height = explicitH ?: ((width * ASPECT_16_9_HEIGHT) / ASPECT_16_9_WIDTH),
                            cache = w.optBoolean("cache", true),
                            scale = w.optBoolean("scale", true),
                            muted = w.optBoolean("muted", true),
                            poster = optNullableStringIn(w, "poster"),
                            posterMode = parsePosterMode(optNullableStringIn(w, "posterMode"))?.name?.lowercase(),
                            explicitWidth = explicitW != null,
                            explicitHeight = explicitH != null
                        )
                    }
                    m.has("whep") -> {
                        val w = m.getJSONObject("whep")
                        val explicitW = optPositiveInt(w, "width")
                        media = Media.Whep(
                            uri = w.getString("uri"),
                            width = explicitW ?: DEFAULT_WEB_WIDTH,
                            height = optPositiveInt(w, "height"),
                            scale = w.optBoolean("scale", true),
                            videoFit = w.optString("videoFit", "cover"),
                            poster = optNullableStringIn(w, "poster"),
                            posterMode = parsePosterMode(optNullableStringIn(w, "posterMode"))?.name?.lowercase(),
                            muted = w.optBoolean("muted", true),
                            explicitWidth = explicitW != null
                        )
                    }
                }
            }

            val parsedButtons = mutableListOf<Button>()
            if (j.has("buttons") && !j.isNull("buttons")) {
                val bArr = j.getJSONArray("buttons")
                for (i in 0 until bArr.length()) {
                    if (parsedButtons.size >= MAX_BUTTONS) break
                    val bObj = bArr.optJSONObject(i) ?: continue
                    val bId = bObj.optString("id", "").trim()
                    val bLabel = bObj.optString("label", "").trim()
                    if (bId.isNotEmpty() && bLabel.isNotEmpty() && bId != "null" && bLabel != "null") {
                        parsedButtons.add(Button(bId, bLabel.take(MAX_BUTTON_LABEL_LENGTH)))
                    }
                }
            }

            val resolvedBorderRadius = when {
                j.has("borderRadius") && !j.isNull("borderRadius") -> j.optDouble("borderRadius", 0.0).toInt()
                j.has("cornerRadius") && !j.isNull("cornerRadius") -> j.optDouble("cornerRadius", 0.0).toInt()
                else -> 0
            }

            val resolvedContentPadding = when {
                j.has("contentPadding") && !j.isNull("contentPadding") -> j.getInt("contentPadding")
                j.has("padding") && !j.isNull("padding") -> j.getInt("padding")
                else -> null
            }

            val rawPosition = if (j.has("position") && !j.isNull("position")) j.get("position") else null
            val resolvedPosition = parsePositionIndex(rawPosition)

            val resolvedFullscreenUpscale = when {
                j.has("fullscreenUpscale") && !j.isNull("fullscreenUpscale") -> j.getBoolean("fullscreenUpscale")
                j.has("upscale") && !j.isNull("upscale") -> j.getBoolean("upscale")
                else -> null
            }

            return PopupProps(
                duration = j.optInt("duration", DEFAULT_DURATION_SEC),
                id = optNullableString("id"),
                position = resolvedPosition,
                title = optNullableString("title"),
                titleSize = j.optDouble("titleSize", DEFAULT_TITLE_SIZE_SP.toDouble()).toFloat(),
                titleColor = j.optString("titleColor", DEFAULT_TEXT_COLOR) ?: DEFAULT_TEXT_COLOR,
                message = optNullableString("message"),
                messageSize = j.optDouble("messageSize", DEFAULT_MESSAGE_SIZE_SP.toDouble()).toFloat(),
                messageColor = j.optString("messageColor", DEFAULT_TEXT_COLOR) ?: DEFAULT_TEXT_COLOR,
                backgroundColor = j.optString("backgroundColor", DEFAULT_BG_COLOR) ?: DEFAULT_BG_COLOR,
                media = media,
                mediaPosition = if (j.has("mediaPosition") && !j.isNull("mediaPosition")) {
                    j.getInt("mediaPosition")
                } else {
                    null
                },
                animationType = j.optInt("animationType", 0),
                animationDuration = j.optInt("animationDuration", DEFAULT_ANIMATION_DURATION_MS),
                animationExit = j.optBoolean("animationExit", false),
                overwrite = j.optBoolean("overwrite", false),
                borderRadius = resolvedBorderRadius,
                borderWidth = j.optInt("borderWidth", 0),
                borderColor = j.optString("borderColor", DEFAULT_BORDER_COLOR) ?: DEFAULT_BORDER_COLOR,
                contentPadding = resolvedContentPadding,
                titleAlignment = j.optInt("titleAlignment", 0),
                messageAlignment = j.optInt("messageAlignment", 0),
                image = optNullableString("image"),
                imageWidth = if (j.has("imageWidth") && !j.isNull("imageWidth")) j.getInt("imageWidth") else null,
                cache = j.optBoolean("cache", true),
                scale = j.optBoolean("scale", true),
                buttons = parsedButtons,
                callback = optNullableString("callback"),
                buttonSize = if (j.has("buttonSize") && !j.isNull("buttonSize")) {
                    j.getDouble("buttonSize").toFloat().coerceIn(BUTTON_SIZE_MIN_SP, BUTTON_SIZE_MAX_SP)
                } else {
                    null
                },
                buttonColor = optNullableString("buttonColor"),
                buttonFocusColor = optNullableString("buttonFocusColor"),
                buttonTextColor = optNullableString("buttonTextColor"),
                buttonFocusTextColor = optNullableString("buttonFocusTextColor"),
                buttonBorderColor = optNullableString("buttonBorderColor"),
                buttonCornerRadius = if (j.has("buttonCornerRadius") && !j.isNull("buttonCornerRadius")) {
                    j.getDouble("buttonCornerRadius").toFloat().coerceIn(0f, BUTTON_CORNER_RADIUS_MAX_DP)
                } else {
                    null
                },
                buttonsInline = j.optBoolean("buttonsInline", false),
                isInternalUpdateAction = false,
                stack = optNullableString("stack")?.trim()?.lowercase()
                    ?.takeIf { it == "vertical" || it == "horizontal" },
                liveBadge = if (j.has("liveBadge") && !j.isNull("liveBadge")) j.getBoolean("liveBadge") else null,
                liveBadgeColor = optNullableString("liveBadgeColor"),
                liveBadgePosition = optNullableString("liveBadgePosition"),
                mediaFullBleed = if (j.has("mediaFullBleed") && !j.isNull("mediaFullBleed")) {
                    j.getBoolean("mediaFullBleed")
                } else {
                    null
                },
                fullscreen = j.optBoolean("fullscreen", false),
                fullscreenUpscale = resolvedFullscreenUpscale,
                dismissOnBack = j.optBoolean("dismissOnBack", false),
                closeButton = if (j.has("closeButton") && !j.isNull("closeButton")) {
                    parseCloseButton(j.get("closeButton"))
                } else {
                    null
                },
                closeButtonTimeout = if (j.has("closeButtonTimeout") && !j.isNull("closeButtonTimeout")) {
                    parseCloseButtonTimeout(j.get("closeButtonTimeout"))
                } else {
                    null
                },
                closeButtonSize = if (j.has("closeButtonSize") && !j.isNull("closeButtonSize")) {
                    parseCloseButtonSize(j.get("closeButtonSize"))
                } else {
                    null
                }
            )
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
        if (buttons.isNotEmpty()) {
            val bArr = org.json.JSONArray()
            for ((id, label) in buttons) {
                bArr.put(JSONObject().apply {
                    put("id", id)
                    put("label", label)
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

        media?.let { m ->
            val mj = JSONObject()
            when (m) {
                is Media.Video -> {
                    mj.put("video", JSONObject().apply {
                        put("uri", m.uri); put("scale", m.scale); put("muted", m.muted); put("udp", m.udp)
                        if (m.explicitWidth) put("width", m.width)
                        m.height?.let { put("height", it) }
                        m.poster?.let { put("poster", it) }
                        m.posterMode?.let { put("posterMode", it) }
                        m.bufferMs?.let { put("bufferMs", it) }
                    })
                }
                is Media.Image -> {
                    mj.put("image", JSONObject().apply {
                        put("uri", m.uri); put("cache", m.cache); put("scale", m.scale)
                        if (m.explicitWidth) put("width", m.width)
                    })
                }
                is Media.Web -> {
                    mj.put("web", JSONObject().apply {
                        put("uri", m.uri); put("cache", m.cache); put("scale", m.scale); put("muted", m.muted)
                        if (m.explicitWidth) put("width", m.width)
                        if (m.explicitHeight) put("height", m.height)
                        m.poster?.let { put("poster", it) }
                        m.posterMode?.let { put("posterMode", it) }
                    })
                }
                is Media.Whep -> {
                    mj.put("whep", JSONObject().apply {
                        put("uri", m.uri)
                        if (m.explicitWidth) put("width", m.width)
                        m.height?.let { put("height", it) }
                        put("scale", m.scale); put("videoFit", m.videoFit); put("muted", m.muted)
                        m.poster?.let { put("poster", it) }
                        m.posterMode?.let { put("posterMode", it) }
                    })
                }
                else -> {} // LocalFile and Bitmap are not serialized back to JSON for external use
            }
            j.put("media", mj)
        }

        return j.toString()
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
                val screenWidth = context.resources.displayMetrics.widthPixels
                val screenHeight = context.resources.displayMetrics.heightPixels
                if (props.coversScreen) {
                    return PopupLayoutMetrics(
                        width = screenWidth,
                        height = screenHeight,
                        boundingBox = android.graphics.Rect(0, 0, screenWidth, screenHeight)
                    )
                }
                val baseMargin = context.dpToPx(20)

                val paddingVal = props.contentPadding ?: settings.contentPadding
                val scaledPadding = props.scaleValue(context, paddingVal)
                val fullBleed = (props.mediaFullBleed ?: settings.mediaFullBleed) &&
                    (props.media != null || !props.image.isNullOrEmpty())
                val borderInset = if (props.borderWidth > 0) props.scaleValue(context, props.borderWidth) else 0
                val mediaPad = if (fullBleed) borderInset else scaledPadding

                var mediaWidthEst = 0
                var mediaHeightEst = 0
                props.rawMediaWidth()?.let { w ->
                    val scaledWidth = props.scaleValue(context, w)
                    mediaWidthEst = scaledWidth
                    val default16By9Height = (scaledWidth * ASPECT_16_9_HEIGHT) / ASPECT_16_9_WIDTH
                    mediaHeightEst = when (val m = props.media) {
                        is Media.Video -> m.height?.let { props.scaleValue(context, it) } ?: default16By9Height
                        is Media.Web -> props.scaleValue(context, m.height)
                        is Media.Whep -> m.height?.let { props.scaleValue(context, it) } ?: default16By9Height
                        else -> default16By9Height
                    }
                }

                val hasMedia = props.media != null || !props.image.isNullOrEmpty()
                val isHorizontalMedia = (props.mediaPosition == 2 || props.mediaPosition == 3) && hasMedia

                val maxTextWidth = props.scaleValue(context, 500)
                val dm = context.resources.displayMetrics
                val titlePaint = android.text.TextPaint().apply {
                    textSize = android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP,
                        props.titleSize,
                        dm
                    )
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val msgPaint = android.text.TextPaint().apply {
                    textSize = android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP,
                        props.messageSize,
                        dm
                    )
                    typeface = android.graphics.Typeface.DEFAULT
                }

                val titleText = props.title?.takeIf { it.isNotEmpty() }
                val msgText = props.message?.takeIf { it.isNotEmpty() }
                val hasTitle = titleText != null
                val hasMessage = msgText != null
                val hasAnyText = hasTitle || hasMessage

                val titleW = titleText?.lines()?.maxOf { titlePaint.measureText(it).toInt() } ?: 0
                val msgW = msgText?.lines()?.maxOf { msgPaint.measureText(it).toInt() } ?: 0
                val textWidthEst = if (hasAnyText) {
                    maxOf(titleW, msgW).coerceIn(context.dpToPx(180), maxTextWidth)
                } else {
                    0
                }

                val titleH = if (titleText != null) calculateTextHeight(titleText, titlePaint, textWidthEst) else 0
                val msgH = if (msgText != null) calculateTextHeight(msgText, msgPaint, textWidthEst) else 0
                val titleMsgGap = if (hasTitle && hasMessage) context.dpToPx(4) else 0
                val buttonRowH = if (props.buttons.isNotEmpty()) context.dpToPx(60) else 0
                val mediaTextGap = if (hasMedia && (hasAnyText || buttonRowH > 0)) {
                    if (isHorizontalMedia) context.dpToPx(12) else context.dpToPx(8)
                } else {
                    0
                }

                val totalTextContentH = titleH + titleMsgGap + msgH + buttonRowH

                val width = if (isHorizontalMedia) {
                    (textWidthEst + mediaTextGap + mediaWidthEst + scaledPadding + mediaPad)
                        .coerceAtLeast(context.dpToPx(240))
                        .coerceAtMost(screenWidth - baseMargin * 2)
                } else {
                    maxOf(textWidthEst + scaledPadding * 2, mediaWidthEst + mediaPad * 2)
                        .coerceAtLeast(context.dpToPx(240))
                        .coerceAtMost(screenWidth - baseMargin * 2)
                }

                val pos = props.getPositionEnum()

                val maxAllowedHeight = (screenHeight * 0.75).toInt()
                val minContentHeight = if (fullBleed && !hasAnyText && buttonRowH == 0) {
                    mediaHeightEst + mediaPad * 2
                } else {
                    scaledPadding * 2 + context.dpToPx(36)
                }
                val verticalPad = if (fullBleed) {
                    if (hasAnyText || buttonRowH > 0) mediaPad + scaledPadding else mediaPad * 2
                } else {
                    scaledPadding * 2
                }
                val height = if (isHorizontalMedia) {
                    (maxOf(totalTextContentH + scaledPadding * 2, mediaHeightEst + mediaPad * 2))
                        .coerceAtLeast(minContentHeight)
                        .coerceAtMost(maxAllowedHeight)
                } else {
                    (totalTextContentH + mediaTextGap + mediaHeightEst + verticalPad)
                        .coerceAtLeast(minContentHeight)
                        .coerceAtMost(maxAllowedHeight)
                }

                val left = when (pos.getScreenSide()) {
                    ScreenSide.LEFT -> baseMargin
                    ScreenSide.RIGHT -> screenWidth - baseMargin - width
                    ScreenSide.CENTER -> (screenWidth - width) / 2
                }
                val right = left + width

                val top = when (pos.getVerticalAnchor()) {
                    VerticalAnchor.TOP -> baseMargin
                    VerticalAnchor.BOTTOM -> screenHeight - baseMargin - height
                    VerticalAnchor.CENTER -> (screenHeight - height) / 2
                }
                val bottom = top + height

                return PopupLayoutMetrics(width, height, android.graphics.Rect(left, top, right, bottom))
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
