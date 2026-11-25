package nl.rogro82.pipup.core

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.colorToHex
import nl.rogro82.pipup.readExactBytes
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Responsible for parsing incoming NanoHTTPD sessions into [PopupProps].
 * Handles both JSON and Multipart/form-data.
 */
class PayloadParser(private val context: Context) {

    /** Payload size limits and asynchronous cache file cleanup helpers. */
    companion object {
        private const val TAG = "PayloadParser"
        private const val CLEANUP_THRESHOLD_MS = 60 * 60 * 1000L // 1 hour
        const val MAX_JSON_BODY_BYTES = 256 * 1024 // 256 KB limit
        const val MAX_MULTIPART_BODY_BYTES = 10 * 1024 * 1024 // 10 MB limit

        /** Single thread executor for all cache-related file operations to avoid I/O contention. */
        private val fileExecutor = Executors.newSingleThreadExecutor()

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

        private fun Map<String, List<String>>.optStrictBool(key: String): Boolean =
            firstVal(key)?.toBooleanStrictOrNull() ?: false
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

        return when {
            contentType.contains("application/json", ignoreCase = true) -> parseJson(session)
            contentType.contains("multipart/form-data", ignoreCase = true) -> parseMultipart(session)
            else -> null
        }
    }

    private fun parseJson(session: NanoHTTPD.IHTTPSession): PopupProps? {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > MAX_JSON_BODY_BYTES) {
            Log.w(TAG, "Rejecting JSON payload: body too large ($contentLength bytes, max $MAX_JSON_BODY_BYTES)")
            throw IllegalArgumentException("body too large ($contentLength bytes, max $MAX_JSON_BODY_BYTES)")
        } else if (contentLength > 0) {
            val content = session.inputStream.readExactBytes(contentLength)
            return PopupProps.fromJson(String(content, Charsets.UTF_8))
        }
        return null
    }

    private fun parseMultipart(session: NanoHTTPD.IHTTPSession): PopupProps? = try {
        val declaredLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (declaredLength > MAX_MULTIPART_BODY_BYTES) {
            Log.w(
                TAG,
                "Rejecting multipart payload: body too large ($declaredLength bytes, max $MAX_MULTIPART_BODY_BYTES)",
            )
            throw IllegalArgumentException("body too large ($declaredLength bytes, max $MAX_MULTIPART_BODY_BYTES)")
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
    } catch (e: IllegalArgumentException) {
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
        val borderRadius = (parms.firstVal("borderRadius") ?: parms.firstVal("cornerRadius"))
            ?.toFloatOrNull()?.toInt() ?: AppSettings.DEFAULT_RADIUS
        val contentPadding = (parms.firstVal("contentPadding") ?: parms.firstVal("padding"))?.toIntOrNull()
        val stack = parms.firstVal("stack")?.trim()?.lowercase()
            ?.takeIf { it == "vertical" || it == "horizontal" }
        val fullscreenUpscale = (parms.firstVal("fullscreenUpscale") ?: parms.firstVal("upscale"))
            ?.toBooleanStrictOrNull()

        return PopupProps(
            title = title,
            message = message,
            duration = parms.optInt("duration", PopupProps.DEFAULT_DURATION_SEC),
            position = PopupProps.parsePositionIndex(parms.firstVal("position")),
            backgroundColor = parms.firstVal("backgroundColor") ?: "#CC000000",
            titleSize = parms.optFloat("titleSize", AppSettings.DEFAULT_TITLE_SIZE),
            titleColor = parms.firstVal("titleColor") ?: context.colorToHex(R.color.preset_platinum),
            messageSize = parms.optFloat("messageSize", AppSettings.DEFAULT_MSG_SIZE),
            messageColor = parms.firstVal("messageColor") ?: context.colorToHex(R.color.preset_silver),
            borderRadius = borderRadius,
            borderWidth = parms.optInt("borderWidth", AppSettings.DEFAULT_BORDER_WIDTH),
            borderColor = parms.firstVal("borderColor") ?: context.colorToHex(R.color.preset_gunmetal),
            contentPadding = contentPadding,
            titleAlignment = parms.optInt("titleAlignment", 0),
            messageAlignment = parms.optInt("messageAlignment", 0),
            mediaPosition = parms.firstVal("mediaPosition")?.toIntOrNull(),
            animationType = parms.optInt("animationType", 0),
            animationDuration = parms.optInt("animationDuration", PopupProps.DEFAULT_ANIMATION_DURATION_MS),
            animationExit = parms.firstVal("animationExit")?.toBoolean() ?: false,
            overwrite = parms.firstVal("overwrite")?.toBoolean() ?: false,
            scale = scale,
            media = media,
            stack = stack,
            mediaFullBleed = parms.firstVal("mediaFullBleed")?.toBooleanStrictOrNull(),
            liveBadge = parms.firstVal("liveBadge")?.toBooleanStrictOrNull(),
            liveBadgeColor = parms.firstVal("liveBadgeColor"),
            liveBadgePosition = parms.firstVal("liveBadgePosition"),
            fullscreen = parms.optStrictBool("fullscreen"),
            fullscreenUpscale = fullscreenUpscale,
            dismissOnBack = parms.optStrictBool("dismissOnBack"),
            closeButton = PopupProps.parseCloseButton(parms.firstVal("closeButton")),
            closeButtonTimeout = PopupProps.parseCloseButtonTimeout(parms.firstVal("closeButtonTimeout")),
            closeButtonSize = PopupProps.parseCloseButtonSize(parms.firstVal("closeButtonSize")),
        )
    }

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
