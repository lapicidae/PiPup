package nl.rogro82.pipup

import android.content.Context
import android.graphics.BitmapFactory
import android.util.TypedValue
import java.net.Inet4Address
import java.net.NetworkInterface.getNetworkInterfaces
import java.net.SocketException

/**
 * Retrieves the first non-loopback IPv4 address of the device.
 */
fun getIpAddress(): String? {
    return try {
        getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: SocketException) {
        null
    }
}

/**
 * Converts density-independent pixels (dp) to device-specific pixels (px).
 */
fun Context.dpToPx(dp: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP,
    dp.toFloat(),
    resources.displayMetrics
).toInt()

/**
 * Scales pixel values relative to a 1080p reference resolution.
 */
fun Context.getScaledPixels(pixels: Int): Int {
    val displayMetrics = resources.displayMetrics
    val scaleFactor = displayMetrics.widthPixels.toFloat() / 1920f
    return (pixels * scaleFactor).toInt()
}

/**
 * Calculates the sample size for bitmap decoding based on target dimensions.
 */
fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
