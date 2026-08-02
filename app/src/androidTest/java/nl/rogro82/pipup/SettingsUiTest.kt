package nl.rogro82.pipup

import android.view.View
import android.widget.SeekBar
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.rogro82.pipup.ui.SettingsActivity
import org.hamcrest.Matcher
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the Settings UI.
 * Verifies that the initial state is correct.
 */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class SettingsUiTest {

    @Test
    fun testInitialState() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            // Verify General submenu is loaded by default
            onView(withId(R.id.text_position)).check(matches(isDisplayed()))
            onView(withId(R.id.spinner_position)).check(matches(isDisplayed()))
            onView(withId(R.id.spinner_language)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun testNavigationAndSliders() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            // Navigate to Background
            it.onActivity { activity ->
                activity.findViewById<View>(R.id.nav_item_background).requestFocus()
            }
            Thread.sleep(1000)

            onView(withId(R.id.seekbar_bg_alpha)).check(matches(isDisplayed()))
            onView(withId(R.id.seekbar_bg_alpha)).perform(setProgress())
        }
    }

    private fun setProgress(): androidx.test.espresso.ViewAction {
        val progress = 128
        return object : androidx.test.espresso.ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(SeekBar::class.java)
            override fun getDescription(): String = "Set progress to $progress"
            override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
                (view as SeekBar).progress = progress
            }
        }
    }
}
