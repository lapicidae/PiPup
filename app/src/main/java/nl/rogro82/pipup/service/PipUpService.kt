package nl.rogro82.pipup.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.*
import nl.rogro82.pipup.core.NotificationManager
import nl.rogro82.pipup.core.PayloadParser
import nl.rogro82.pipup.core.WebServer
import androidx.media3.common.util.UnstableApi

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
    }

    private val handler = Handler(Looper.getMainLooper())
    private val settings = PiPupApp.settings

    private lateinit var webServer: WebServer
    private lateinit var notificationManager: NotificationManager
    private lateinit var payloadParser: PayloadParser

    private var cachedLandingPage: String? = null

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "nl.rogro82.pipup.SETTINGS_CHANGED") {
                Log.d(TAG, "Settings change detected, clearing web cache")
                cachedLandingPage = null
                // Also update foreground notification in case language changed
                updateForegroundNotification(settings.language)
            }
        }
    }

    @androidx.annotation.Keep
    internal var warmWebView: android.webkit.WebView? = null

    override fun onCreate() {
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

        // Pre-warm WebView if enabled to avoid cold-start timeouts on first WHEP request
        if (settings.preWarmWebView) {
            handler.post {
                try {
                    @SuppressLint("SetJavaScriptEnabled")
                    val wv = android.webkit.WebView(applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true // Required for some WebRTC players
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = android.webkit.WebViewClient()
                        loadUrl("about:blank")
                    }
                    warmWebView = wv
                    Log.d(TAG, "WebView engine pre-warmed and reference kept (ref: ${warmWebView?.hashCode()})")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to pre-warm WebView: ${e.message}")
                }
            }
        }

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
            registerReceiver(settingsReceiver, android.content.IntentFilter("nl.rogro82.pipup.SETTINGS_CHANGED"), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(settingsReceiver, android.content.IntentFilter("nl.rogro82.pipup.SETTINGS_CHANGED"))
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
        try {
            unregisterReceiver(settingsReceiver)
        } catch (_: Exception) {}
        webServer.stop()
        notificationManager.cancelAll()

        warmWebView?.let { wv ->
            wv.post {
                try {
                    Log.d(TAG, "Destroying pre-warmed WebView")
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {}
            }
            warmWebView = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri.lowercase()
        val method = session.method

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
                "/cancel" -> {
                    notificationManager.cancelAll()
                    ok("Queue cleared")
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

    private fun processNotify(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return payloadParser.parse(session)?.let { props ->
            val finalProps = applySettingsDefaults(props)
            notificationManager.enqueue(finalProps)
            ok("Enqueued")
        } ?: invalidRequest("Invalid payload")
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
                <title>${getString(R.string.server_landing_title, getString(R.string.app_name))}</title>
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
                    ${if (logoSvg.isNotEmpty()) "<div class=\"logo-container\">$logoSvg</div>" else "<h1>${getString(R.string.app_name)}</h1>"}
                    <p>${getString(R.string.server_landing_description)}</p>
                    <p><a href="https://github.com/lapicidae/PiPup" target="_blank">${getString(R.string.server_landing_docs)}</a></p>
                    <div class="version">
                        ${getString(R.string.app_name)} v$versionName<br>
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
                        settings.apply(data)
                        applyGlobalSettings(data)
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
        // 1. Invalidate cache to ensure the latest theme/language is used for landing page
        cachedLandingPage = null

        // 2. Update Foreground Notification (respects current language settings)
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
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", message ?: "OK")
    }

    private fun invalidRequest(message: String?): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", message ?: "Invalid Request")
    }
}
