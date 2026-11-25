package nl.rogro82.pipup.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.BuildConfig
import nl.rogro82.pipup.Json
import nl.rogro82.pipup.MainActivity
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.Pusher
import nl.rogro82.pipup.R
import nl.rogro82.pipup.UpdateManager
import nl.rogro82.pipup.UpdateWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import nl.rogro82.pipup.applyAppLocaleAndTheme
import nl.rogro82.pipup.core.ModuleContext
import nl.rogro82.pipup.core.NotificationManager
import nl.rogro82.pipup.core.PowerController
import nl.rogro82.pipup.core.WebServer
import nl.rogro82.pipup.getLocalizedContext
import nl.rogro82.pipup.registerProtectedReceiver
import nl.rogro82.pipup.showToast

private data class PopupStateSnapshot(
    val activeSlots: List<Pair<PopupProps, Long>>,
    val visible: Boolean,
    val dreaming: Boolean,
    val lastPopup: PopupProps?,
    val now: Long,
)

private fun mediaInfo(p: PopupProps): Map<String, Any?>? = when (val m = p.media) {
    is PopupProps.Media.Web -> mapOf(
        "type" to "web",
        "width" to m.width,
        "height" to m.height,
        "poster" to (m.poster != null),
    )
    is PopupProps.Media.Whep -> mapOf(
        "type" to "whep",
        "width" to m.width,
        "height" to m.height,
        "poster" to (m.poster != null),
    )
    is PopupProps.Media.Video -> mapOf(
        "type" to "video",
        "width" to m.width,
        "poster" to (m.poster != null),
        "bufferMs" to m.bufferMs,
    )
    is PopupProps.Media.Image -> mapOf("type" to "image", "width" to m.width)
    is PopupProps.Media.Bitmap -> mapOf("type" to "bitmap", "width" to m.width)
    else -> null
}

private fun mediaMuted(p: PopupProps): Boolean? = when (val m = p.media) {
    is PopupProps.Media.Web -> m.muted
    is PopupProps.Media.Whep -> m.muted
    is PopupProps.Media.Video -> m.muted
    else -> null
}

/**
 * Main background service responsible for hosting the WebServer and managing the notification queue.
 *
 * This service runs as a foreground service to ensure it remains active for incoming requests.
 * It handles localized notifications, pre-warming the WebView engine, and processing API requests.
 */
@OptIn(UnstableApi::class)
class PipUpService : Service() {

    /** Service constants and singleton accessor. */
    companion object {
        private const val TAG = "PipUpService"
        private const val CHANNEL_ID = "pipup_service"
        private const val NOTIFICATION_ID = 1001
        private const val MS_PER_SECOND = 1000L
        private const val WEBSERVER_TIMEOUT_MS = 30_000
        private const val CALLBACK_TIMEOUT_MS = 5000
        /** The port on which the internal WebServer listens. */
        const val SERVER_PORT = 7979

        @SuppressLint("StaticFieldLeak")
        private var instance: PipUpService? = null

        /** Returns the currently running [PipUpService] instance, or null if stopped. */
        fun getInstance(): PipUpService? = instance
    }

    private val handler = Handler(Looper.getMainLooper())
    private val settings = PiPupApp.settings

    private lateinit var webServer: WebServer
    /** The notification manager responsible for displaying and queueing popups. */
    val notificationManager by lazy {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        NotificationManager(this, wm).apply {
            onPopupShown = { props ->
                emit("popup_shown", mapOf("shownId" to props.id))
            }
            onPopupReplaced = { props, replacedId ->
                emit("popup_replaced", mapOf("shownId" to props.id, "replacedId" to replacedId))
            }
            onPopupRemoved = { reason, removedId ->
                emit("popup_removed", mapOf("reason" to reason, "removedId" to removedId))
            }
            onStateChanged = {
                notifyLocalStateChange()
            }
        }
    }
    /** The module manager for this service. */
    val moduleManager by lazy { (application as PiPupApp).moduleManager }

