package nl.rogro82.pipup.service

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import nl.rogro82.pipup.R
import nl.rogro82.pipup.showToast

/**
 * Receiver for Device Administration events.
 * Required for the remote sleep (lock) functionality.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        context.showToast(context.getString(R.string.admin_enabled))
    }

    override fun onDisabled(context: Context, intent: Intent) {
        context.showToast(context.getString(R.string.admin_disabled))
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
