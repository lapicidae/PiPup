package nl.rogro82.pipup

import android.content.Context
import android.view.WindowManager
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.rogro82.pipup.core.NotificationManager
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Automated test suite for UI Stability, Popup Stacking, and Animation variety.
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
        5 to "Scale & Bounce"
    )

    private val posNames = mapOf(
        0 to "Top Right",
        1 to "Top Left",
        2 to "Bottom Right",
        3 to "Bottom Left",
        4 to "Center"
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
        val positions = listOf(0, 1, 2, 3, 4)
        val animations = listOf(1, 3, 5)
        val exitVariants = listOf(false, true)

        var count = 1
        val total = positions.size * animations.size * exitVariants.size

        for (exit in exitVariants) {
            for (pos in positions) {
                for (anim in animations) {
                    val props = PopupProps(
                        title = "Anim: ${animNames[anim]} ($count/$total)",
                        message = "Position: ${posNames[pos]}\n" +
                                  "Exit Animation: ${if (exit) "ENABLED" else "DISABLED"}\n" +
                                  "Config: pos=$pos, type=$anim, exit=$exit\n" +
                                  "UTF-8: $utf8String",
                        position = pos,
                        animationType = anim,
                        animationExit = exit,
                        duration = 2,
                        backgroundColor = if (exit) "#DD1B5E20" else "#DD1A237E"
                    )

                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        manager.enqueue(props)
                    }
                    Thread.sleep(3000)
                    count++
                }
            }
        }

        // Final Media Test
        val dummyImageUrl = "https://dummyimage.com/640x360/b71c1c/ffffff.png&text=Exit+Anim+Test"
        val mediaProps = PopupProps(
            title = "Final Media & Exit Test",
            message = "This popup MUST animate OUT using Scale & Bounce.\nExit Animation: ENABLED ✅",
            media = PopupProps.Media.Image(dummyImageUrl, 480),
            position = 4,
            duration = 3,
            animationType = 5,
            animationExit = true,
            backgroundColor = "#DDb71c1c"
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.enqueue(mediaProps)
        }
        Thread.sleep(5000)
    }

    @Test
    fun testPopupStacking() = runTest { manager ->
        // 1. Simultaneous popup stacking in same position (TopRight)
        val p1 = PopupProps(id = "cam_front", title = "Camera Front", message = "Front door stream", position = 0, duration = 6)
        val p2 = PopupProps(id = "cam_back", title = "Camera Back", message = "Backyard stream", position = 0, duration = 6)
        val p3 = PopupProps(id = "doorbell", title = "Doorbell", message = "Someone is at the door", position = 0, duration = 6)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.enqueue(p1)
            manager.enqueue(p2)
            manager.enqueue(p3)
        }
        Thread.sleep(3000)

        // 2. In-place update of "cam_front"
        val p1Updated = PopupProps(id = "cam_front", title = "Camera Front (Updated)", message = "Motion detected!", position = 0, duration = 4, overwrite = true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.enqueue(p1Updated)
        }
        Thread.sleep(3000)

        // 3. Queueing ID-less popups alongside ID popups
        val queue1 = PopupProps(title = "Queue Item 1", message = "Standard queued notification 1", position = 4, duration = 2)
        val queue2 = PopupProps(title = "Queue Item 2", message = "Standard queued notification 2", position = 4, duration = 2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.enqueue(queue1)
            manager.enqueue(queue2)
        }
        Thread.sleep(5000)
    }

    @Test
    fun testStaggeredMultiWindow() = runTest { manager ->
        val popA = PopupProps(id = "pop_A", title = "Popup A", message = "First staggered popup (4s)", position = 4, duration = 4)
        val popB = PopupProps(id = "pop_B", title = "Popup B", message = "Second staggered popup (8s)", position = 4, duration = 8)
        val popC = PopupProps(id = "pop_C", title = "Popup C", message = "Third staggered popup (12s)", position = 4, duration = 12)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.enqueue(popA) }
        Thread.sleep(1000)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.enqueue(popB) }
        Thread.sleep(1000)

        InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.enqueue(popC) }
        Thread.sleep(15000)
    }

    @Test
    fun testAllPositionsStacking() = runTest { manager ->
        for (pos in 0..4) {
            val s1 = PopupProps(id = "pos_${pos}_1", title = "Pos $pos #1", message = "First at pos $pos", position = pos, duration = 3)
            val s2 = PopupProps(id = "pos_${pos}_2", title = "Pos $pos #2", message = "Second at pos $pos", position = pos, duration = 3)
            val s3 = PopupProps(id = "pos_${pos}_3", title = "Pos $pos #3", message = "Third at pos $pos", position = pos, duration = 3)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                manager.enqueue(s1)
                manager.enqueue(s2)
                manager.enqueue(s3)
            }
            Thread.sleep(4000)
            manager.cancelAll()
        }
    }
}
