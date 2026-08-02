package nl.rogro82.pipup

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.rogro82.pipup.service.PipUpService
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Automated test for the Web API (/notify).
 * Verifies all modification possibilities mentioned in the readme.md.
 */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class NotificationApiTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val baseUrl = "http://localhost:7979"

    @Before
    fun setup() {
        // Ensure the service is running
        val intent = Intent(context, PipUpService::class.java)
        context.startForegroundService(intent)
        Thread.sleep(1000)
    }

    @Test
    fun testJsonNotificationStyling() {
        // Test combinations of text styling, borders and colors
        val payloads = listOf(
            """{
                "title": "Style Test: Big & Blue",
                "titleSize": 32.0,
                "titleColor": "#0000FF",
                "titleAlignment": 1,
                "message": "Centered message with border",
                "messageAlignment": 1,
                "borderWidth": 4,
                "borderColor": "#FFFF00",
                "borderRadius": 20,
                "backgroundColor": "#AA000000",
                "duration": 2
            }""",
            """{
                "title": "Style Test: Small & Red",
                "titleSize": 12.0,
                "titleColor": "#FF0000",
                "titleAlignment": 2,
                "message": "Right aligned text",
                "messageAlignment": 2,
                "backgroundColor": "#FFFFFF",
                "messageColor": "#000000",
                "duration": 2
            }"""
        )

        for (json in payloads) {
            val code = sendPost("$baseUrl/notify", json.toByteArray())
            assertEquals(200, code)
            Thread.sleep(2500)
        }
    }

    @Test
    fun testMediaPositionsAndAnimations() {
        // Test different media positions and animations
        val anims = listOf(3, 5, 10) // Slide & Bounce, Scale & Bounce, Diagonal Zoom
        val mediaPos = listOf(0, 1, 2, 3) // Top, Bottom, Left, Right

        for (i in anims.indices) {
            val json = """{
                "title": "Animation: ${anims[i]}",
                "message": "Media Position: ${mediaPos[i % mediaPos.size]}",
                "animationType": ${anims[i]},
                "animationExit": true,
                "mediaPosition": ${mediaPos[i % mediaPos.size]},
                "duration": 2,
                "media": {
                    "image": { "uri": "https://dummyimage.com/200x150/000/fff&text=Media", "width": 200 }
                }
            }"""
            val code = sendPost("$baseUrl/notify", json.toByteArray())
            assertEquals(200, code)
            Thread.sleep(3000)
        }
    }

    @Test
    fun testMultipartImageUpload() {
        val boundary = "Boundary-${System.currentTimeMillis()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        // Create a dummy bitmap for upload
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val imageBytes = bos.toByteArray()

        val url = URL("$baseUrl/notify")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.doInput = true
            conn.doOutput = true
            conn.useCaches = false
            conn.requestMethod = "POST"
            conn.setRequestProperty("Connection", "Keep-Alive")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            DataOutputStream(conn.outputStream).use { dos ->
                // Title field
                writeFormField(dos, boundary, "title", "Multipart Upload Test")
                // Message field
                writeFormField(dos, boundary, "message", "Verifying local file upload handling")
                // Background field
                writeFormField(dos, boundary, "backgroundColor", "#CC4A148C")
                // Duration
                writeFormField(dos, boundary, "duration", "4")

                // Image file
                dos.writeBytes("$twoHyphens$boundary$lineEnd")
                dos.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"test.png\"$lineEnd")
                dos.writeBytes("Content-Type: image/png$lineEnd")
                dos.writeBytes(lineEnd)
                dos.write(imageBytes)
                dos.writeBytes(lineEnd)

                // End
                dos.writeBytes("$twoHyphens$boundary$twoHyphens$lineEnd")
                dos.flush()
            }

            assertEquals(200, conn.responseCode)
            Thread.sleep(5000)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun testCancelAndOverwrite() {
        // 1. Send a long notification
        val longJson = """{"title": "Persistent", "message": "Should be canceled", "duration": 30}"""
        sendPost("$baseUrl/notify", longJson.toByteArray())
        Thread.sleep(1000)

        // 2. Cancel it
        val cancelCode = sendPost("$baseUrl/cancel", ByteArray(0))
        assertEquals(200, cancelCode)
        Thread.sleep(1000)

        // 3. Send with overwrite
        val first = """{"title": "First", "message": "Going to be overwritten", "duration": 10}"""
        sendPost("$baseUrl/notify", first.toByteArray())
        Thread.sleep(1000)

        val second = """{"title": "Second", "message": "I have priority!", "overwrite": true, "duration": 3, "backgroundColor": "#FF5722"}"""
        val overwriteCode = sendPost("$baseUrl/notify", second.toByteArray())
        assertEquals(200, overwriteCode)
        Thread.sleep(4000)
    }

    @Test
    fun testAllScreenPositions() {
        // Test all 5 screen positions defined in readme.md
        val positions = listOf(0, 1, 2, 3, 4)
        val posNames = listOf("TopRight", "TopLeft", "BottomRight", "BottomLeft", "Center")

        for (i in positions.indices) {
            val json = """{
                "title": "Position Test: ${posNames[i]}",
                "message": "Screen position index: ${positions[i]}",
                "position": ${positions[i]},
                "duration": 2,
                "backgroundColor": "#AA1A237E"
            }"""
            val code = sendPost("$baseUrl/notify", json.toByteArray())
            assertEquals(200, code)
            Thread.sleep(2500)
        }
    }

    @Test
    fun testTextAlignment() {
        // Test all alignment possibilities (Left=0, Center=1, Right=2)
        val alignments = listOf(0, 1, 2)
        val names = listOf("Left", "Center", "Right")

        for (i in alignments.indices) {
            val json = """{
                "title": "Alignment Test: ${names[i]}",
                "titleAlignment": ${alignments[i]},
                "message": "This message should be ${names[i]} aligned. We use a longer text to see the effect clearly on multiple lines if possible, but even for short text it should work.",
                "messageAlignment": ${alignments[i]},
                "duration": 3,
                "backgroundColor": "#AA1A237E"
            }"""
            val code = sendPost("$baseUrl/notify", json.toByteArray())
            assertEquals(200, code)
            Thread.sleep(4000)
        }
    }

    private fun sendPost(urlStr: String, data: ByteArray): Int {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (data.isNotEmpty()) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Length", data.size.toString())
                conn.outputStream.use { it.write(data) }
            }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun writeFormField(dos: DataOutputStream, boundary: String, name: String, value: String) {
        dos.writeBytes("--$boundary\r\n")
        dos.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        dos.writeBytes("$value\r\n")
    }
}
