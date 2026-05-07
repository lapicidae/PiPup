package nl.rogro82.pipup

import android.content.Context
import android.util.TypedValue
import java.net.Inet4Address
import java.net.NetworkInterface.getNetworkInterfaces
import java.net.SocketException

/**
 * General utility functions for the PiPup application.
 */
object Utils {

    /**
     * Retrieves the first non-loopback IPv4 address of the device.
     *
     * @return The IPv4 address as a string, or null if none is found or a socket error occurs.
     */
    fun getIpAddress(): String? {
        try {
            val interfaces = getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (_: SocketException) {
            // Log or handle exception as needed
        }

        return null
    }

    /**
     * Converts density-independent pixels (dp) to device-specific pixels (px).
     *
     * This ensures that UI elements maintain consistent physical size across
     * different screen densities.
     *
     * @param context The current context.
     * @param dp The value in dp to convert.
     * @return The equivalent value in pixels (px).
     */
    fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * Scales pixel values relative to a 1080p reference resolution.
     *
     * This ensures that media elements take up the same proportional space
     * on screen regardless of whether the TV is 720p, 1080p, or 4K.
     *
     * @param context The current context.
     * @param pixels The pixel value defined for a 1080p display.
     * @return The scaled pixel value for the current screen resolution.
     */
    fun getScaledPixels(context: Context, pixels: Int): Int {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        // Use 1920 (Full HD) as the base reference for all scaling
        val scaleFactor = screenWidth.toFloat() / 1920f
        return (pixels * scaleFactor).toInt()
    }
}
