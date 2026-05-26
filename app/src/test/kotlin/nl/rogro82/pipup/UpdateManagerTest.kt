package nl.rogro82.pipup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun testCompareVersions() {
        fun compare(v1: String, v2: String): Int {
            val parts1 = v1.split("-")
            val parts2 = v2.split("-")

            val main1 = parts1[0].split(".").mapNotNull { it.toIntOrNull() }
            val main2 = parts2[0].split(".").mapNotNull { it.toIntOrNull() }

            val length = maxOf(main1.size, main2.size)
            for (i in 0 until length) {
                val n1 = main1.getOrElse(i) { 0 }
                val n2 = main2.getOrElse(i) { 0 }
                if (n1 != n2) return n1.compareTo(n2)
            }

            val suffix1 = parts1.getOrNull(1)
            val suffix2 = parts2.getOrNull(1)

            return when {
                suffix1 == null && suffix2 == null -> 0
                suffix1 == null -> 1
                suffix2 == null -> -1
                else -> suffix1.compareTo(suffix2)
            }
        }

        // Standard versions
        assertTrue(compare("1.2.4", "1.2.3") > 0)
        assertTrue(compare("1.3.0", "1.2.9") > 0)
        assertTrue(compare("2.0.0", "1.9.9") > 0)
        assertEquals(0, compare("1.1.1", "1.1.1"))

        // Stable vs Beta
        assertTrue(compare("1.2.3", "1.2.3-beta1") > 0)
        assertTrue(compare("1.2.3-beta1", "1.2.3") < 0)

        // Beta vs Beta
        assertTrue(compare("1.2.3-beta2", "1.2.3-beta1") > 0)

        // Different lengths
        assertTrue(compare("1.2", "1.1.9") > 0)
        assertTrue(compare("1.2.0", "1.2") == 0)

        // New PiPup format (vMajor.Minor.Patch-Timestamp)
        assertTrue(compare("0.2.5-20260524.2243", "0.2.5-20260524.1437") > 0)
        assertTrue(compare("0.2.6-20260525.0000", "0.2.5-20260524.2243") > 0)
        assertTrue(compare("0.2.5", "0.2.5-20260524.2243") > 0) // Stable 0.2.5 is newer than timestamped 0.2.5
    }
}
