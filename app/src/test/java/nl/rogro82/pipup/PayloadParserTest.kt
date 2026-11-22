package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test for [PopupProps] to verify property initialization and defaults.
 */
class PayloadParserTest {

    @Test
    fun testPopupPropsInitialization() {
        val props = PopupProps(
            id = "test_id_123",
            title = "Test Title",
            message = "Test Message",
            duration = 10,
            position = 2,
            overwrite = true
        )

        assertEquals("test_id_123", props.id)
        assertEquals("Test Title", props.title)
        assertEquals("Test Message", props.message)
        assertEquals(10, props.duration)
        assertEquals(2, props.position)
        assertTrue(props.overwrite)
    }

    @Test
    fun testPopupPropsDefaults() {
        val props = PopupProps(title = "Default Test")
        assertEquals("Default Test", props.title)
        assertEquals(10, props.duration)
        assertEquals(0, props.position)
        assertFalse(props.overwrite)
    }

    @Test
    fun testDefaultDuration() {
        val props = PopupProps(title = "Default Duration Test")
        assertEquals(10, props.duration)
    }

    @Test
    fun testPositionValuesMapping() {
        val expectedPositions = listOf(
            0 to PopupProps.Position.TopRight,
            1 to PopupProps.Position.TopLeft,
            2 to PopupProps.Position.BottomRight,
            3 to PopupProps.Position.BottomLeft,
            4 to PopupProps.Position.Center,
            5 to PopupProps.Position.TopCenter,
            6 to PopupProps.Position.BottomCenter,
            7 to PopupProps.Position.CenterLeft,
            8 to PopupProps.Position.CenterRight
        )

        for ((index, expectedEnum) in expectedPositions) {
            val props = PopupProps(title = "Pos $index", position = index)
            assertEquals(index, props.position)
            assertEquals(expectedEnum, props.getPositionEnum())
        }
    }

    @Test
    fun testOverwriteFlag() {
        val propsDefault = PopupProps(title = "Default Overwrite")
        assertFalse(propsDefault.overwrite)

        val propsOverwrite = PopupProps(title = "Explicit Overwrite", overwrite = true)
        assertTrue(propsOverwrite.overwrite)
    }

    @Test
    fun testButtonsParsingAndLimits() {
        val jsonStr = """
        {
          "title": "Button Test",
          "buttons": [
            { "id": "btn1", "label": "  Accept  " },
            { "id": "btn2", "label": "Very Long Label That Exceeds Thirty Characters Max" },
            { "id": "", "label": "No ID" },
            { "id": "no_label", "label": "   " },
            { "id": "btn3", "label": "Label 3" },
            { "id": "btn4", "label": "Label 4" },
            { "id": "btn5", "label": "Label 5" },
            { "id": "btn6", "label": "Label 6 Overflow" }
          ]
        }
        """.trimIndent()

        val props = PopupProps.fromJson(jsonStr)
        assertEquals(5, props.buttons.size) // Capped at max 5
        assertEquals("btn1", props.buttons[0].id)
        assertEquals("Accept", props.buttons[0].label)
        assertEquals("Very Long Label That Exceeds T", props.buttons[1].label) // Capped at 30 chars
    }

    @Test
    fun testIsInternalUpdateActionSecurity() {
        // Verify external JSON input cannot forge isInternalUpdateAction
        val jsonStr = """
        {
          "title": "Forged Update",
          "isInternalUpdateAction": true
        }
        """.trimIndent()

        val props = PopupProps.fromJson(jsonStr)
        assertFalse(props.isInternalUpdateAction)
    }

    @Test
    fun testStackingParsing() {
        val jsonVertical = """{"title": "Vertical Stack", "stack": "vertical"}"""
        val propsVertical = PopupProps.fromJson(jsonVertical)
        assertEquals("vertical", propsVertical.stack)

        val jsonHorizontal = """{"title": "Horizontal Stack", "stack": "HORIZONTAL"}"""
        val propsHorizontal = PopupProps.fromJson(jsonHorizontal)
        assertEquals("horizontal", propsHorizontal.stack)

        val jsonInvalid = """{"title": "Invalid Stack", "stack": "invalid_mode"}"""
        val propsInvalid = PopupProps.fromJson(jsonInvalid)
        org.junit.Assert.assertNull(propsInvalid.stack)
    }

    @Test
    fun testInternalUpdateActionDefaults() {
        val props = PopupProps(title = "Internal Action Test")
        assertFalse(props.isInternalUpdateAction)
    }

