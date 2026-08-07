package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface.getNetworkInterfaces
import java.net.SocketException
import java.util.Locale

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

/**
 * Reads exactly [length] bytes from the given [InputStream].
 */
fun InputStream.readExactBytes(length: Int): ByteArray {
    val buffer = ByteArray(length)
    var totalRead = 0
    while (totalRead < length) {
        val read = read(buffer, totalRead, length - totalRead)
        if (read <= 0) break
        totalRead += read
    }
    return buffer
}

/**
 * Returns a context with the specified language and theme applied.
 * Essential for background services to respect app-level settings.
 */
fun Context.getLocalizedContext(langTag: String, appTheme: Int = -1): Context {
    val locale = if (langTag == "default") {
        Resources.getSystem().configuration.locales[0]
    } else {
        Locale.forLanguageTag(langTag)
    }

    val config = Configuration(resources.configuration)
    config.setLocale(locale)

    // Apply theme if specified (0: Dark, 1: Light)
    if (appTheme != -1) {
        val nightMode = if (appTheme == 0) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
    }

    return createConfigurationContext(config)
}

/**
 * Returns the hex string representation of a color resource.
 */
fun Context.colorToHex(colorRes: Int): String {
    val color = ContextCompat.getColor(this, colorRes)
    return String.format("#%06X", 0xFFFFFF and color)
}

/**
 * Displays a custom Toast with the PiPup icon.
 */
@SuppressLint("InflateParams")
fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    val mainHandler = Handler(Looper.getMainLooper())
    mainHandler.post {
        try {
            val inflater = LayoutInflater.from(this)
            val layout = inflater.inflate(R.layout.toast_custom, null)
            layout.findViewById<TextView>(R.id.toast_text).text = message

            val toast = Toast(applicationContext)
            toast.duration = duration
            @Suppress("DEPRECATION")
            toast.view = layout
            toast.show()
        } catch (_: Exception) {
            // Fallback to standard toast if custom view fails (e.g. background restrictions on newer Android)
            Toast.makeText(applicationContext, message, duration).show()
        }
    }
}
