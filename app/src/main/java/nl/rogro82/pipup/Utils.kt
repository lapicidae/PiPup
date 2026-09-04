package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.os.LocaleListCompat
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

    fun writeValueAsString(value: Any): String = try {
        mapper.writeValueAsString(value)
    } catch (_: Exception) {
        ""
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
 * Automatically falls back to native system toast if overlay permissions are missing
 * or if the app is in the background (where custom toast views are restricted by Android).
 */
@SuppressLint("InflateParams")
fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    val mainHandler = Handler(Looper.getMainLooper())
    mainHandler.post {
        // Fallback to standard system toast if:
        // 1. Overlay permission is missing (custom views require it)
        // 2. OR the app is in the background (Android 11+ blocks custom toast views in background)
        if (!Permissions.overlay(this) || !Permissions.activityVisible) {
            Toast.makeText(applicationContext, message, duration).show()
            return@post
        }

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
            // Final fallback to system toast
            Toast.makeText(applicationContext, message, duration).show()
        }
    }
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
 * Includes optimization to avoid redundant AppCompatDelegate calls that cause activity recreation.
 */
fun applyAppLocaleAndTheme(langTag: String, appTheme: Int) {
    // 1. Locale Optimization
    val currentLocales = AppCompatDelegate.getApplicationLocales()
    val desiredLocales: LocaleListCompat = if (langTag == "default") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(langTag)
    }

    if (currentLocales.toLanguageTags() != desiredLocales.toLanguageTags()) {
        Log.d("Utils", "Applying new locale: ${desiredLocales.toLanguageTags()}")
        AppCompatDelegate.setApplicationLocales(desiredLocales)
    }

    // 2. Theme Optimization
    val currentMode = AppCompatDelegate.getDefaultNightMode()
    val desiredMode = if (appTheme == 0) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO

    if (currentMode != desiredMode) {
        Log.d("Utils", "Applying new theme mode: $desiredMode")
        AppCompatDelegate.setDefaultNightMode(desiredMode)
    }
}

/**
 * Extension to find the nearest Activity from a Context.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Shared logic for showing a hex color input dialog with TV-optimized focus management.
 */
fun Context.showHexInputDialog(initialHex: String, onSet: (String) -> Unit) {
    val padding = dpToPx(12)
    val input = EditText(this).apply {
        setText(initialHex.replace("#", ""))
        isSingleLine = true
        background = ContextCompat.getDrawable(this@showHexInputDialog, R.drawable.field_background)
        setPadding(padding, padding, padding, padding)
        gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE
        inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        setTextColor(ContextCompat.getColor(this@showHexInputDialog, R.color.colorOnSurface))
        onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(v, 0)
            }
        }
    }

    val container = FrameLayout(this).apply {
        val margin = dpToPx(24)
        setPadding(margin, margin / 2, margin, 0)
        addView(input)
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle(R.string.settings_edit_hex_title).setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val h = "#${input.text.toString().uppercase()}"
            try {
                h.toColorInt()
                onSet(h)
            } catch (_: Exception) {
                Log.e("Utils", "Invalid hex color entered: $h")
            }
        }.setNegativeButton(android.R.string.cancel, null)
        .create()

    dialog.window?.apply {
        setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        attributes = attributes.apply { y = 100 }
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }
    dialog.show()

    val handler = Handler(Looper.getMainLooper())
    findActivity()?.window?.decorView?.let { decor ->
        var wasKeyboardVisible = true
        val posUpdater = Runnable {
            dialog.window?.let { win ->
                val p = win.attributes
                p.gravity = if (wasKeyboardVisible) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
                p.y = if (wasKeyboardVisible) 100 else 0
                win.attributes = p
            }
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            decor.getWindowVisibleDisplayFrame(r)
            val screenHeight = decor.rootView.height
            val keypadHeight = screenHeight - r.bottom
            val isKeyboardVisible = (keypadHeight > screenHeight * 0.15) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)

            if (isKeyboardVisible != wasKeyboardVisible) {
                wasKeyboardVisible = isKeyboardVisible
                handler.removeCallbacks(posUpdater)
                handler.postDelayed(posUpdater, if (isKeyboardVisible) 0 else 250)
            }
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
        dialog.setOnDismissListener {
            decor.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            handler.removeCallbacks(posUpdater)
        }
    }

    input.setOnKeyListener { _, keyCode, event ->
        if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
            true
        } else false
    }

    val buttonKeyListener = View.OnKeyListener { _, keyCode, event ->
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { input.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { input.requestFocus(); input.dispatchKeyEvent(event); true }
                else -> false
            }
        } else false
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnKeyListener(buttonKeyListener)
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnKeyListener(buttonKeyListener)

    input.requestFocus()
}
