package nl.rogro82.pipup

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/**
 * Custom Test Runner for PiPup.
 *
 * Prepares the environment via Shell-Commands BEFORE the app starts.
 * The application code remains 100% clean and unaware of the test environment.
 */
class PiPupTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        val packageName = targetContext.packageName

        // Grant the special SYSTEM_ALERT_WINDOW permission via shell.
        // This is done before the Application or any Activity is created.
        runBlockingShell("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        runBlockingShell("cmd appops set $packageName SYSTEM_ALERT_WINDOW allow")

        // Clear potential UI state (Settings app)
        runBlockingShell("am force-stop com.android.settings")

        // Wait until the system settings reflect the change.
        // Once this loop finishes, Settings.canDrawOverlays(this) will return true
        // natively in the MainActivity, skipping the permission dialog.
        var granted = false
        repeat(20) {
            if (Settings.canDrawOverlays(targetContext)) {
                granted = true
                return@repeat
            }
            Thread.sleep(250)
        }

        if (!granted) {
            Log.e("PiPupTestRunner", "SYSTEM_ALERT_WINDOW NOT GRANTED BY SYSTEM")
        }

        super.onCreate(arguments)
    }

    private fun runBlockingShell(command: String) {
        try {
            uiAutomation.executeShellCommand(command).use { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                    val b = ByteArray(1024)
                    while (stream.read(b) != -1) { /* consume */ }
                }
            }
        } catch (e: Exception) {
            Log.e("PiPupTestRunner", "Shell error: $command", e)
        }
    }
}
