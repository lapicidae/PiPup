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
import fi.iki.elonen.NanoHTTPD
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import nl.rogro82.pipup.*
import nl.rogro82.pipup.core.NotificationManager
import nl.rogro82.pipup.core.PayloadParser
import nl.rogro82.pipup.core.WebServer
import nl.rogro82.pipup.core.ModuleManager
import nl.rogro82.pipup.core.modules.PowerModule
import nl.rogro82.pipup.core.modules.SystemModule
import nl.rogro82.pipup.core.modules.DiscoveryModule
import nl.rogro82.pipup.core.modules.MediaModule
import androidx.media3.common.util.UnstableApi
import androidx.core.content.edit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Main background service responsible for hosting the WebServer and managing the notification queue.
 *
 * This service runs as a foreground service to ensure it remains active for incoming requests.
 * It handles localized notifications, pre-warming the WebView engine, and processing API requests.
 */
@OptIn(UnstableApi::class)
class PipUpService : Service() {

    companion object {
        private const val TAG = "PipUpService"
        private const val CHANNEL_ID = "pipup_service"
        private const val NOTIFICATION_ID = 1001
        /** The port on which the internal WebServer listens. */
        const val SERVER_PORT = 7979

        @SuppressLint("StaticFieldLeak")
        private var instance: PipUpService? = null
        fun getInstance(): PipUpService? = instance
    }

    /** Returns the module manager for this service. */
    fun getModuleManager(): ModuleManager = moduleManager

    private val handler = Handler(Looper.getMainLooper())
    // Hand-off of HTTP requests to the main thread to ensure sequential processing and sync
    private val requestHandler = Handler(Looper.getMainLooper())
    private val settings = PiPupApp.settings

    private lateinit var webServer: WebServer
    private lateinit var notificationManager: NotificationManager
    private lateinit var payloadParser: PayloadParser
    private lateinit var moduleManager: ModuleManager

    private val mPopupsShown = java.util.concurrent.atomic.AtomicLong(0)
    private val mStartedAt = SystemClock.elapsedRealtime()
    @Volatile private var mLastPopup: PopupProps? = null
    @Volatile private var mLastPopupAt: Long = 0L

