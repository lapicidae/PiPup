package nl.rogro82.pipup.core

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.Json
import nl.rogro82.pipup.PopupProps
import java.io.File
import java.io.InputStream

/**
 * Responsible for parsing incoming NanoHTTPD sessions into [PopupProps].
 * Handles both JSON and Multipart/form-data.
 */
class PayloadParser(private val context: Context) {

    companion object {
        private const val TAG = "PayloadParser"
    }

    fun parse(session: NanoHTTPD.IHTTPSession): PopupProps? {
        val headers = session.headers
        var contentType = headers["content-type"] ?: ""

        // Force UTF-8 for multipart requests if not specified
        if (contentType.contains("multipart/form-data", ignoreCase = true) &&
            !contentType.contains("charset", ignoreCase = true)) {
            contentType = "$contentType; charset=utf-8"
            headers["content-type"] = contentType
        }

        return when {
            contentType.contains("application/json", ignoreCase = true) -> parseJson(session)
            contentType.contains("multipart/form-data", ignoreCase = true) -> parseMultipart(session)
            else -> null
        }
    }

    private fun parseJson(session: NanoHTTPD.IHTTPSession): PopupProps? = try {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            val content = session.inputStream.readExactBytes(contentLength)
            Json.mapper.readValue(content, PopupProps::class.java)
        } else null
    } catch (e: Exception) {
        Log.e(TAG, "JSON parsing error", e)
        null
    }

    private fun parseMultipart(session: NanoHTTPD.IHTTPSession): PopupProps? = try {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)

        val parms = session.parameters
        fun getVal(key: String): String? = parms[key]?.firstOrNull()

        val title = getVal("title")
        val message = getVal("message")
        Log.d(TAG, "Parsed multipart text: title=$title, message=$message")
        val duration = getVal("duration")?.toIntOrNull() ?: 10
        val position = getVal("position")?.toIntOrNull() ?: 0
        val bgColor = getVal("backgroundColor") ?: "#CC000000"
        val scale = getVal("scale")?.toBoolean() ?: true

        val titleSize = getVal("titleSize")?.toFloatOrNull() ?: AppSettings.DEFAULT_TITLE_SIZE
        val titleColor = getVal("titleColor") ?: AppSettings.DEFAULT_TITLE_COLOR
        val messageSize = getVal("messageSize")?.toFloatOrNull() ?: AppSettings.DEFAULT_MSG_SIZE
        val messageColor = getVal("messageColor") ?: AppSettings.DEFAULT_MSG_COLOR
        val borderRadius = getVal("borderRadius")?.toIntOrNull() ?: AppSettings.DEFAULT_RADIUS
        val borderWidth = getVal("borderWidth")?.toIntOrNull() ?: AppSettings.DEFAULT_BORDER_WIDTH
        val borderColor = getVal("borderColor") ?: AppSettings.DEFAULT_BORDER_COLOR
        val titleAlignment = getVal("titleAlignment")?.toIntOrNull() ?: 0
        val messageAlignment = getVal("messageAlignment")?.toIntOrNull() ?: 0
        val mediaPosition = getVal("mediaPosition")?.toIntOrNull()
        val animationType = getVal("animationType")?.toIntOrNull() ?: 0
        val animationDuration = getVal("animationDuration")?.toIntOrNull() ?: 500
        val animationExit = getVal("animationExit")?.toBoolean() ?: false
        val overwrite = getVal("overwrite")?.toBoolean() ?: false

        var media: PopupProps.Media? = null
        files["image"]?.let { tempPath ->
            val imageWidth = getVal("imageWidth")?.toIntOrNull() ?: 480
            val srcFile = File(tempPath)
            val isReadable = srcFile.canRead()
            Log.d(TAG, "Multipart image received: path=$tempPath, size=${srcFile.length()}, readable=$isReadable")

            if (tempPath.isNotEmpty() && srcFile.exists() && srcFile.length() > 0) {
                // Copy to app's cache dir to prevent deletion on session close
                try {
                    val persistentFile = File(context.cacheDir, "multipart_${System.currentTimeMillis()}_${(0..1000).random()}.png")
                    srcFile.copyTo(persistentFile, overwrite = true)
                    Log.d(TAG, "Successfully persisted image to: ${persistentFile.absolutePath}, final size=${persistentFile.length()}")
                    media = PopupProps.Media.LocalFile(persistentFile.absolutePath, imageWidth)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy multipart file from $tempPath to persistent cache", e)
                }
            } else {
                Log.w(TAG, "Ignoring multipart image part because file is empty or missing at: $tempPath")
            }
        }

        PopupProps(
            title = title, message = message, duration = duration, position = position,
            backgroundColor = bgColor, titleSize = titleSize, titleColor = titleColor,
            messageSize = messageSize, messageColor = messageColor, borderRadius = borderRadius,
            borderWidth = borderWidth, borderColor = borderColor, titleAlignment = titleAlignment,
            messageAlignment = messageAlignment, mediaPosition = mediaPosition,
            animationType = animationType, animationDuration = animationDuration,
            animationExit = animationExit, overwrite = overwrite, scale = scale, media = media
        )
    } catch (e: Exception) {
        Log.e(TAG, "Multipart parsing error", e)
        null
    }

    private fun InputStream.readExactBytes(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = read(buffer, totalRead, length - totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return buffer
    }
}
