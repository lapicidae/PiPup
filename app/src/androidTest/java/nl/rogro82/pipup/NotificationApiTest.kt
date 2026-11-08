package nl.rogro82.pipup

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.rogro82.pipup.service.PipUpService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun testCancelWithoutParamsAndAllTrue() {
        // Clean state
        sendPostWithResponse("$baseUrl/cancel", ByteArray(0))

        // 1. Cancel active popup with POST /cancel
        val notify = """{"title": "Active Popup", "duration": 30}"""
        sendPostWithResponse("$baseUrl/notify", notify.toByteArray())
        Thread.sleep(500)

        val resCancel = sendPostWithResponse("$baseUrl/cancel", ByteArray(0))
        assertEquals(200, resCancel.code)
        assertEquals("OK: Cancelled", resCancel.body)

        // 2. Cancel when empty
        val resEmpty = sendPostWithResponse("$baseUrl/cancel", ByteArray(0))
        assertEquals(200, resEmpty.code)
        assertEquals("OK: Nothing to cancel", resEmpty.body)

        // 3. Cancel with POST /cancel?all=true
        sendPostWithResponse("$baseUrl/notify", notify.toByteArray())
        Thread.sleep(500)

        val resAllTrue = sendPostWithResponse("$baseUrl/cancel?all=true", ByteArray(0))
        assertEquals(200, resAllTrue.code)
        assertEquals("OK: Cancelled", resAllTrue.body)
    }

    @Test
    fun testCancelByIdContract() {
        // Clean state
        sendPostWithResponse("$baseUrl/cancel", ByteArray(0))

        // 1. Active popup cancel by ID
        val activeNotify = """{"id": "active_id_1", "title": "Active ID Test", "duration": 30}"""
        sendPostWithResponse("$baseUrl/notify", activeNotify.toByteArray())
        Thread.sleep(500)

        val resActiveCancel = sendPostWithResponse("$baseUrl/cancel?id=active_id_1", ByteArray(0))
        assertEquals(200, resActiveCancel.code)
        assertEquals("OK: Cancelled", resActiveCancel.body)

        // 2. Queued popup cancel by ID (fill capacity up to max 5 popups to guarantee 6th is queued)
        for (i in 1..5) {
            val fill = """{"id": "q_$i", "title": "Fill $i", "duration": 30}"""
            sendPostWithResponse("$baseUrl/notify", fill.toByteArray())
        }
        val queued = """{"id": "queued_target", "title": "Queued Target", "duration": 30}"""
        sendPostWithResponse("$baseUrl/notify", queued.toByteArray())
        Thread.sleep(500)

        val resQueueCancel = sendPostWithResponse("$baseUrl/cancel?id=queued_target", ByteArray(0))
        assertEquals(200, resQueueCancel.code)
        assertEquals("OK: Removed from queue", resQueueCancel.body)

        // Clean active
        sendPostWithResponse("$baseUrl/cancel", ByteArray(0))

        // 3. Non-existent ID cancel
        sendPostWithResponse("$baseUrl/notify", activeNotify.toByteArray())
        Thread.sleep(500)

        val resNotFound = sendPostWithResponse("$baseUrl/cancel?id=non_existent_id", ByteArray(0))
        assertEquals(200, resNotFound.code)
        assertEquals("ID not found: non_existent_id", resNotFound.body)

        // Clean state
        sendPostWithResponse("$baseUrl/cancel", ByteArray(0))
    }

    @Test
    fun testUpdateInPlaceRegression() {
        // Clean state
        sendPostWithResponse("$baseUrl/cancel", ByteArray(0))

        val initial = """{"id": "inplace_1", "title": "Initial Title", "message": "Initial Message", "duration": 30}"""
        val resInitial = sendPostWithResponse("$baseUrl/notify", initial.toByteArray())
        assertEquals(200, resInitial.code)
        assertEquals("OK: Enqueued", resInitial.body)

        Thread.sleep(500)

        val update = """{"id": "inplace_1", "title": "Updated Title", "message": "Updated Message", "duration": 30}"""
        val resUpdate = sendPostWithResponse("$baseUrl/notify", update.toByteArray())
        assertEquals(200, resUpdate.code)
        assertEquals("OK: Enqueued", resUpdate.body)

        // Clean state
        sendPostWithResponse("$baseUrl/cancel", ByteArray(0))
    }

    @Test
    fun testPayloadTooLargeRegression() {
        // Construct a JSON payload exceeding MAX_JSON_BODY_BYTES (256 KB)
        val largeMessage = "A".repeat(260 * 1024)
        val largePayload = """{"title": "Large Payload", "message": "$largeMessage"}"""

        val res = sendPostWithResponse("$baseUrl/notify", largePayload.toByteArray())
        assertEquals(400, res.code)

        // Clean state
        sendPostWithResponse("$baseUrl/cancel", ByteArray(0))
    }

    @Test
    fun testMaxPopupsOneBehavior() {
        sendPostWithResponse("$baseUrl/cancel", ByteArray(0))
        val settingsRes = sendGetWithResponse("$baseUrl/settings")
        val origMax = try {
            org.json.JSONObject(settingsRes.body).optInt("maxPopups", 1)
        } catch (_: Exception) {
            1
        }

        try {
            sendPostWithResponse("$baseUrl/settings", """{"maxPopups": 1}""".toByteArray())

            // 1. Enqueue first notification
            val first = """{"id": "pop_1", "title": "First", "duration": 30}"""
            val res1 = sendPostWithResponse("$baseUrl/notify", first.toByteArray())
            assertEquals(200, res1.code)

            val state1 = sendGetWithResponse("$baseUrl/state")
            assertEquals(200, state1.code)
            val json1 = org.json.JSONObject(state1.body)
            assertEquals("pop_1", json1.getJSONObject("popup").getString("id"))

            // 2. Enqueue second with overwrite = false -> should queue, pop_1 remains active
            val secondQueue = """{"id": "pop_2", "title": "Second Queued", "overwrite": false, "duration": 30}"""
            val res2 = sendPostWithResponse("$baseUrl/notify", secondQueue.toByteArray())
            assertEquals(200, res2.code)

            val state2 = sendGetWithResponse("$baseUrl/state")
            assertEquals(200, state2.code)
            val json2 = org.json.JSONObject(state2.body)
            assertEquals("pop_1", json2.getJSONObject("popup").getString("id"))

            // Cancel pop_2 from queue
            val resCancelQueue = sendPostWithResponse("$baseUrl/cancel?id=pop_2", ByteArray(0))
            assertEquals(200, resCancelQueue.code)
            assertEquals("OK: Removed from queue", resCancelQueue.body)

            // 3. Enqueue third with overwrite = true -> should evict pop_1 immediately
            val thirdOverwrite = """{"id": "pop_3", "title": "Third Overwrite", "overwrite": true, "duration": 30}"""
            val res3 = sendPostWithResponse("$baseUrl/notify", thirdOverwrite.toByteArray())
            assertEquals(200, res3.code)

            val state3 = sendGetWithResponse("$baseUrl/state")
            assertEquals(200, state3.code)
            val json3 = org.json.JSONObject(state3.body)
            assertEquals("pop_3", json3.getJSONObject("popup").getString("id"))

            val resCancelActive = sendPostWithResponse("$baseUrl/cancel?id=pop_3", ByteArray(0))
            assertEquals(200, resCancelActive.code)
            assertEquals("OK: Cancelled", resCancelActive.body)
        } finally {
            try {
                sendPostWithResponse("$baseUrl/cancel", ByteArray(0))
            } catch (_: Exception) {}
            try {
                sendPostWithResponse("$baseUrl/settings", """{"maxPopups": $origMax}""".toByteArray())
            } catch (_: Exception) {}
        }
    }

    @Test
    fun testDefaultDurationContract() {
        val jsonWithoutDuration = """{"title": "Default Duration Test", "message": "Duration omitted"}"""
        val res = sendPostWithResponse("$baseUrl/notify", jsonWithoutDuration.toByteArray())
        assertEquals(200, res.code)
        assertEquals("OK: Enqueued", res.body)
        sendPostWithResponse("$baseUrl/cancel", ByteArray(0))
    }

    @Test
    fun testNonOverlappingStackingAndLoadingModes() {
        val origMax = PiPupApp.settings.maxPopups
        val origBehavior = PiPupApp.settings.popupLoadingBehavior
        try {
            sendPostWithResponse(
                "$baseUrl/settings",
                """{"maxPopups": 5, "popupLoadingBehavior": 1}""".toByteArray()
            )
            sendPostWithResponse("$baseUrl/cancel", ByteArray(0))
            Thread.sleep(300)

            val multiline = (1..7).joinToString("\\n") { "Line $it of tall popup content" }

            // 1. Place tall popup #1 at TopRight (pos 0) and tall popup #2 at BottomRight (pos 2)
            val tr1 = """{"id": "tr_1", "title": "TR 1", "message": "$multiline", "position": 0, "duration": 30}"""
            val br1 = """{"id": "br_1", "title": "BR 1", "message": "$multiline", "position": 2, "duration": 30}"""
            assertEquals(200, sendPostWithResponse("$baseUrl/notify", tr1.toByteArray()).code)
            assertEquals(200, sendPostWithResponse("$baseUrl/notify", br1.toByteArray()).code)
            Thread.sleep(600)

            // 2. Enqueue a 2nd tall popup at TopRight (tr_2, overwrite=false) -> would shift down into br_1, so must queue!
            val tr2 = """{"id": "tr_2", "title": "TR 2", "message": "$multiline", "position": 0, "overwrite": false, "duration": 30}"""
            assertEquals(200, sendPostWithResponse("$baseUrl/notify", tr2.toByteArray()).code)
            Thread.sleep(500)

            val stateAfterTr2 = org.json.JSONObject(sendGetWithResponse("$baseUrl/state").body)
            val activeIdsAfterTr2 = mutableSetOf<String>()
            val popupsArr1 = stateAfterTr2.getJSONArray("popups")
            for (i in 0 until popupsArr1.length()) {
                activeIdsAfterTr2.add(popupsArr1.getJSONObject(i).getString("id"))
            }
            assertTrue("tr_1 should be active", activeIdsAfterTr2.contains("tr_1"))
            assertTrue("br_1 should be active", activeIdsAfterTr2.contains("br_1"))
            assertTrue("tr_2 must wait in queue to prevent overlap", !activeIdsAfterTr2.contains("tr_2"))

            // 3. In Parallel Fast-Track mode (1), enqueue a non-colliding popup at TopLeft (pos 1) -> overtakes queued tr_2!
            val tl1 = """{"id": "tl_1", "title": "TL FastTrack", "message": "Fits on left", "position": 1, "overwrite": false, "duration": 30}"""
            assertEquals(200, sendPostWithResponse("$baseUrl/notify", tl1.toByteArray()).code)
            Thread.sleep(500)

            val stateAfterTl1 = org.json.JSONObject(sendGetWithResponse("$baseUrl/state").body)
            val activeIdsAfterTl1 = mutableSetOf<String>()
            val popupsArr2 = stateAfterTl1.getJSONArray("popups")
            for (i in 0 until popupsArr2.length()) {
                activeIdsAfterTl1.add(popupsArr2.getJSONObject(i).getString("id"))
            }
            assertTrue("tl_1 should fast-track and become active", activeIdsAfterTl1.contains("tl_1"))
            assertTrue("tr_2 must still wait in queue", !activeIdsAfterTl1.contains("tr_2"))

            // 4. Cancel br_1 -> now tr_2 has space at TopRight and must automatically display!
            val cancelBr1 = sendPostWithResponse("$baseUrl/cancel?id=br_1", ByteArray(0))
            assertEquals(200, cancelBr1.code)
            Thread.sleep(1000)

            val stateAfterCancel = org.json.JSONObject(sendGetWithResponse("$baseUrl/state").body)
            val activeIdsAfterCancel = mutableSetOf<String>()
            val popupsArr3 = stateAfterCancel.getJSONArray("popups")
            for (i in 0 until popupsArr3.length()) {
                activeIdsAfterCancel.add(popupsArr3.getJSONObject(i).getString("id"))
            }
            assertTrue("tr_2 should now be active after br_1 was removed", activeIdsAfterCancel.contains("tr_2"))

            // 5. Verify First-Ready-Wins (popupLoadingBehavior=1) at the same position with maxPopups=1:
            // A slow media popup is enqueued first, followed immediately by a fast text popup.
            // The fast text popup becomes ready first and wins the screen slot; once cancelled,
            // the slow popup (waiting in mReadyWaiting) is displayed.
            sendPostWithResponse("$baseUrl/cancel", ByteArray(0))
            sendPostWithResponse(
                "$baseUrl/settings",
                """{"maxPopups": 1, "popupLoadingBehavior": 1}""".toByteArray()
            )
            Thread.sleep(300)

            val slowImgUrl = "https://dummyimage.com/320x180/112233/ffffff&text=Slow+${System.currentTimeMillis()}"
            val slowPopup = """{
                "id": "slow_1",
                "title": "Slow Media",
                "position": 0,
                "duration": 30,
                "media": { "image": { "uri": "$slowImgUrl", "width": 320 } }
            }"""
            val fastPopup = """{
                "id": "fast_2",
                "title": "Fast Text",
                "message": "Ready immediately",
                "position": 0,
                "duration": 30
            }"""
            assertEquals(200, sendPostWithResponse("$baseUrl/notify", slowPopup.toByteArray()).code)
            assertEquals(200, sendPostWithResponse("$baseUrl/notify", fastPopup.toByteArray()).code)
            Thread.sleep(150)

            val stateRace = org.json.JSONObject(sendGetWithResponse("$baseUrl/state").body)
            assertEquals(
                "fast_2 should overtake slow_1 while slow_1 is still loading",
                "fast_2",
                stateRace.getJSONObject("popup").getString("id")
            )
        } finally {
            try {
                sendPostWithResponse("$baseUrl/cancel", ByteArray(0))
            } catch (_: Exception) {}
            try {
                sendPostWithResponse(
                    "$baseUrl/settings",
                    """{"maxPopups": $origMax, "popupLoadingBehavior": $origBehavior}""".toByteArray()
                )
            } catch (_: Exception) {}
        }
    }

    private data class ApiResponse(val code: Int, val body: String)

    private fun sendGetWithResponse(urlStr: String): ApiResponse {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            val code = try { conn.responseCode } catch (_: Exception) { 500 }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            ApiResponse(code, body)
        } catch (_: Exception) {
            val code = try { conn.responseCode } catch (_: Exception) { 500 }
            val body = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) {
                ""
            }
            ApiResponse(code, body)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun sendPost(urlStr: String, data: ByteArray): Int {
        return sendPostWithResponse(urlStr, data).code
    }

    private fun sendPostWithResponse(urlStr: String, data: ByteArray): ApiResponse {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (data.isNotEmpty()) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Length", data.size.toString())
                try {
                    conn.outputStream.use { it.write(data) }
                } catch (_: Exception) {}
            }
            val code = try { conn.responseCode } catch (_: Exception) { 400 }
            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) {
                ""
            }
            ApiResponse(code, body)
        } catch (_: Exception) {
            val code = try { conn.responseCode } catch (_: Exception) { 400 }
            val body = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (_: Exception) {
                ""
            }
            ApiResponse(code, body)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun writeFormField(dos: DataOutputStream, boundary: String, name: String, value: String) {
        dos.writeBytes("--$boundary\r\n")
        dos.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        dos.writeBytes("$value\r\n")
    }
}
