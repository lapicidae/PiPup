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
        return try {
            when (uri) {
                "/cancel" -> {
                    notificationManager.cancelAll()
                    ok("Queue cleared")
                }
                "/notify", "/", "/api/notify" -> {
                    payloadParser.parse(session)?.let { props ->
                        val finalProps = applySettingsDefaults(props)
                        notificationManager.enqueue(finalProps)
                        ok("Enqueued")
                    } ?: invalidRequest("Invalid payload")
                }
                "/settings" -> {
                    handleSettingsRequest(session)
                }
                else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request error", e)
            invalidRequest(e.message)
        }
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
            animationExit = settings.animationExit
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
