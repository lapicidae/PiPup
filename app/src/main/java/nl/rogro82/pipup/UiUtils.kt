package nl.rogro82.pipup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt

/**
 * Displays a custom Toast with the PiPup icon.
 * Automatically falls back to native system toast if overlay permissions are missing
 * or if the app is in the background (where custom toast views are restricted by Android).
 */
@android.annotation.SuppressLint("InflateParams")
fun Context.showToast(message: String, isLong: Boolean = false) {
    val duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
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
                Log.e("UiUtils", "Invalid hex color entered: $h")
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
        val handled = event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP
        if (handled) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
        }
        handled
    }

    val buttonKeyListener = View.OnKeyListener { _, keyCode, event ->
        if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { input.requestFocus(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { input.requestFocus(); input.dispatchKeyEvent(event); true }
            else -> false
        }
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnKeyListener(buttonKeyListener)
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnKeyListener(buttonKeyListener)

    input.requestFocus()
}
