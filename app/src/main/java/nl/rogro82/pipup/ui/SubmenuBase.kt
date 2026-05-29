package nl.rogro82.pipup.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R

/**
 * Base class for common submenu logic.
 */
@UnstableApi
abstract class SubmenuBase(
    protected val context: Context,
    protected val settings: AppSettings,
    protected val onSettingsChanged: (Boolean) -> Unit,
    protected val previewArea: FrameLayout
) : SubmenuController {

    protected val activeSeekBars = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val animationDebounceToken = "animation_debounce"

    override fun onBackPress(): Boolean {
        if (activeSeekBars.isNotEmpty()) {
            val barId = activeSeekBars.first()
            activeSeekBars.remove(barId)
            (previewArea.rootView.findViewById<SeekBar>(barId))?.let { updateSeekBarAppearance(it, false) }
            return true
        }
        return false
    }

    override fun updatePreviewPosition(v: View) {
        val location = IntArray(2)
        v.getLocationOnScreen(location)
        val screenHeight = context.resources.displayMetrics.heightPixels
        val shouldBeAtTop = location[1] > screenHeight * 0.4

        val popup = previewArea.getChildAt(0) ?: return
        val params = popup.layoutParams as FrameLayout.LayoutParams
        val newGravity = (if (shouldBeAtTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.END

        if (params.gravity != newGravity) {
            params.gravity = newGravity
            val margin = (context.resources.displayMetrics.density * 10).toInt()
            params.setMargins(0, margin, margin, margin)
            popup.layoutParams = params
        }
    }

    protected fun setupSeekBar(
        root: View,
        resId: Int,
        valueResId: Int,
        initialValue: Int,
        onChanged: (Int) -> Unit
    ) {
        val seekBar = root.findViewById<SeekBar>(resId) ?: return
        val textView = root.findViewById<TextView>(valueResId)

        seekBar.progress = initialValue
        updateSliderValueDisplay(seekBar, textView)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val isAdjusting = s?.let { activeSeekBars.contains(it.id) } ?: false
                if (fromUser || isAdjusting) {
                    updateSliderValueDisplay(seekBar, textView)
                    onChanged(progress)

                    if (s?.id == R.id.seekbar_animation_duration) {
                        onSettingsChanged(false)
                        handler.removeCallbacksAndMessages(animationDebounceToken)
                        handler.postAtTime({ onSettingsChanged(true) }, animationDebounceToken, android.os.SystemClock.uptimeMillis() + 800)
                    } else {
                        onSettingsChanged(false)
                    }
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {
                if (s?.id == R.id.seekbar_animation_duration) {
                    handler.removeCallbacksAndMessages(animationDebounceToken)
                }
            }
            override fun onStopTrackingTouch(s: SeekBar?) {
                if (s?.id == R.id.seekbar_animation_duration) {
                    handler.removeCallbacksAndMessages(animationDebounceToken)
                    onSettingsChanged(true)
                }
            }
        })

        val oldFocusListener = seekBar.onFocusChangeListener
        seekBar.setOnFocusChangeListener { v, hasFocus ->
            oldFocusListener?.onFocusChange(v, hasFocus)
            if (!hasFocus) {
                activeSeekBars.remove(seekBar.id)
                updateSeekBarAppearance(seekBar, false)
            }
            if (hasFocus) updatePreviewPosition(seekBar)
        }

        seekBar.setOnKeyListener { view, keyCode, event ->
            val bar = view as SeekBar
            val isActive = activeSeekBars.contains(bar.id)
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        val becomingActive = !isActive
                        if (becomingActive) activeSeekBars.add(bar.id) else activeSeekBars.remove(bar.id)
                        updateSeekBarAppearance(bar, becomingActive)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (isActive) { bar.progress -= 1; true } else {
                        (context as? SettingsActivity)?.focusRail()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (isActive) { bar.progress += 1; true } else true
                    else -> false
                }
            } else isActive
        }
        updateSeekBarAppearance(seekBar, false)

        textView?.visibility = if (settings.advancedMode) View.VISIBLE else View.GONE
    }

    protected fun updateSliderValueDisplay(bar: SeekBar, textView: TextView?) {
        val format = if (bar.id == R.id.seekbar_animation_duration) "%d ms" else context.getString(R.string.settings_slider_value_format, bar.progress, bar.max)
        textView?.text = if (bar.id == R.id.seekbar_animation_duration) String.format(format, bar.progress) else format
    }

    protected fun updateSeekBarAppearance(bar: SeekBar, active: Boolean) {
        val color = ContextCompat.getColor(context, if (active) R.color.colorPrimary else R.color.colorOnSurfaceVariant)
        val trackColor = ContextCompat.getColor(context, R.color.colorOutline)
        bar.thumbTintList = ColorStateList.valueOf(color)
        bar.progressTintList = ColorStateList.valueOf(color)
        bar.progressBackgroundTintList = ColorStateList.valueOf(trackColor)
    }

    protected fun setupSpinner(
        root: View,
        resId: Int,
        adapter: ArrayAdapter<*>,
        initialSelection: Int,
        onChanged: (Int) -> Unit
    ) {
        val spinner = root.findViewById<Spinner>(resId) ?: return
        spinner.adapter = adapter
        spinner.setSelection(initialSelection)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            private var initialCall = true
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (initialCall) { initialCall = false; return }
                onChanged(pos)
                onSettingsChanged(p?.id == R.id.spinner_animation_type)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val oldFocusListener = spinner.onFocusChangeListener
        spinner.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            oldFocusListener?.onFocusChange(v, hasFocus)
            if (hasFocus) updatePreviewPosition(v)
        }
    }
}
