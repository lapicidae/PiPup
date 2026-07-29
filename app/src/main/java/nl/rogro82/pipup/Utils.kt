package nl.rogro82.pipup

import android.content.Context
import android.os.Build
import android.util.TypedValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.Inet4Address
import java.net.NetworkInterface.getNetworkInterfaces
import java.net.SocketException

/**
 * Singleton for shared JSON operations.
 * ObjectMapper is thread-safe and heavy to initialize, so we share one instance.
 */
object Json {
    val mapper = jacksonObjectMapper()
}

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
 * Detects if the app is running on an Android Emulator.
 */
fun isEmulator(): Boolean {
    return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
            || Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for x86")
            || Build.MANUFACTURER.contains("Genymotion")
            || Build.PRODUCT.contains("sdk_google")
            || Build.PRODUCT.contains("google_sdk")
            || Build.PRODUCT.contains("sdk")
            || Build.PRODUCT.contains("sdk_x86")
            || Build.PRODUCT.contains("vbox86p")
            || Build.PRODUCT.contains("emulator")
            || Build.PRODUCT.contains("simulator")
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
