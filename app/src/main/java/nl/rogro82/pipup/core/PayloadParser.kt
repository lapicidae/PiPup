package nl.rogro82.pipup.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.calculateInSampleSize
import java.io.InputStream

/**
 * Responsible for parsing incoming NanoHTTPD sessions into [PopupProps].
 * Handles both JSON and Multipart/form-data.
 */
class PayloadParser(private val objectMapper: ObjectMapper) {

    companion object {
        private const val TAG = "PayloadParser"
        private const val MAX_BODY_SIZE = 20 * 1024 * 1024 // 20MB
    }

    fun parse(session: NanoHTTPD.IHTTPSession): PopupProps? {
        val contentType = session.headers["content-type"] ?: ""
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
            objectMapper.readValue(content, PopupProps::class.java)
        } else null
    } catch (e: Exception) {
        Log.e(TAG, "JSON parsing error", e)
        null
    }

    private fun parseMultipart(session: NanoHTTPD.IHTTPSession): PopupProps? = try {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength in 1..MAX_BODY_SIZE) {
            val rawBody = session.inputStream.readExactBytes(contentLength)
            MultipartHelper(rawBody).parse()
        } else null
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

    private class MultipartHelper(private val rawBody: ByteArray) {

        fun parse(): PopupProps {
            val title = getRawPart("title")
            val message = getRawPart("message")
            val duration = getRawPart("duration")?.toIntOrNull() ?: 10
            val position = getRawPart("position")?.toIntOrNull() ?: 0
            val bgColor = getRawPart("backgroundColor") ?: "#CC000000"
            val scale = getRawPart("scale")?.toBoolean() ?: true

            val titleSize = getRawPart("titleSize")?.toFloatOrNull() ?: AppSettings.DEFAULT_TITLE_SIZE
            val titleColor = getRawPart("titleColor") ?: AppSettings.DEFAULT_TITLE_COLOR
            val messageSize = getRawPart("messageSize")?.toFloatOrNull() ?: AppSettings.DEFAULT_MSG_SIZE
            val messageColor = getRawPart("messageColor") ?: AppSettings.DEFAULT_MSG_COLOR
            val borderRadius = getRawPart("borderRadius")?.toIntOrNull() ?: AppSettings.DEFAULT_RADIUS
            val borderWidth = getRawPart("borderWidth")?.toIntOrNull() ?: AppSettings.DEFAULT_BORDER_WIDTH
            val borderColor = getRawPart("borderColor") ?: AppSettings.DEFAULT_BORDER_COLOR
            val titleAlignment = getRawPart("titleAlignment")?.toIntOrNull() ?: 0
            val messageAlignment = getRawPart("messageAlignment")?.toIntOrNull() ?: 0
            val mediaPosition = getRawPart("mediaPosition")?.toIntOrNull()
            val animationType = getRawPart("animationType")?.toIntOrNull() ?: 0
            val animationDuration = getRawPart("animationDuration")?.toIntOrNull() ?: 500
            val animationExit = getRawPart("animationExit")?.toBoolean() ?: false
            val overwrite = getRawPart("overwrite")?.toBoolean() ?: false

            var media: PopupProps.Media? = null
            getPartBytes("image")?.let { imageBytes ->
                if (imageBytes.isNotEmpty()) {
                    val bitmap = decodeBitmap(imageBytes)
                    if (bitmap != null) {
                        val imageWidth = getRawPart("imageWidth")?.toIntOrNull() ?: 480
                        media = PopupProps.Media.Bitmap(bitmap, imageWidth)
                    }
                }
            }

            return PopupProps(
                title = title, message = message, duration = duration, position = position,
                backgroundColor = bgColor, titleSize = titleSize, titleColor = titleColor,
                messageSize = messageSize, messageColor = messageColor, borderRadius = borderRadius,
                borderWidth = borderWidth, borderColor = borderColor, titleAlignment = titleAlignment,
                messageAlignment = messageAlignment, mediaPosition = mediaPosition,
                animationType = animationType, animationDuration = animationDuration,
                animationExit = animationExit, overwrite = overwrite, scale = scale, media = media
            )
        }

        private fun decodeBitmap(bytes: ByteArray): Bitmap? = try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.inSampleSize = calculateInSampleSize(options, 1920, 1080)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap decode error", e)
            null
        }

        private fun getRawPart(name: String): String? = getPartBytes(name)?.let { String(it, Charsets.UTF_8).trim() }

        private fun getPartBytes(name: String): ByteArray? {
            val searchStr = "name=\"$name\"".toByteArray(Charsets.US_ASCII)
            val start = rawBody.indexOf(searchStr)
            if (start == -1) return null

            val contentStart = rawBody.indexOf(byteArrayOf(13, 10, 13, 10), start).let { if (it == -1) -1 else it + 4 }
            if (contentStart == -1) return null

            val contentEnd = rawBody.indexOf(byteArrayOf(13, 10, '-'.code.toByte()), contentStart)
            return if (contentEnd != -1) rawBody.copyOfRange(contentStart, contentEnd) else rawBody.copyOfRange(contentStart, rawBody.size)
        }

        private fun ByteArray.indexOf(pattern: ByteArray, startIndex: Int = 0): Int {
            for (i in startIndex..size - pattern.size) {
                var found = true
                for (j in pattern.indices) {
                    if (this[i + j] != pattern[j]) {
                        found = false
                        break
                    }
                }
                if (found) return i
            }
            return -1
        }
    }
}
