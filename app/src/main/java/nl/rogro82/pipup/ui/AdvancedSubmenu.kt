package nl.rogro82.pipup.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R

@UnstableApi
class AdvancedSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        // Energy Status
        updateEnergyStatusDisplay(root)
        root.findViewById<View>(R.id.container_energy_status)?.apply {
            setOnClickListener { openEnergySettings() }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }

        // Network Import
        root.findViewById<Button>(R.id.btn_import_network)?.apply {
            setOnClickListener { showImportIpDialog() }
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

        // Version Info
        root.findViewById<TextView>(R.id.text_app_version)?.apply {
            val versionName = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "v?.?.?" }
            text = context.getString(R.string.settings_app_version_label, versionName)
        }
    }

    private fun updateEnergyStatusDisplay(root: View) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        root.findViewById<TextView>(R.id.text_energy_status)?.setText(if (isIgnoring) R.string.energy_status_unrestricted else R.string.energy_status_optimized)
        root.findViewById<View>(R.id.view_energy_indicator)?.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (isIgnoring) R.color.status_green else R.color.status_red)
        )
    }

    private fun openEnergySettings() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            Toast.makeText(context, R.string.energy_status_unrestricted, Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.energy_optimization_title)
            .setMessage(context.getString(R.string.energy_optimization_message) + "\n\n" + context.getString(R.string.energy_optimization_instructions))
            .setPositiveButton(R.string.settings_yes) { _, _ ->
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                try { context.startActivity(intent) } catch (_: Exception) {}
            }
            .setNegativeButton(R.string.energy_optimization_later, null)
            .create()

        dialog.show()
        // Pre-select "Later" (Cancel)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun showResetConfirmation() {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_reset_confirm_title)
            .setMessage(R.string.settings_reset_confirm_msg)
            .setPositiveButton(R.string.settings_yes) { _, _ ->
                settings.resetToDefaults()
                val mode = if (settings.appTheme == 0) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                AppCompatDelegate.setDefaultNightMode(mode)
                (context as? SettingsActivity)?.recreate()
            }
            .setNegativeButton(R.string.settings_no, null)
            .create()

        dialog.show()
        // Pre-select "No" for safety
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun showImportIpDialog() {
        val input = EditText(context).apply {
            hint = context.getString(R.string.settings_import_ip_hint)
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_import_ip_title).setView(input)
            .setPositiveButton(R.string.settings_import_action) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) performNetworkImport(ip)
            }.setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.window?.apply {
            setGravity(Gravity.TOP)
            attributes = attributes.apply { y = 100 } // Offset from top
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()

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
                    val data = (context as SettingsActivity).mapper.readValue(json, AppSettings.SettingsData::class.java)
                    (context as SettingsActivity).runOnUiThread {
                        settings.apply(data)
                        (context as SettingsActivity).recreate()
                        Toast.makeText(context, R.string.settings_import_success, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    (context as SettingsActivity).runOnUiThread {
                        Toast.makeText(context, context.getString(R.string.settings_import_error, "HTTP ${connection.responseCode}"), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                (context as SettingsActivity).runOnUiThread {
                    Toast.makeText(context, context.getString(R.string.settings_import_error, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
