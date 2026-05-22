package nl.rogro82.pipup

import android.annotation.SuppressLint
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.util.UnstableApi

/**
 * Main Activity displaying server status and version information.
 *
 * Serves as the entry point for both Leanback (TV) and standard launchers.
 */
@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private val mHandler = Handler(Looper.getMainLooper())
    private val mAutoFinishRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            Log.d("MainActivity", "Auto-finishing MainActivity to save resources")
            finish()
        }
    }

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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainLayout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Versioning info
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
            versionText.text = "v?.?.?"
        }

        // Server Status
        val statusLabel = findViewById<TextView>(R.id.textViewConnection)
        val addressLabel = findViewById<TextView>(R.id.textViewServerAddress)

        // IP Address retrieval is moved to a background thread to prevent UI stutter
        Thread {
            val ip = Utils.getIpAddress()
            runOnUiThread {
                if (ip != null) {
                    statusLabel.text = getString(R.string.server_running)
                    addressLabel.text = getString(R.string.server_address, ip, PipUpService.PIPUP_SERVER_PORT)
                } else {
                    statusLabel.text = getString(R.string.no_network_connection)
                    addressLabel.text = "---.---.---.---"
                }
            }
        }.start()

        findViewById<TextView>(R.id.textViewInfo).text = getString(R.string.more_information)

        // Settings Button
        findViewById<ImageButton>(R.id.btn_open_settings).setOnClickListener {
            mHandler.removeCallbacks(mAutoFinishRunnable)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Start Background Service
        val serviceIntent = Intent(this, PipUpService::class.java)
        startForegroundService(serviceIntent)

        askPermission()
    }

    override fun onResume() {
        super.onResume()
        requestBatteryOptimizationExemption()

        // Schedule auto-finish after 60 seconds of inactivity to free graphics memory
        mHandler.removeCallbacks(mAutoFinishRunnable)
        mHandler.postDelayed(mAutoFinishRunnable, 60000)
    }

    override fun onPause() {
        super.onPause()
        mHandler.removeCallbacks(mAutoFinishRunnable)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val appSettings = AppSettings(this)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)

        if (BuildConfig.DEBUG) {
            Log.d("MainActivity", "Battery optimization status - isIgnoring: $isIgnoring, dismissed: ${appSettings.dismissBatteryOptimization}")
        }

        if (isIgnoring || appSettings.dismissBatteryOptimization) return

        AlertDialog.Builder(this)
            .setTitle(R.string.energy_optimization_title)
            .setMessage(getString(R.string.energy_optimization_message) + "\n\n" + getString(R.string.energy_optimization_instructions))
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
            .show()
    }

    private fun showEnergyInstructionsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.energy_optimization_title)
            .setMessage(R.string.energy_optimization_manual)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun askPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            overlayPermissionLauncher.launch(intent)
        }
    }
}
