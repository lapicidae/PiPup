package nl.rogro82.pipup.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.ActivationStrategy
import nl.rogro82.pipup.core.ModuleSettingDefinition
import nl.rogro82.pipup.core.PiPupModule
import nl.rogro82.pipup.core.SettingCategory
import nl.rogro82.pipup.core.SettingType
import nl.rogro82.pipup.findActivity
import nl.rogro82.pipup.showHexInputDialog

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
    protected val handler = Handler(Looper.getMainLooper())
    private val animationDebounceToken = "animation_debounce"

    protected val settingsActivity: SettingsActivity?
        get() = context.findActivity() as? SettingsActivity

    override fun onBackPress(): Boolean {
        if (activeSeekBars.isNotEmpty()) {
            val barId = activeSeekBars.first()
            activeSeekBars.remove(barId)
            (previewArea.rootView.findViewById<SeekBar>(barId))?.let { updateSeekBarAppearance(it, false) }
            return true
        }
        return false
    }

    /**
     * Updates the position of the preview popup based on the focused view's location.
     * Ensures the preview doesn't cover important UI elements.
     *
     * @param v The currently focused view.
     */
    override fun updatePreviewPosition(v: View) {
        val location = IntArray(2)
        v.getLocationOnScreen(location)
        val screenHeight = context.resources.displayMetrics.heightPixels
        // Only move to top if the focused item is in the lower 40% of the screen
        val shouldBeAtTop = location[1] > screenHeight * 0.6

        val popup = previewArea.getChildAt(0) ?: return
        val params = popup.layoutParams as? FrameLayout.LayoutParams ?: return
        val newGravity = (if (shouldBeAtTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.END

        if (params.gravity != newGravity) {
            params.gravity = newGravity
            val density = context.resources.displayMetrics.density
            val marginSide = (density * 10).toInt()
            // More margin at top to avoid covering the menu titles/values
            val marginTop = (density * 40).toInt()
            val marginBottom = (density * 10).toInt()

            if (shouldBeAtTop) {
                params.setMargins(0, marginTop, marginSide, 0)
            } else {
                params.setMargins(0, 0, marginSide, marginBottom)
            }
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
                        settingsActivity?.focusRail()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (isActive) { bar.progress += 1; true } else true
                    else -> false
                }
            } else isActive
        }
        updateSeekBarAppearance(seekBar, false)

        val isAlwaysVisible = seekBar.id == R.id.seekbar_media_timeout || seekBar.id == R.id.seekbar_media_retries
        textView?.visibility = if (settings.advancedMode || isAlwaysVisible) View.VISIBLE else View.GONE
    }

    protected fun updateSliderValueDisplay(bar: SeekBar, textView: TextView?) {
        val format = when (bar.id) {
            R.id.seekbar_animation_duration -> "%d ms"
            R.id.seekbar_media_timeout -> context.resources.getQuantityString(R.plurals.settings_media_timeout_seconds, bar.progress, bar.progress)
            R.id.seekbar_media_retries -> context.resources.getQuantityString(R.plurals.settings_media_retries_count, bar.progress, bar.progress)
            else -> context.getString(R.string.settings_slider_value_format, bar.progress, bar.max)
        }
        textView?.text = when (bar.id) {
            R.id.seekbar_animation_duration -> String.format(format, bar.progress)
            else -> format
        }
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

        val safeSelection = initialSelection.coerceIn(0, (adapter.count - 1).coerceAtLeast(0))
        spinner.setSelection(safeSelection)

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

    protected fun setSelectedColorInSpinner(root: View, spinnerId: Int, adapter: SettingsActivity.ColorSpinnerAdapter, hex: String) {
        val clean = hex.replace("#", "").let { if (it.length == 8) it.substring(2) else it }
        val idx = adapter.colors.indexOfFirst { it.hex.equals("#$clean", true) }
        if (idx != -1) root.findViewById<Spinner>(spinnerId)?.setSelection(idx)
    }

    protected fun handleHexInput(btn: Button, initialHex: String, onSet: (String) -> Unit) {
        context.showHexInputDialog(initialHex) { newHex ->
            btn.text = newHex
            onSet(newHex)
            onSettingsChanged(false)
        }
    }

    /**
     * Renders settings from all active modules that belong to the specified category.
     */
    protected fun renderModuleSettings(container: ViewGroup, category: SettingCategory) {
        val mm = (context.applicationContext as PiPupApp).moduleManager
        val modules = mm.getAllModules()

        modules.forEach { module ->
            // Only show settings for logically active modules (Eco or Performance)
            if (module.id != "system" && settings.getActivationStrategy(module.id) == ActivationStrategy.OFF) {
                return@forEach
            }

            val metadata = module.getSettingsMetadata().filter { it.category == category }
            metadata.forEach { def ->
                renderSetting(container, module, def)
            }
        }
    }

    protected fun renderSetting(container: ViewGroup, module: PiPupModule, def: ModuleSettingDefinition) {
        when (def.type) {
            SettingType.BOOLEAN -> renderBooleanSetting(container, module, def)
            SettingType.STRING_SELECT -> renderSelectSetting(container, module, def)
            else -> {}
        }
    }

    protected fun renderBooleanSetting(container: ViewGroup, module: PiPupModule, def: ModuleSettingDefinition) {
        val defaultValue = def.defaultValue as? Boolean ?: return

        val view = LayoutInflater.from(context).inflate(R.layout.item_setting_toggle, container, false)
        val label = context.getString(def.labelRes)
        view.findViewById<TextView>(R.id.setting_label)?.text = context.getString(R.string.settings_module_setting_format, module.name, label)
        val switch = view.findViewById<SwitchCompat>(R.id.setting_switch)

        val current = settings.getModuleSetting(module.id, def.key, defaultValue)
        switch?.isChecked = current

        view.setOnClickListener {
            val next = !settings.getModuleSetting(module.id, def.key, defaultValue)
            settings.setModuleSetting(module.id, def.key, next)
            switch?.isChecked = next
            onSettingsChanged(false)
        }

        container.addView(view)
    }

    protected fun renderSelectSetting(container: ViewGroup, module: PiPupModule, def: ModuleSettingDefinition) {
        val view = LayoutInflater.from(context).inflate(R.layout.item_setting_select, container, false)
        val label = context.getString(def.labelRes)
        view.findViewById<TextView>(R.id.setting_label)?.text = context.getString(R.string.settings_module_setting_format, module.name, label)

        val valueText = view.findViewById<TextView>(R.id.setting_value)
        val options = def.options ?: return

        fun updateText(value: String) {
            val resId = options[value] ?: return
            valueText?.text = context.getString(resId)
        }

        if (def.key == "resource_mode") {
            // Special handling for legacy ActivationStrategy
            val current = settings.getActivationStrategy(module.id)
            updateText(if (current == ActivationStrategy.PERFORMANCE) "performance" else "eco")

            view.setOnClickListener {
                val currentStrategy = settings.getActivationStrategy(module.id)
                val next = if (currentStrategy == ActivationStrategy.ECO) ActivationStrategy.PERFORMANCE else ActivationStrategy.ECO
                settings.setActivationStrategy(module.id, next)
                updateText(if (next == ActivationStrategy.PERFORMANCE) "performance" else "eco")
                (context.applicationContext as PiPupApp).moduleManager.updateModuleState(module.id, next)
                notifySettingsChanged()
            }
        } else {
            val current = settings.getModuleSetting(module.id, def.key, def.defaultValue as String)
            updateText(current)
            // Generic STRING_SELECT click handler not yet implemented
        }

        container.addView(view)
    }

    protected fun notifySettingsChanged() {
        val intent = Intent(PiPupApp.ACTION_SETTINGS_CHANGED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}
