package nl.rogro82.pipup

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.rogro82.pipup.service.PipUpService
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verifies that the app supports WebP images via the Web API.
 */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class WebpSupportTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val baseUrl = "http://localhost:7979"

    @Before
    fun setup() {
        val intent = Intent(context, PipUpService::class.java)
        context.startForegroundService(intent)
        Thread.sleep(1000)
    }

    @Test
    fun testWebpNotification() {
        // Lossless WebP with Alpha from Google Gallery
        val webpUrl = "https://www.gstatic.com/webp/gallery/4.sm.webp"

        val json = """{
            "title": "WebP Support Test",
            "message": "Testing native WebP rendering with Alpha channel",
            "duration": 5,
            "position": 4,
            "media": {
                "image": { "uri": "$webpUrl", "width": 480 }
            }
        }"""

        val url = URL("$baseUrl/notify")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(json.toByteArray()) }

            assertEquals(200, conn.responseCode)
            // Wait to visually confirm on screen during local run
            Thread.sleep(6000)
        } finally {
            conn.disconnect()
        }
    }
}