    @Volatile private var mDreaming = false
    private val mDreamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mDreaming = intent?.action == Intent.ACTION_DREAMING_STARTED
            Log.d(TAG, "Screensaver ${if (mDreaming) "started" else "stopped"}")
        }
    }

    private var cachedLandingPage: String? = null

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "nl.rogro82.pipup.SETTINGS_CHANGED") {
                Log.d(TAG, "Settings change detected, applying global states")
                cachedLandingPage = null

                // 1. Re-apply Locale
                val lang = settings.language
                val appLocale: LocaleListCompat = if (lang == "default") {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(lang)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)

                // 2. Re-apply Theme
                val appTheme = settings.appTheme
                val mode = if (appTheme == 0) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                AppCompatDelegate.setDefaultNightMode(mode)

                // 3. Update Worker schedule
                UpdateWorker.schedule(applicationContext, settings.updateInterval)

                // 4. Update Modules
                moduleManager.setModuleEnabled("power", settings.powerModuleEnabled)
                moduleManager.setModuleEnabled("discovery", settings.discoveryModuleEnabled)
                moduleManager.setModuleEnabled("media", settings.mediaModuleEnabled)

                // 5. Update Media pre-warm
                (moduleManager.getModule("media") as? MediaModule)?.updatePreWarmState()

                // 6. Update Notification
                updateForegroundNotification(settings.language)
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
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = NotificationManager(this, wm)
        payloadParser = PayloadParser(applicationContext)

        moduleManager = ModuleManager()
        moduleManager.registerModule(SystemModule(this))
        moduleManager.registerModule(PowerModule(this))
        moduleManager.registerModule(DiscoveryModule(this))
        moduleManager.registerModule(MediaModule(this))

        moduleManager.setModuleEnabled("system", true)
        moduleManager.setModuleEnabled("power", settings.powerModuleEnabled)
        moduleManager.setModuleEnabled("discovery", settings.discoveryModuleEnabled)
        moduleManager.setModuleEnabled("media", settings.mediaModuleEnabled)

        registerReceiver(mDreamReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, IntentFilter("nl.rogro82.pipup.SETTINGS_CHANGED"), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(settingsReceiver, IntentFilter("nl.rogro82.pipup.SETTINGS_CHANGED"))
        }

        try {
            webServer.start(30000)
            Log.i(TAG, "WebServer started on port $SERVER_PORT (timeout: 30s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebServer", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroying, cleaning up resources...")
        instance = null
        try {
            unregisterReceiver(settingsReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(mDreamReceiver)
        } catch (_: Exception) {}
        moduleManager.shutdown()
        webServer.stop()
        notificationManager.cancelAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri.lowercase()
        val method = session.method

        // 1. Check if power module is enabled before processing its request
        if (uri == "/power" && !settings.powerModuleEnabled) {
             val localizedContext = getLocalizedContext(settings.language)
             val moduleName = localizedContext.getString(R.string.settings_module_power)
             showToast(localizedContext.getString(R.string.error_module_disabled, moduleName))
             return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "Module disabled")
        }

        // 2. Dispatch to ModuleManager
        val moduleResponse = moduleManager.handleRequest(session)
        if (moduleResponse != null) return moduleResponse

        // 3. Core API Fallback
        return try {
            when (uri) {
                "/" -> {
                    if (method == NanoHTTPD.Method.GET) {
                        handleLandingPage()
                    } else {
                        // Handle POST/PUT to root as a notification for compatibility
                        processNotify(session)
                    }
                }
                "/notify", "/api/notify" -> processNotify(session)
                "/state" -> {
                    if (method == NanoHTTPD.Method.GET || method == NanoHTTPD.Method.POST) {
                        stateResponse()
                    } else NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method Not Allowed")
                }
                "/cancel" -> {
                    val id = session.parameters["id"]?.firstOrNull()
                    val result = runOnMainSync {
                        val current = notificationManager.getCurrentProps()
                        if (id != null && current != null && current.id != id) {
                            ok("id mismatch: visible popup is ${current.id}")
                        } else {
                            notificationManager.cancelAll()
                            ok("Queue cleared")
                        }
                    }
                    result ?: invalidRequest("Main thread timeout")
                }
                "/settings" -> handleSettingsRequest(session)
                "/favicon.svg", "/favicon.ico" -> handleFavicon()
                else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request error", e)
            invalidRequest(e.message)
        }
    }

    private fun handleFavicon(): NanoHTTPD.Response {
        return try {
            val stream = assets.open("logo.svg")
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "image/svg+xml", stream, stream.available().toLong())
        } catch (_: Exception) {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "")
        }
    }

    private fun runOnMainSync(block: () -> NanoHTTPD.Response): NanoHTTPD.Response? {
        val latch = CountDownLatch(1)
        var result: NanoHTTPD.Response? = null
        requestHandler.post {
            try {
                result = block()
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(2000, TimeUnit.MILLISECONDS)) result else null
    }

    private fun processNotify(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val props = payloadParser.parse(session) ?: return invalidRequest("failed to parse input")

        // Check if media module is required and enabled
        if (!settings.mediaModuleEnabled && (props.media is PopupProps.Media.Web || props.media is PopupProps.Media.Whep)) {
            val localizedContext = getLocalizedContext(settings.language)
            val moduleName = localizedContext.getString(R.string.settings_module_media)
            showToast(localizedContext.getString(R.string.error_module_disabled, moduleName))
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "Media module disabled")
        }

        val finalProps = applySettingsDefaults(props)

        // Asynchronous hand-off to notification manager to avoid blocking NanoHTTPD threads
        // or timing out on the main thread during heavy load.
        mPopupsShown.incrementAndGet()
        mLastPopup = finalProps
        mLastPopupAt = SystemClock.elapsedRealtime()
        notificationManager.enqueue(finalProps)

        return ok("Enqueued: ${finalProps.title ?: "Untitled"}")
    }

    private fun stateResponse(): NanoHTTPD.Response {
        val current = notificationManager.getCurrentProps()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val appName = getString(R.string.app_name)
        val state = mutableMapOf<String, Any?>(
            "app" to appName,
            "version" to BuildConfig.VERSION_NAME,
            "id" to deviceId(),
            "name" to deviceName(),
            "visible" to notificationManager.isDisplaying(),
            "screenOn" to powerManager.isInteractive,
            "dreaming" to mDreaming,
            "popupsShown" to mPopupsShown.get(),
            "uptime" to (SystemClock.elapsedRealtime() - mStartedAt) / 1000,
            "device" to mapOf(
                "model" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER,
                "android" to Build.VERSION.RELEASE
            )
        )
        if (current != null) {
            state["popup"] = mapOf(
                "id" to current.id,
                "title" to current.title,
                "duration" to current.duration,
                "indefinite" to (current.duration <= 0),
                "elapsed" to ((SystemClock.elapsedRealtime() - mLastPopupAt) / 1000)
            )
        }
        state["permissions"] = Permissions.asMap(this)
        state["update"] = mapOf(
            "available" to UpdateManager.updateAvailable(this),
            "latest" to UpdateManager.latestVersion,
            "installing" to UpdateManager.isInstalling,
            "silent" to UpdateManager.silentInstall,
            "checkedSecondsAgo" to UpdateManager.lastCheckedAt.takeIf { it > 0 }
                ?.let { (System.currentTimeMillis() - it) / 1000 },
            "error" to UpdateManager.lastError
        )
        moduleManager.augmentState(state)

        val last = mLastPopup
        if (last != null) {
            state["lastPopup"] = mapOf(
                "title" to last.title,
                "duration" to last.duration,
                "position" to last.getPositionEnum().name,
                "muted" to mediaMuted(last),
                "media" to mediaInfo(last),
                "secondsAgo" to ((SystemClock.elapsedRealtime() - mLastPopupAt) / 1000)
            )
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            Json.writeValueAsString(state)
        )
    }

    private fun mediaInfo(p: PopupProps): Map<String, Any?>? = when (val m = p.media) {
        is PopupProps.Media.Web -> mapOf("type" to "web", "width" to m.width, "height" to m.height)
        is PopupProps.Media.Video -> mapOf("type" to "video", "width" to m.width)
        is PopupProps.Media.Image -> mapOf("type" to "image", "width" to m.width)
        is PopupProps.Media.Bitmap -> mapOf("type" to "bitmap", "width" to m.width)
        else -> null
    }

    private fun mediaMuted(p: PopupProps): Boolean? = when (val m = p.media) {
        is PopupProps.Media.Web -> m.muted
        is PopupProps.Media.Video -> m.muted
        else -> null
    }

    private fun deviceId(): String {
        val context = applicationContext.createDeviceProtectedStorageContext()

        val prefs = context.getSharedPreferences("pipup_id", MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            // One-time migration if old prefs exist in regular storage
            val oldPrefs = getSharedPreferences("pipup_id", MODE_PRIVATE)
            id = oldPrefs.getString("device_id", null)
            if (id != null) {
                prefs.edit { putString("device_id", id) }
                oldPrefs.edit { remove("device_id") }
            }

            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit { putString("device_id", id) }
            }
        }
        return id
    }

    private fun deviceName(): String {
        return android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?: Build.MODEL
    }

    private fun handleLandingPage(): NanoHTTPD.Response {
        cachedLandingPage?.let {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", it).apply {
                setGzipEncoding(false)
                addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                addHeader("Pragma", "no-cache")
                addHeader("Expires", "0")
            }
        }

        val appName = getString(R.string.app_name)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "Unknown" }

        val logoSvg = try {
            assets.open("logo.svg").bufferedReader().use { it.readText() }
                .replace(Regex("<\\?xml.*?\\?>"), "") // Remove XML header
                .replace(Regex("<!DOCTYPE.*?>", RegexOption.DOT_MATCHES_ALL), "") // Remove Doctype
        } catch (_: Exception) { "" }

        // Force a context that reflects the user's theme setting
        val themedContext = getLocalizedContext(settings.language, settings.appTheme)

        val bg = themedContext.colorToHex(R.color.colorSurface)
        val cardBg = themedContext.colorToHex(R.color.colorSurfaceVariant)
        val primary = themedContext.colorToHex(R.color.colorPrimary)
        val text = themedContext.colorToHex(R.color.colorOnSurface)
        val textSecondary = themedContext.colorToHex(R.color.colorOnSurfaceVariant)
        val accent = themedContext.colorToHex(R.color.colorOnPrimaryContainer)
        val outline = themedContext.colorToHex(R.color.colorOutline)
        val statusGreen = themedContext.colorToHex(R.color.status_green)

        Log.d(TAG, "Generating landing page. Theme: ${settings.appTheme}, Resolved BG: $bg")

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <link rel="icon" type="image/svg+xml" href="/favicon.svg">
                <title>${getString(R.string.server_landing_title, appName)}</title>
                <style>
                    body { font-family: sans-serif; background-color: $bg; color: $text; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                    .card { background-color: $cardBg; padding: 2.5rem; border-radius: 20px; box-shadow: 0 10px 40px rgba(0,0,0,0.4); text-align: center; max-width: 450px; border: 1px solid $outline; }
                    .logo-container { width: 120px; height: auto; margin: 0 auto 1.5rem; }
                    .logo-container svg { width: 100%; height: auto; display: block; }
                    .logo-container .currentColor { color: $primary !important; }
                    h1 { color: $primary; margin: 0.5rem 0; font-size: 2.5rem; letter-spacing: -1px; }
                    p { color: $textSecondary; line-height: 1.6; font-size: 1.1rem; }
                    code { background-color: $bg; padding: 2px 6px; border-radius: 4px; color: $accent; font-family: monospace; border: 1px solid $outline; }
                    .status { display: inline-flex; align-items: center; padding: 6px 14px; background-color: $bg; color: $statusGreen; border-radius: 20px; font-size: 0.85rem; font-weight: bold; margin-bottom: 1rem; border: 1px solid $statusGreen; }
                    .status::before { content: ""; width: 8px; height: 8px; background-color: $statusGreen; border-radius: 50%; margin-right: 8px; box-shadow: 0 0 8px $statusGreen; }
                    .version { font-size: 0.8rem; color: $textSecondary; margin-top: 2.5rem; border-top: 1px solid $outline; paddingTop: 1.5rem; }
                    a { color: $accent; text-decoration: none; font-weight: 500; }
                    a:hover { text-decoration: underline; color: $primary; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="status">${getString(R.string.server_landing_status)}</div>
                    ${if (logoSvg.isNotEmpty()) "<div class=\"logo-container\">$logoSvg</div>" else "<h1>$appName</h1>"}
                    <p>${getString(R.string.server_landing_description)}</p>
                    <p><a href="https://github.com/lapicidae/PiPup" target="_blank">${getString(R.string.server_landing_docs)}</a></p>
                    <div class="version">
                        $appName v$versionName<br>
                        ${getString(R.string.server_landing_running_on, Build.MODEL, Build.VERSION.RELEASE)}
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        cachedLandingPage = html
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", html).apply {
            setGzipEncoding(false)
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            addHeader("Pragma", "no-cache")
            addHeader("Expires", "0")
        }
    }

    private fun handleSettingsRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return when (session.method) {
            NanoHTTPD.Method.GET -> {
                val json = Json.mapper.writeValueAsString(settings.getAll())
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json).apply { setGzipEncoding(false) }
            }
            NanoHTTPD.Method.POST -> {
                val length = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (length > 0) {
                    val content = session.inputStream.readExactBytes(length)
                    val data = Json.mapper.readValue(content, AppSettings.SettingsData::class.java)
                    handler.post {
                        if (data != null) {
                            settings.apply(data)
                            applyGlobalSettings(data)
                        }
                        // Notify UI about settings change
                        val intent = Intent("nl.rogro82.pipup.SETTINGS_CHANGED").apply {
                            setPackage(packageName)
                            putExtra("origin", "remote")
                        }
                        sendBroadcast(intent)
                    }
                    ok("Settings updated")
                } else invalidRequest("Empty")
            }
            else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method Not Allowed")
        }
    }

    private fun applyGlobalSettings(data: AppSettings.SettingsData) {
        // Invalidate cache to ensure the latest theme/language is used for landing page
        cachedLandingPage = null

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
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun applySettingsDefaults(props: PopupProps): PopupProps {
        return props.copy(
            backgroundColor = if (props.backgroundColor == "#CC000000") settings.getFullBackgroundColor() else props.backgroundColor,
            borderColor = if (props.borderColor == "#00000000") settings.borderColor else props.borderColor,
            borderRadius = if (props.borderRadius == 0) settings.borderRadius else props.borderRadius,
            borderWidth = if (props.borderWidth == 0) settings.borderWidth else props.borderWidth,
            titleColor = if (props.titleColor == "#FFFFFF") settings.titleColor else props.titleColor,
            titleSize = if (props.titleSize == 24f) settings.titleSize else props.titleSize,
            messageColor = if (props.messageColor == "#FFFFFF") settings.messageColor else props.messageColor,
            messageSize = if (props.messageSize == 16f) settings.messageSize else props.messageSize,
            titleAlignment = if (props.titleAlignment == 0) settings.titleAlignment else props.titleAlignment,
            messageAlignment = if (props.messageAlignment == 0) settings.messageAlignment else props.messageAlignment,
            mediaPosition = props.mediaPosition ?: settings.mediaPosition,
            animationType = if (props.animationType == 0) settings.animationType else props.animationType,
            animationDuration = if (props.animationDuration == 500) settings.animationDuration else props.animationDuration,
            animationExit = props.animationExit || settings.animationExit
        )
    }

    private fun initNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "PiPup Service", AndroidNotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NOTIFICATION_SERVICE) as AndroidNotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun ok(message: String?): NanoHTTPD.Response {
        Log.d(TAG, "Response OK: $message")
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", message ?: "OK")
    }

    private fun invalidRequest(message: String?): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "invalid request: ${message ?: "unknown error"}")
    }
}
