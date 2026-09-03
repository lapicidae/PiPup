package nl.rogro82.pipup.ui

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Rect
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R
import nl.rogro82.pipup.getIpAddress
import nl.rogro82.pipup.showToast
import nl.rogro82.pipup.service.PipUpService
import nl.rogro82.pipup.core.modules.DiscoveryModule

@UnstableApi
class AdvancedSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        val appName = context.getString(R.string.app_name)

        // Network Import
        root.findViewById<Button>(R.id.btn_import_network)?.apply {
            text = context.getString(R.string.settings_import_network, appName)
            setOnClickListener { showImportDeviceDialog() }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }

        // Media Timeout
        setupSeekBar(root, R.id.seekbar_media_timeout, R.id.text_media_timeout_value, settings.mediaTimeout) {
            settings.mediaTimeout = it
        }

        // Media Retries
        setupSeekBar(root, R.id.seekbar_media_retries, R.id.text_media_retries_value, settings.mediaRetries) {
            settings.mediaRetries = it
        }

        // Pre-warm WebView Toggle
        root.findViewById<View>(R.id.container_pre_warm)?.apply {
            // Hide if media module is disabled
            visibility = if (settings.mediaModuleEnabled) View.VISIBLE else View.GONE

            val sw = findViewById<SwitchCompat>(R.id.switch_pre_warm)
            sw.isChecked = settings.preWarmWebView
            setOnClickListener { sw.toggle() }
            sw.setOnCheckedChangeListener { _, isChecked ->
                settings.preWarmWebView = isChecked
                onSettingsChanged(false)
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }

        // Advanced Mode Toggle
        root.findViewById<View>(R.id.container_advanced)?.apply {
            val sw = findViewById<SwitchCompat>(R.id.switch_advanced)
            sw.isChecked = settings.advancedMode
            setOnClickListener { sw.toggle() }
            sw.setOnCheckedChangeListener { _, isChecked ->
                settings.advancedMode = isChecked
                onSettingsChanged(false)
                // Refresh submenu to update visibility of slider values
                onBind(root)
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }

        // Reset Settings
        root.findViewById<Button>(R.id.btn_reset)?.apply {
            setOnClickListener { showResetConfirmation() }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }
    }

    private fun showResetConfirmation() {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_reset_confirm_title)
            .setMessage(R.string.settings_reset_confirm_msg)
            .setPositiveButton(R.string.settings_yes) { _, _ ->
                settings.resetToDefaults()
                val mode = if (settings.appTheme == 0) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                AppCompatDelegate.setDefaultNightMode(mode)
                settingsActivity?.recreate()
            }
            .setNegativeButton(R.string.settings_no, null)
            .create()

        dialog.show()
        // Pre-select "No" for safety
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun showImportDeviceDialog() {
        val service = PipUpService.getInstance()
        val discovery = service?.getModuleManager()?.getModule("discovery") as? DiscoveryModule

        if (discovery == null || !settings.discoveryModuleEnabled) {
            context.showToast(context.getString(R.string.error_module_disabled, context.getString(R.string.settings_module_discovery)))
            showImportIpDialog()
            return
        }

        val devices = mutableListOf<NsdServiceInfo>()
        val deviceNames = mutableListOf<String>()
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, deviceNames)
        val localId = discovery.getDeviceId()
        val myIp = getIpAddress()

        val deviceListener = object : DiscoveryModule.DeviceListener {
            override fun onDeviceFound(serviceInfo: NsdServiceInfo) {
                val address = serviceInfo.getHostAddress()

                // 1. Filter out self (by unique ID or IP address)
                val remoteId = serviceInfo.attributes["id"]?.let { String(it) }
                if (remoteId == localId || (address != null && address == myIp)) {
                    return
                }

                settingsActivity?.runOnUiThread {
                    // 2. Filter duplicates by service name to allow multiple mocks from one IP (stress test)
                    if (devices.none { it.serviceName == serviceInfo.serviceName }) {
                        devices.add(serviceInfo)
                        deviceNames.add("${serviceInfo.serviceName} (${address ?: "???"})")
                        if (deviceNames.getOrNull(0) == context.getString(R.string.settings_import_discover)) {
                            deviceNames.removeAt(0)
                        }
                        adapter.notifyDataSetChanged()
                    }
                }
            }

            override fun onDeviceLost(serviceInfo: NsdServiceInfo) {
                settingsActivity?.runOnUiThread {
                    val index = devices.indexOfFirst { it.serviceName == serviceInfo.serviceName }
                    if (index != -1) {
                        devices.removeAt(index)
                        deviceNames.removeAt(index)
                        if (deviceNames.isEmpty()) {
                            deviceNames.add(context.getString(R.string.settings_import_no_devices))
                        }
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        }

        val builder = AlertDialog.Builder(context)
        builder.setTitle(context.getString(R.string.settings_import_network, context.getString(R.string.app_name)))
        builder.setAdapter(adapter) { _: DialogInterface, which: Int ->
            discovery.stopDiscovery()
            val host = devices.getOrNull(which)?.getHostAddress()
            host?.let { performNetworkImport(it) }
        }
        builder.setNeutralButton(R.string.settings_import_manual) { d: DialogInterface, _: Int ->
            discovery.stopDiscovery()
            d.dismiss()
            showImportIpDialog()
        }
        builder.setNegativeButton(android.R.string.cancel) { d: DialogInterface, _: Int ->
            discovery.stopDiscovery()
            d.dismiss()
        }

        val dialog = builder.create()

        dialog.setOnShowListener {
            if (deviceNames.isEmpty()) {
                deviceNames.add(context.getString(R.string.settings_import_discover))
                adapter.notifyDataSetChanged()
            }
        }

        dialog.setOnDismissListener {
            discovery.stopDiscovery()
        }

        dialog.show()
        discovery.startDiscovery(deviceListener)
    }

    private fun showImportIpDialog() {
        val input = EditText(context).apply {
            hint = context.getString(R.string.settings_import_ip_hint)
            isSingleLine = true
            onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(v, 0)
                }
            }
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_import_ip_title).setView(input)
            .setPositiveButton(R.string.settings_import_action) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) performNetworkImport(ip)
            }.setNegativeButton(android.R.string.cancel, null)
            .create()

        // Initial state: TOP to avoid overlap and resizing
        dialog.window?.apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = 100 }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        dialog.show()

        // Reliable detection via Activity decor view
        context.findActivity()?.window?.decorView?.let { decor ->
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
                    // Immediate move to top, 250ms delay to move to center (debouncing)
                    handler.postDelayed(posUpdater, if (isKeyboardVisible) 0 else 250)
                }
            }
            decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
            dialog.setOnDismissListener {
                decor.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                handler.removeCallbacks(posUpdater)
            }
        }

        val buttonKeyListener = View.OnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                input.requestFocus()
                true
            } else false
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnKeyListener(buttonKeyListener)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnKeyListener(buttonKeyListener)

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
                    val data = settingsActivity?.mapper?.readValue(json, AppSettings.SettingsData::class.java)
                    settingsActivity?.runOnUiThread {
                        if (data != null) {
                            settings.apply(data)
                            // Notify system about settings change
                            val intent = Intent("nl.rogro82.pipup.SETTINGS_CHANGED").apply {
                                setPackage(context.packageName)
                                putExtra("origin", "remote")
                            }
                            context.sendBroadcast(intent)
                        }
                        settingsActivity?.recreate()
                        context.showToast(context.getString(R.string.settings_import_success))
                    }
                } else {
                    settingsActivity?.runOnUiThread {
                        context.showToast(context.getString(R.string.settings_import_error, "HTTP ${connection.responseCode}"), Toast.LENGTH_LONG)
                    }
                }
            } catch (e: Exception) {
                settingsActivity?.runOnUiThread {
                    context.showToast(context.getString(R.string.settings_import_error, e.message ?: "Unknown error"), Toast.LENGTH_LONG)
                }
            }
        }.start()
    }

    private fun NsdServiceInfo.getHostAddress(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            host?.hostAddress
        }
    }
}
