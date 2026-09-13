package nl.rogro82.pipup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.util.Log
import android.util.TypedValue
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.json.JSONObject
import org.json.JSONArray
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface.getNetworkInterfaces
import java.net.SocketException
import java.util.Locale

/**
 * Returns the hex string representation of a color resource.
 */
fun Context.colorToHex(colorRes: Int): String {
    val color = androidx.core.content.ContextCompat.getColor(this, colorRes)
    return String.format("#%06X", 0xFFFFFF and color)
}

/**
 * Singleton for shared JSON operations using native org.json.
 */
object Json {
    fun writeValueAsString(value: Any): String {
        return when (value) {
            is Map<*, *> -> JSONObject(value).toString()
            is List<*> -> JSONArray(value).toString()
            else -> ""
        }
    }
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
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
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
 * Caching removed to ensure zero retention in leak tests.
 */
fun Context.getLocalizedContext(langTag: String, appTheme: Int = -1): Context {
    val locale = if (langTag == "default") {
        Resources.getSystem().configuration.locales[0]
    } else {
        Locale.forLanguageTag(langTag)
    }

    val config = Configuration(resources.configuration)
    config.setLocale(locale)

    if (appTheme != -1) {
        val nightMode = if (appTheme == 0) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
    }

    return createConfigurationContext(config)
}

/**
 * Helper to register a broadcast receiver with the appropriate flags for Android 13+.
 */
fun Context.registerProtectedReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        registerReceiver(receiver, filter)
    }
}

/**
 * Centralized logic to apply application-wide locale and theme settings.
 */
fun applyAppLocaleAndTheme(langTag: String, appTheme: Int) {
    val currentLocales = AppCompatDelegate.getApplicationLocales()
    val desiredLocales: LocaleListCompat = if (langTag == "default") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(langTag)
    }

    if (currentLocales.toLanguageTags() != desiredLocales.toLanguageTags()) {
        Log.d("CoreUtils", "Applying new locale: ${desiredLocales.toLanguageTags()}")
        AppCompatDelegate.setApplicationLocales(desiredLocales)
    }

    val currentMode = AppCompatDelegate.getDefaultNightMode()
    val desiredMode = if (appTheme == 0) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO

    if (currentMode != desiredMode) {
        Log.d("CoreUtils", "Applying new theme mode: $desiredMode")
        AppCompatDelegate.setDefaultNightMode(desiredMode)
    }
}

/**
 * Triggers a manual garbage collection and finalization.
 */
fun triggerSystemGc() {
    System.gc()
    Runtime.getRuntime().gc()
    System.runFinalization()
}
