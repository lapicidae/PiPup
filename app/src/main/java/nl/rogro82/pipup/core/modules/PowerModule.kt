package nl.rogro82.pipup.core.modules

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.Json
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.ModuleMode
import nl.rogro82.pipup.core.ModuleContext
import nl.rogro82.pipup.core.PiPupModule
import nl.rogro82.pipup.core.PowerController

/**
 * Module responsible for remote power management (screen wake/sleep).
 */
class PowerModule : PiPupModule {

    companion object {
        private const val TAG = "PowerModule"
    }

    override val id: String = "power"
    override val name: String = "Power Control"

    override val supportedModes: List<ModuleMode> = listOf(ModuleMode.OFF, ModuleMode.ON)
    override val defaultMode: ModuleMode = ModuleMode.OFF

    private var moduleContext: ModuleContext? = null

    override fun onEnable(context: ModuleContext) {
        Log.d(TAG, "Power module enabled")
        this.moduleContext = context
    }

    override fun onDisable() {
        Log.d(TAG, "Power module disabled")
        moduleContext = null
    }

    override fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri.lowercase()
        if (uri == "/power" && session.method == NanoHTTPD.Method.POST) {
            return handlePowerRequest(session)
        }
        return null
    }

    private fun handlePowerRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val context = moduleContext?.androidContext ?: return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "Missing context"
        )

        val requested = session.parameters["state"]?.firstOrNull()?.lowercase()
        val target = when (requested) {
            "on", "wake", "true", "1" -> true
            "off", "sleep", "false", "0" -> false
            "toggle" -> !PowerController.isScreenOn(context)
            else -> return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "text/plain",
                "State must be on, off or toggle"
            )
        }

        val success = if (target) PowerController.wake(context) else PowerController.sleep(context)
        val method = if (target) "wake_activity" else PowerController.getSleepMethod(context)

        if (!success && !target && method == null) {
            moduleContext?.showToast(moduleContext?.getString(R.string.error_power_permission_missing) ?: "")
        }

        val body = Json.writeValueAsString(mapOf(
            "state" to if (target) "on" else "off",
            "ok" to success,
            "method" to method,
            "screenOn" to PowerController.isScreenOn(context)
        ))

        return NanoHTTPD.newFixedLengthResponse(
            if (success) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
            "application/json",
            body
        )
    }

    override fun augmentState(state: MutableMap<String, Any?>) {
        val context = moduleContext?.androidContext ?: return
        state["power"] = mapOf(
            "canWake" to true,
            "canSleep" to (PowerController.getSleepMethod(context) != null),
            "sleepMethod" to PowerController.getSleepMethod(context),
            "screenOn" to PowerController.isScreenOn(context)
        )
    }

    override fun getRequiredPermissions(): List<String> = listOf(
        Permissions.KEY_POWER
    )
}
