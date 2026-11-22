package nl.rogro82.pipup

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import nl.rogro82.pipup.service.PipUpService
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented test suite covering all supported media types:
 * - Images: PNG, JPEG, WebP, and SVG (via SvgDecoder)
 * - Video: MP4 stream with poster, posterMode, bufferMs, crossfade, and LIVE badge
 * - Web: HTML/WebView with embedded <video>, poster, and muted playback
 * - WHEP: WebRTC/WHEP stream initialization with poster, posterMode, and state reporting
 */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class MediaTypesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val baseUrl = "http://localhost:7979"
    private var mockAssetServer: MockMediaAssetServer? = null
    private val mockPort = 18989

    /**
     * Lightweight local NanoHTTPD server providing deterministic SVG, HTML/Web, and WHEP test endpoints
     * without external network flakiness.
     */
    private class MockMediaAssetServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            return when (session.uri) {
                "/test.svg" -> {
                    val svg = """
                        <svg xmlns="http://www.w3.org/2000/svg" width="320" height="180" viewBox="0 0 320 180">
                          <rect width="320" height="180" rx="16" fill="#004D40"/>
                          <circle cx="160" cy="90" r="50" fill="#00BFA5"/>
                        </svg>
                    """.trimIndent()
                    val bytes = svg.toByteArray(Charsets.UTF_8)
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "image/svg+xml",
                        ByteArrayInputStream(bytes),
                        bytes.size.toLong()
                    )
                }
                "/web_page.html" -> {
                    val html = """
                        <!DOCTYPE html>
                        <html>
                        <body style="margin:0;background:#1A237E;color:#FFF;display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;">
                          <h2>PiPup Web Media Test</h2>
                        </body>
                        </html>
                    """.trimIndent()
                    newFixedLengthResponse(Response.Status.OK, "text/html", html)
                }
                "/whep" -> {
                    // Return a valid minimal SDP answer so WHEP signaling path is exercised
                    val sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
                    newFixedLengthResponse(Response.Status.CREATED, "application/sdp", sdp)
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }
    }

    @Before
    fun setup() {
        val intent = Intent(context, PipUpService::class.java)
        context.startForegroundService(intent)
        mockAssetServer = MockMediaAssetServer(mockPort).apply { start() }
        Thread.sleep(800)
        postJson("$baseUrl/cancel", "")
    }

    @After
    fun tearDown() {
        try {
            postJson("$baseUrl/cancel", "")
        } catch (_: Exception) {}
        try {
            mockAssetServer?.stop()
        } catch (_: Exception) {}
    }

    @Test
    fun testAllImageFormats_PngJpgWebpSvg() {
        val imageCases = listOf(
            Triple("img_png", "PNG Image", "https://dummyimage.com/320x180/1565c0/ffffff.png&text=PNG"),
            Triple("img_jpg", "JPG Image", "https://dummyimage.com/320x180/2e7d32/ffffff.jpg&text=JPG"),
            Triple("img_webp", "WebP Image", "https://www.gstatic.com/webp/gallery/4.sm.webp"),
            Triple("img_svg", "SVG Vector", "http://127.0.0.1:$mockPort/test.svg")
        )

        for ((id, label, uri) in imageCases) {
            val json = """{
                "id": "$id",
                "title": "Format: $label",
                "message": "Verifying $label decoding",
                "duration": 3,
                "position": 4,
                "overwrite": true,
                "media": {
                    "image": { "uri": "$uri", "width": 320, "cache": false }
                }
            }"""
            assertEquals(200, postJson("$baseUrl/notify", json))
            Thread.sleep(1500)

            val state = getStateJson()
            val lastPopup = state.getJSONObject("lastPopup")
            assertEquals(id, lastPopup.getString("id"))
            assertEquals("image", lastPopup.getJSONObject("media").getString("type"))
        }
    }

    @Test
    fun testVideoMediaWithPosterBufferAndLiveBadge() {
        val videoUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
        val posterUrl = "https://dummyimage.com/480x270/b71c1c/ffffff.png&text=Video+Poster"

        val json = """{
            "id": "media_video_test",
            "title": "Video Stream Test",
            "message": "ExoPlayer MP4 + Poster + Crossfade + LIVE Badge",
            "position": "TopRight",
            "duration": 6,
            "mediaFullBleed": true,
            "liveBadge": true,
            "liveBadgeColor": "#B3261E",
            "liveBadgePosition": "top_left",
            "media": {
                "video": {
                    "uri": "$videoUrl",
                    "width": 480,
                    "muted": true,
                    "poster": "$posterUrl",
                    "posterMode": "fill",
                    "bufferMs": 500
                }
            }
        }"""

        assertEquals(200, postJson("$baseUrl/notify", json))
        Thread.sleep(3500)

        val state = getStateJson()
        val lastPopup = state.getJSONObject("lastPopup")
        assertEquals("media_video_test", lastPopup.getString("id"))
        assertTrue(lastPopup.getBoolean("muted"))

        val media = lastPopup.getJSONObject("media")
        assertEquals("video", media.getString("type"))
        assertEquals(480, media.getInt("width"))
        assertTrue(media.getBoolean("poster"))
        assertEquals(500, media.getInt("bufferMs"))
    }

    @Test
    fun testWebMediaWithPoster() {
        val webUrl = "http://127.0.0.1:$mockPort/web_page.html"
        val posterUrl = "https://dummyimage.com/480x270/1a237e/ffffff.png&text=Web+Poster"

        val json = """{
            "id": "media_web_test",
            "title": "Web Media Test",
            "message": "WebView rendering with poster & VIDEO_WATCH_JS",
            "position": "BottomRight",
            "duration": 4,
            "liveBadge": true,
            "media": {
                "web": {
                    "uri": "$webUrl",
                    "width": 480,
                    "height": 270,
                    "muted": false,
                    "poster": "$posterUrl",
                    "posterMode": "fit"
                }
            }
        }"""

        assertEquals(200, postJson("$baseUrl/notify", json))
        Thread.sleep(2000)

        val state = getStateJson()
        val lastPopup = state.getJSONObject("lastPopup")
        assertEquals("media_web_test", lastPopup.getString("id"))
        assertFalse(lastPopup.getBoolean("muted"))

        val media = lastPopup.getJSONObject("media")
        assertEquals("web", media.getString("type"))
        assertEquals(480, media.getInt("width"))
        assertEquals(270, media.getInt("height"))
        assertTrue(media.getBoolean("poster"))
    }

    @Test
    fun testWhepMediaWithPosterAndState() {
        val whepUrl = "http://127.0.0.1:$mockPort/whep"
        val posterUrl = "https://dummyimage.com/480x270/4a148c/ffffff.png&text=WHEP+Poster"

        val json = """{
            "id": "media_whep_test",
            "title": "WHEP Stream Test",
            "message": "WebRTC WHEP with poster & LIVE badge",
            "position": "BottomLeft",
            "duration": 4,
            "mediaFullBleed": true,
            "liveBadge": true,
            "liveBadgePosition": "bottom_right",
            "media": {
                "whep": {
                    "uri": "$whepUrl",
                    "width": 480,
                    "height": 270,
                    "muted": true,
                    "videoFit": "cover",
                    "poster": "$posterUrl",
                    "posterMode": "stretch"
                }
            }
        }"""

        assertEquals(200, postJson("$baseUrl/notify", json))
        Thread.sleep(1500)

        val state = getStateJson()
        val lastPopup = state.getJSONObject("lastPopup")
        assertEquals("media_whep_test", lastPopup.getString("id"))
        assertTrue(lastPopup.getBoolean("muted"))

        val media = lastPopup.getJSONObject("media")
        assertEquals("whep", media.getString("type"))
        assertEquals(480, media.getInt("width"))
        assertEquals(270, media.getInt("height"))
        assertTrue(media.getBoolean("poster"))
    }

    private fun postJson(urlStr: String, json: String): Int {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (json.isNotEmpty()) {
                conn.doOutput = true
                val bytes = json.toByteArray(Charsets.UTF_8)
                conn.setRequestProperty("Content-Length", bytes.size.toString())
                conn.outputStream.use { it.write(bytes) }
            }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun getStateJson(): JSONObject {
        val conn = URL("$baseUrl/state").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}
