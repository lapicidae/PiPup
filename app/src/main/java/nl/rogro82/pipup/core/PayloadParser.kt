package nl.rogro82.pipup.core

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.colorToHex
import nl.rogro82.pipup.readExactBytes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Responsible for parsing incoming NanoHTTPD sessions into [PopupProps].
 * Handles both JSON (with or without explicit Content-Type, including chunked streams)
 * and Multipart/form-data.
 */
class PayloadParser(private val context: Context) {

    /** Payload size limits and asynchronous cache file cleanup helpers. */
    companion object {
        private const val TAG = "PayloadParser"
        private const val CLEANUP_THRESHOLD_MS = 60 * 60 * 1000L // 1 hour
        private const val STREAM_BUFFER_BYTES = 8192
        private const val PIXELS_PER_MEGAPIXEL = 1_000_000L
        /** Maximum allowed JSON payload size in bytes (256 KB). */
        const val MAX_JSON_BODY_BYTES = 256 * 1024
        /** Maximum allowed multipart payload size in bytes (32 MB). */
        const val MAX_MULTIPART_BODY_BYTES = 32L * 1024 * 1024
        /** Maximum allowed uploaded image pixel count (32 MP) to guard against OOM bombs. */
        const val MAX_IMAGE_PIXELS = 32_000_000L

        /** Single thread executor for all cache-related file operations to avoid I/O contention. */
        private val fileExecutor = Executors.newSingleThreadExecutor()

        /**
         * Drains [stream] into a [ByteArray], enforcing an upper limit of [maxBytes].
         * @throws IllegalArgumentException if the stream exceeds [maxBytes].
         */
        fun readBytesWithLimit(stream: InputStream, maxBytes: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                require(out.size() + n <= maxBytes) { "body too large (max $maxBytes)" }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /**
         * Reads a UTF-8 JSON request body from [session] up to [maxBytes], supporting both
         * `Content-Length` and chunked transfer streams.
         *
         * @param session The incoming HTTP session to read from.
         * @param maxBytes Maximum permitted body size in bytes.
         * @param requireChunkedHeader When `true`, only drains an unsized stream if
         * `Transfer-Encoding: chunked` is declared (used by endpoints where an empty body is valid).
         * @throws IllegalArgumentException if the body exceeds [maxBytes].
         */
        fun readJsonBody(
            session: NanoHTTPD.IHTTPSession,
            maxBytes: Int = MAX_JSON_BODY_BYTES,
            requireChunkedHeader: Boolean = false,
        ): String {
            val declaredLength = session.headers["content-length"]?.toIntOrNull()
            if (declaredLength != null) {
                require(declaredLength <= maxBytes) {
                    "body too large ($declaredLength bytes, max $maxBytes)"
                }
            }
            val isChunked = session.headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
            val bytes = when {
                declaredLength != null && declaredLength > 0 -> session.inputStream.readExactBytes(declaredLength)
                declaredLength == null && (!requireChunkedHeader || isChunked) ->
                    readBytesWithLimit(session.inputStream, maxBytes)
                else -> return ""
            }
            return String(bytes, Charsets.UTF_8).trim()
        }

        /**
         * Parses common truthy (`"true"`, `"1"`, `"on"`) and falsy (`"false"`, `"0"`, `"off"`)
         * query/header/JSON boolean strings, or returns `null` if unset or unrecognized.
         */
        fun parseFlexibleBoolean(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
            "true", "1", "on" -> true
            "false", "0", "off" -> false
            else -> null
        }

        /**
         * Safely deletes a file in the background.
         */
        fun deleteFileAsync(path: String) {
            fileExecutor.execute {
                try {
                    val file = File(path)
                    if (file.exists() && file.delete()) {
                        Log.d("PayloadParser", "Deleted cache file: $path")
                    }
                } catch (_: Exception) {}
            }
        }

        private fun Map<String, List<String>>.firstVal(key: String): String? = this[key]?.firstOrNull()

        private fun Map<String, List<String>>.optInt(key: String, default: Int): Int =
            firstVal(key)?.toIntOrNull() ?: default

        private fun Map<String, List<String>>.optFloat(key: String, default: Float): Float =
            firstVal(key)?.toFloatOrNull() ?: default

        private fun Map<String, List<String>>.optStrictBool(key: String, default: Boolean = false): Boolean =
            firstVal(key)?.toBooleanStrictOrNull() ?: default
    }

    private val isCleaningUp = AtomicBoolean(false)

    /**
     * Parses the incoming session body and parameters into [PopupProps].
     *
     * @param session The NanoHTTPD session.
     * @return The parsed properties, or null on error.
     */
    fun parse(session: NanoHTTPD.IHTTPSession): PopupProps? {
        val headers = session.headers
        var contentType = headers["content-type"] ?: ""

        // Force UTF-8 for multipart requests if not specified
        if (contentType.contains("multipart/form-data", ignoreCase = true) &&
            !contentType.contains("charset", ignoreCase = true)) {
            contentType = "$contentType; charset=utf-8"
            headers["content-type"] = contentType
        }

        return if (contentType.contains("multipart/form-data", ignoreCase = true)) {
            parseMultipart(session)
        } else {
            parseJson(session)
        }
    }

    private fun parseJson(session: NanoHTTPD.IHTTPSession): PopupProps? {
        val text = readJsonBody(session)
        return if (text.startsWith("{")) PopupProps.fromJson(text) else null
    }

    private fun parseMultipart(session: NanoHTTPD.IHTTPSession): PopupProps? = try {
        val declaredLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        require(declaredLength <= MAX_MULTIPART_BODY_BYTES) {
            "body too large ($declaredLength bytes, max $MAX_MULTIPART_BODY_BYTES)"
        }

        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val parms = session.parameters
        val scale = parms.firstVal("scale")?.toBoolean() ?: true
        val media = files["image"]?.let { tempPath ->
            val rawImageWidth = parms.firstVal("imageWidth")?.toIntOrNull()?.takeIf { it > 0 }
            persistMultipartImage(tempPath, rawImageWidth, scale)
        }
        buildMultipartPopupProps(parms, scale, media)
    } catch (e: IOException) {
        Log.e(TAG, "Multipart parsing error", e)
        null
    } catch (e: NanoHTTPD.ResponseException) {
        Log.e(TAG, "Multipart parsing error", e)
        null
    } catch (e: SecurityException) {
        Log.e(TAG, "Multipart parsing error", e)
        null
    }

    private fun buildMultipartPopupProps(
        parms: Map<String, List<String>>,
        scale: Boolean,
        media: PopupProps.Media?,
    ): PopupProps {
        val title = parms.firstVal("title")
        val message = parms.firstVal("message")
        Log.d(TAG, "Parsed multipart text: title=$title, message=$message")
        val rawAnimation = parms.firstVal("animation") ?: parms.firstVal("animationType")
        return PopupProps(
            id = parms.firstVal("id"),
            title = title,
            message = message,
            duration = parms.optInt("duration", PopupProps.DEFAULT_DURATION_SEC),
            position = PopupProps.parsePositionIndex(parms.firstVal("position")),
            backgroundColor = parms.firstVal("backgroundColor") ?: "#CC000000",
            titleSize = parms.optFloat("titleSize", AppSettings.DEFAULT_TITLE_SIZE),
            titleColor = parms.firstVal("titleColor") ?: context.colorToHex(R.color.preset_platinum),
            messageSize = parms.optFloat("messageSize", AppSettings.DEFAULT_MSG_SIZE),
            messageColor = parms.firstVal("messageColor") ?: context.colorToHex(R.color.preset_silver),
            borderRadius = (parms.firstVal("borderRadius") ?: parms.firstVal("cornerRadius"))
                ?.toFloatOrNull()?.toInt(),
            borderWidth = parms.firstVal("borderWidth")?.toIntOrNull(),
            borderColor = parms.firstVal("borderColor"),
            contentPadding = (parms.firstVal("contentPadding") ?: parms.firstVal("padding"))?.toIntOrNull(),
            titleAlignment = parms.optInt("titleAlignment", 0),
            messageAlignment = parms.optInt("messageAlignment", 0),
            mediaPosition = parms.firstVal("mediaPosition")?.toIntOrNull(),
            animationType = PopupProps.parseAnimation(rawAnimation),
            animationDuration = parms.optInt("animationDuration", PopupProps.DEFAULT_ANIMATION_DURATION_MS),
            animationExit = parms.firstVal("animationExit")?.toBoolean() ?: false,
            overwrite = parms.firstVal("overwrite")?.toBoolean() ?: false,
            scale = scale,
            media = media,
        ).withMultipartExtendedFields(parms)
    }

    private fun PopupProps.withMultipartExtendedFields(parms: Map<String, List<String>>): PopupProps = copy(
        urgency = PopupProps.parseUrgency(parms.firstVal("urgency")),
        opacity = PopupProps.parseOpacity(parms.firstVal("opacity")),
        bringToFront = parms.optStrictBool("bringToFront"),
        dismissScreensaver = parms.optStrictBool("dismissScreensaver", default = true),
        showProgress = parms.optStrictBool("showProgress"),
        icon = PopupProps.Icon.parse(parms.firstVal("icon")),
        iconPosition = PopupProps.parseIconPosition(parms.firstVal("iconPosition")),
        iconWidth = PopupProps.parseIconWidth(parms.firstVal("iconWidth")),
        stack = parms.firstVal("stack")?.trim()?.lowercase()
            ?.takeIf { it == "vertical" || it == "horizontal" },
        mediaFullBleed = parms.firstVal("mediaFullBleed")?.toBooleanStrictOrNull(),
        liveBadge = parms.firstVal("liveBadge")?.toBooleanStrictOrNull(),
        liveBadgeColor = parms.firstVal("liveBadgeColor"),
        liveBadgePosition = parms.firstVal("liveBadgePosition"),
        fullscreen = parms.optStrictBool("fullscreen"),
        fullscreenUpscale = (parms.firstVal("fullscreenUpscale") ?: parms.firstVal("upscale"))
            ?.toBooleanStrictOrNull(),
        dismissOnBack = parms.optStrictBool("dismissOnBack"),
        closeButton = PopupProps.parseCloseButton(parms.firstVal("closeButton")),
        closeButtonTimeout = PopupProps.parseCloseButtonTimeout(parms.firstVal("closeButtonTimeout")),
        closeButtonSize = PopupProps.parseCloseButtonSize(parms.firstVal("closeButtonSize")),
    )

    private fun persistMultipartImage(
        tempPath: String,
        rawImageWidth: Int?,
        scale: Boolean,
    ): PopupProps.Media? {
        val imageWidth = rawImageWidth ?: PopupProps.DEFAULT_MEDIA_WIDTH
        val srcFile = File(tempPath)
        Log.d(TAG, "Multipart image received: path=$tempPath, size=${srcFile.length()}, readable=${srcFile.canRead()}")

        if (tempPath.isEmpty() || !srcFile.exists() || srcFile.length() <= 0) {
            Log.w(TAG, "Ignoring multipart image part because file is empty or missing at: $tempPath")
            return null
        }

        validateImageBounds(srcFile)

        return try {
            val persistentFile = File(
                context.cacheDir,
                "multipart_${System.currentTimeMillis()}_${(0..1000).random()}.png"
            )
            srcFile.copyTo(persistentFile, overwrite = true)
            Log.d(
                TAG,
                "Successfully persisted image to: ${persistentFile.absolutePath}, " +
                    "final size=${persistentFile.length()}"
            )
            triggerCacheCleanup()
            PopupProps.Media.LocalFile(
                persistentFile.absolutePath,
                imageWidth,
                scale = scale,
                explicitWidth = rawImageWidth != null
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy multipart file from $tempPath to persistent cache", e)
            null
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to copy multipart file from $tempPath to persistent cache", e)
            null
        }
    }

    private fun validateImageBounds(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        require(w > 0 && h > 0) { "could not decode the uploaded image" }
        val pixels = w.toLong() * h.toLong()
        require(pixels <= MAX_IMAGE_PIXELS) {
            "image too large (${w}x${h}, max ${MAX_IMAGE_PIXELS / PIXELS_PER_MEGAPIXEL} MP)"
        }
    }

    /**
     * Triggers the asynchronous cleanup of old multipart cache files.
     */
    private fun triggerCacheCleanup() {
        if (isCleaningUp.compareAndSet(false, true)) {
            fileExecutor.execute {
                try {
                    cleanupMultipartCache()
                } finally {
                    isCleaningUp.set(false)
                }
            }
        }
    }

    /**
     * Removes old multipart cache files that were leaked (e.g. after a crash).
     */
    private fun cleanupMultipartCache() {
        try {
            val files = context.cacheDir.listFiles { _, name -> name.startsWith("multipart_") } ?: return
            val now = System.currentTimeMillis()
            files.forEach { file ->
                if (now - file.lastModified() > CLEANUP_THRESHOLD_MS && file.delete()) {
                    Log.d(TAG, "Cleaned up leaked multipart cache file: ${file.name}")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Error during multipart cache cleanup", e)
        }
    }
}
