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

            val data = Json.mapper.readValue(response, AppSettings.SettingsData::class.java)
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
            backgroundAlpha = 100
        )

        val url = URL("http://localhost:7979/settings")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Content-Length", Json.mapper.writeValueAsBytes(newSettings).size.toString())

            connection.outputStream.use { it.write(Json.mapper.writeValueAsBytes(newSettings)) }

            assertEquals(200, connection.responseCode)
            Thread.sleep(500)

            val currentSettings = PiPupApp.settings.getAll()
            assertEquals(4, currentSettings.positionIndex)
            assertEquals("#FF00FF", currentSettings.backgroundColor)
            assertEquals(100, currentSettings.backgroundAlpha)

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

            val service = PipUpService()
            // Test the 'internal' method for logic verification
            val inputProps = PopupProps(title = "Default", message = "Test")
            val enqueuedProps = service.applySettingsDefaults(inputProps)

            // Verify injection (Alpha is prepended in getFullBackgroundColor)
            assertEquals("#C8112233", enqueuedProps.backgroundColor)
            assertEquals(50, enqueuedProps.borderRadius)

        } finally {
            PiPupApp.settings.apply(originalSettings)
        }
    }
}
