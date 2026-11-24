package nl.rogro82.pipup

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.rogro82.pipup.service.PipUpService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
@UnstableApi
class SettingsSyncTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        // Ensure the service is running
        val intent = Intent(context, PipUpService::class.java)
        context.startForegroundService(intent)
        Thread.sleep(1000)
    }

    @Test
    fun testGetSettings() {
        val url = URL("http://localhost:7979/settings")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            assertTrue(response.contains("positionIndex"))

            val data = AppSettings.SettingsData.fromJson(response)
            assertNotNull(data)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun testPostSettings() {
        val originalSettings = PiPupApp.settings.getAll()
        val newSettings = originalSettings.copy(
            positionIndex = 4,
            backgroundColor = "#FF00FF",
            backgroundAlpha = 100,
            defaultBufferMs = 750,
            mediaLoadingPlaceholder = true,
            mediaFullBleed = true,
            fullscreenUpscale = true,
            liveBadge = true,
            liveBadgePosition = "top_left"
        )

        val url = URL("http://localhost:7979/settings")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val jsonBytes = newSettings.toJSONObject().toString().toByteArray(Charsets.UTF_8)
            connection.setRequestProperty("Content-Length", jsonBytes.size.toString())

            connection.outputStream.use { it.write(jsonBytes) }

            assertEquals(200, connection.responseCode)
            Thread.sleep(500)

            val currentSettings = PiPupApp.settings.getAll()
            assertEquals(4, currentSettings.positionIndex)
            assertEquals("#FF00FF", currentSettings.backgroundColor)
            assertEquals(100, currentSettings.backgroundAlpha)
            assertEquals(750, currentSettings.defaultBufferMs)
            assertTrue(currentSettings.mediaLoadingPlaceholder)
            assertTrue(currentSettings.mediaFullBleed)
            assertTrue(currentSettings.fullscreenUpscale)
            assertTrue(currentSettings.liveBadge)
            assertEquals("top_left", currentSettings.liveBadgePosition)

        } finally {
            PiPupApp.settings.apply(originalSettings)
            connection.disconnect()
        }
    }

    @Test
    fun testSettingsInjection() {
        val originalSettings = PiPupApp.settings.getAll()
        try {
            // Set custom defaults
            PiPupApp.settings.backgroundColor = "#112233"
            PiPupApp.settings.backgroundAlpha = 200
            PiPupApp.settings.borderRadius = 50
            PiPupApp.settings.defaultBufferMs = 800
            PiPupApp.settings.mediaFullBleed = true
            PiPupApp.settings.fullscreenUpscale = true
            PiPupApp.settings.liveBadge = true
            PiPupApp.settings.liveBadgePosition = "top_right"

            // Test the logic verification via AppSettings
            val inputProps = PopupProps(
                title = "Default",
                message = "Test",
                media = PopupProps.Media.Video(uri = "rtsp://test/stream")
            )
            val enqueuedProps = PiPupApp.settings.applyDefaults(inputProps)

            // Verify injection (Alpha is prepended in getFullBackgroundColor)
            assertEquals("#C8112233", enqueuedProps.backgroundColor)
            assertEquals(50, enqueuedProps.borderRadius)
            assertEquals(true, enqueuedProps.mediaFullBleed)
            assertEquals(true, enqueuedProps.fullscreenUpscale)
            assertEquals(true, enqueuedProps.liveBadge)
            assertEquals("top_right", enqueuedProps.liveBadgePosition)
            assertEquals(800, (enqueuedProps.media as PopupProps.Media.Video).bufferMs)

        } finally {
            PiPupApp.settings.apply(originalSettings)
        }
    }

    @Test
    fun testWebhookHeartbeatQueryAndPartialJsonPreserveStyling() {
        val originalSettings = PiPupApp.settings.getAll()
        val originalWebhook = PiPupApp.settings.webhook
        try {
            PiPupApp.settings.positionIndex = 3
            PiPupApp.settings.backgroundColor = "#223344"

            // 1. Set via query parameter
            val queryUrl = URL("http://localhost:7979/settings?webhookHeartbeat=60")
            val queryConn = queryUrl.openConnection() as HttpURLConnection
            try {
                queryConn.requestMethod = "POST"
                assertEquals(200, queryConn.responseCode)
            } finally {
                queryConn.disconnect()
            }
            assertEquals(60, PiPupApp.settings.webhookHeartbeat)

            // 2. Set via webhook-only JSON body without clobbering styling settings
            val jsonBody = """{"webhookHeartbeat": 120}""".toByteArray(Charsets.UTF_8)
            val jsonConn = (URL("http://localhost:7979/settings").openConnection() as HttpURLConnection)
            try {
                jsonConn.requestMethod = "POST"
                jsonConn.doOutput = true
                jsonConn.setRequestProperty("Content-Type", "application/json")
                jsonConn.setRequestProperty("Content-Length", jsonBody.size.toString())
                jsonConn.outputStream.use { it.write(jsonBody) }
                assertEquals(200, jsonConn.responseCode)
            } finally {
                jsonConn.disconnect()
            }
            assertEquals(120, PiPupApp.settings.webhookHeartbeat)
            assertEquals(3, PiPupApp.settings.positionIndex)
            assertEquals("#223344", PiPupApp.settings.backgroundColor)

            // 3. Reject negative interval with 400 Bad Request
            val invalidUrl = URL("http://localhost:7979/settings?webhookHeartbeat=-5")
            val invalidConn = invalidUrl.openConnection() as HttpURLConnection
            try {
                invalidConn.requestMethod = "POST"
                assertEquals(400, invalidConn.responseCode)
            } finally {
                invalidConn.disconnect()
            }
            assertEquals(120, PiPupApp.settings.webhookHeartbeat)

            // 4. Verify push.heartbeat is exposed in GET /state
            val stateConn = (URL("http://localhost:7979/state").openConnection() as HttpURLConnection)
            try {
                stateConn.requestMethod = "GET"
                assertEquals(200, stateConn.responseCode)
                val stateJson = org.json.JSONObject(stateConn.inputStream.bufferedReader().use { it.readText() })
                assertEquals(120, stateJson.getJSONObject("push").getInt("heartbeat"))
            } finally {
                stateConn.disconnect()
            }
        } finally {
            PiPupApp.settings.webhook = originalWebhook
            Pusher.url = originalWebhook
            PiPupApp.settings.apply(originalSettings)
        }
    }

    @Test
    fun testPartialPostSettingsPreservesOmittedFields() {
        val originalSettings = PiPupApp.settings.getAll()
        try {
            PiPupApp.settings.apply(
                originalSettings.copy(
                    positionIndex = 2,
                    backgroundColor = "#334455",
                    maxPopups = 3,
                    advancedMode = true,
                    liveBadge = true
                )
            )

            val partialBody = """{"maxPopups": 4}""".toByteArray(Charsets.UTF_8)
            val conn = (URL("http://localhost:7979/settings").openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Content-Length", partialBody.size.toString())
                conn.outputStream.use { it.write(partialBody) }
                assertEquals(200, conn.responseCode)
            } finally {
                conn.disconnect()
            }
            Thread.sleep(300)

            val updated = PiPupApp.settings.getAll()
            assertEquals(4, updated.maxPopups)
            assertEquals(2, updated.positionIndex)
            assertEquals("#334455", updated.backgroundColor)
            assertTrue(updated.advancedMode)
            assertTrue(updated.liveBadge)
        } finally {
            PiPupApp.settings.apply(originalSettings)
        }
    }
}