    private val startedAt = SystemClock.elapsedRealtime()

    @Volatile private var dreaming = false
    private val dreamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            dreaming = intent?.action == Intent.ACTION_DREAMING_STARTED
            Log.d(TAG, "Screensaver ${if (dreaming) "started" else "stopped"}")
            emit(if (dreaming) "dreaming_started" else "dreaming_stopped")
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isScreenOn = intent?.action == Intent.ACTION_SCREEN_ON
            Log.d(TAG, "Screen state changed: ${intent?.action}")
            emit(if (isScreenOn) "screen_on" else "screen_off")
        }
    }

    @Suppress("TooGenericExceptionCaught") // Telemetry hook must never crash the service when serializing state
    private fun emit(event: String, extra: Map<String, Any?> = emptyMap()) {
        if (Pusher.url.isNullOrBlank()) return
        val snapshot = captureStateSnapshot()
        Pusher.push(event) {
            try {
                val body = buildState(snapshot).apply {
                    put("event", event)
                    putAll(extra)
                }
                Json.writeValueAsString(body)
            } catch (ex: Throwable) {
                Log.e(TAG, "push $event: building state failed: ${ex.message}")
                null
            }
        }
    }

    private val webhookHeartbeatRunnable = object : Runnable {
        override fun run() {
            val intervalSec = settings.webhookHeartbeat
            if (intervalSec > 0 && !Pusher.url.isNullOrBlank()) {
                emit("heartbeat")
                handler.postDelayed(this, intervalSec * 1000L)
            }
        }
    }

    @Synchronized
    private fun rescheduleWebhookHeartbeat() {
        handler.removeCallbacks(webhookHeartbeatRunnable)
        val intervalSec = settings.webhookHeartbeat
        if (intervalSec > 0 && !Pusher.url.isNullOrBlank()) {
            handler.postDelayed(webhookHeartbeatRunnable, intervalSec * MS_PER_SECOND)
        }
    }

    private val landingPageRenderer by lazy { LandingPageRenderer(this, settings) }

    private val settingsApiHandler by lazy {
        SettingsApiHandler(
            context = this,
            settings = settings,
            mainHandler = handler,
            onSettingsApplied = { data ->
                if (data != null) applyGlobalSettings(data)
                rescheduleWebhookHeartbeat()
            },
            onEmitSettings = { emit("settings") },
        )
    }

    /** Concrete implementation of [ModuleContext] for the background service. */
    private val moduleContextImpl = object : ModuleContext {
        override val settings: AppSettings get() = this@PipUpService.settings
        override val notificationManager: NotificationManager get() = this@PipUpService.notificationManager
        override val androidContext: Context get() = this@PipUpService.applicationContext
        override fun getSystemService(name: String): Any? = this@PipUpService.getSystemService(name)
        override fun getPackageName(): String = this@PipUpService.packageName
        override fun showToast(message: String) = this@PipUpService.showToast(message)
        override fun getString(resId: Int): String = this@PipUpService.getString(resId)
        override fun getString(resId: Int, vararg formatArgs: Any): String =
            this@PipUpService.getString(resId, *formatArgs)
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PiPupApp.ACTION_SETTINGS_CHANGED) {
                Log.d(TAG, "Settings change detected, applying global states")
                landingPageRenderer.invalidateCache()

                // 1. Re-apply Locale & Theme
                applyAppLocaleAndTheme(settings.language, settings.appTheme)

                // 2. Update Worker schedule
                UpdateWorker.schedule(applicationContext, settings.updateInterval)

                // 3. Update Modules from latest strategies
                moduleManager.initialize(moduleContextImpl)

                // 4. Update Notification
                updateForegroundNotification(settings.language)

                // 5. Keep webhook heartbeat schedule in sync
                rescheduleWebhookHeartbeat()
            }
        }
    }

    override fun onCreate() {
        instance = this
        super.onCreate()
        initNotificationChannel()

        val localizedContext = getLocalizedContext(settings.language)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(localizedContext.getString(R.string.app_name))
            .setContentText(localizedContext.getString(R.string.service_listening))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        moduleManager.initialize(moduleContextImpl)

        registerReceiver(dreamReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        })

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        webServer = WebServer(
            SERVER_PORT,
            object : WebServer.Handler {
                override fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                    return this@PipUpService.handleRequest(session)
                }
            },
        )

        // Set temp directory for NanoHTTPD to app's cache to avoid permission issues
        try {
            System.setProperty("java.io.tmpdir", applicationContext.cacheDir.absolutePath)
        } catch (_: Exception) {}

        // Register settings receiver to react to UI changes
        registerProtectedReceiver(settingsReceiver, IntentFilter(PiPupApp.ACTION_SETTINGS_CHANGED))

        Pusher.url = settings.webhook

        try {
            webServer.start(WEBSERVER_TIMEOUT_MS)
            Log.i(TAG, "WebServer started on port $SERVER_PORT (timeout: 30s)")
            emit("started")
            rescheduleWebhookHeartbeat()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start WebServer", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroying, cleaning up resources...")
        instance = null
        handler.removeCallbacks(webhookHeartbeatRunnable)
        try {
            unregisterReceiver(settingsReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(dreamReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        moduleManager.shutdown()
        webServer.stop()
        notificationManager.cancelAll()
        super.onDestroy()
    }

    @Volatile private var lastBroadcastDisplaying: Boolean? = null

    val isDisplaying: Boolean get() = notificationManager.isDisplaying()

    /** Broadcasts a local popup state change intent to in-app listeners when display state changes. */
    fun notifyLocalStateChange() {
        val current = isDisplaying
        if (lastBroadcastDisplaying == current) return
        lastBroadcastDisplaying = current
        val intent = Intent(PiPupApp.ACTION_POPUP_STATE_CHANGED).apply {
            setPackage(PiPupApp.APP_PACKAGE)
        }
        sendBroadcast(intent)
    }

    /** Dismisses all active and queued popups. */
    fun cancelAllPopups() {
        notificationManager.cancelAll()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Answer every startForegroundService() - mandatory even if already in foreground
        // to prevent RemoteServiceException on some Android versions/vendors.
        updateForegroundNotification(settings.language)

        if (intent?.action == "DISPLAY_NOTIFICATION") {
            intent.getStringExtra("props")?.let { propsJson ->
                try {
                    val props = PopupProps.fromJson(propsJson)
                    val isInternal = intent.getBooleanExtra("is_internal", false)
                    notificationManager.enqueueInternalPopup(props, isInternal)
                } catch (e: JSONException) {
                    Log.e(TAG, "Failed to parse DISPLAY_NOTIFICATION intent payload: ${e.message}")
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Failed to parse DISPLAY_NOTIFICATION intent payload: ${e.message}")
                }
            }
        }

        return START_STICKY
    }

    private fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri.lowercase()
        val method = session.method

        // 1. Dispatch to ModuleManager (Handles core API via SystemModule + other modules)
        val moduleResponse = moduleManager.handleRequest(session)
        if (moduleResponse != null) return moduleResponse

        // 2. Fallback for unhandled routes
        return try {
            when (uri) {
                "/" -> {
                    if (method == NanoHTTPD.Method.GET) {
                        landingPageRenderer.renderResponse()
                    } else {
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not Found")
                    }
                }
                "/state" -> {
                    if (method == NanoHTTPD.Method.GET || method == NanoHTTPD.Method.POST) {
                        stateResponse()
                    } else {
                        NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                            "text/plain",
                            "Method Not Allowed",
                        )
                    }
                }
                "/settings" -> settingsApiHandler.handleRequest(session)
                "/favicon.svg", "/favicon.ico" -> landingPageRenderer.renderFaviconResponse()
                else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Request error", e)
            invalidRequest(e.message)
        } catch (e: JSONException) {
            Log.e(TAG, "Request error", e)
            invalidRequest(e.message)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Request error", e)
            invalidRequest(e.message)
        }
    }

    private fun captureStateSnapshot(): PopupStateSnapshot = PopupStateSnapshot(
        activeSlots = notificationManager.getActiveSlotDetails(),
        visible = notificationManager.isDisplaying(),
        dreaming = dreaming,
        lastPopup = notificationManager.lastPopup,
        now = SystemClock.elapsedRealtime(),
    )

    private fun buildState(snapshot: PopupStateSnapshot = captureStateSnapshot()): MutableMap<String, Any?> {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val sleepMethod = PowerController.getSleepMethod(this)
        val now = snapshot.now

        val state = mutableMapOf<String, Any?>(
            "app" to getString(R.string.app_name),
            "version" to BuildConfig.VERSION_NAME,
            "id" to settings.deviceId,
            "name" to deviceName(),
            "visible" to snapshot.visible,
            "screenOn" to powerManager.isInteractive,
            "dreaming" to snapshot.dreaming,
            "popupsShown" to notificationManager.popupsShown.get(),
            "watchdogCleanups" to notificationManager.watchdogCleanups.get(),
            "uptime" to (now - startedAt) / MS_PER_SECOND,
            "device" to mapOf(
                "model" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER,
                "android" to Build.VERSION.RELEASE,
            ),
            "popups" to buildPopupsState(snapshot.activeSlots, now),
            "power" to mapOf(
                "canWake" to true,
                "canSleep" to (sleepMethod != null),
                "sleepMethod" to sleepMethod,
            ),
            "permissions" to Permissions.asMap(this),
            "update" to buildUpdateState(),
            "haPipup" to mapOf("recommended" to null, "minimum" to "1.0.0", "connected" to null),
            "push" to buildPushState(),
        )

        snapshot.activeSlots.lastOrNull()?.let { (topProps, topShownAt) ->
            state["popup"] = mapOf(
                "id" to topProps.id,
                "title" to topProps.title,
                "duration" to topProps.duration,
                "indefinite" to (topProps.duration <= 0),
                "elapsed" to ((now - topShownAt) / MS_PER_SECOND),
            )
        }
        buildLastPopupState(snapshot.lastPopup, now)?.let { state["lastPopup"] = it }
        moduleManager.augmentState(state)
        return state
    }

    private fun buildPopupsState(
        activeSlots: List<Pair<PopupProps, Long>>,
        now: Long,
    ): List<Map<String, Any?>> = activeSlots.map { (props, slotShownAt) ->
        mapOf(
            "id" to props.id,
            "position" to props.getPositionEnum().name,
            "duration" to props.duration,
            "indefinite" to (props.duration <= 0),
            "elapsed" to ((now - slotShownAt) / MS_PER_SECOND),
            "media" to mediaInfo(props)?.get("type"),
        )
    }

    private fun buildUpdateState(): Map<String, Any?> = mapOf(
        "checksEnabled" to true,
        "source" to "github",
        "available" to UpdateManager.updateAvailable(this),
        "latest" to UpdateManager.latestVersion,
        "installing" to UpdateManager.isInstalling,
        "phase" to if (UpdateManager.isInstalling) "installing" else null,
        "silent" to UpdateManager.silentInstall,
        "checkedSecondsAgo" to UpdateManager.lastCheckedAt.takeIf { it > 0 }
            ?.let { (System.currentTimeMillis() - it) / MS_PER_SECOND },
        "error" to UpdateManager.lastError,
    )

    private fun buildLastPopupState(last: PopupProps?, now: Long): Map<String, Any?>? {
        if (last == null) return null
        return mapOf(
            "id" to last.id,
            "position" to last.getPositionEnum().name,
            "stack" to last.effectiveStack,
            "fullscreen" to last.coversScreen,
            "duration" to last.duration,
            "indefinite" to (last.duration <= 0),
            "muted" to mediaMuted(last),
            "media" to mediaInfo(last),
            "tts" to false,
            "sound" to false,
            "animation" to last.animationType,
            "buttons" to last.buttons.size,
            "firstFrameMs" to notificationManager.lastFirstFrameMs,
            "mediaError" to notificationManager.lastMediaError,
            "secondsAgo" to ((now - notificationManager.lastPopupAt) / MS_PER_SECOND),
        )
    }

    private fun buildPushState(): Map<String, Any?> = mapOf(
        "supported" to true,
        "webhook" to !Pusher.url.isNullOrBlank(),
        "heartbeat" to settings.webhookHeartbeat,
        "lastEvent" to Pusher.lastEvent,
        "lastStatus" to Pusher.lastStatus,
        "lastError" to Pusher.lastError,
        "lastSecondsAgo" to Pusher.lastAt.takeIf { it > 0 }
            ?.let { (System.currentTimeMillis() - it) / MS_PER_SECOND },
    )

    private fun stateResponse(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            Json.writeValueAsString(buildState())
        )
    }

    private fun deviceName(): String {
        return android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?: Build.MODEL
    }

    private fun applyGlobalSettings(data: AppSettings.SettingsData) {
        // Invalidate cache to ensure the latest theme/language is used for landing page
        landingPageRenderer.invalidateCache()

        // Update Foreground Notification (respects current language settings)
        updateForegroundNotification(data.language)
    }

    private fun updateForegroundNotification(lang: String) {
        val localizedContext = getLocalizedContext(lang)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(localizedContext.getString(R.string.app_name))
            .setContentText(localizedContext.getString(R.string.service_listening))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "PiPup Service", AndroidNotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NOTIFICATION_SERVICE) as AndroidNotificationManager
        manager.createNotificationChannel(channel)
    }

    /** Handles an interactive popup action button click, firing webhook and optional callback. */
    fun handleButtonClick(props: PopupProps, btn: PopupProps.Button, slotKey: String? = null) {
        val targetId = props.id ?: slotKey
        Log.i(TAG, "Popup button pressed: ${btn.id} (${btn.label}) for popup $targetId")
        if (btn.id == "install" || btn.id == "confirm_install") {
            if (props.isInternalUpdateAction || notificationManager.isInternalUpdate(targetId ?: "")) {
                UpdateManager(this).resumePendingUpdate()
            } else {
                Log.w(TAG, "Rejecting unauthorized external install button action for popup id: $targetId")
            }
        }
        val payload = mapOf(
            "popup" to props.id,
            "button" to btn.id,
            "label" to btn.label,
            "device" to settings.deviceId
        )
        Pusher.push("button", Json.writeValueAsString(payload))

        props.callback?.takeIf { it.isNotBlank() }?.let { url ->
            dispatchButtonCallback(url, payload)
        }
        notificationManager.handleButtonRemoval(slotKey ?: props.id ?: "")
    }

    private fun dispatchButtonCallback(urlStr: String, payload: Map<String, Any?>) {
        (applicationContext as? PiPupApp)?.applicationScope?.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val uri = URI(urlStr)
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                if (scheme != "http" && scheme != "https") {
                    Log.w(TAG, "Rejecting invalid callback URL scheme: $scheme")
                    return@launch
                }
                val url = uri.toURL()
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = CALLBACK_TIMEOUT_MS
                    readTimeout = CALLBACK_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(Json.writeValueAsString(payload).toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                Log.d(TAG, "Button callback dispatched to $urlStr (HTTP $code)")
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: IOException) {
                Log.w(TAG, "Button callback failed for $urlStr: ${ex.message}")
            } catch (ex: URISyntaxException) {
                Log.w(TAG, "Button callback failed for $urlStr: ${ex.message}")
            } catch (ex: IllegalArgumentException) {
                Log.w(TAG, "Button callback failed for $urlStr: ${ex.message}")
            } catch (ex: SecurityException) {
                Log.w(TAG, "Button callback failed for $urlStr: ${ex.message}")
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {}
            }
        }
    }

    private fun invalidRequest(message: String?): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            "text/plain",
            "invalid request: ${message ?: "unknown error"}",
        )
    }
}
