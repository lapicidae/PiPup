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
            4 to PopupProps.Position.Center
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
}
