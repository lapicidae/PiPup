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
    val scale: Boolean = true
) {
    companion object {
        private const val DEFAULT_TEXT_COLOR = "#FFFFFF"
        private const val DEFAULT_BG_COLOR = "#CC000000"
        private const val DEFAULT_BORDER_COLOR = "#00000000"

        /**
         * Manual JSON parser for PopupProps to avoid reflection overhead.
         */
        fun fromJson(jsonStr: String): PopupProps {
            val j = JSONObject(jsonStr)

            // Handle polymorphic media structure
            var media: Media? = null
            if (j.has("media") && !j.isNull("media")) {
                val m = j.getJSONObject("media")
                when {
                    m.has("video") -> {
                        val v = m.getJSONObject("video")
                        media = Media.Video(
                            uri = v.getString("uri"),
                            width = v.optInt("width", 480),
                            scale = v.optBoolean("scale", true),
                            muted = v.optBoolean("muted", true),
                            udp = v.optBoolean("udp", false)
                        )
                    }
                    m.has("image") -> {
                        val i = m.getJSONObject("image")
                        media = Media.Image(
                            uri = i.getString("uri"),
                            width = i.optInt("width", 480),
                            cache = i.optBoolean("cache", true),
                            scale = i.optBoolean("scale", true)
                        )
                    }
                    m.has("web") -> {
                        val w = m.getJSONObject("web")
                        media = Media.Web(
                            uri = w.getString("uri"),
                            width = w.optInt("width", 640),
                            height = w.optInt("height", 480),
                            cache = w.optBoolean("cache", true),
                            scale = w.optBoolean("scale", true),
                            muted = w.optBoolean("muted", true)
                        )
                    }
                    m.has("whep") -> {
                        val w = m.getJSONObject("whep")
                        media = Media.Whep(
                            uri = w.getString("uri"),
                            width = w.optInt("width", 640),
                            height = w.optInt("height", 480),
                            scale = w.optBoolean("scale", true),
                            videoFit = w.optString("videoFit", "cover")
                        )
                    }
                }
            }

            fun optNullableString(key: String): String? = if (j.has(key) && !j.isNull(key)) j.getString(key) else null

            return PopupProps(
                duration = j.optInt("duration", 10),
                id = optNullableString("id"),
                position = j.optInt("position", 0),
                title = optNullableString("title"),
                titleSize = j.optDouble("titleSize", 24.0).toFloat(),
                titleColor = j.optString("titleColor", DEFAULT_TEXT_COLOR),
                message = optNullableString("message"),
                messageSize = j.optDouble("messageSize", 16.0).toFloat(),
                messageColor = j.optString("messageColor", DEFAULT_TEXT_COLOR),
                backgroundColor = j.optString("backgroundColor", DEFAULT_BG_COLOR),
                media = media,
                mediaPosition = if (j.has("mediaPosition") && !j.isNull("mediaPosition")) j.getInt("mediaPosition") else null,
                animationType = j.optInt("animationType", 0),
                animationDuration = j.optInt("animationDuration", 500),
                animationExit = j.optBoolean("animationExit", false),
                overwrite = j.optBoolean("overwrite", false),
                borderRadius = j.optInt("borderRadius", 0),
                borderWidth = j.optInt("borderWidth", 0),
                borderColor = j.optString("borderColor", DEFAULT_BORDER_COLOR),
                contentPadding = if (j.has("contentPadding") && !j.isNull("contentPadding")) j.getInt("contentPadding") else null,
                titleAlignment = j.optInt("titleAlignment", 0),
                messageAlignment = j.optInt("messageAlignment", 0),
                image = optNullableString("image"),
                imageWidth = if (j.has("imageWidth") && !j.isNull("imageWidth")) j.getInt("imageWidth") else null,
                cache = j.optBoolean("cache", true),
                scale = j.optBoolean("scale", true)
            )
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

        media?.let { m ->
            val mj = JSONObject()
            when (m) {
                is Media.Video -> {
                    mj.put("video", JSONObject().apply {
                        put("uri", m.uri); put("width", m.width); put("scale", m.scale); put("muted", m.muted); put("udp", m.udp)
                    })
                }
                is Media.Image -> {
                    mj.put("image", JSONObject().apply {
                        put("uri", m.uri); put("width", m.width); put("cache", m.cache); put("scale", m.scale)
                    })
                }
                is Media.Web -> {
                    mj.put("web", JSONObject().apply {
                        put("uri", m.uri); put("width", m.width); put("height", m.height); put("cache", m.cache); put("scale", m.scale); put("muted", m.muted)
                    })
                }
                is Media.Whep -> {
                    mj.put("whep", JSONObject().apply {
                        put("uri", m.uri); put("width", m.width); put("height", m.height); put("scale", m.scale); put("videoFit", m.videoFit)
                    })
                }
                else -> {} // LocalFile and Bitmap are not serialized back to JSON for external use
            }
            j.put("media", mj)
        }

        return j.toString()
    }

    sealed class Media {
        data class Video(
            val uri: String,
            val width: Int = 480,
            val scale: Boolean = true,
            val muted: Boolean = true,
            val udp: Boolean = false
        ) : Media()
        data class Image(
            val uri: String,
            val width: Int = 480,
            val cache: Boolean = true,
            val scale: Boolean = true
        ) : Media()
        data class Web(
            val uri: String,
            val width: Int = 640,
            val height: Int = 480,
            val cache: Boolean = true,
            val scale: Boolean = true,
            val muted: Boolean = true
        ) : Media()
        data class Whep(
            val uri: String,
            val width: Int = 640,
            val height: Int = 480,
            val scale: Boolean = true,
            val videoFit: String = "cover" // cover, contain, fill
        ) : Media()
        data class LocalFile(
            val path: String,
            val width: Int = 480,
            val scale: Boolean = true
        ) : Media()
        data class Bitmap(
            val bitmap: android.graphics.Bitmap,
            val width: Int = 480,
            val scale: Boolean = true
        ) : Media()
    }

    /**
     * Enum defining supported screen positions.
     */
    enum class Position(val index: Int) {
        TopRight(0), TopLeft(1), BottomRight(2), BottomLeft(3), Center(4)
    }

    fun getPositionEnum(): Position {
        return Position.entries.find { it.index == position } ?: Position.TopRight
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

    private fun safeParseColor(hex: String, fallback: Int): Int {
        return try {
            val clean = if (hex.startsWith("#")) hex else "#$hex"
            clean.toColorInt()
        } catch (_: Exception) { fallback }
    }
}
