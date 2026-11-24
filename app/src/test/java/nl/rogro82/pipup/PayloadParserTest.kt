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

    @Test
    fun testFullscreenParsingAndLayoutMetrics() {
        val json = """
        {
          "id": "fs_popup",
          "position": "TopRight",
          "stack": "horizontal",
          "fullscreen": true,
          "borderWidth": 0,
          "contentPadding": 0,
          "mediaFullBleed": true,
          "media": {
            "web": {
              "uri": "http://127.0.0.1:8123",
              "width": 1920,
              "height": 1080
            }
          }
        }
        """.trimIndent()

        val props = PopupProps.fromJson(json)
        assertTrue(props.fullscreen)
        assertTrue(props.coversScreen)
        org.junit.Assert.assertNull(props.effectiveStack)
        assertEquals(PopupProps.Position.Center, props.getPositionEnum())
        assertEquals(PopupProps.StackDirection.VERTICAL, props.getStackDirectionEnum())

        val roundTrip = PopupProps.fromJson(props.toJson())
        assertTrue(roundTrip.fullscreen)
        assertTrue(roundTrip.coversScreen)
        org.junit.Assert.assertNull(roundTrip.fullscreenUpscale)

        val upscaleJson = """{"fullscreen": true, "fullscreenUpscale": true}"""
        val upscaleProps = PopupProps.fromJson(upscaleJson)
        assertEquals(true, upscaleProps.fullscreenUpscale)
        assertEquals(true, PopupProps.fromJson(upscaleProps.toJson()).fullscreenUpscale)

        val aliasJson = """{"fullscreen": true, "upscale": false}"""
        assertEquals(false, PopupProps.fromJson(aliasJson).fullscreenUpscale)

        fun assertBox(expectedW: Int, expectedH: Int, actual: Pair<Int, Int>) {
            assertEquals(expectedW, actual.first)
            assertEquals(expectedH, actual.second)
        }

        // 480p (640x360) inside 1864x900 available fullscreen box:
        // - without upscale: stays at native 640x360
        // - with upscale: fills up to 1600x900 (16:9 fit in 1864x900)
        assertBox(
            640,
            360,
            nl.rogro82.pipup.ui.MediaRenderer.fitInFullscreenBox(1864, 900, 640, 360, upscale = false)
        )
        assertBox(
            1600,
            900,
            nl.rogro82.pipup.ui.MediaRenderer.fitInFullscreenBox(1864, 900, 640, 360, upscale = true)
        )

        // 1080p (1920x1080) inside 1864x900 available fullscreen box:
        // - always clamped/downscaled to 1600x900 regardless of upscale flag
        assertBox(
            1600,
            900,
            nl.rogro82.pipup.ui.MediaRenderer.fitInFullscreenBox(1864, 900, 1920, 1080, upscale = false)
        )
        assertBox(
            1600,
            900,
            nl.rogro82.pipup.ui.MediaRenderer.fitInFullscreenBox(1864, 900, 1920, 1080, upscale = true)
        )

        // Explicit width in payload (e.g. width=400) bounds the fullscreen media width even when upscale=true:
        assertBox(
            400,
            225,
            nl.rogro82.pipup.ui.MediaRenderer.fitInFullscreenBox(
                1864,
                900,
                640,
                360,
                upscale = true,
                requestedW = 400
            )
        )
        assertBox(
            400,
            225,
            nl.rogro82.pipup.ui.MediaRenderer.fitInFullscreenBox(
                1864,
                900,
                1920,
                1080,
                upscale = false,
                requestedW = 400
            )
        )
        // Web (sourceW=0, sourceH=0) with explicit width+height vs omitted width+height:
        assertBox(
            1864,
            900,
            nl.rogro82.pipup.ui.MediaRenderer.fitInFullscreenBox(1864, 900, 0, 0)
        )
        assertBox(
            500,
            300,
            nl.rogro82.pipup.ui.MediaRenderer.fitInFullscreenBox(
                1864,
                900,
                0,
                0,
                requestedW = 500,
                requestedH = 300
            )
        )

        // Verify explicitWidth / explicitHeight tracking in JSON parsing and round-trip serialization:
        val autoVideoJson = """{"fullscreen": true, "media": {"video": {"uri": "rtsp://cam/1"}}}"""
        val autoVideoProps = PopupProps.fromJson(autoVideoJson)
        val autoVideo = autoVideoProps.media as PopupProps.Media.Video
        assertFalse(autoVideo.explicitWidth)
        org.junit.Assert.assertNull(autoVideo.height)
        val autoRoundTrip = PopupProps.fromJson(autoVideoProps.toJson()).media as PopupProps.Media.Video
        assertFalse(autoRoundTrip.explicitWidth)

        val explicitVideoJson = """{"fullscreen": true, "media": {"video": {"uri": "rtsp://cam/1", "width": 350}}}"""
        val explicitVideoProps = PopupProps.fromJson(explicitVideoJson)
        val explicitVideo = explicitVideoProps.media as PopupProps.Media.Video
        assertTrue(explicitVideo.explicitWidth)
        assertEquals(350, explicitVideo.width)
        val explicitRoundTrip = PopupProps.fromJson(explicitVideoProps.toJson()).media as PopupProps.Media.Video
        assertTrue(explicitRoundTrip.explicitWidth)
        assertEquals(350, explicitRoundTrip.width)
    }

    @Test
    fun testDismissOnBackParsingAndInputFocusRequirement() {
        val defaultProps = PopupProps.fromJson("""{"title": "Toast"}""")
        assertFalse(defaultProps.dismissOnBack)
        assertFalse(defaultProps.requiresInputFocus)

        val dismissProps = PopupProps.fromJson("""{"title": "Cam", "fullscreen": true, "dismissOnBack": true}""")
        assertTrue(dismissProps.dismissOnBack)
        assertTrue(dismissProps.requiresInputFocus)
        assertTrue(PopupProps.fromJson(dismissProps.toJson()).dismissOnBack)

        val buttonProps = PopupProps.fromJson(
            """{"title": "Confirm", "buttons": [{"id": "ok", "label": "OK"}]}"""
        )
        assertFalse(buttonProps.dismissOnBack)
        assertTrue(buttonProps.requiresInputFocus)
        assertTrue(buttonProps.dismissibleByBack)
    }

    @Test
    fun testCloseButtonParsingFocusAndBackSeparation() {
        val defaultProps = PopupProps.fromJson("""{"title": "Plain"}""")
        org.junit.Assert.assertNull(defaultProps.closeButton)
        org.junit.Assert.assertNull(defaultProps.closeButtonTimeout)
        org.junit.Assert.assertNull(defaultProps.closeButtonSize)
        assertFalse(defaultProps.hasCloseButton)
        assertFalse(defaultProps.requiresInputFocus)
        assertFalse(defaultProps.dismissibleByBack)

        val cornerProps = PopupProps.fromJson(
            """{"fullscreen": true, "closeButton": "bottom_left", "closeButtonTimeout": 5, "closeButtonSize": 56}"""
        )
        assertEquals("bottom_left", cornerProps.closeButton)
        assertEquals(5, cornerProps.closeButtonTimeout)
        assertEquals(56, cornerProps.closeButtonSize)
        assertTrue(cornerProps.hasCloseButton)
        assertTrue(cornerProps.requiresInputFocus)
        assertFalse(cornerProps.dismissibleByBack)

        val roundTrip = PopupProps.fromJson(cornerProps.toJson())
        assertEquals("bottom_left", roundTrip.closeButton)
        assertEquals(5, roundTrip.closeButtonTimeout)
        assertEquals(56, roundTrip.closeButtonSize)

        val minClamped = PopupProps.fromJson("""{"closeButton": true, "closeButtonSize": 10}""")
        assertEquals(24, minClamped.closeButtonSize)
        val maxClamped = PopupProps.fromJson("""{"closeButton": true, "closeButtonSize": 120}""")
        assertEquals(72, maxClamped.closeButtonSize)

        val boolTrueProps = PopupProps.fromJson("""{"closeButton": true, "closeButtonTimeout": 0}""")
        assertEquals("top_right", boolTrueProps.closeButton)
        assertEquals(0, boolTrueProps.closeButtonTimeout)
        assertTrue(boolTrueProps.hasCloseButton)

        val boolFalseProps = PopupProps.fromJson("""{"closeButton": false}""")
        assertEquals("none", boolFalseProps.closeButton)
        assertFalse(boolFalseProps.hasCloseButton)
        assertFalse(boolFalseProps.requiresInputFocus)

        val combinedProps = PopupProps.fromJson(
            """{"closeButton": "top_right", "dismissOnBack": true}"""
        )
        assertTrue(combinedProps.hasCloseButton)
        assertTrue(combinedProps.requiresInputFocus)
        assertTrue(combinedProps.dismissibleByBack)
    }
}
