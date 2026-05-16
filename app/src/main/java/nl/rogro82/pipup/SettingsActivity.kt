package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Activity for configuring global notification defaults with a live preview.
 * 
 * Implements a strict "Click to Adjust" slider logic for TV OSD.
 */
@SuppressLint("UseSwitchCompatOrMaterialCode")
class SettingsActivity : Activity() {

    private lateinit var appSettings: AppSettings
    private lateinit var previewArea: FrameLayout
    private lateinit var submenuContainer: LinearLayout
    
    // Submenu View References
    private var spinnerBgColor: Spinner? = null
    private var btnEditBgHex: Button? = null
    private var seekBgAlpha: SeekBar? = null
    private var spinnerTitleColor: Spinner? = null
    private var btnEditTitleHex: Button? = null
    private var seekTitleSize: SeekBar? = null
    private var spinnerMessageColor: Spinner? = null
    private var btnEditMessageHex: Button? = null
    private var seekMessageSize: SeekBar? = null
    private var seekRadius: SeekBar? = null
    private var seekBorderWidth: SeekBar? = null
    private var spinnerBorderColor: Spinner? = null
    private var btnEditBorderHex: Button? = null
    private var spinnerPosition: Spinner? = null
    private var spinnerMediaPosition: Spinner? = null
    private var spinnerTitleAlignment: Spinner? = null
    private var spinnerMessageAlignment: Spinner? = null
    private var seekPadding: SeekBar? = null
    private var switchAdvanced: SwitchCompat? = null
    private var btnImportNetwork: Button? = null

    private var currentAdvancedMode = false
    private var currentLayoutRes: Int = -1
    private var currentNavId: Int = -1
    
    // Tracks which SeekBars are currently in "Adjust Mode"
    private val activeSeekBars = mutableSetOf<Int>()
    
    private val mapper = jacksonObjectMapper()

    companion object { 
        private const val TAG = "PiPupSettings"
    }

    /**
     * Material You (Material 3) inspired color palette.
     * Tonal palettes based on key colors.
     */
    private val materialColors = listOf(
        ColorEntry(R.string.color_black, "#1C1B1F"),
        ColorEntry(R.string.color_white, "#FFFBFE"),
        ColorEntry(R.string.color_grey, "#F4EFF4"),
        ColorEntry(R.string.color_blue, "#6750A4"),
        ColorEntry(R.string.color_teal, "#625B71"),
        ColorEntry(R.string.color_pink, "#7D5260"),
        ColorEntry(R.string.color_red, "#B3261E"),
        ColorEntry(R.string.color_light_blue, "#D0BCFF"),
        ColorEntry(R.string.color_green, "#388E3C"),
        ColorEntry(R.string.color_orange, "#F57C00")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_settings)

            appSettings = AppSettings(this)
            previewArea = findViewById(R.id.preview_area)
            submenuContainer = findViewById(R.id.submenu_container)
            
            // Explicitly link navigation rail to content area
            val settingsScroll = findViewById<View>(R.id.settings_scroll)
            val railIds = listOf(R.id.nav_item_general, R.id.nav_item_background, R.id.nav_item_text_style, R.id.nav_item_border)
            railIds.forEach { id ->
                findViewById<View>(id)?.nextFocusRightId = settingsScroll?.id ?: View.NO_ID
            }
            
            setupNavRail()
            
            // Default to General
            loadSubmenu(R.layout.submenu_general, R.id.nav_item_general)
            findViewById<View>(R.id.nav_item_general).requestFocus()

