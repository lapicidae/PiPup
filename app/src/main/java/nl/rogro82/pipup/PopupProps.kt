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
    val fullscreenUpscale: Boolean? = null
) {
    /**
     * Whether this popup covers the entire display (including system bars and cutouts).
     */
    val coversScreen: Boolean
        get() = fullscreen

    /**
     * Whether this popup contains a media object or legacy image URI.
     */
    val hasMedia: Boolean
        get() = media != null || !image.isNullOrEmpty()

    /**
     * Effective stacking axis direction ("vertical" or "horizontal"), or null if stacking is not set or disabled by [coversScreen].
     */
    val effectiveStack: String?
        get() = if (coversScreen) null else stack

    /**
     * Resolves the effective [Media] object, wrapping legacy top-level [image]/[imageWidth] fields when [media] is null.
     */
    fun resolveEffectiveMedia(): Media? {
        return media ?: image?.let {
            val explicitW = imageWidth?.takeIf { w -> w > 0 }
            Media.Image(
                uri = it,
                width = explicitW ?: 480,
                cache = cache,
                scale = scale,
                explicitWidth = explicitW != null
            )
        }
    }

    @Transient
    var measuredSize: Pair<Int, Int>? = null

    data class Button(val id: String, val label: String)
    companion object {
        private const val DEFAULT_TEXT_COLOR = "#FFFFFF"
        private const val DEFAULT_BG_COLOR = "#CC000000"
        private const val DEFAULT_BORDER_COLOR = "#00000000"

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
                            width = explicitW ?: 480,
                            height = optPositiveInt(v, "height"),
                            scale = v.optBoolean("scale", true),
                            muted = v.optBoolean("muted", true),
                            udp = v.optBoolean("udp", false),
                            poster = optNullableStringIn(v, "poster"),
                            posterMode = parsePosterMode(optNullableStringIn(v, "posterMode"))?.name?.lowercase(),
                            bufferMs = optPositiveInt(v, "bufferMs")?.coerceIn(100, 10_000),
                            explicitWidth = explicitW != null
                        )
                    }
                    m.has("image") -> {
                        val i = m.getJSONObject("image")
                        val explicitW = optPositiveInt(i, "width")
                        media = Media.Image(
                            uri = i.getString("uri"),
                            width = explicitW ?: 480,
                            cache = i.optBoolean("cache", true),
                            scale = i.optBoolean("scale", true),
                            explicitWidth = explicitW != null
                        )
                    }
                    m.has("web") -> {
                        val w = m.getJSONObject("web")
                        val explicitW = optPositiveInt(w, "width")
                        val width = explicitW ?: 640
                        val explicitH = optPositiveInt(w, "height")
                        media = Media.Web(
                            uri = w.getString("uri"),
                            width = width,
                            height = explicitH ?: ((width * 9) / 16),
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
                            width = explicitW ?: 640,
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
                    if (parsedButtons.size >= 5) break
                    val bObj = bArr.optJSONObject(i) ?: continue
                    val bId = bObj.optString("id", "").trim()
                    val bLabel = bObj.optString("label", "").trim()
                    if (bId.isNotEmpty() && bLabel.isNotEmpty() && bId != "null" && bLabel != "null") {
                        parsedButtons.add(Button(bId, bLabel.take(30)))
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

            val resolvedPosition = parsePositionIndex(if (j.has("position") && !j.isNull("position")) j.get("position") else null)

            val resolvedFullscreenUpscale = when {
                j.has("fullscreenUpscale") && !j.isNull("fullscreenUpscale") -> j.getBoolean("fullscreenUpscale")
                j.has("upscale") && !j.isNull("upscale") -> j.getBoolean("upscale")
                else -> null
            }

            return PopupProps(
                duration = j.optInt("duration", 10),
                id = optNullableString("id"),
                position = resolvedPosition,
                title = optNullableString("title"),
                titleSize = j.optDouble("titleSize", 24.0).toFloat(),
                titleColor = j.optString("titleColor", DEFAULT_TEXT_COLOR) ?: DEFAULT_TEXT_COLOR,
                message = optNullableString("message"),
                messageSize = j.optDouble("messageSize", 16.0).toFloat(),
                messageColor = j.optString("messageColor", DEFAULT_TEXT_COLOR) ?: DEFAULT_TEXT_COLOR,
                backgroundColor = j.optString("backgroundColor", DEFAULT_BG_COLOR) ?: DEFAULT_BG_COLOR,
                media = media,
                mediaPosition = if (j.has("mediaPosition") && !j.isNull("mediaPosition")) j.getInt("mediaPosition") else null,
                animationType = j.optInt("animationType", 0),
                animationDuration = j.optInt("animationDuration", 500),
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
                    j.getDouble("buttonSize").toFloat().coerceIn(10f, 40f)
                } else {
                    null
                },
                buttonColor = optNullableString("buttonColor"),
                buttonFocusColor = optNullableString("buttonFocusColor"),
                buttonTextColor = optNullableString("buttonTextColor"),
                buttonFocusTextColor = optNullableString("buttonFocusTextColor"),
                buttonBorderColor = optNullableString("buttonBorderColor"),
                buttonCornerRadius = if (j.has("buttonCornerRadius") && !j.isNull("buttonCornerRadius")) {
                    j.getDouble("buttonCornerRadius").toFloat().coerceIn(0f, 50f)
                } else {
                    null
                },
                buttonsInline = j.optBoolean("buttonsInline", false),
                isInternalUpdateAction = false,
                stack = optNullableString("stack")?.trim()?.lowercase()?.takeIf { it == "vertical" || it == "horizontal" },
                liveBadge = if (j.has("liveBadge") && !j.isNull("liveBadge")) j.getBoolean("liveBadge") else null,
                liveBadgeColor = optNullableString("liveBadgeColor"),
                liveBadgePosition = optNullableString("liveBadgePosition"),
                mediaFullBleed = if (j.has("mediaFullBleed") && !j.isNull("mediaFullBleed")) j.getBoolean("mediaFullBleed") else null,
                fullscreen = j.optBoolean("fullscreen", false),
                fullscreenUpscale = resolvedFullscreenUpscale
            )
        }

        /**
         * Parses a position value (integer index 0..8 or position name string) into a numeric position index.
         */
        fun parsePositionIndex(raw: Any?): Int {
            return when (raw) {
                is Number -> raw.toInt().takeIf { it in 0..8 } ?: 0
                is String -> {
                    val trimmed = raw.trim()
                    trimmed.toIntOrNull()?.takeIf { it in 0..8 } ?: run {
                        val normalized = trimmed.replace("_", "").replace("-", "").lowercase()
                        Position.entries.find { it.name.lowercase() == normalized }?.index ?: 0
                    }
                }
                else -> 0
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

    sealed class Media {
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
        data class Image(
            val uri: String,
            val width: Int = 480,
            val cache: Boolean = true,
            val scale: Boolean = true,
            val explicitWidth: Boolean = false
        ) : Media()
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
        data class LocalFile(
            val path: String,
            val width: Int = 480,
            val scale: Boolean = true,
            val explicitWidth: Boolean = false
        ) : Media()
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
        TopRight(0),
        TopLeft(1),
        BottomRight(2),
        BottomLeft(3),
        Center(4),
        TopCenter(5),
        BottomCenter(6),
        CenterLeft(7),
        CenterRight(8);

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
            null -> imageWidth ?: if (!image.isNullOrEmpty()) 480 else null
        }
    }

    /**
     * Scales [px] according to the current screen resolution if [scale] is enabled on this popup.
     */
    fun scaleValue(context: android.content.Context, px: Int): Int {
        return if (scale) context.getScaledPixels(px) else px
    }

    fun getTitleGravity(): Int = mapAlignmentToGravity(titleAlignment)
    fun getMessageGravity(): Int = mapAlignmentToGravity(messageAlignment)

    private fun mapAlignmentToGravity(alignment: Int): Int {
        return when (alignment) {
            1 -> android.view.Gravity.CENTER_HORIZONTAL
            2 -> android.view.Gravity.END
            else -> android.view.Gravity.START
        }
    }

    fun getBackgroundColorInt(): Int = safeParseColor(backgroundColor, DEFAULT_BG_COLOR.toColorInt())
    fun getBorderColorInt(): Int = safeParseColor(borderColor, Color.TRANSPARENT)
    fun getTitleColorInt(): Int = safeParseColor(titleColor, Color.WHITE)
    fun getMessageColorInt(): Int = safeParseColor(messageColor, Color.WHITE)

    /**
     * Computes pre-inflation 2D layout metrics for this popup.
     */
    fun calculateMetrics(context: android.content.Context, settings: AppSettings = PiPupApp.settings): PopupLayoutMetrics {
        return PopupLayoutMetrics.calculate(context, this, settings)
    }

    private fun safeParseColor(hex: String, fallback: Int): Int {
        return try {
            val clean = if (hex.startsWith("#")) hex else "#$hex"
            clean.toColorInt()
        } catch (_: Exception) { fallback }
    }

    /**
     * Pure data calculation component for popup width, height, and 2D bounding boxes using StaticLayout without UI inflation.
     */
    data class PopupLayoutMetrics(
        val width: Int,
        val height: Int,
        val boundingBox: android.graphics.Rect
    ) {
        companion object {
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
                    (paint.textSize * 1.2f * (text.length / 30 + 1)).toInt()
                }
            }

            /**
             * Computes estimated dimensions and initial bounding box for [props] on the given [context] display.
             */
            fun calculate(context: android.content.Context, props: PopupProps, settings: AppSettings): PopupLayoutMetrics {
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
                    mediaHeightEst = when (val m = props.media) {
                        is Media.Video -> m.height?.let { props.scaleValue(context, it) } ?: ((scaledWidth * 9) / 16)
                        is Media.Web -> props.scaleValue(context, m.height)
                        is Media.Whep -> m.height?.let { props.scaleValue(context, it) } ?: ((scaledWidth * 9) / 16)
                        else -> (scaledWidth * 9) / 16
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
