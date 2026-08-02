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
 * Automated test for UI Stability, UTF-8 capability, and Animation variety.
 * Verifies both entrance and exit animations.
 */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class UiStabilityTest {

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

    @Test
    fun testComprehensivePopups() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val manager = NotificationManager(appContext, wm)

        val utf8String = "🚀 PiPup: Ää Öö Üü ß | € | 漢字 | 🔥🌈"

        // Test combinations of positions, animations and EXIT transitions
        val positions = listOf(0, 1, 2, 3, 4) // All 5 positions (TR, TL, BR, BL, Center)
        val animations = listOf(1, 3, 5) // Key animation types
        val exitVariants = listOf(false, true)

        var count = 1
        val total = positions.size * animations.size * exitVariants.size

        for (exit in exitVariants) {
            for (pos in positions) {
                for (anim in animations) {
                    val props = PopupProps(
                        title = "Anim: ${animNames[anim]} ($count/$total)",
                        message = "Position: ${posNames[pos]}\n" +
                                  "Exit Animation: ${if (exit) "ENABLED" else "DISABLED (Fade Only)"}\n" +
                                  "Config: pos=$pos, type=$anim, exit=$exit\n" +
                                  "UTF-8: $utf8String",
                        position = pos,
                        animationType = anim,
                        animationExit = exit,
                        duration = 2,
                        backgroundColor = if (exit) "#DD1B5E20" else "#DD1A237E" // Green if exit enabled, Indigo if not
                    )

                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        manager.enqueue(props)
                    }
                    // Wait for duration + buffer for exit animation
                    Thread.sleep(3000)
                    count++
                }
            }
        }

        // Final Media Test with Exit Animation
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

        manager.cancelAll()
    }
}