            findViewById<Button>(R.id.btn_save).setOnClickListener { 
                saveCurrentToSettings()
                finish() 
            }
            findViewById<Button>(R.id.btn_reset).setOnClickListener { 
                showResetConfirmation() 
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Settings", e)
            finish()
        }
    }

    private fun setupNavRail() {
        configureNavItem(R.id.nav_item_general, R.string.settings_nav_general, R.drawable.ic_general_style, R.layout.submenu_general)
        configureNavItem(R.id.nav_item_background, R.string.settings_nav_background, R.drawable.ic_bg, R.layout.submenu_background)
        configureNavItem(R.id.nav_item_text_style, R.string.settings_nav_text, R.drawable.ic_text_style, R.layout.submenu_text)
        configureNavItem(R.id.nav_item_border, R.string.settings_nav_border, R.drawable.ic_border_style, R.layout.submenu_border)

        // Force a strict vertical focus chain within the navigation rail to prevent "hooks" or jumps
        val railIds = listOf(R.id.nav_item_general, R.id.nav_item_background, R.id.nav_item_text_style, R.id.nav_item_border, R.id.btn_reset, R.id.btn_save)
        for (i in railIds.indices) {
            findViewById<View>(railIds[i])?.apply {
                nextFocusUpId = if (i > 0) railIds[i - 1] else id
                nextFocusDownId = if (i < railIds.size - 1) railIds[i + 1] else id
                // Ensure moving down/up doesn't accidentally jump into the content area
                nextFocusLeftId = id 
            }
        }
    }

    private fun configureNavItem(navId: Int, textRes: Int, iconRes: Int, layoutRes: Int) {
        val root = findViewById<View>(navId) ?: return
        val label = root.findViewById<TextView>(R.id.nav_text)
        val icon = root.findViewById<ImageView>(R.id.nav_icon)
        
        label?.setText(textRes)
        icon?.setImageResource(iconRes)
        
        root.setOnClickListener { focusFirstInSubmenu() }

        root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && currentLayoutRes != layoutRes) {
                loadSubmenu(layoutRes, navId)
            }
            val color = if (hasFocus) "#202124".toColorInt() else "#E8EAED".toColorInt()
            label?.setTextColor(color)
            icon?.imageTintList = ColorStateList.valueOf(color)
        }
    }

    private fun loadSubmenu(layoutRes: Int, navId: Int) {
        currentLayoutRes = layoutRes
        currentNavId = navId
        activeSeekBars.clear()
        submenuContainer.removeAllViews()
        LayoutInflater.from(this).inflate(layoutRes, submenuContainer, true)
        
        initSubmenuViews()
        setupInputs()
        setupListeners()
        
        pinNavigationLeft(navId)
        mapNavigationRight(navId)
        lockSubmenuVerticalFocus()
        
        updatePreview()
    }

    private fun lockSubmenuVerticalFocus() {
        val container = submenuContainer.getChildAt(0) as? ViewGroup ?: return
        val focusableViews = mutableListOf<View>()
        
        fun collectFocusableViews(view: View) {
            // Only consider views that can actually take focus and are visible
            if (view.isFocusable && view.isVisible && view.id != View.NO_ID) {
                focusableViews.add(view)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    collectFocusableViews(view.getChildAt(i))
                }
            }
        }
        
        collectFocusableViews(container)
        
        if (focusableViews.isNotEmpty()) {
            for (i in focusableViews.indices) {
                val current = focusableViews[i]
                
                // Link Down: Next item in list or lock if last
                current.nextFocusDownId = if (i < focusableViews.size - 1) focusableViews[i + 1].id else current.id
                
                // Link Up: Previous item in list or lock if first
                current.nextFocusUpId = if (i > 0) focusableViews[i - 1].id else current.id
                
                // Ensure horizontal movement stays within the submenu item if needed
                // (though for our simple list, it mostly helps against jumping to the rail)
                current.nextFocusRightId = current.id
            }
        }
    }

    private fun pinNavigationLeft(navId: Int) {
        val container = submenuContainer.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.isFocusable) child.nextFocusLeftId = navId
            if (child is ViewGroup) {
                for (j in 0 until child.childCount) {
                    val subChild = child.getChildAt(j)
                    if (subChild.isFocusable) subChild.nextFocusLeftId = navId
                }
            }
        }
    }

    private fun mapNavigationRight(navId: Int) {
        val navItem = findViewById<View>(navId) ?: return
        val container = submenuContainer.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.isFocusable && v.isVisible) {
                navItem.nextFocusRightId = v.id
                break
            }
        }
    }

    private fun initSubmenuViews() {
        spinnerBgColor = findViewById(R.id.spinner_bg_color)
        btnEditBgHex = findViewById(R.id.btn_edit_bg_hex)
        seekBgAlpha = findViewById(R.id.seekbar_bg_alpha)
        spinnerTitleColor = findViewById(R.id.spinner_title_color)
        btnEditTitleHex = findViewById(R.id.btn_edit_title_hex)
        seekTitleSize = findViewById(R.id.seekbar_title_size)
        spinnerMessageColor = findViewById(R.id.spinner_message_color)
        btnEditMessageHex = findViewById(R.id.btn_edit_message_hex)
        seekMessageSize = findViewById(R.id.seekbar_message_size)
        seekRadius = findViewById(R.id.seekbar_radius)
        seekBorderWidth = findViewById(R.id.seekbar_border_width)
        spinnerBorderColor = findViewById(R.id.spinner_border_color)
        btnEditBorderHex = findViewById(R.id.btn_edit_border_hex)
        spinnerPosition = findViewById(R.id.spinner_position)
        spinnerMediaPosition = findViewById(R.id.spinner_media_position)
        spinnerTitleAlignment = findViewById(R.id.spinner_title_alignment)
        spinnerMessageAlignment = findViewById(R.id.spinner_message_alignment)
        seekPadding = findViewById(R.id.seekbar_padding)
        switchAdvanced = findViewById(R.id.switch_advanced)
        btnImportNetwork = findViewById(R.id.btn_import_network)
    }

    private fun setupInputs() {
        val colorAdapter = ColorSpinnerAdapter(this, materialColors)
        val textColorAdapter = ColorSpinnerAdapter(this, materialColors.map { it.copy(isDefault = it.nameRes == R.string.color_white) })
        
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

        val suffix = getString(R.string.settings_default_suffix)
        val alignmentItems = listOf(
            "${getString(R.string.settings_alignment_left)}$suffix",
            getString(R.string.settings_alignment_center),
            getString(R.string.settings_alignment_right)
        )
        val alignmentAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, alignmentItems).apply { 
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) 
        }

        spinnerTitleAlignment?.let {
            it.adapter = alignmentAdapter
            it.setSelection(appSettings.titleAlignment)
        }

        spinnerMessageAlignment?.let {
            it.adapter = alignmentAdapter
            it.setSelection(appSettings.messageAlignment)
        }

        seekPadding?.progress = appSettings.contentPadding
        switchAdvanced?.isChecked = currentAdvancedMode
        toggleAdvancedVisibility(currentAdvancedMode)

        spinnerBgColor?.let { it.adapter = colorAdapter; setSelectedColorInSpinner(it, appSettings.backgroundColor) }
        btnEditBgHex?.text = appSettings.backgroundColor
        seekBgAlpha?.progress = appSettings.backgroundAlpha

        spinnerTitleColor?.let { it.adapter = textColorAdapter; setSelectedColorInSpinner(it, appSettings.titleColor) }
        btnEditTitleHex?.text = appSettings.titleColor
        seekTitleSize?.progress = appSettings.titleSize.toInt()
        spinnerMessageColor?.let { it.adapter = textColorAdapter; setSelectedColorInSpinner(it, appSettings.messageColor) }
        btnEditMessageHex?.text = appSettings.messageColor
        seekMessageSize?.progress = appSettings.messageSize.toInt()

        seekRadius?.progress = appSettings.borderRadius
        seekBorderWidth?.progress = appSettings.borderWidth
        spinnerBorderColor?.let { it.adapter = colorAdapter; setSelectedColorInSpinner(it, appSettings.borderColor) }
        btnEditBorderHex?.text = appSettings.borderColor
    }

    private fun setupListeners() {
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { 
                saveCurrentToSettings()
                updatePreview() 
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }

        listOfNotNull(seekBgAlpha, seekTitleSize, seekMessageSize, seekRadius, seekBorderWidth, seekPadding).forEach { sb ->
            sb.setOnSeekBarChangeListener(seekListener)
            
            sb.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    activeSeekBars.remove(sb.id)
                    updateSeekBarAppearance(sb, false)
                }
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
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (isActive) {
                                bar.progress -= 1
                                true
                            } else {
                                if (currentNavId != -1) findViewById<View>(currentNavId)?.requestFocus()
                                true 
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (isActive) {
                                bar.progress += 1
                                true
                            } else {
                                true 
                            }
                        }
                        else -> false
                    }
                } else false
            }
        }

        findViewById<View>(R.id.container_advanced)?.setOnClickListener { switchAdvanced?.toggle() }
        switchAdvanced?.setOnCheckedChangeListener { _, isChecked -> 
            currentAdvancedMode = isChecked
            toggleAdvancedVisibility(isChecked) 
        }

        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val hex = materialColors[pos].hex
                when (p?.id) {
                    R.id.spinner_bg_color -> btnEditBgHex?.text = hex
                    R.id.spinner_title_color -> btnEditTitleHex?.text = hex
                    R.id.spinner_message_color -> btnEditMessageHex?.text = hex
                    R.id.spinner_border_color -> btnEditBorderHex?.text = hex
                }
                saveCurrentToSettings()
                updatePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spinnerBgColor?.onItemSelectedListener = spinnerListener
        spinnerTitleColor?.onItemSelectedListener = spinnerListener
        spinnerMessageColor?.onItemSelectedListener = spinnerListener
        spinnerBorderColor?.onItemSelectedListener = spinnerListener
        
        val alignmentListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                saveCurrentToSettings()
                updatePreview()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        spinnerTitleAlignment?.onItemSelectedListener = alignmentListener
        spinnerMessageAlignment?.onItemSelectedListener = alignmentListener
        
        spinnerPosition?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { 
                saveCurrentToSettings()
                updatePreview() 
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spinnerMediaPosition?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                saveCurrentToSettings()
                updatePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        
        val hexClick = View.OnClickListener { showHexInputDialog(it as Button) }
        btnEditBgHex?.setOnClickListener(hexClick)
        btnEditTitleHex?.setOnClickListener(hexClick)
        btnEditMessageHex?.setOnClickListener(hexClick)
        btnEditBorderHex?.setOnClickListener(hexClick)

        btnImportNetwork?.setOnClickListener { showImportIpDialog() }
    }

    private fun updateSeekBarAppearance(bar: SeekBar, active: Boolean) {
        val color = if (active) Color.WHITE else "#BDBDBD".toColorInt()
        bar.thumbTintList = ColorStateList.valueOf(color)
        bar.progressTintList = ColorStateList.valueOf(color)
    }

    private fun toggleAdvancedVisibility(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        btnEditBgHex?.visibility = v
        btnEditTitleHex?.visibility = v
        btnEditMessageHex?.visibility = v
        btnEditBorderHex?.visibility = v
        
        // Re-lock focus as visibility changes might change the "last" item
        lockSubmenuVerticalFocus()
    }

    private fun showHexInputDialog(btn: Button) {
        val input = EditText(this).apply { 
            setText(btn.text.toString().replace("#", ""))
            isSingleLine = true
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.settings_edit_hex_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val h = "#${input.text.toString().uppercase()}"
                try { 
                    h.toColorInt()
                    btn.text = h
                    saveCurrentToSettings()
                    updatePreview() 
                } catch (_: Exception) {}
            }.setNegativeButton(android.R.string.cancel, null).show()
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
        spinnerTitleAlignment?.let { appSettings.titleAlignment = it.selectedItemPosition }
        spinnerMessageAlignment?.let { appSettings.messageAlignment = it.selectedItemPosition }
        seekPadding?.let { appSettings.contentPadding = it.progress }
    }

    @OptIn(UnstableApi::class)
    private fun updatePreview() {
        try {
            previewArea.removeAllViews()
            
            // Create a small placeholder bitmap for the media preview using KTX applyCanvas
            val placeholder = createBitmap(320, 180).applyCanvas {
                drawColor(Color.DKGRAY)
                val paint = Paint().apply {
                    color = Color.LTGRAY
                    textSize = 40f
                    textAlign = Paint.Align.CENTER
                }
                drawText(getString(R.string.settings_preview_media), 160f, 100f, paint)
            }

            val tempProps = PopupProps(
                backgroundColor = appSettings.getFullBackgroundColor(),
                titleSize = appSettings.titleSize,
                titleColor = appSettings.titleColor,
                messageSize = appSettings.messageSize,
                messageColor = appSettings.messageColor,
                borderRadius = appSettings.borderRadius,
                borderWidth = appSettings.borderWidth,
                borderColor = appSettings.borderColor,
                contentPadding = appSettings.contentPadding,
                titleAlignment = appSettings.titleAlignment,
                messageAlignment = appSettings.messageAlignment,
                title = getString(R.string.settings_preview_title),
                message = getString(R.string.settings_preview_message),
                position = appSettings.positionIndex,
                mediaPosition = appSettings.mediaPosition,
                media = PopupProps.Media.Bitmap(placeholder, 180)
            )
            val v = PopupView.build(this, tempProps)
            
            val marginPx = Utils.dpToPx(this, 10)

            previewArea.addView(v, FrameLayout.LayoutParams(-2, -2).apply { 
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                setMargins(0, 0, marginPx, marginPx)
            })
        } catch (_: Exception) {}
    }

    private fun setSelectedColorInSpinner(s: Spinner, hex: String) {
        val clean = hex.replace("#", "").let { if (it.length == 8) it.substring(2) else it }
        val idx = materialColors.indexOfFirst { it.hex.equals("#$clean", true) }
        if (idx != -1) s.setSelection(idx)
    }

    private fun showResetConfirmation() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.settings_reset_confirm_title)
            .setMessage(R.string.settings_reset_confirm_msg)
            .setPositiveButton(R.string.settings_yes) { _, _ -> 
                appSettings.resetToDefaults()
                loadSubmenu(currentLayoutRes, currentNavId)
            }
            .setNegativeButton(R.string.settings_no, null).show()
    }

    private fun focusFirstInSubmenu() {
        val container = submenuContainer.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until container.childCount) {
            val v = container.getChildAt(i)
            if (v.isFocusable && v.isVisible) {
                v.requestFocus()
                return
            }
        }
    }

    private fun showImportIpDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.settings_import_ip_hint)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.settings_import_ip_title)
            .setView(input)
            .setPositiveButton(R.string.settings_import_action) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    performNetworkImport(ip)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performNetworkImport(ip: String) {
        val urlString = if (ip.startsWith("http")) "$ip:7979/settings" else "http://$ip:7979/settings"
        
        Thread {
            try {
                val url = java.net.URL(urlString)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                if (connection.responseCode == 200) {
                    val json = connection.inputStream.bufferedReader().use { it.readText() }
                    val data = mapper.readValue(json, AppSettings.SettingsData::class.java)
                    
                    runOnUiThread {
                        appSettings.apply(data)
                        loadSubmenu(currentLayoutRes, currentNavId)
                        Toast.makeText(this, R.string.settings_import_success, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.settings_import_error, "HTTP ${connection.responseCode}"), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network import failed", e)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.settings_import_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    data class ColorEntry(val nameRes: Int, val hex: String, var isDefault: Boolean = false)
    private class ColorSpinnerAdapter(context: Context, val colors: List<ColorEntry>) : ArrayAdapter<ColorEntry>(context, 0, colors) {
        override fun getView(p: Int, v: View?, g: ViewGroup): View = create(p, v, g)
        override fun getDropDownView(p: Int, v: View?, g: ViewGroup): View = create(p, v, g)
        private fun create(p: Int, v: View?, g: ViewGroup): View {
            val res = v ?: LayoutInflater.from(context).inflate(R.layout.item_color_spinner, g, false)
            val entry = colors[p]
            res.findViewById<View>(R.id.color_preview).background.setTint(entry.hex.toColorInt())
            val name = context.getString(entry.nameRes)
            val label = if (entry.isDefault) "$name${context.getString(R.string.settings_default_suffix)}" else name
            res.findViewById<TextView>(R.id.color_name).text = label
            return res
        }
    }
}
