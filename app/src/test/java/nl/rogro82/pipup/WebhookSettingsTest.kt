package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for webhook heartbeat normalization and [AppSettings.SettingsData] serialization.
 */
class WebhookSettingsTest {

    @Test
    fun normalizeWebhookHeartbeatHandlesZeroClampingAndNegatives() {
        assertEquals(0, AppSettings.normalizeWebhookHeartbeat(0))
        assertEquals(AppSettings.MIN_WEBHOOK_HEARTBEAT_SEC, AppSettings.normalizeWebhookHeartbeat(1))
        assertEquals(AppSettings.MIN_WEBHOOK_HEARTBEAT_SEC, AppSettings.normalizeWebhookHeartbeat(9))
        assertEquals(60, AppSettings.normalizeWebhookHeartbeat(60))
        assertEquals(AppSettings.MAX_WEBHOOK_HEARTBEAT_SEC, AppSettings.normalizeWebhookHeartbeat(100_000))
        assertNull(AppSettings.normalizeWebhookHeartbeat(-1))
    }

    @Test
    fun settingsDataRoundTripsWebhookHeartbeat() {
        val parsed = AppSettings.SettingsData.fromJson("""{"webhookHeartbeat": 60}""")
        assertEquals(60, parsed.webhookHeartbeat)
        assertEquals(60, parsed.toJSONObject().getInt("webhookHeartbeat"))

        val defaultParsed = AppSettings.SettingsData.fromJson("""{}""")
        assertEquals(0, defaultParsed.webhookHeartbeat)
    }
}
