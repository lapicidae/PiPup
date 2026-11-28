package nl.rogro82.pipup.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.ModuleMode
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
        val popup = previewArea.getChildAt(0) ?: return
        SettingsPreviewController.applyPosition(popup, v)
    }

    protected fun setupSeekBar(
        root: View,
        resId: Int,
        valueResId: Int,
        initialValue: Int,
        max: Int? = null,
        onChanged: (Int) -> Unit
    ) {
        val seekBar = root.findViewById<SeekBar>(resId) ?: return
        val textView = root.findViewById<TextView>(valueResId)

        if (max != null) {
            seekBar.max = max
        }
        seekBar.progress = initialValue
        updateSliderValueDisplay(seekBar, textView)

        attachSeekBarChangeListener(seekBar, textView, onChanged)
        attachSeekBarFocusAndKeyListeners(seekBar)
        updateSeekBarAppearance(seekBar, false)

        val isAlwaysVisible = seekBar.id in ALWAYS_VISIBLE_SEEKBAR_IDS
        textView?.visibility = if (settings.advancedMode || isAlwaysVisible) View.VISIBLE else View.GONE
    }

    private fun attachSeekBarChangeListener(seekBar: SeekBar, textView: TextView?, onChanged: (Int) -> Unit) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val isAdjusting = s?.let { activeSeekBars.contains(it.id) } ?: false
                if (!fromUser && !isAdjusting) return
                updateSliderValueDisplay(seekBar, textView)
                onChanged(progress)

                if (s?.id == R.id.seekbar_animation_duration) {
                    onSettingsChanged(false)
                    handler.removeCallbacksAndMessages(animationDebounceToken)
                    handler.postAtTime(
                        { onSettingsChanged(true) },
                        animationDebounceToken,
                        android.os.SystemClock.uptimeMillis() + ANIMATION_DEBOUNCE_MS,
                    )
                } else {
                    onSettingsChanged(false)
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
    }

    private fun attachSeekBarFocusAndKeyListeners(seekBar: SeekBar) {
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
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener isActive
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val becomingActive = !isActive
                    if (becomingActive) activeSeekBars.add(bar.id) else activeSeekBars.remove(bar.id)
                    updateSeekBarAppearance(bar, becomingActive)
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (isActive) {
                    bar.progress -= 1
                    true
                } else {
                    settingsActivity?.focusRail()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isActive) bar.progress += 1
                    true
                }
                else -> false
            }
        }
    }

    protected fun updateSliderValueDisplay(bar: SeekBar, textView: TextView?) {
        textView?.text = when (bar.id) {
            R.id.seekbar_animation_duration -> "${bar.progress} ms"
            R.id.seekbar_media_timeout -> context.resources.getQuantityString(
                R.plurals.settings_media_timeout_seconds, bar.progress, bar.progress,
            )
            R.id.seekbar_close_button_size -> "${bar.progress + PopupProps.CLOSE_BUTTON_SIZE_MIN} dp"
            R.id.seekbar_close_button_timeout -> formatCloseButtonTimeout(bar.progress)
            R.id.seekbar_media_retries -> context.resources.getQuantityString(
                R.plurals.settings_media_retries_count, bar.progress, bar.progress,
            )
            R.id.seekbar_max_popups -> "${bar.progress + 1}"
            R.id.seekbar_buffer_ms -> if (bar.progress == 0) {
                context.getString(R.string.language_default)
            } else {
                "${bar.progress * BUFFER_STEP_MS} ms"
            }
            R.id.seekbar_title_size,
            R.id.seekbar_message_size,
            R.id.seekbar_button_size -> "${bar.progress + PopupProps.TEXT_SIZE_UI_MIN} sp"
            R.id.seekbar_icon_width ->
                "${PopupProps.ICON_WIDTH_MIN + bar.progress * PopupProps.ICON_WIDTH_STEP_PX} px"
            else -> context.getString(R.string.settings_slider_value_format, bar.progress, bar.max)
        }
    }

    private fun formatCloseButtonTimeout(seconds: Int): String = if (seconds == 0) {
        context.getString(R.string.settings_close_button_off)
    } else {
        context.resources.getQuantityString(R.plurals.settings_media_timeout_seconds, seconds, seconds)
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
            override fun onNothingSelected(p: AdapterView<*>?) {
                // Spinners always keep a valid selection; nothing to reset.
            }
        }
        val oldFocusListener = spinner.onFocusChangeListener
        spinner.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            oldFocusListener?.onFocusChange(v, hasFocus)
            if (hasFocus) updatePreviewPosition(v)
        }
    }

    protected fun setSelectedColorInSpinner(
        root: View,
        spinnerId: Int,
        adapter: SettingsActivity.ColorSpinnerAdapter,
        hex: String,
    ) {
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

    protected fun setupHexColorButton(
        root: View,
        buttonId: Int,
        currentColor: String,
        onColorSet: (String) -> Unit
    ) {
        root.findViewById<Button>(buttonId)?.apply {
            text = currentColor
            visibility = if (settings.advancedMode) View.VISIBLE else View.GONE
            setOnClickListener { handleHexInput(this, currentColor) { onColorSet(it) } }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }
    }

    protected fun setupSwitch(
        root: View,
        containerId: Int,
        switchId: Int,
        initialChecked: Boolean,
        onChanged: (Boolean) -> Unit
    ) {
        root.findViewById<View>(containerId)?.apply {
            val sw = findViewById<SwitchCompat>(switchId) ?: return@apply
            sw.isChecked = initialChecked
            setOnClickListener { sw.toggle() }
            sw.setOnCheckedChangeListener { _, isChecked ->
                onChanged(isChecked)
                onSettingsChanged(false)
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }
    }

    /**
     * Adds an optional M3 section header and a tonal section card container to [parent]
     * and returns the card [LinearLayout] for populating controls.
     */
    protected fun addSectionCard(parent: ViewGroup, title: String? = null): LinearLayout {
        val inflater = LayoutInflater.from(context)
        if (!title.isNullOrBlank()) {
            val header = inflater.inflate(R.layout.item_settings_section_header, parent, false) as TextView
            header.text = title
            parent.addView(header)
        }
        return (inflater.inflate(R.layout.item_settings_section_card, parent, false) as LinearLayout)
            .also { parent.addView(it) }
    }

    /**
     * Renders settings from all active modules that belong to the specified category inside a section card.
     */
    protected fun renderModuleSettings(container: ViewGroup, category: SettingCategory) {
        val mm = (context.applicationContext as PiPupApp).moduleManager
        val entries = mm.getAllModules()
            .filter { module ->
                ModuleMode.OFF !in module.supportedModes || settings.getModuleMode(module.id) != ModuleMode.OFF
            }
            .flatMap { module ->
                module.getSettingsMetadata()
                    .filter { it.category == category }
                    .map { module to it }
            }
        if (entries.isEmpty()) return

        val card = addSectionCard(container, context.getString(R.string.settings_nav_modules))
        entries.forEach { (module, def) ->
            renderSetting(card, module, def)
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
        val rawLabel = context.getString(def.labelRes)
        val label = if (def.category != null) {
            context.getString(R.string.settings_module_setting_format, context.getString(module.nameRes), rawLabel)
        } else {
            rawLabel
        }
        view.findViewById<TextView>(R.id.setting_label)?.text = label

        def.descriptionRes?.let { descRes ->
            val descView = view.findViewById<TextView>(R.id.setting_desc)
            descView?.text = context.getString(descRes)
            descView?.visibility = View.VISIBLE
        }

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
        val rawLabel = context.getString(def.labelRes)
        val label = if (def.category != null) {
            context.getString(R.string.settings_module_setting_format, context.getString(module.nameRes), rawLabel)
        } else {
            rawLabel
        }
        view.findViewById<TextView>(R.id.setting_label)?.text = label

        def.descriptionRes?.let { descRes ->
            val descView = view.findViewById<TextView>(R.id.setting_desc)
            descView?.text = context.getString(descRes)
            descView?.visibility = View.VISIBLE
        }

        val valueText = view.findViewById<TextView>(R.id.setting_value)
        val options = def.options ?: return

        fun updateText(value: String) {
            val resId = options[value] ?: return
            valueText?.text = context.getString(resId)
        }

        if (def.key == "resource_mode") {
            // Internal mapping for the UI switch: On (ON) vs Eco (ECO)
            val current = settings.getModuleMode(module.id)
            updateText(if (current == ModuleMode.ON) "performance" else "eco")

            view.setOnClickListener {
                val currentMode = settings.getModuleMode(module.id)
                val next = if (currentMode == ModuleMode.ECO) ModuleMode.ON else ModuleMode.ECO
                settings.setModuleMode(module.id, next)
                updateText(if (next == ModuleMode.ON) "performance" else "eco")
                (context.applicationContext as PiPupApp).moduleManager.updateModuleState(module.id, next)
                notifySettingsChanged()
            }
        } else {
            val current = settings.getModuleSetting(module.id, def.key, def.defaultValue as String)
            updateText(current)

            view.setOnClickListener {
                // For now we only support simple string toggles if no options are complex
                // Select settings with many options usually need a dialog, but for now
                // we handle the case where it might be a simple toggle or the first of its kind.
                // NOTE: select settings for modules are currently only used for 'resource_mode'
            }
        }

        container.addView(view)
    }

    protected fun notifySettingsChanged() {
        val intent = Intent(PiPupApp.ACTION_SETTINGS_CHANGED).apply {
            setPackage(PiPupApp.APP_PACKAGE)
        }
        context.sendBroadcast(intent)
    }

    /** Shared slider step and debounce constants for [SubmenuBase]. */
    companion object {
        const val BUFFER_STEP_MS = 100
        private const val ANIMATION_DEBOUNCE_MS = 800L
        private val ALWAYS_VISIBLE_SEEKBAR_IDS = setOf(
            R.id.seekbar_media_timeout,
            R.id.seekbar_media_retries,
            R.id.seekbar_max_popups,
            R.id.seekbar_buffer_ms,
            R.id.seekbar_close_button_size,
            R.id.seekbar_close_button_timeout,
        )
    }
}
