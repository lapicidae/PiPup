package nl.rogro82.pipup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import fi.iki.elonen.NanoHTTPD
import java.util.LinkedList
import java.util.Queue

/**
 * Background service hosting the PiPup API server.
 * Manages notification queue, display synchronization, and animations.
 *
 * This service runs as a foreground service to ensure it isn't killed by the system
 * while waiting for notifications. It manages a [WindowManager] overlay to display
 * popups above all other content.
 */
@UnstableApi
class PipUpService : Service() {

    private val mHandler = Handler(Looper.getMainLooper())

    private val mWindowManager: WindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private val mObjectMapper: ObjectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private var mOverlay: FrameLayout? = null
    private var mCurrentPopup: PopupView? = null
    private var mNextPopup: PopupView? = null
    private var mNextProps: PopupProps? = null
    private var mPreparingProps: PopupProps? = null
    private var mPreparingView: PopupView? = null
    private var mWebServer: WebServer? = null
    private val mSettings by lazy { AppSettings(this) }

    // Pre-calculated UI metrics
    private val baseMarginPx by lazy { Utils.dpToPx(this, 20) }

    private val mNotificationQueue: Queue<PopupProps> = LinkedList()
    private var mIsPreparing = false
    private val mDurationToken = Any()

    /**
     * Initializes the service, sets up the foreground notification, and starts the web server.
     */
    override fun onCreate() {
        super.onCreate()
        initNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PiPup")
            .setContentText("Listening for notifications...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(PIPUP_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(PIPUP_NOTIFICATION_ID, notification)
        }

        mWebServer = WebServer(PIPUP_SERVER_PORT, object : WebServer.Handler {
            override fun handleHttpRequest(session: NanoHTTPD.IHTTPSession?): NanoHTTPD.Response {
                return this@PipUpService.handleHttpRequest(session)
            }
        })

        try {
            mWebServer?.start()
            Log.i(LOG_TAG, "Server started on port $PIPUP_SERVER_PORT")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to start server", e)
        }
    }

