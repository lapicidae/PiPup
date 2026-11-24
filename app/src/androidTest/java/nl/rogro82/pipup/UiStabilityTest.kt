package nl.rogro82.pipup

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.rogro82.pipup.core.NotificationManager
import nl.rogro82.pipup.ui.PopupView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * Automated test suite for UI Stability, Popup Stacking across all 9 positions, and Animation variety.
 */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class UiStabilityTest {

    private val appContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val windowManager: WindowManager
        get() = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val animNames = mapOf(
        0 to "None",
        1 to "Fade",
        2 to "Slide",
        3 to "Slide & Bounce",
        4 to "Scale In",
        5 to "Scale & Bounce",
        6 to "Flip Horizontal",
        7 to "Flip Vertical",
        8 to "Sweep",
        9 to "Drop &Overshoot",
        10 to "Diagonal Zoom"
    )

    private inline fun runTest(block: (NotificationManager) -> Unit) {
        val manager = NotificationManager(appContext, windowManager)
        try {
            block(manager)
        } finally {
            manager.cancelAll()
        }
    }

    @Test
    fun testComprehensivePopups() = runTest { manager ->
        val utf8String = "🚀 PiPup: Ää Öö Üü ß | € | 漢字 | 🔥🌈"
        val allPositions = PopupProps.Position.entries
        val animations = listOf(1, 2, 3, 4, 5, 6, 7, 8, 10)
        val total = allPositions.size

        // 9 concise steps covering all 9 screen positions (0..8), rotating animations and exit flags
        for (i in allPositions.indices) {
            val pos = allPositions[i]
            val anim = animations[i % animations.size]
            val exit = (i % 2 == 0)

            val props = PopupProps(
                id = "comp_${pos.index}",
                title = "${pos.name} (${i + 1}/$total) • ${animNames[anim]}",
                message = "Pos: ${pos.name} (${pos.index}) | Anim: $anim | Exit: ${if (exit) "ON" else "OFF"}\n" +
                    "UTF-8: $utf8String",
                position = pos.index,
                animationType = anim,
                animationExit = exit,
                duration = 2,
                backgroundColor = if (exit) "#DD1B5E20" else "#DD1A237E"
            )

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                manager.enqueue(props)
            }
            Thread.sleep(2200)
        }
    }

    @Test
    fun testPopupStacking() = runTest { manager ->
        val origMax = PiPupApp.settings.maxPopups
        try {
            PiPupApp.settings.maxPopups = 3
            // 1. Simultaneous popup stacking in same position (TopRight)
            val p1 = PopupProps(id = "cam_front", title = "Camera Front", message = "Front door stream", position = 0, duration = 6)
            val p2 = PopupProps(id = "cam_back", title = "Camera Back", message = "Backyard stream", position = 0, duration = 6)
            val p3 = PopupProps(id = "doorbell", title = "Doorbell", message = "Someone is at the door", position = 0, duration = 6)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                manager.enqueue(p1)
                manager.enqueue(p2)
                manager.enqueue(p3)
            }
            Thread.sleep(2500)

            // 2. In-place update of "cam_front"
            val p1Updated = PopupProps(
                id = "cam_front",
                title = "Camera Front (Updated)",
                message = "Motion detected!",
                position = 0,
                duration = 4,
                overwrite = true
            )
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                manager.enqueue(p1Updated)
            }
            Thread.sleep(2500)

            // 3. Queueing ID-less popups alongside ID popups
            val queue1 = PopupProps(title = "Queue Item 1", message = "Standard queued notification 1", position = 4, duration = 2)
            val queue2 = PopupProps(title = "Queue Item 2", message = "Standard queued notification 2", position = 4, duration = 2)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                manager.enqueue(queue1)
                manager.enqueue(queue2)
            }
            Thread.sleep(4500)
        } finally {
            manager.cancelAll()
            PiPupApp.settings.maxPopups = origMax
        }
    }

    @Test
    fun testStaggeredMultiWindow() = runTest { manager ->
        val popA = PopupProps(id = "pop_A", title = "Popup A", message = "First staggered popup (3s)", position = 4, duration = 3)
        val popB = PopupProps(id = "pop_B", title = "Popup B", message = "Second staggered popup (5s)", position = 4, duration = 5)
        val popC = PopupProps(id = "pop_C", title = "Popup C", message = "Third staggered popup (7s)", position = 4, duration = 7)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.enqueue(popA) }
        Thread.sleep(800)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.enqueue(popB) }
        Thread.sleep(800)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.enqueue(popC) }
        Thread.sleep(8000)
    }

    @Test
    fun testAllPositionsStacking() = runTest { manager ->
        val origMax = PiPupApp.settings.maxPopups
        try {
            PiPupApp.settings.maxPopups = 3
            for (pos in PopupProps.Position.entries) {
                val idx = pos.index
                val stackDir = if (idx in listOf(5, 6)) "horizontal" else "vertical"
                val s1 = PopupProps(id = "pos_${idx}_1", title = "${pos.name} #1", message = "First ($stackDir)", position = idx, stack = stackDir, duration = 2)
                val s2 = PopupProps(id = "pos_${idx}_2", title = "${pos.name} #2", message = "Second ($stackDir)", position = idx, stack = stackDir, duration = 2)

                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    manager.enqueue(s1)
                    manager.enqueue(s2)
                }
                Thread.sleep(1800)
                manager.cancelAll()
            }
        } finally {
            manager.cancelAll()
            PiPupApp.settings.maxPopups = origMax
        }
    }

    @Test
    fun testMediaFullBleedAndLiveBadgeUi() = runTest { manager ->
        val origMax = PiPupApp.settings.maxPopups
        try {
            PiPupApp.settings.maxPopups = 2
            val dummyImageUrl = "https://dummyimage.com/640x360/004d40/ffffff.png&text=Full+Bleed+Test"

            // 1. Media-only full-bleed popup (no title/message/buttons)
            val fullBleedMediaOnly = PopupProps(
                id = "fb_media_only",
                media = PopupProps.Media.Image(dummyImageUrl, 400),
                mediaFullBleed = true,
                borderRadius = 24,
                borderWidth = 2,
                borderColor = "#00BFA5",
                position = 0,
                duration = 3
            )

            // 2. Full-bleed popup WITH title, message, and buttons (edge-to-edge media + padded text/buttons)
            val fullBleedWithTextAndButtons = PopupProps(
                id = "fb_with_text",
                title = "Full-Bleed Camera",
                message = "Edge-to-edge media with padded text & buttons",
                media = PopupProps.Media.Image(dummyImageUrl, 400),
                mediaFullBleed = true,
                contentPadding = 16,
                borderRadius = 24,
                borderWidth = 2,
                borderColor = "#FFAB00",
                buttons = listOf(
                    PopupProps.Button("open", "Open"),
                    PopupProps.Button("dismiss", "Dismiss")
                ),
                position = 2,
                duration = 3
            )

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                manager.enqueue(fullBleedMediaOnly)
                manager.enqueue(fullBleedWithTextAndButtons)
            }
            Thread.sleep(2000)

            // 3. In-place update toggling liveBadge & liveBadgePosition in preview mode
            val badgePreview = fullBleedWithTextAndButtons.copy(
                title = "Full-Bleed + LIVE Badge",
                liveBadge = true,
                liveBadgeColor = "#D32F2F",
                liveBadgePosition = "top_left",
                isPreview = true,
                overwrite = true
            )
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                manager.enqueue(badgePreview)
            }
            Thread.sleep(2000)
        } finally {
            manager.cancelAll()
            PiPupApp.settings.maxPopups = origMax
        }
    }

    @Test
    fun testOverlayPermissionDeniedDoesNotCrash() {
        val delegate = windowManager
        val denyingWindowManager = Proxy.newProxyInstance(
            WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java)
        ) { _, method, args ->
            if (method.name == "addView") {
                throw WindowManager.BadTokenException(
                    "Unable to add window -- permission denied for window type 2038"
                )
            }
            if (args == null) method.invoke(delegate) else method.invoke(delegate, *args)
        } as WindowManager
        val manager = NotificationManager(appContext, denyingWindowManager)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                manager.enqueue(
                    PopupProps(
                        id = "denied_1",
                        title = "Permission Denied Test 1",
                        message = "Must not crash without SYSTEM_ALERT_WINDOW",
                        duration = 5
                    )
                )
                manager.enqueue(
                    PopupProps(
                        title = "Permission Denied Test 2",
                        message = "Burst notification without overlay permission",
                        duration = 5
                    )
                )
            }
            // Wait for any posted UI work on the main thread to drain
            InstrumentationRegistry.getInstrumentation().runOnMainSync { }

            assertFalse(
                "No popup should remain active when overlay creation fails",
                manager.isDisplaying()
            )
            assertTrue(
                "Active slot list must be empty when overlay creation fails",
                manager.getActiveSlotDetails().isEmpty()
            )
        } finally {
            manager.cancelAll()
        }
    }

    @Test
    fun testOverlayCloseButtonAutoHideWakeAndDismiss() {
        val dm = appContext.resources.displayMetrics
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val closeCalls = AtomicInteger(0)
        var popupView: PopupView? = null

        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val props = PopupProps(
                    id = "close_overlay_test",
                    fullscreen = true,
                    mediaFullBleed = true,
                    contentPadding = 0,
                    borderWidth = 0,
                    closeButton = "top_right",
                    closeButtonTimeout = 1,
                    closeButtonSize = 56,
                    media = PopupProps.Media.Bitmap(bitmap, 640)
                )
                val view = PopupView.build(appContext, props).apply {
                    onClose = { closeCalls.incrementAndGet() }
                }
                popupView = view
                val widthSpec = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, dm.widthPixels, dm.heightPixels)

                // Overlaid close button must take zero layout space (media keeps full screen bounds)
                val mediaContainer = view.findViewById<View>(R.id.popup_media_container)
                assertEquals(dm.widthPixels, mediaContainer.measuredWidth)
                assertEquals(dm.heightPixels, mediaContainer.measuredHeight)

                val closeBtn = view.findViewById<View>(R.id.popup_close_button)
                assertEquals(View.VISIBLE, closeBtn.visibility)
                assertEquals(appContext.dpToPx(56), closeBtn.layoutParams.width)
                assertEquals(appContext.dpToPx(56), closeBtn.layoutParams.height)
                assertFalse(view.isCloseButtonHidden())
            }

            // Wait for 1s auto-hide timer to fade the close button out
            Thread.sleep(1300)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val view = popupView!!
                assertTrue("Close button must fade out after timeout", view.isCloseButtonHidden())

                // Variant B: First OK press while hidden wakes the close button without closing
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                assertFalse("First OK press must reveal close button", view.isCloseButtonHidden())
                assertEquals("First OK press while hidden must not close popup", 0, closeCalls.get())

                // Second OK / click while visible invokes onClose
                view.findViewById<View>(R.id.popup_close_button).performClick()
                assertEquals("Click while visible must invoke onClose", 1, closeCalls.get())
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                popupView?.cleanup()
            }
            bitmap.recycle()
        }
    }
}
