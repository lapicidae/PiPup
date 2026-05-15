package nl.rogro82.pipup

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
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
class MainActivity : ComponentActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w("MainActivity", "Overlay permission not granted!")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Log.w("MainActivity", "Notification permission denied!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        
        val ip = Utils.getIpAddress()
        if (ip != null) {
            statusLabel.text = getString(R.string.server_running)
            addressLabel.text = getString(R.string.server_address, ip, PipUpService.PIPUP_SERVER_PORT)
        } else {
            statusLabel.text = getString(R.string.no_network_connection)
            addressLabel.text = "---.---.---.---"
        }

        findViewById<TextView>(R.id.textViewInfo).text = getString(R.string.more_information)

        // Settings Button
        findViewById<ImageButton>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Start Background Service
        val serviceIntent = Intent(this, PipUpService::class.java)
        startForegroundService(serviceIntent)

        askPermission()
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun askPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            overlayPermissionLauncher.launch(intent)
        }
    }
}
