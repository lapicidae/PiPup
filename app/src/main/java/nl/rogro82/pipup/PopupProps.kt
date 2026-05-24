package nl.rogro82.pipup

import android.graphics.Color
import androidx.core.graphics.toColorInt
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Data class representing all properties of a notification popup.
 * Matches the polymorphic structure used in the official PiPup API.
 *
 * @property duration How long (in seconds) the notification should be displayed.
 * @property position The screen position (0-4). See [Position].
 * @property title The primary heading of the notification.
 * @property animationType The entry animation style (0-10).
 * @property animationDuration The duration of the entry animation in ms.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PopupProps(
    val duration: Int = 10,
    val position: Int = 0,
    val title: String? = null,
    val titleSize: Float = 24f,
    val titleColor: String = "#FFFFFF",
    val message: String? = null,
    val messageSize: Float = 16f,
    val messageColor: String = "#FFFFFF",
    val backgroundColor: String = "#CC000000",

    val media: Media? = null,
    val mediaPosition: Int? = null, // 0: Top, 1: Bottom, 2: Left, 3: Right
    val animationType: Int = 0, // 0: None, 1: Fade, 2: Slide
    val animationDuration: Int = 500,
    val animationExit: Boolean = false,

    // Advanced Styling (from Settings)
    val borderRadius: Int = 0,
    val borderWidth: Int = 0,
    val borderColor: String = "#00000000",

    // Internal/Extra
    val contentPadding: Int? = null,
    val titleAlignment: Int = 0, // 0: Left, 1: Center, 2: Right
    val messageAlignment: Int = 0,
    val image: String? = null,
    val imageWidth: Int? = null,
    val cache: Boolean = true,
    val scale: Boolean = true
) {
    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.WRAPPER_OBJECT)
    @JsonSubTypes(
        JsonSubTypes.Type(Media.Video::class, name = "video"),
        JsonSubTypes.Type(Media.Image::class, name = "image"),
        JsonSubTypes.Type(Media.Web::class, name = "web")
    )
    sealed class Media {
        data class Video(
            val uri: String,
            val width: Int = 480,
            val scale: Boolean = true
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

    fun getBackgroundColorInt(): Int = safeParseColor(backgroundColor, "#CC000000".toColorInt())
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
