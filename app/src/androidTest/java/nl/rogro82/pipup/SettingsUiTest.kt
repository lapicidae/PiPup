package nl.rogro82.pipup

import android.view.View
import android.widget.SeekBar
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import nl.rogro82.pipup.ui.SettingsActivity
import org.hamcrest.Matcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the Settings UI.
 * Verifies that the initial state is correct.
 */
@RunWith(AndroidJUnit4::class)
@UnstableApi
class SettingsUiTest {

    @Before
    fun exitTouchMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @Test
    fun testInitialState() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            // Verify General submenu is loaded by default with core layout and app controls
            onView(withId(R.id.text_position)).check(matches(isDisplayed()))
            onView(withId(R.id.spinner_position)).check(matches(isDisplayed()))
            onView(withId(R.id.seekbar_padding)).check(matches(isDisplayed()))
            onView(withId(R.id.spinner_language))
                .perform(androidx.test.espresso.action.ViewActions.scrollTo())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun testMediaSubmenu() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            it.onActivity { activity ->
                activity.findViewById<View>(R.id.nav_item_media).requestFocus()
            }
            Thread.sleep(1000)

            onView(withId(R.id.spinner_media_position)).check(matches(isDisplayed()))
            onView(withId(R.id.switch_media_full_bleed)).check(matches(isDisplayed()))
            onView(withId(R.id.switch_fullscreen_upscale)).check(matches(isDisplayed()))
            onView(withId(R.id.switch_live_badge))
                .perform(androidx.test.espresso.action.ViewActions.scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.spinner_live_badge_position))
                .perform(androidx.test.espresso.action.ViewActions.scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.switch_media_placeholder))
                .perform(androidx.test.espresso.action.ViewActions.scrollTo())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun testAboutSubmenu() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            it.onActivity { activity ->
                activity.findViewById<View>(R.id.nav_item_about).requestFocus()
            }
            Thread.sleep(1000)

            onView(withId(R.id.text_about_version)).check(matches(isDisplayed()))
            onView(withId(R.id.btn_open_source_licenses))
                .perform(androidx.test.espresso.action.ViewActions.scrollTo())
                .check(matches(isDisplayed()))

            // Trigger via performClick() to avoid entering touch mode on Android TV emulator
            it.onActivity { activity ->
                activity.findViewById<View>(R.id.btn_open_source_licenses).performClick()
            }

            onView(withText(R.string.settings_about_licenses_title))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .check(matches(isDisplayed()))
            onView(withText("AndroidX (Jetpack)"))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.settings_about_licenses_close))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun testNavigationAndSliders() {
        val originalAlpha = PiPupApp.settings.backgroundAlpha
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use {
                // Navigate to Background
                it.onActivity { activity ->
                    activity.findViewById<View>(R.id.nav_item_background).requestFocus()
                }
                Thread.sleep(1000)

                onView(withId(R.id.seekbar_bg_alpha)).check(matches(isDisplayed()))
                onView(withId(R.id.seekbar_bg_alpha)).perform(setProgress())
            }
        } finally {
            PiPupApp.settings.backgroundAlpha = originalAlpha
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
