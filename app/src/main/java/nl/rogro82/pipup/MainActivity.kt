package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.service.PipUpService
import nl.rogro82.pipup.ui.SettingsActivity

/**
 * Main Activity displaying server status and version information.
 *
 * Serves as the entry point for both Leanback (TV) and standard launchers.
 */
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var appSettings: AppSettings
    private var isEnergyDialogOpen = false

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w("MainActivity", "Overlay permission not granted!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.MainTheme)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.mainLayout)?.layoutDirection = View.LAYOUT_DIRECTION_LOCALE

        appSettings = AppSettings(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainLayout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Server Status
        val statusLabel = findViewById<TextView>(R.id.textViewConnection)
        val addressLabel = findViewById<TextView>(R.id.textViewServerAddress)
        val infoLabel = findViewById<TextView>(R.id.textViewInfo)

        // IP Address retrieval is moved to a background thread to prevent UI stutter
        Thread {
            val ip = getIpAddress()
            runOnUiThread {
                if (ip != null) {
                    statusLabel.text = getString(R.string.server_running)
                    addressLabel.text = String.format(java.util.Locale.US, "%s:%d", ip, PipUpService.SERVER_PORT)
                } else {
                    statusLabel.text = getString(R.string.no_network_connection)
                    addressLabel.text = getString(R.string.address_placeholder)
                }
            }
        }.start()

        infoLabel.text = getString(R.string.more_information)

        // Settings Button
        findViewById<ImageButton>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Start Background Service
        val serviceIntent = Intent(this, PipUpService::class.java)
        startForegroundService(serviceIntent)

        askPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshVersionAndUpdates()
        // Only request battery exemption if overlay permission is granted
        // to avoid double dialogs during the onboarding flow.
        if (Settings.canDrawOverlays(this)) {
            requestBatteryOptimizationExemption()
        }
    }

    private fun refreshVersionAndUpdates() {
        val versionText = findViewById<TextView>(R.id.textViewVersion)
        val updateIndicator = findViewById<TextView>(R.id.textViewUpdateAvailable)
        val updateManager = UpdateManager(this)

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName
            val pattern = if (BuildConfig.DEBUG) getString(R.string.version_number_debug) else getString(R.string.version_number)
            versionText.text = String.format(java.util.Locale.US, pattern, version)

            // Show update indicator if a newer version was found by background worker
            val hasUpdateTag = appSettings.updateAvailableTag.isNotEmpty()
            val updateAvailable = hasUpdateTag && updateManager.isNewer(appSettings.updateAvailableTag)

            if (hasUpdateTag && !updateAvailable) {
                // We have a stored update tag, but we are already on that version or newer.
                // Clear the tag to avoid unnecessary checks or confusing state.
                appSettings.updateAvailableTag = ""
            }

            Log.d("MainActivity", "Update check: tag=${appSettings.updateAvailableTag}, available=$updateAvailable")

            if (updateAvailable) {
                updateIndicator.visibility = View.VISIBLE
                updateIndicator.text = getString(R.string.settings_update_found_indicator)
            } else {
                updateIndicator.visibility = View.GONE
            }

            // If interval is set to "On App Open" (1), trigger a check now with cooldown
            if (appSettings.updateInterval == 1) {
                val cooldownMs = 300000L // 5 minutes cooldown for app open check
                val timeSinceLastCheck = System.currentTimeMillis() - appSettings.lastUpdateCheck

                if (timeSinceLastCheck > cooldownMs || appSettings.updateRepeat) {
                    updateManager.checkForUpdates(appSettings.updateChannel == 1, object : UpdateManager.UpdateCallback {
                        override fun onUpdateAvailable(release: GitHubRelease) {
                            appSettings.updateAvailableTag = release.tagName
                            appSettings.lastUpdateCheck = System.currentTimeMillis()

                            if (appSettings.updateRepeat || appSettings.lastNotifiedTag != release.tagName) {
                                updateManager.showUpdateNotification(release)
                                appSettings.lastNotifiedTag = release.tagName
                            }

                            runOnUiThread {
                                updateIndicator.visibility = View.VISIBLE
                            }
                        }
                        override fun onNoUpdate() {
                            runOnUiThread {
                                appSettings.updateAvailableTag = ""
                                appSettings.lastUpdateCheck = System.currentTimeMillis()
                                updateIndicator.visibility = View.GONE
                            }
                        }
                        override fun onError(message: String) {
                            Log.e("MainActivity", "Auto update check failed: $message")
                        }
                    })
                } else {
                    Log.d("MainActivity", "Skipping auto update check (cooldown active). Last check: ${timeSinceLastCheck / 1000 / 60} min ago")
                }
            }

        } catch (_: Exception) {
            versionText.text = getString(R.string.version_placeholder)
            updateIndicator.visibility = View.GONE
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        if (isEnergyDialogOpen) return

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)

        if (BuildConfig.DEBUG) {
            Log.d("MainActivity", "Battery optimization status - isIgnoring: $isIgnoring, dismissed: ${appSettings.dismissBatteryOptimization}")
        }

        if (isIgnoring || appSettings.dismissBatteryOptimization) return

        isEnergyDialogOpen = true

        val isRtl = isRtl()
        val dir = if (isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        val messageText = getString(R.string.energy_optimization_message) + "\n\n" + getString(R.string.energy_optimization_instructions)
        val messageView = TextView(this).apply {
            text = messageText
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.START
            }
            val p = dpToPx(24)
            setPadding(p, p, p, 0)
            textSize = 18f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.colorOnSurface))
            gravity = android.view.Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            layoutDirection = dir
        }

        val container = FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(messageView)
            layoutDirection = dir
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.energy_optimization_title)
            .setView(container)
            .setPositiveButton(R.string.settings_yes) { _, _ ->
                appSettings.dismissBatteryOptimization = true

                // Open the main settings page, trying to avoid the last active sub-page
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }

                try {
                    if (BuildConfig.DEBUG) {
                        Log.d("MainActivity", "Opening system settings main page.")
                    }
                    startActivity(intent)
                    Toast.makeText(this, R.string.energy_optimization_instructions, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to open settings", e)
                    showEnergyInstructionsDialog()
                }
            }
            .setNegativeButton(R.string.energy_optimization_later) { _, _ ->
                appSettings.dismissBatteryOptimization = true
            }
            .setOnDismissListener { isEnergyDialogOpen = false }
            .create()

        dialog.show()
        // Pre-select "Later" for better TV navigation
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun showEnergyInstructionsDialog() {
        val isRtl = isRtl()
        val dir = if (isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        val messageView = TextView(this).apply {
            setText(R.string.energy_optimization_manual)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.START
            }
            val p = dpToPx(24)
            setPadding(p, p, p, 0)
            textSize = 18f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.colorOnSurface))
            gravity = android.view.Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            layoutDirection = dir
        }

        val container = FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(messageView)
            layoutDirection = dir
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.energy_optimization_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.show()
    }

    private fun askPermission() {
        if (!Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                overlayPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to launch overlay permission settings", e)
                Toast.makeText(this, "Please grant overlay permission via ADB if settings are unavailable.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
