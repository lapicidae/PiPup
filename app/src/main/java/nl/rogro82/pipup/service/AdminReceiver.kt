package nl.rogro82.pipup.service

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import nl.rogro82.pipup.R

/**
 * Receiver for Device Administration events.
 * Required for the remote sleep (lock) functionality.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, context.getString(R.string.admin_enabled), Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, context.getString(R.string.admin_disabled), Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * Returns the [ComponentName] for this receiver.
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, AdminReceiver::class.java)
        }
    }
}
