package nl.rogro82.pipup.service

import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.*
import nl.rogro82.pipup.core.NotificationManager
import nl.rogro82.pipup.core.PayloadParser
import nl.rogro82.pipup.core.WebServer
import androidx.media3.common.util.UnstableApi

/**
 * Main Service for PiPup. Orchestrates WebServer, Parsing and Notifications.
 */
@OptIn(UnstableApi::class)
class PipUpService : Service() {

    companion object {
        private const val TAG = "PipUpService"
        private const val CHANNEL_ID = "pipup_service"
        private const val NOTIFICATION_ID = 1001
        const val SERVER_PORT = 7979
    }

    private val handler = Handler(Looper.getMainLooper())
    private val mapper = ObjectMapper().registerKotlinModule()
    private val settings by lazy { AppSettings(this) }

    private lateinit var webServer: WebServer
    private lateinit var notificationManager: NotificationManager
    private lateinit var payloadParser: PayloadParser

    override fun onCreate() {
        super.onCreate()
        initNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PiPup")
            .setContentText("Listening for notifications...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = NotificationManager(this, wm)
        payloadParser = PayloadParser(mapper)

        webServer = WebServer(
            SERVER_PORT,
            object : WebServer.Handler {
                override fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
                    return this@PipUpService.handleRequest(session)
                }
            },
        )

        try {
            webServer.start()
            Log.i(TAG, "WebServer started on port $SERVER_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebServer", e)
        }
    }

    override fun onDestroy() {
        webServer.stop()
        notificationManager.cancelAll()
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
                else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request error", e)
            invalidRequest(e.message)
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
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "Unknown" }

        val logoSvg = try {
            assets.open("logo.svg").bufferedReader().use { it.readText() }
                .replace(Regex("<\\?xml.*?\\?>"), "") // Remove XML header
                .replace(Regex("<!DOCTYPE.*?>", RegexOption.DOT_MATCHES_ALL), "") // Remove Doctype
        } catch (_: Exception) { "" }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>${getString(R.string.server_landing_title, getString(R.string.app_name))}</title>
                <style>
                    body { font-family: sans-serif; background-color: #0F1417; color: #DFE3E7; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                    .card { background-color: #1A1F24; padding: 2.5rem; border-radius: 20px; box-shadow: 0 10px 40px rgba(0,0,0,0.6); text-align: center; max-width: 450px; border: 1px solid #32393F; }
                    .logo-container { width: 120px; height: auto; margin: 0 auto 1.5rem; }
                    .logo-container svg { width: 100%; height: auto; display: block; filter: drop-shadow(0 4px 10px rgba(0,0,0,0.3)); }
                    .logo-container .currentColor { color: #8ECFF2 !important; }
                    h1 { color: #8ECFF2; margin: 0.5rem 0; font-size: 2.5rem; letter-spacing: -1px; }
                    p { color: #C0C7CD; line-height: 1.6; font-size: 1.1rem; }
                    code { background-color: #000; padding: 2px 6px; border-radius: 4px; color: #D0BCFF; font-family: monospace; }
                    .status { display: inline-flex; align-items: center; padding: 6px 14px; background-color: rgba(56, 142, 60, 0.2); color: #81C784; border-radius: 20px; font-size: 0.85rem; font-weight: bold; margin-bottom: 1rem; border: 1px solid rgba(56, 142, 60, 0.3); }
                    .status::before { content: ""; width: 8px; height: 8px; background-color: #4CAF50; border-radius: 50%; margin-right: 8px; box-shadow: 0 0 8px #4CAF50; }
                    .version { font-size: 0.8rem; color: #625B71; margin-top: 2.5rem; border-top: 1px solid #32393F; paddingTop: 1.5rem; }
                    a { color: #D0BCFF; text-decoration: none; font-weight: 500; }
                    a:hover { text-decoration: underline; color: #8ECFF2; }
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

        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", html)
    }

    private fun handleSettingsRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return when (session.method) {
            NanoHTTPD.Method.GET -> {
                val json = mapper.writeValueAsString(settings.getAll())
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json)
            }
            NanoHTTPD.Method.POST -> {
                val length = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (length > 0) {
                    val data = mapper.readValue(session.inputStream, AppSettings.SettingsData::class.java)
                    handler.post { settings.apply(data) }
                    ok("Settings updated")
                } else invalidRequest("Empty")
            }
            else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method Not Allowed")
        }
    }

    private fun applySettingsDefaults(props: PopupProps): PopupProps {
        return props.copy(
            backgroundColor = if (props.backgroundColor == "#CC000000") settings.getFullBackgroundColor() else props.backgroundColor,
            borderRadius = if (props.borderRadius == 0) settings.borderRadius else props.borderRadius,
            borderWidth = if (props.borderWidth == 0) settings.borderWidth else props.borderWidth,
            borderColor = if (props.borderColor == "#00000000") settings.borderColor else props.borderColor,
            titleAlignment = if (props.titleAlignment == 0) settings.titleAlignment else props.titleAlignment,
            messageAlignment = if (props.messageAlignment == 0) settings.messageAlignment else props.messageAlignment,
            mediaPosition = props.mediaPosition ?: settings.mediaPosition,
            animationType = if (props.animationType == 0) settings.animationType else props.animationType,
            animationDuration = if (props.animationDuration == 500) settings.animationDuration else props.animationDuration,
            animationExit = settings.animationExit,
        )
    }

    private fun initNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "PiPup Service", AndroidNotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NOTIFICATION_SERVICE) as AndroidNotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun ok(msg: String) = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", msg)
    private fun invalidRequest(msg: String?) = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", msg ?: "Invalid")
}