    @Test
    fun testPositionAndSideMapping() {
        val topLeft = PopupProps.Position.TopLeft
        assertEquals(PopupProps.ScreenSide.LEFT, topLeft.getScreenSide())
        assertEquals(PopupProps.VerticalAnchor.TOP, topLeft.getVerticalAnchor())

        val center = PopupProps.Position.Center
        assertEquals(PopupProps.ScreenSide.CENTER, center.getScreenSide())
        assertEquals(PopupProps.VerticalAnchor.CENTER, center.getVerticalAnchor())

        val bottomRight = PopupProps.Position.BottomRight
        assertEquals(PopupProps.ScreenSide.RIGHT, bottomRight.getScreenSide())
        assertEquals(PopupProps.VerticalAnchor.BOTTOM, bottomRight.getVerticalAnchor())
    }

    @Test
    fun testStackDirectionNormalization() {
        val vert = PopupProps.parseStackDirection("VERTICAL")
        assertEquals(PopupProps.StackDirection.VERTICAL, vert)

        val horiz = PopupProps.parseStackDirection("  Horizontal  ")
        assertEquals(PopupProps.StackDirection.HORIZONTAL, horiz)

        val fallback = PopupProps.parseStackDirection("invalid_string")
        assertEquals(PopupProps.StackDirection.VERTICAL, fallback)
    }

    @Test
    fun testMediaPosterBufferAndPosterModeParsing() {
        val videoJson = """
        {
          "title": "Camera",
          "cornerRadius": 24,
          "padding": 16,
          "position": "BottomLeft",
          "mediaFullBleed": true,
          "liveBadge": true,
          "liveBadgeColor": "#FF0000",
          "liveBadgePosition": "top_left",
          "media": {
            "video": {
              "uri": "rtsp://cam/stream",
              "width": 640,
              "height": 360,
              "muted": false,
              "poster": "http://cam/snap.jpg",
              "posterMode": "FILL",
              "bufferMs": 50
            }
          }
        }
        """.trimIndent()

        val props = PopupProps.fromJson(videoJson)
        assertEquals(24, props.borderRadius)
        assertEquals(16, props.contentPadding)
        assertEquals(3, props.position)
        assertEquals(PopupProps.Position.BottomLeft, props.getPositionEnum())
        assertEquals(true, props.mediaFullBleed)
        assertEquals(true, props.liveBadge)
        assertEquals("#FF0000", props.liveBadgeColor)
        assertEquals("top_left", props.liveBadgePosition)

        val video = props.media as PopupProps.Media.Video
        assertEquals("rtsp://cam/stream", video.uri)
        assertEquals(640, video.width)
        assertEquals(360, video.height)
        assertFalse(video.muted)
        assertEquals("http://cam/snap.jpg", video.poster)
        assertEquals("fill", video.posterMode)
        assertEquals(100, video.bufferMs) // Clamped to min 100ms
    }

    @Test
    fun testBufferMsUpperClampAndWhepWebPosterMode() {
        val whepJson = """
        {
          "position": "top_right",
          "media": {
            "whep": {
              "uri": "http://go2rtc/api/webrtc?src=cam",
              "width": 800,
              "height": 450,
              "muted": false,
              "poster": "http://cam/poster.jpg",
              "posterMode": "stretch"
            }
          }
        }
        """.trimIndent()

        val whepProps = PopupProps.fromJson(whepJson)
        assertEquals(0, whepProps.position)
        val whep = whepProps.media as PopupProps.Media.Whep
        assertEquals(800, whep.width)
        assertEquals(450, whep.height)
        assertFalse(whep.muted)
        assertEquals("http://cam/poster.jpg", whep.poster)
        assertEquals("stretch", whep.posterMode)

        val webJson = """
        {
          "media": {
            "web": {
              "uri": "http://cam/player.html",
              "width": 640,
              "poster": "http://cam/web.jpg",
              "posterMode": "invalid_mode"
            }
          }
        }
        """.trimIndent()
        val webProps = PopupProps.fromJson(webJson)
        val web = webProps.media as PopupProps.Media.Web
        assertEquals(360, web.height)
        assertEquals("http://cam/web.jpg", web.poster)
        org.junit.Assert.assertNull(web.posterMode)
    }

    @Test
    fun testLowLatencyBufferDurationsAndAnamorphicAspect() {
        val (minBuf, maxBuf, startBuf, rebuf) = nl.rogro82.pipup.ui.MediaRenderer.lowLatencyBufferDurations(250)!!
        assertEquals(250, startBuf)
        assertEquals(500, rebuf)
        assertEquals(1000, minBuf)
        assertEquals(5000, maxBuf)

        // Substream 704x576 with DAR 16:9 (PAR ~ 1.4545) -> target height for width 640 should be 360
        val par = (16f / 9f) / (704f / 576f)
        val h = nl.rogro82.pipup.ui.MediaRenderer.heightForDisplayAspect(640, 704, 576, par)
        assertEquals(360, h)
    }
}
