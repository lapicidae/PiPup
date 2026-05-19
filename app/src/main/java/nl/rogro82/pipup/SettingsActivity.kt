package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.rogro82.pipup.databinding.ActivitySettingsBinding

/**
 * Modernized SettingsActivity with Auto-Save and Smart Preview.
 */
@SuppressLint("UseSwitchCompatOrMaterialCode")
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var appSettings: AppSettings

    // Submenu View References
    private var spinnerAppTheme: Spinner? = null
    private var spinnerBgColor: Spinner? = null
    private var btnEditBgHex: Button? = null
    private var seekBgAlpha: SeekBar? = null
    private var textBgAlphaValue: TextView? = null
    private var spinnerTitleColor: Spinner? = null
    private var btnEditTitleHex: Button? = null
    private var seekTitleSize: SeekBar? = null
    private var textTitleSizeValue: TextView? = null
    private var spinnerMessageColor: Spinner? = null
    private var btnEditMessageHex: Button? = null
    private var seekMessageSize: SeekBar? = null
    private var textMessageSizeValue: TextView? = null
    private var seekRadius: SeekBar? = null
    private var textRadiusValue: TextView? = null
    private var seekBorderWidth: SeekBar? = null
    private var textBorderWidthValue: TextView? = null
    private var spinnerBorderColor: Spinner? = null
    private var btnEditBorderHex: Button? = null
    private var spinnerPosition: Spinner? = null
    private var spinnerMediaPosition: Spinner? = null
    private var spinnerAnimationType: Spinner? = null
    private var seekAnimationDuration: SeekBar? = null
    private var textAnimationDurationValue: TextView? = null
    private var spinnerTitleAlignment: Spinner? = null
    private var spinnerMessageAlignment: Spinner? = null
    private var seekPadding: SeekBar? = null
    private var textPaddingValue: TextView? = null
    private var switchAdvanced: SwitchCompat? = null
    private var btnImportNetwork: Button? = null
    private var containerEnergyStatus: View? = null
    private var textEnergyStatus: TextView? = null
    private var viewEnergyIndicator: View? = null
    private var btnReset: Button? = null

    private var currentAdvancedMode = false
    private var currentLayoutRes: Int = -1
    private var currentNavId: Int = -1
    
    // Tracks which SeekBars are currently in "Adjust Mode"
    private val activeSeekBars = mutableSetOf<Int>()
    
    private val mapper = jacksonObjectMapper()

    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveRunnable = Runnable { appSettings.save() }

    companion object { 
        private const val TAG = "PiPupSettings"
    }

    private val materialColors = listOf(
        ColorEntry(R.string.color_black, "#0F1417"),
        ColorEntry(R.string.color_white, "#DFE3E7"),
        ColorEntry(R.string.color_grey, "#C0C7CD"),
        ColorEntry(R.string.color_blue, "#8ECFF2"),
        ColorEntry(R.string.color_teal, "#625B71"),
        ColorEntry(R.string.color_pink, "#7D5260"),
        ColorEntry(R.string.color_red, "#B3261E"),
        ColorEntry(R.string.color_light_blue, "#D0BCFF"),
        ColorEntry(R.string.color_green, "#388E3C"),
        ColorEntry(R.string.color_orange, "#F57C00")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.SettingsTheme)
        super.onCreate(savedInstanceState)
        try {
            appSettings = AppSettings(this)
            binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            setupNavRail()
            
            // Default to General
            loadSubmenu(R.layout.submenu_general, R.id.nav_item_general)
            findViewById<View>(R.id.nav_item_general).requestFocus()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Settings", e)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateEnergyStatusDisplay()
    }

    private fun setupNavRail() {
        configureNavItem(R.id.nav_item_back, R.string.settings_back, R.drawable.ic_back, -1)
        configureNavItem(R.id.nav_item_general, R.string.settings_nav_general, R.drawable.ic_general_style, R.layout.submenu_general)
        configureNavItem(R.id.nav_item_background, R.string.settings_nav_background, R.drawable.ic_bg, R.layout.submenu_background)
        configureNavItem(R.id.nav_item_text_style, R.string.settings_nav_text, R.drawable.ic_text_style, R.layout.submenu_text)
        configureNavItem(R.id.nav_item_border, R.string.settings_nav_border, R.drawable.ic_border_style, R.layout.submenu_border)
        configureNavItem(R.id.nav_item_advanced, R.string.settings_nav_advanced, R.drawable.ic_advanced, R.layout.submenu_advanced)

        // Vertical focus chain in rail
        val railIds = listOf(R.id.nav_item_back, R.id.nav_item_general, R.id.nav_item_background, R.id.nav_item_text_style, R.id.nav_item_border, R.id.nav_item_advanced)
        for (i in railIds.indices) {
            findViewById<View>(railIds[i])?.apply {
                nextFocusUpId = if (i > 0) railIds[i - 1] else id
                nextFocusDownId = if (i < railIds.size - 1) railIds[i + 1] else id
                nextFocusLeftId = id 
                nextFocusRightId = R.id.settings_scroll
            }
        }
    }

    private fun configureNavItem(navId: Int, textRes: Int, iconRes: Int, layoutRes: Int) {
        val root = findViewById<View>(navId) ?: return
        val label = root.findViewById<TextView>(R.id.nav_text)
        val icon = root.findViewById<ImageView>(R.id.nav_icon)
        
        label?.setText(textRes)
        icon?.setImageResource(iconRes)
        
        fun updateItemAppearance() {
            val hasFocus = root.isFocused
            val isSelected = currentLayoutRes == layoutRes && navId != R.id.nav_item_back
            
            val color = when {
                hasFocus -> ContextCompat.getColor(this, R.color.colorOnPrimary)
                isSelected -> ContextCompat.getColor(this, R.color.colorOnPrimaryContainer)
                else -> ContextCompat.getColor(this, R.color.colorOnSurfaceVariant)
            }
            
            label?.setTextColor(color)
            icon?.imageTintList = ColorStateList.valueOf(color)
            root.isSelected = isSelected
        }

        // Initialize appearance
        updateItemAppearance()
        
        if (navId == R.id.nav_item_back) {
            root.setOnClickListener { finish() }
            root.setOnFocusChangeListener { _, _ -> updateItemAppearance() }
            return
        }

        root.setOnClickListener { focusFirstInSubmenu() }

        root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && currentLayoutRes != layoutRes) {
                loadSubmenu(layoutRes, navId)
            }
            updateItemAppearance()
        }
    }

    private fun loadSubmenu(layoutRes: Int, navId: Int) {
        currentLayoutRes = layoutRes
        currentNavId = navId
        activeSeekBars.clear()
        binding.submenuContainer.removeAllViews()
        LayoutInflater.from(this).inflate(layoutRes, binding.submenuContainer, true)
        
        // Refresh all nav items to update isSelected state
        val railIds = listOf(R.id.nav_item_back, R.id.nav_item_general, R.id.nav_item_background, R.id.nav_item_text_style, R.id.nav_item_border, R.id.nav_item_advanced)
        railIds.forEach { id ->
            val v: View? = findViewById(id)
            v?.onFocusChangeListener?.onFocusChange(v, v.isFocused)
        }
        
        initSubmenuViews()
        setupInputs()
        setupListeners()
        
        // Setup focus constraints for Submenu
        val container = binding.submenuContainer.getChildAt(0) as? ViewGroup
        container?.let {
            val focusableChildren = mutableListOf<View>()
            for (i in 0 until it.childCount) {
                val child = it.getChildAt(i)
                if (child.isFocusable && child.isVisible) focusableChildren.add(child)
            }
            
            if (focusableChildren.isNotEmpty()) {
                val navItem: View? = findViewById(navId)
                navItem?.nextFocusRightId = focusableChildren[0].id

                for (i in focusableChildren.indices) {
                    val child = focusableChildren[i]
                    child.nextFocusLeftId = navId
                    // Prevent wrapping to rail on Up/Down
                    child.nextFocusUpId = if (i > 0) focusableChildren[i - 1].id else child.id
                    child.nextFocusDownId = if (i < focusableChildren.size - 1) focusableChildren[i + 1].id else child.id
                    
                    // Special case: if we are at the first element, ensure header is visible on focus
                    if (i == 0) {
                        val oldListener = child.onFocusChangeListener
                        child.setOnFocusChangeListener { v, hasFocus ->
                            if (hasFocus) {
                                (findViewById<View>(R.id.settings_scroll) as? android.widget.ScrollView)?.smoothScrollTo(0, 0)
                            }
                            oldListener?.onFocusChange(v, hasFocus)
                            updatePreviewPosition(hasFocus, v)
                        }
                    }
                }
            }
        }
        
        // Ensure we scroll to the very top to see the header
        findViewById<View>(R.id.settings_scroll)?.post {
            findViewById<View>(R.id.settings_scroll)?.scrollTo(0, 0)
        }
        
        updatePreview()
    }

    private fun initSubmenuViews() {
        spinnerAppTheme = findViewById(R.id.spinner_app_theme)
        spinnerBgColor = findViewById(R.id.spinner_bg_color)
        btnEditBgHex = findViewById(R.id.btn_edit_bg_hex)
        seekBgAlpha = findViewById(R.id.seekbar_bg_alpha)
        textBgAlphaValue = findViewById(R.id.text_bg_alpha_value)
        spinnerTitleColor = findViewById(R.id.spinner_title_color)
        btnEditTitleHex = findViewById(R.id.btn_edit_title_hex)
        seekTitleSize = findViewById(R.id.seekbar_title_size)
        textTitleSizeValue = findViewById(R.id.text_title_size_value)
        spinnerMessageColor = findViewById(R.id.spinner_message_color)
        btnEditMessageHex = findViewById(R.id.btn_edit_message_hex)
        seekMessageSize = findViewById(R.id.seekbar_message_size)
        textMessageSizeValue = findViewById(R.id.text_message_size_value)
        seekRadius = findViewById(R.id.seekbar_radius)
        textRadiusValue = findViewById(R.id.text_radius_value)
        seekBorderWidth = findViewById(R.id.seekbar_border_width)
        textBorderWidthValue = findViewById(R.id.text_border_width_value)
        spinnerBorderColor = findViewById(R.id.spinner_border_color)
        btnEditBorderHex = findViewById(R.id.btn_edit_border_hex)
        spinnerPosition = findViewById(R.id.spinner_position)
        spinnerMediaPosition = findViewById(R.id.spinner_media_position)
        spinnerAnimationType = findViewById(R.id.spinner_animation_type)
        seekAnimationDuration = findViewById(R.id.seekbar_animation_duration)
        textAnimationDurationValue = findViewById(R.id.text_animation_duration_value)
        spinnerTitleAlignment = findViewById(R.id.spinner_title_alignment)
        spinnerMessageAlignment = findViewById(R.id.spinner_message_alignment)
        seekPadding = findViewById(R.id.seekbar_padding)
        textPaddingValue = findViewById(R.id.text_padding_value)
        switchAdvanced = findViewById(R.id.switch_advanced)
        btnImportNetwork = findViewById(R.id.btn_import_network)
        containerEnergyStatus = findViewById(R.id.container_energy_status)
        textEnergyStatus = findViewById(R.id.text_energy_status)
        viewEnergyIndicator = findViewById(R.id.view_energy_indicator)
        btnReset = findViewById(R.id.btn_reset)
    }

    private fun setupInputs() {
        spinnerPosition?.let {
            val suffix = getString(R.string.settings_default_suffix)
            val items = PopupProps.Position.entries.map { p ->
                val name = when (p) {
                    PopupProps.Position.TopRight -> getString(R.string.settings_pos_top_right)
                    PopupProps.Position.TopLeft -> getString(R.string.settings_pos_top_left)
                    PopupProps.Position.BottomRight -> getString(R.string.settings_pos_bottom_right)
                    PopupProps.Position.BottomLeft -> getString(R.string.settings_pos_bottom_left)
                    PopupProps.Position.Center -> getString(R.string.settings_pos_center)
                }
                if (p.index == 0) "$name$suffix" else name
            }
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            it.setSelection(appSettings.positionIndex)
        }

        spinnerMediaPosition?.let {
            val suffix = getString(R.string.settings_default_suffix)
            val items = listOf(
                "${getString(R.string.settings_media_pos_top)}$suffix",
                getString(R.string.settings_media_pos_bottom),
                getString(R.string.settings_media_pos_left),
                getString(R.string.settings_media_pos_right)
            )
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            it.setSelection(appSettings.mediaPosition)
        }

        spinnerAnimationType?.let {
            val suffix = getString(R.string.settings_default_suffix)
            val items = listOf(
                "${getString(R.string.settings_animation_none)}$suffix",
                getString(R.string.settings_animation_fade),
                getString(R.string.settings_animation_slide),
                getString(R.string.settings_animation_slide_bounce),
                getString(R.string.settings_animation_scale),
                getString(R.string.settings_animation_scale_bounce),
                getString(R.string.settings_animation_scale_tada),
                getString(R.string.settings_animation_slide_zoom),
                getString(R.string.settings_animation_slide_flip),
                getString(R.string.settings_animation_slide_tada),
                getString(R.string.settings_animation_diagonal_zoom)
            )
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            it.setSelection(appSettings.animationType)
        }

        seekAnimationDuration?.progress = appSettings.animationDuration
        
        spinnerAppTheme?.let {
            val items = listOf(getString(R.string.settings_theme_dark), getString(R.string.settings_theme_light))
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            it.setSelection(appSettings.appTheme)
        }

        val suffix = getString(R.string.settings_default_suffix)
        val alignmentItems = listOf("${getString(R.string.settings_alignment_left)}$suffix", getString(R.string.settings_alignment_center), getString(R.string.settings_alignment_right))
        val alignmentAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, alignmentItems).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerTitleAlignment?.apply { adapter = alignmentAdapter; setSelection(appSettings.titleAlignment) }
        spinnerMessageAlignment?.apply { adapter = alignmentAdapter; setSelection(appSettings.messageAlignment) }

        seekPadding?.progress = appSettings.contentPadding
        currentAdvancedMode = appSettings.advancedMode
        switchAdvanced?.isChecked = currentAdvancedMode
        toggleAdvancedVisibility(currentAdvancedMode)

        spinnerBgColor?.let { it.adapter = ColorSpinnerAdapter(this, materialColors, AppSettings.DEFAULT_BG_COLOR); setSelectedColorInSpinner(it, appSettings.backgroundColor) }
        btnEditBgHex?.text = appSettings.backgroundColor
        seekBgAlpha?.progress = appSettings.backgroundAlpha

        spinnerTitleColor?.let { it.adapter = ColorSpinnerAdapter(this, materialColors, AppSettings.DEFAULT_TITLE_COLOR); setSelectedColorInSpinner(it, appSettings.titleColor) }
        btnEditTitleHex?.text = appSettings.titleColor
        seekTitleSize?.progress = appSettings.titleSize.toInt()
        spinnerMessageColor?.let { it.adapter = ColorSpinnerAdapter(this, materialColors, AppSettings.DEFAULT_MSG_COLOR); setSelectedColorInSpinner(it, appSettings.messageColor) }
        btnEditMessageHex?.text = appSettings.messageColor
        seekMessageSize?.progress = appSettings.messageSize.toInt()

        seekRadius?.progress = appSettings.borderRadius
        seekBorderWidth?.progress = appSettings.borderWidth
        spinnerBorderColor?.let { it.adapter = ColorSpinnerAdapter(this, materialColors, AppSettings.DEFAULT_BORDER_COLOR); setSelectedColorInSpinner(it, appSettings.borderColor) }
        btnEditBorderHex?.text = appSettings.borderColor

        updateEnergyStatusDisplay()
        
        // Initial values for sliders - MUST be called after all .progress = ...
        updateSliderValues()
    }

    private fun updateEnergyStatusDisplay() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Energy status check - isIgnoring: $isIgnoring")
        }
        
        textEnergyStatus?.setText(if (isIgnoring) R.string.energy_status_unrestricted else R.string.energy_status_optimized)
        viewEnergyIndicator?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isIgnoring) R.color.status_green else R.color.status_red)
        )
    }

    private fun scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 300) // 300ms debounce
    }

    private fun setupListeners() {
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { 
                // Allow updates if it's a real user touch OR our custom D-Pad adjust mode
                val isAdjusting = s?.let { activeSeekBars.contains(it.id) } ?: false
                if (!fromUser && !isAdjusting) return
                
                // Update numeric value display in real-time if advanced mode is on
                if (currentAdvancedMode) {
                    s?.let { updateSliderValueDisplay(it) }
                }

                val oldDuration = appSettings.animationDuration

                // Sync to memory and update preview instantly
                saveCurrentToSettings() 
                
                val durationChanged = s?.id == R.id.seekbar_animation_duration && oldDuration != appSettings.animationDuration
                updatePreview(animate = durationChanged) 
                
                // Schedule asynchronous disk persistence
                scheduleSave()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }

        listOfNotNull(seekBgAlpha, seekTitleSize, seekMessageSize, seekRadius, seekBorderWidth, seekPadding, seekAnimationDuration).forEach { sb ->
            sb.setOnSeekBarChangeListener(seekListener)
            updateSeekBarAppearance(sb, false)
            sb.setOnFocusChangeListener { _, hasFocus -> 
                if (!hasFocus) { activeSeekBars.remove(sb.id); updateSeekBarAppearance(sb, false) }
                updatePreviewPosition(hasFocus, sb)
            }
            sb.setOnKeyListener { view, keyCode, event ->
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
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (isActive) { bar.progress -= 1; true } else { findViewById<View>(currentNavId)?.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (isActive) { bar.progress += 1; true } else true
                        else -> false
                    }
                } else isActive // Consume events when active to prevent system default seek
            }
        }

        findViewById<View>(R.id.container_advanced)?.setOnClickListener { switchAdvanced?.toggle() }
        switchAdvanced?.setOnCheckedChangeListener { _, isChecked -> 
            appSettings.advancedMode = isChecked
            currentAdvancedMode = isChecked
            toggleAdvancedVisibility(isChecked) 
        }

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (p?.id == R.id.spinner_app_theme) {
                    if (appSettings.appTheme != pos) {
                        appSettings.appTheme = pos
                        applyTheme(pos)
                    }
                    return
                }
                
                val oldType = appSettings.animationType
                
                when (p?.id) {
                    R.id.spinner_bg_color -> btnEditBgHex?.text = materialColors[pos].hex
                    R.id.spinner_title_color -> btnEditTitleHex?.text = materialColors[pos].hex
                    R.id.spinner_message_color -> btnEditMessageHex?.text = materialColors[pos].hex
                    R.id.spinner_border_color -> btnEditBorderHex?.text = materialColors[pos].hex
                }
                saveCurrentToSettings()
                
                val typeChanged = p?.id == R.id.spinner_animation_type && oldType != appSettings.animationType
                updatePreview(animate = typeChanged)
                scheduleSave()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        listOfNotNull(spinnerBgColor, spinnerTitleColor, spinnerMessageColor, spinnerBorderColor, spinnerPosition, spinnerMediaPosition, spinnerAnimationType, spinnerTitleAlignment, spinnerMessageAlignment, spinnerAppTheme).forEach {
            it.onItemSelectedListener = spinnerListener
            val oldListener = it.onFocusChangeListener
            it.setOnFocusChangeListener { v, hasFocus -> 
                oldListener?.onFocusChange(v, hasFocus)
                updatePreviewPosition(hasFocus, it) 
            }
        }
        
        val hexClick = View.OnClickListener { showHexInputDialog(it as Button) }
        listOfNotNull(btnEditBgHex, btnEditTitleHex, btnEditMessageHex, btnEditBorderHex).forEach { it.setOnClickListener(hexClick) }

        btnImportNetwork?.setOnClickListener { showImportIpDialog() }
        btnImportNetwork?.setOnFocusChangeListener { v, hasFocus -> updatePreviewPosition(hasFocus, v) }
        
        containerEnergyStatus?.setOnClickListener { openEnergySettings() }
        containerEnergyStatus?.setOnFocusChangeListener { v, hasFocus -> updatePreviewPosition(hasFocus, v) }
        
        findViewById<View>(R.id.container_advanced)?.setOnFocusChangeListener { v, hasFocus -> updatePreviewPosition(hasFocus, v) }
        
        btnReset?.setOnClickListener { showResetConfirmation() }
        btnReset?.setOnFocusChangeListener { v, hasFocus -> updatePreviewPosition(hasFocus, v) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && activeSeekBars.isNotEmpty()) {
            val barId = activeSeekBars.first()
            activeSeekBars.remove(barId)
            findViewById<SeekBar>(barId)?.let { updateSeekBarAppearance(it, false) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun updatePreviewPosition(hasFocus: Boolean, view: View) {
        if (!hasFocus) return
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val screenHeight = resources.displayMetrics.heightPixels
        val shouldBeAtTop = location[1] > screenHeight * 0.4
        
        val params = binding.previewArea.layoutParams as ConstraintLayout.LayoutParams
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        params.verticalBias = if (shouldBeAtTop) 0.02f else 0.98f
        binding.previewArea.layoutParams = params
    }

    private fun openEnergySettings() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            // Already unrestricted - no need to show the instructions/dialog
            Toast.makeText(this, R.string.energy_status_unrestricted, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.energy_optimization_title)
            .setMessage(getString(R.string.energy_optimization_message) + "\n\n" + getString(R.string.energy_optimization_instructions))
            .setPositiveButton(R.string.settings_yes) { _, _ ->
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open settings", e)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateSeekBarAppearance(bar: SeekBar, active: Boolean) {
        val color = ContextCompat.getColor(this, if (active) R.color.colorPrimary else R.color.colorOnSurfaceVariant)
        val trackColor = ContextCompat.getColor(this, R.color.colorOutline)
        bar.thumbTintList = ColorStateList.valueOf(color)
        bar.progressTintList = ColorStateList.valueOf(color)
        bar.progressBackgroundTintList = ColorStateList.valueOf(trackColor)
    }

    private fun toggleAdvancedVisibility(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        listOfNotNull(btnEditBgHex, btnEditTitleHex, btnEditMessageHex, btnEditBorderHex).forEach { it.visibility = v }
        listOfNotNull(textBgAlphaValue, textTitleSizeValue, textMessageSizeValue, textRadiusValue, textBorderWidthValue, textPaddingValue, textAnimationDurationValue).forEach { it.visibility = v }
        if (show) updateSliderValues()
    }

    private fun updateSliderValues() {
        listOfNotNull(seekBgAlpha, seekTitleSize, seekMessageSize, seekRadius, seekBorderWidth, seekPadding, seekAnimationDuration).forEach { 
            updateSliderValueDisplay(it) 
        }
    }

    private fun updateSliderValueDisplay(bar: SeekBar) {
        val targetText = when (bar.id) {
            R.id.seekbar_bg_alpha -> textBgAlphaValue
            R.id.seekbar_title_size -> textTitleSizeValue
            R.id.seekbar_message_size -> textMessageSizeValue
            R.id.seekbar_radius -> textRadiusValue
            R.id.seekbar_border_width -> textBorderWidthValue
            R.id.seekbar_padding -> textPaddingValue
            R.id.seekbar_animation_duration -> textAnimationDurationValue
            else -> null
        }
        val format = if (bar.id == R.id.seekbar_animation_duration) "%d ms" else getString(R.string.settings_slider_value_format, bar.progress, bar.max)
        targetText?.text = if (bar.id == R.id.seekbar_animation_duration) String.format(format, bar.progress) else format
    }

    private fun showHexInputDialog(btn: Button) {
        val input = EditText(this).apply { setText(btn.text.toString().replace("#", "")); isSingleLine = true }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_edit_hex_title).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val h = "#${input.text.toString().uppercase()}"
                try { 
                    h.toColorInt()
                    btn.text = h
                    saveCurrentToSettings()
                    updatePreview() 
                    scheduleSave()
                } catch (_: Exception) {}
            }.setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.apply {
            setGravity(Gravity.TOP)
            attributes = attributes.apply { y = 100 } // Slight offset from top
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
        input.requestFocus()
    }

    private fun applyTheme(themeIndex: Int) {
        val mode = if (themeIndex == 0) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        appSettings.save() // Save before recreating
        AppCompatDelegate.setDefaultNightMode(mode)
        recreate()
    }

    private fun saveCurrentToSettings() {
        btnEditBgHex?.let { appSettings.backgroundColor = it.text.toString() }
        seekBgAlpha?.let { appSettings.backgroundAlpha = it.progress }
        btnEditTitleHex?.let { appSettings.titleColor = it.text.toString() }
        seekTitleSize?.let { appSettings.titleSize = it.progress.toFloat() }
        btnEditMessageHex?.let { appSettings.messageColor = it.text.toString() }
        seekMessageSize?.let { appSettings.messageSize = it.progress.toFloat() }
        btnEditBorderHex?.let { appSettings.borderColor = it.text.toString() }
        seekRadius?.let { appSettings.borderRadius = it.progress }
        seekBorderWidth?.let { appSettings.borderWidth = it.progress }
        spinnerPosition?.let { appSettings.positionIndex = it.selectedItemPosition }
        spinnerMediaPosition?.let { appSettings.mediaPosition = it.selectedItemPosition }
        spinnerAnimationType?.let { appSettings.animationType = it.selectedItemPosition }
        seekAnimationDuration?.let { appSettings.animationDuration = it.progress }
        spinnerTitleAlignment?.let { appSettings.titleAlignment = it.selectedItemPosition }
        spinnerMessageAlignment?.let { appSettings.messageAlignment = it.selectedItemPosition }
        seekPadding?.let { appSettings.contentPadding = it.progress }
    }

    @OptIn(UnstableApi::class)
    private fun updatePreview(animate: Boolean = false) {
        try {
            binding.previewArea.removeAllViews()
            val placeholder = createBitmap(320, 180).applyCanvas {
                drawColor(ContextCompat.getColor(this@SettingsActivity, R.color.preview_placeholder_bg))
                val paint = Paint().apply { 
                    color = ContextCompat.getColor(this@SettingsActivity, R.color.preview_placeholder_text)
                    textSize = 40f
                    textAlign = Paint.Align.CENTER 
                }
                drawText(getString(R.string.settings_preview_media), 160f, 100f, paint)
            }
            val tempProps = PopupProps(
                backgroundColor = appSettings.getFullBackgroundColor(), titleSize = appSettings.titleSize,
                titleColor = appSettings.titleColor, messageSize = appSettings.messageSize,
                messageColor = appSettings.messageColor, borderRadius = appSettings.borderRadius,
                borderWidth = appSettings.borderWidth, borderColor = appSettings.borderColor,
                contentPadding = appSettings.contentPadding, titleAlignment = appSettings.titleAlignment,
                messageAlignment = appSettings.messageAlignment, title = getString(R.string.settings_preview_title),
                message = getString(R.string.settings_preview_message), position = appSettings.positionIndex,
                mediaPosition = appSettings.mediaPosition, media = PopupProps.Media.Bitmap(placeholder, 180),
                animationType = appSettings.animationType, animationDuration = appSettings.animationDuration
            )
            val popup = PopupView.build(this, tempProps)
            binding.previewArea.addView(popup, FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, Utils.dpToPx(this@SettingsActivity, 10), Utils.dpToPx(this@SettingsActivity, 10))
            })
            
            if (animate && tempProps.animationType != 0) {
                popup.post {
                    popup.animateIn()
                }
            }
        } catch (_: Exception) {}
    }

    private fun setSelectedColorInSpinner(s: Spinner, hex: String) {
        val clean = hex.replace("#", "").let { if (it.length == 8) it.substring(2) else it }
        val idx = materialColors.indexOfFirst { it.hex.equals("#$clean", true) }
        if (idx != -1) s.setSelection(idx)
    }

    private fun showResetConfirmation() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_reset_confirm_title).setMessage(R.string.settings_reset_confirm_msg)
            .setPositiveButton(R.string.settings_yes) { _, _ -> 
                appSettings.resetToDefaults()
                val mode = if (appSettings.appTheme == 0) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                AppCompatDelegate.setDefaultNightMode(mode)
                recreate()
            }
            .setNegativeButton(R.string.settings_no, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun focusFirstInSubmenu() {
        val container = binding.submenuContainer.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.isFocusable && v.isVisible) { v.requestFocus(); return }
        }
    }

    private fun showImportIpDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.settings_import_ip_hint)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_import_ip_title).setView(input)
            .setPositiveButton(R.string.settings_import_action) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) performNetworkImport(ip)
            }.setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.apply {
            setGravity(Gravity.TOP)
            attributes = attributes.apply { y = 100 } // Slight offset from top
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
        input.requestFocus()
    }

    private fun performNetworkImport(ip: String) {
        val urlString = if (ip.startsWith("http")) "$ip:7979/settings" else "http://$ip:7979/settings"
        Thread {
            try {
                val url = java.net.URL(urlString); val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000; connection.readTimeout = 5000
                if (connection.responseCode == 200) {
                    val json = connection.inputStream.bufferedReader().use { it.readText() }
                    val data = mapper.readValue(json, AppSettings.SettingsData::class.java)
                    runOnUiThread { 
                        appSettings.apply(data)
                        
                        loadSubmenu(currentLayoutRes, currentNavId)
                        Toast.makeText(this, R.string.settings_import_success, Toast.LENGTH_SHORT).show() 
                    }
                } else {
                    runOnUiThread { Toast.makeText(this, getString(R.string.settings_import_error, "HTTP ${connection.responseCode}"), Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network import failed", e)
                runOnUiThread { Toast.makeText(this, getString(R.string.settings_import_error, e.message), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    data class ColorEntry(val nameRes: Int, val hex: String)
    private class ColorSpinnerAdapter(context: Context, val colors: List<ColorEntry>, val defaultHex: String) : ArrayAdapter<ColorEntry>(context, 0, colors) {
        override fun getView(p: Int, v: View?, g: ViewGroup): View = create(p, v, g)
        override fun getDropDownView(p: Int, v: View?, g: ViewGroup): View = create(p, v, g)
        private fun create(p: Int, v: View?, g: ViewGroup): View {
            val res = v ?: LayoutInflater.from(context).inflate(R.layout.item_color_spinner, g, false)
            val entry = colors[p]
            val preview = res.findViewById<View>(R.id.color_preview)
            preview.background.setTint(entry.hex.toColorInt())
            
            val label = res.findViewById<TextView>(R.id.color_name)
            val name = context.getString(entry.nameRes)
            
            val isActuallyDefault = entry.hex.equals(defaultHex, true)

            label.text = if (isActuallyDefault) "$name${context.getString(R.string.settings_default_suffix)}" else name
            label.setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))

            return res
        }
    }
}