    /**
     * Stops the web server and removes any existing overlay before the service is destroyed.
     */
    override fun onDestroy() {
        mWebServer?.stop()
        removeOverlayFromWindowManager()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun initNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Removes the current popup from the screen and processes the next one in the queue.
     * If a next popup is already prepared, it is shown immediately.
     */
    private fun removePopup() {
        Log.d(LOG_TAG, "Removing current popup and checking for next...")
        mHandler.removeCallbacksAndMessages(mDurationToken)

        mCurrentPopup?.let {
            mOverlay?.removeView(it)
            mCurrentPopup = null
        }

        // If we have a next popup already prepared, show it immediately
        if (mNextPopup != null && mNextProps != null) {
            Log.d(LOG_TAG, "Next popup already prepared, showing: ${mNextProps?.title}")
            val nextView = mNextPopup!!
            val nextProps = mNextProps!!
            mNextPopup = null
            mNextProps = null
            showPopup(nextView, nextProps)
        } else {
            // Otherwise try to process the queue
            processNextNotification()
        }
    }

    private fun removeOverlayFromWindowManager() {
        mOverlay?.let {
            try {
                mWindowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error removing overlay", e)
            }
            mOverlay = null
        }
    }

    /**
     * Ensures that the [FrameLayout] overlay exists in the [WindowManager].
     *
     * @return The overlay container.
     */
    private fun ensureOverlay(): FrameLayout {
        mOverlay?.let { return it }
        val overlay = FrameLayout(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        mWindowManager.addView(overlay, params)
        mOverlay = overlay
        return overlay
    }

    /**
     * Handles incoming HTTP requests and routes them to appropriate logic.
     * Supported endpoints:
     * - `/notify`: Enqueues a new notification.
     * - `/cancel`: Clears the notification queue.
     * - `/settings`: GET or POST application settings.
     *
     * @param session The NanoHTTPD session.
     * @return A response to the HTTP client.
     */
    private fun handleHttpRequest(session: NanoHTTPD.IHTTPSession?): NanoHTTPD.Response {
        if (session == null) return invalidRequest("Null session")
        val uri = session.uri.lowercase()
        Log.d(LOG_TAG, ">>> HTTP ${session.method} $uri")

        return try {
            when (uri) {
                "/cancel" -> {
                    mHandler.post {
                        mNotificationQueue.clear()
                        // Cancel current preparation
                        mHandler.removeCallbacksAndMessages(SAFETY_TIMEOUT_TOKEN)
                        mIsPreparing = false
                        mPreparingProps = null
                        mPreparingView?.let { mOverlay?.removeView(it) }
                        mPreparingView = null

                        // Clear buffered next popup
                        mNextPopup?.let { mOverlay?.removeView(it) }
                        mNextPopup = null
                        mNextProps = null

                        removePopup()
                    }
                    ok("Notification queue cleared")
                }
                "/notify", "/", "/api/notify" -> {
                    val popup = parsePayload(session)
                    if (popup != null) {
                        // Apply styling defaults from AppSettings only if values are "zero/default" in props
                        val finalProps = popup.copy(
                            backgroundColor = if (popup.backgroundColor == "#CC000000") mSettings.getFullBackgroundColor() else popup.backgroundColor,
                            borderRadius = if (popup.borderRadius == 0) mSettings.borderRadius else popup.borderRadius,
                            borderWidth = if (popup.borderWidth == 0) mSettings.borderWidth else popup.borderWidth,
                            borderColor = if (popup.borderColor == "#00000000") mSettings.borderColor else popup.borderColor,
                            titleAlignment = if (popup.titleAlignment == 0) mSettings.titleAlignment else popup.titleAlignment,
                            messageAlignment = if (popup.messageAlignment == 0) mSettings.messageAlignment else popup.messageAlignment,
                            mediaPosition = popup.mediaPosition ?: mSettings.mediaPosition,
                            animationType = if (popup.animationType == 0) mSettings.animationType else popup.animationType,
                            animationDuration = if (popup.animationDuration == 500) mSettings.animationDuration else popup.animationDuration
                        )
                        Log.i(LOG_TAG, "Enqueuing notification: ${finalProps.title}")
                        Log.d(LOG_TAG, "Message length: ${finalProps.message?.length ?: 0}")
                        if ((finalProps.message?.length ?: 0) > 0) {
                            Log.v(LOG_TAG, "Message snippet: ${finalProps.message?.take(100)}...")
                        }
                        enqueueNotification(finalProps)
                        ok("Notification enqueued")
                    } else {
                        Log.e(LOG_TAG, "Failed to parse payload")
                        invalidRequest("Failed to parse payload")
                    }
                }
                "/settings" -> {
                    if (session.method == NanoHTTPD.Method.GET) {
                        val json = mObjectMapper.writeValueAsString(mSettings.getAll())
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json)
                    } else if (session.method == NanoHTTPD.Method.POST) {
                        val contentLength = session.headers["content-length"]?.toInt() ?: 0
                        if (contentLength > 0) {
                            val buffer = ByteArray(contentLength)
                            var totalRead = 0
                            while (totalRead < contentLength) {
                                val read = session.inputStream.read(buffer, totalRead, contentLength - totalRead)
                                if (read <= 0) break
                                totalRead += read
                            }
                            val data = mObjectMapper.readValue(buffer, AppSettings.SettingsData::class.java)
                            mHandler.post { mSettings.apply(data) }
                            ok("Settings updated")
                        } else {
                            invalidRequest("Empty payload")
                        }
                    } else {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method Not Allowed")
                    }
                }
                else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error handling request", e)
            invalidRequest(e.message)
        }
    }

    /**
     * Parses the incoming request payload (JSON or Multipart) into a [PopupProps] object.
     *
     * @param session The NanoHTTPD session.
     * @return The parsed properties, or null if parsing fails.
     */
    private fun parsePayload(session: NanoHTTPD.IHTTPSession): PopupProps? {
        val contentType = (session.headers["content-type"] ?: "").lowercase()

        return try {
            if (contentType.contains("application/json")) {
                Log.d(LOG_TAG, "Parsing JSON payload using direct byte stream")
                // Optimization: Read raw bytes from inputStream to avoid NanoHTTPD's incorrect string decoding
                val contentLength = session.headers["content-length"]?.toInt() ?: 0
                if (contentLength > 0) {
                    val content = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = session.inputStream.read(content, read, contentLength - read)
                        if (r <= 0) break
                        read += r
                    }
                    mObjectMapper.readValue(content, PopupProps::class.java)
                } else null
            } else if (contentType.contains("multipart/form-data")) {
                Log.d(LOG_TAG, "Parsing Multipart payload using raw byte extraction")

                // 1. Read the entire body as raw bytes to bypass NanoHTTPD's destructive string parsing
                val contentLength = session.headers["content-length"]?.toInt() ?: 0
                val rawBody = if (contentLength > 0 && contentLength < 20 * 1024 * 1024) { // Max 20MB
                    val buffer = ByteArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = session.inputStream.read(buffer, totalRead, contentLength - totalRead)
                        if (read <= 0) break
                        totalRead += read
                    }
                    buffer
                } else null

                fun getPartBytes(name: String): ByteArray? {
                    if (rawBody == null) return null
                    val searchStr = "name=\"$name\"".toByteArray(Charsets.US_ASCII)
                    var start = -1
                    for (i in 0 until rawBody.size - searchStr.size) {
                        var match = true
                        for (j in searchStr.indices) {
                            if (rawBody[i + j] != searchStr[j]) { match = false; break }
                        }
                        if (match) { start = i; break }
                    }
                    if (start == -1) return null

                    var contentStart = -1
                    for (i in start until rawBody.size - 4) {
                        if (rawBody[i] == 13.toByte() && rawBody[i+1] == 10.toByte() &&
                            rawBody[i+2] == 13.toByte() && rawBody[i+3] == 10.toByte()) {
                            contentStart = i + 4
                            break
                        }
                    }
                    if (contentStart == -1) return null

                    var contentEnd = rawBody.size
                    for (i in contentStart until rawBody.size - 4) {
                        if (rawBody[i] == 13.toByte() && rawBody[i+1] == 10.toByte() && rawBody[i+2] == '-'.code.toByte()) {
                            contentEnd = i
                            break
                        }
                    }
                    return rawBody.copyOfRange(contentStart, contentEnd)
                }

                fun getRawPart(name: String): String? {
                    val bytes = getPartBytes(name) ?: return null
                    return String(bytes, Charsets.UTF_8).trim()
                }

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

                var media: PopupProps.Media? = null
                val imageBytes = getPartBytes("image")
                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    try {
                        Log.d(LOG_TAG, "Image bytes extracted: ${imageBytes.size} bytes")
                        val bitmap: Bitmap? = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        if (bitmap != null) {
                            val imageWidth = getRawPart("imageWidth")?.toIntOrNull() ?: 480
                            media = PopupProps.Media.Bitmap(bitmap, imageWidth)
                        } else {
                            Log.e(LOG_TAG, "Failed to decode bitmap from extracted bytes")
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Error decoding image: ${e.message}")
                    }
                }

                PopupProps(
                    title = title,
                    message = message,
                    duration = duration,
                    position = position,
                    backgroundColor = bgColor,
                    titleSize = titleSize,
                    titleColor = titleColor,
                    messageSize = messageSize,
                    messageColor = messageColor,
                    borderRadius = borderRadius,
                    borderWidth = borderWidth,
                    borderColor = borderColor,
                    titleAlignment = titleAlignment,
                    messageAlignment = messageAlignment,
                    mediaPosition = mediaPosition,
                    animationType = animationType,
                    animationDuration = animationDuration,
                    scale = scale,
                    media = media
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Payload parsing error", e)
            null
        }
    }

    /**
     * Adds a notification properties object to the queue.
     *
     * @param props The properties defining the notification.
     */
    private fun enqueueNotification(props: PopupProps) {
        mNotificationQueue.add(props)
        Log.d(LOG_TAG, "Queue size: ${mNotificationQueue.size}, Preparing: $mIsPreparing")
        if (mCurrentPopup == null && !mIsPreparing) {
            processNextNotification()
        }
    }

    /**
     * Checks if a notification can be processed and initiates preparation.
     */
    private fun processNextNotification() {
        // Stricter flow: only prepare if not already preparing AND the next slot is empty
        if (mIsPreparing || mNextPopup != null || mNotificationQueue.isEmpty()) {
            Log.v(LOG_TAG, "Skipping processNextNotification: preparing=$mIsPreparing, next=${mNextPopup != null}, queue=${mNotificationQueue.size}")
            return
        }

        val props = mNotificationQueue.poll() ?: return
        mIsPreparing = true
        mPreparingProps = props
        Log.d(LOG_TAG, "Preparing notification: ${props.title}")
        mHandler.post { preparePopup(props) }
    }

    /**
     * Prepares the [PopupView] for display. This includes loading media (images/video).
     *
     * @param props The properties defining the notification.
     */
    private fun preparePopup(props: PopupProps) {
        val popupView = PopupView(this, props)
        mPreparingView = popupView

        popupView.readyListener = object : PopupView.ReadyListener {
            override fun onReady() {
                Log.d(LOG_TAG, "Popup signaled READY: ${props.title}")
                mHandler.post { handlePopupReady(popupView, props) }
            }
        }

        // Safety timeout - ensure we always clean up mIsPreparing
        val isVideo = props.media is PopupProps.Media.Video
        val timeoutTime = SystemClock.uptimeMillis() + (if (isVideo) 20000 else 10000)
        mHandler.postAtTime({
            Log.w(LOG_TAG, "Media load timeout reached for ${props.title}")
            handlePopupReady(popupView, props)
        }, SAFETY_TIMEOUT_TOKEN, timeoutTime)

        // Starts loading. Use alpha instead of isVisible=false so VideoView gets a surface to buffer.
        popupView.create()
        popupView.alpha = 0f
        popupView.isVisible = true
        addPopupToOverlay(popupView, props)
    }

    /**
     * Callback triggered when a popup signals that all its media is loaded and it's ready for display.
     *
     * @param popupView The view that is ready.
     * @param props The properties of the ready notification.
     */
    private fun handlePopupReady(popupView: PopupView, props: PopupProps) {
        // If this is the current popup already (e.g. timeout fired first), don't remove it
        if (mCurrentPopup === popupView) {
            Log.d(LOG_TAG, "Ready signal received for already showing popup: ${props.title}")
            return
        }

        // Orphan check: ignore callbacks for notifications that were already cleared or timed out
        if (props !== mPreparingProps) {
            Log.w(LOG_TAG, "Ignoring orphan READY signal for: ${props.title}")
            if (mCurrentPopup !== popupView && mNextPopup !== popupView) {
                mOverlay?.removeView(popupView)
            }
            return
        }

        mHandler.removeCallbacksAndMessages(SAFETY_TIMEOUT_TOKEN)
        mIsPreparing = false
        mPreparingProps = null
        mPreparingView = null

        if (mCurrentPopup == null) {
            // Nothing showing, display immediately
            showPopup(popupView, props)
        } else {
            // Already showing something, cache it as next
            Log.d(LOG_TAG, "Caching ${props.title} as next popup")
            mNextPopup = popupView
            mNextProps = props
        }
    }

    /**
     * Adds the popup view to the root overlay container with proper gravity and margins.
     *
     * @param popupView The view to add.
     * @param props The properties defining the position.
     */
    private fun addPopupToOverlay(popupView: PopupView, props: PopupProps) {
        val overlay = ensureOverlay()
        if (popupView.parent != null) {
            (popupView.parent as? ViewGroup)?.removeView(popupView)
        }

        val params = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = when (props.getPositionEnum()) {
                PopupProps.Position.TopRight -> Gravity.TOP or Gravity.END
                PopupProps.Position.TopLeft -> Gravity.TOP or Gravity.START
                PopupProps.Position.BottomRight -> Gravity.BOTTOM or Gravity.END
                PopupProps.Position.BottomLeft -> Gravity.BOTTOM or Gravity.START
                PopupProps.Position.Center -> Gravity.CENTER
            }
            setMargins(baseMarginPx, baseMarginPx, baseMarginPx, baseMarginPx)
        }
        overlay.addView(popupView, params)
    }

    /**
     * Displays a prepared popup on the screen and schedules its removal.
     *
     * @param popupView The view to display.
     * @param props The properties associated with the view.
     */
    private fun showPopup(popupView: PopupView, props: PopupProps) {
        if (popupView.alpha == 1f && popupView.isVisible) {
            Log.v(LOG_TAG, "showPopup ignored: already fully visible")
            return
        }

        Log.i(LOG_TAG, "Displaying popup: ${props.title} (duration: ${props.duration}s)")
        mCurrentPopup = popupView
        popupView.isVisible = true
        popupView.startMedia()

        popupView.animateIn()

        // Schedule removal
        mHandler.postAtTime({
            Log.d(LOG_TAG, "Duration expired for: ${props.title}")
            removePopup()
        }, mDurationToken, SystemClock.uptimeMillis() + (props.duration * 1000))

        // While this one is showing, start preparing the next one from the queue
        processNextNotification()
    }

    companion object {
        private const val LOG_TAG = "PipUpService"
        private const val CHANNEL_ID = "pipup_service"
        private const val CHANNEL_NAME = "PiPup Background Service"
        const val PIPUP_SERVER_PORT = 7979
        const val PIPUP_NOTIFICATION_ID = 1001
        private val SAFETY_TIMEOUT_TOKEN = Any()

        fun ok(msg: String?): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", msg ?: "OK")
        fun invalidRequest(msg: String?): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", msg ?: "Invalid")
    }
}
