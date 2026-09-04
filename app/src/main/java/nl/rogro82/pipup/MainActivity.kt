package nl.rogro82.pipup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.util.UnstableApi
import java.util.Calendar
import nl.rogro82.pipup.core.PowerController
import nl.rogro82.pipup.service.PipUpService
import nl.rogro82.pipup.ui.SettingsActivity

/**
 * Main Activity displaying server status and version information.
 *
 * Serves as the entry point for both Leanback (TV) and standard launchers.
 */
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private val appSettings = PiPupApp.settings
    private val handler = Handler(Looper.getMainLooper())

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PiPupApp.ACTION_SETTINGS_CHANGED) {
                if (intent.getStringExtra("origin") == "remote") {
                    Log.d("MainActivity", "Remote settings change detected, refreshing UI")
                    recreate()
                }
            }
        }
    }

    private val refreshTask = object : Runnable {
        override fun run() {
            updateNotificationArea()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.MainTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainLayout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Server Status
        val statusLabel = findViewById<TextView>(R.id.textViewConnection)
        val addressLabel = findViewById<TextView>(R.id.textViewServerAddress)

        // IP Address retrieval is moved to a background thread to prevent UI stutter
        Thread {
            val ip = getIpAddress()
            runOnUiThread {
                if (ip != null) {
                    statusLabel.text = getString(R.string.server_running)
                    addressLabel.text = getString(R.string.server_address, ip, PipUpService.SERVER_PORT)
                } else {
                    statusLabel.text = getString(R.string.no_network_connection)
                    addressLabel.text = getString(R.string.address_placeholder)
                }
            }
        }.start()

        findViewById<TextView>(R.id.textViewInfo).text = getString(R.string.more_information)

        // Settings Button
        findViewById<ImageButton>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Start Background Service
        val serviceIntent = Intent(this, PipUpService::class.java)
        startForegroundService(serviceIntent)

        registerProtectedReceiver(settingsReceiver, IntentFilter(PiPupApp.ACTION_SETTINGS_CHANGED))
    }

    override fun onResume() {
        super.onResume()
        Permissions.onActivityResumed()
        refreshVersionAndUpdates()
        updateNotificationArea()
        handler.post(refreshTask)

        // Check for finished update downloads that might have been missed
        if (appSettings.pendingUpdateId != -1L) {
            UpdateManager(this).resumePendingUpdate()
        }

        // Daily Nag handles all required permissions (including Overlay after first start)
        checkAndShowPermissionNag()
    }

    override fun onPause() {
        super.onPause()
        Permissions.onActivityPaused()
        handler.removeCallbacks(refreshTask)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(settingsReceiver)
        } catch (_: Exception) {}
    }

    /**
     * Lists required permissions that are currently missing.
     * Note: Battery optimization and install permissions are considered optional.
     */
    private fun getMissingRequiredPermissions(): List<String> {
        val missing = mutableListOf<String>()

        if (!Permissions.overlay(this)) {
            missing.add(getString(R.string.permission_overlay))
        }

        if (appSettings.powerModuleEnabled && PowerController.getSleepMethod(this) == null) {
            missing.add(getString(R.string.settings_module_power))
        }

        return missing
    }

    /**
     * Updates the status area below the version number with relevant notices.
     */
    private fun updateNotificationArea() {
        val area = findViewById<TextView>(R.id.textViewNotificationArea) ?: return
        val missing = getMissingRequiredPermissions()

        if (missing.isNotEmpty()) {
            // Priority 1: Required Permissions Missing (Detailed, not clickable)
            area.visibility = View.VISIBLE
            area.text = getString(R.string.permission_missing, missing.joinToString(", "))
            area.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        } else if (appSettings.updateAvailableTag.isNotEmpty() && UpdateManager.isNewer(this, appSettings.updateAvailableTag)) {
            // Priority 2: App Update Available (Informational only)
            area.visibility = View.VISIBLE
            area.text = getString(R.string.settings_update_found_indicator)
            area.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            area.visibility = View.GONE
        }

        // Ensure it's never focusable or clickable as per Gold Standard lock logic
        area.isFocusable = false
        area.isClickable = false
        area.setOnClickListener(null)
    }

    /**
     * Shows a popup redirecting to the permissions menu, but at most once per day.
     */
    private fun checkAndShowPermissionNag() {
        val missing = getMissingRequiredPermissions()
        if (missing.isEmpty()) return

        // Daily nag logic
        val today = Calendar.getInstance()
        val lastNag = Calendar.getInstance().apply { timeInMillis = appSettings.lastPermissionNagDate }

        val isSameDay = appSettings.lastPermissionNagDate != 0L &&
                        today.get(Calendar.YEAR) == lastNag.get(Calendar.YEAR) &&
                        today.get(Calendar.DAY_OF_YEAR) == lastNag.get(Calendar.DAY_OF_YEAR)

        if (isSameDay) return

        val appName = getString(R.string.app_name)

        AlertDialog.Builder(this)
            .setTitle(R.string.nag_title)
            .setMessage(getString(R.string.nag_message, appName))
            .setPositiveButton(R.string.settings_nav_permissions) { _, _ ->
                val intent = Intent(this, SettingsActivity::class.java)
                intent.putExtra(SettingsActivity.EXTRA_NAV_ID, R.id.nav_item_permissions)
                startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                appSettings.lastPermissionNagDate = System.currentTimeMillis()
            }
            .show()
            .getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun refreshVersionAndUpdates() {
        val versionText = findViewById<TextView>(R.id.textViewVersion)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName
            versionText.text = if (BuildConfig.DEBUG) {
                getString(R.string.version_number_debug, version)
            } else {
                getString(R.string.version_number, version)
            }
        } catch (_: Exception) {
            versionText.text = getString(R.string.version_placeholder)
        }
    }
}
