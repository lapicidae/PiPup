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
        assertEquals(0, props.position)
        assertFalse(props.overwrite)
    }
}
