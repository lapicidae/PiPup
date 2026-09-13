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
import nl.rogro82.pipup.R
import nl.rogro82.pipup.UpdateManager
import nl.rogro82.pipup.UpdateWorker
import nl.rogro82.pipup.applyAppLocaleAndTheme
import nl.rogro82.pipup.colorToHex
import nl.rogro82.pipup.core.ModuleMode
import nl.rogro82.pipup.core.ModuleContext
import nl.rogro82.pipup.core.NotificationManager
import nl.rogro82.pipup.core.WebServer
import nl.rogro82.pipup.getLocalizedContext
import nl.rogro82.pipup.readExactBytes
import nl.rogro82.pipup.registerProtectedReceiver
import nl.rogro82.pipup.showToast

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

    private val handler = Handler(Looper.getMainLooper())
    private val settings = PiPupApp.settings

    private lateinit var webServer: WebServer
    private val notificationManager by lazy {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        NotificationManager(this, wm)
    }
    /** The module manager for this service. */
    val moduleManager by lazy { (application as PiPupApp).moduleManager }

    private val startedAt = SystemClock.elapsedRealtime()

    @Volatile private var dreaming = false
    private val dreamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            dreaming = intent?.action == Intent.ACTION_DREAMING_STARTED
            Log.d(TAG, "Screensaver ${if (dreaming) "started" else "stopped"}")
        }
    }

    private var cachedLandingPage: String? = null

    /** Concrete implementation of [ModuleContext] for the background service. */
    private val moduleContextImpl = object : ModuleContext {
        override val settings: AppSettings get() = this@PipUpService.settings
        override val notificationManager: NotificationManager get() = this@PipUpService.notificationManager
        override val androidContext: Context get() = this@PipUpService.applicationContext
        override fun getSystemService(name: String): Any? = this@PipUpService.getSystemService(name)
        override fun getPackageName(): String = this@PipUpService.packageName
        override fun showToast(message: String) = this@PipUpService.showToast(message)
        override fun getString(resId: Int): String = this@PipUpService.getString(resId)
        override fun getString(resId: Int, vararg formatArgs: Any): String = this@PipUpService.getString(resId, *formatArgs)
    }

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PiPupApp.ACTION_SETTINGS_CHANGED) {
                Log.d(TAG, "Settings change detected, applying global states")
                cachedLandingPage = null

                // 1. Re-apply Locale & Theme
                applyAppLocaleAndTheme(settings.language, settings.appTheme)

                // 2. Update Worker schedule
                UpdateWorker.schedule(applicationContext, settings.updateInterval)

                // 3. Update Modules from latest strategies
                moduleManager.initialize(moduleContextImpl)

                // 4. Update Notification
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

        moduleManager.initialize(moduleContextImpl)

        registerReceiver(dreamReceiver, IntentFilter().apply {
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
        registerProtectedReceiver(settingsReceiver, IntentFilter(PiPupApp.ACTION_SETTINGS_CHANGED))

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
            unregisterReceiver(dreamReceiver)
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

        // 1. Check if module-specific routes are blocked by activation mode
        if (uri == "/power" && settings.getModuleMode("power") == ModuleMode.OFF) {
             val localizedContext = getLocalizedContext(settings.language)
             val moduleName = localizedContext.getString(R.string.settings_module_power)
             showToast(localizedContext.getString(R.string.error_module_disabled, moduleName))
             return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "Module disabled")
        }

        // 2. Dispatch to ModuleManager (Handles core API via SystemModule + other modules)
        val moduleResponse = moduleManager.handleRequest(session)
        if (moduleResponse != null) return moduleResponse

        // 3. Fallback for unhandled routes
        return try {
            when (uri) {
                "/" -> {
                    if (method == NanoHTTPD.Method.GET) {
                        handleLandingPage()
                    } else {
                        // Forward to /notify logic via ModuleManager if possible,
                        // but root is a special case.
                        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not Found")
                    }
                }
                "/state" -> {
                    if (method == NanoHTTPD.Method.GET || method == NanoHTTPD.Method.POST) {
                        stateResponse()
                    } else NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method Not Allowed")
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

    private fun stateResponse(): NanoHTTPD.Response {
        val current = notificationManager.getCurrentProps()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val appName = getString(R.string.app_name)
        val state = mutableMapOf<String, Any?>(
            "app" to appName,
            "version" to BuildConfig.VERSION_NAME,
            "id" to settings.deviceId,
            "name" to deviceName(),
            "visible" to notificationManager.isDisplaying(),
            "screenOn" to powerManager.isInteractive,
            "dreaming" to dreaming,
            "popupsShown" to notificationManager.popupsShown.get(),
            "uptime" to (SystemClock.elapsedRealtime() - startedAt) / 1000,
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
                "elapsed" to ((SystemClock.elapsedRealtime() - notificationManager.lastPopupAt) / 1000)
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

        val last = notificationManager.lastPopup
        if (last != null) {
            state["lastPopup"] = mapOf(
                "title" to last.title,
                "duration" to last.duration,
                "position" to last.getPositionEnum().name,
                "muted" to mediaMuted(last),
                "media" to mediaInfo(last),
                "secondsAgo" to ((SystemClock.elapsedRealtime() - notificationManager.lastPopupAt) / 1000)
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
                val json = settings.getAll().toJSONObject().toString()
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json).apply { setGzipEncoding(false) }
            }
            NanoHTTPD.Method.POST -> {
                val length = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (length > 0) {
                    val content = session.inputStream.readExactBytes(length)
                    try {
                        val data = AppSettings.SettingsData.fromJson(String(content, Charsets.UTF_8))
                        handler.post {
                            settings.apply(data)
                            applyGlobalSettings(data)

                            // Notify UI about settings change
                            val intent = Intent(PiPupApp.ACTION_SETTINGS_CHANGED).apply {
                                setPackage(packageName)
                                putExtra("origin", "remote")
                            }
                            sendBroadcast(intent)
                        }
                        ok()
                    } catch (e: Exception) {
                        invalidRequest(e.message)
                    }
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

    private fun initNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "PiPup Service", AndroidNotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NOTIFICATION_SERVICE) as AndroidNotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun ok(): NanoHTTPD.Response {
        Log.d(TAG, "Response OK")
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "OK")
    }

    private fun invalidRequest(message: String?): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "invalid request: ${message ?: "unknown error"}")
    }
}
