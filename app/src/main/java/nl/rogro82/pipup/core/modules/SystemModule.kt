package nl.rogro82.pipup.core.modules

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.Json
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.core.PiPupModule
import nl.rogro82.pipup.core.PowerController

/**
 * Module responsible for system diagnostics and permission management.
 * This module is always active and cannot be disabled.
 */
class SystemModule(private val context: Context) : PiPupModule {

    companion object {
        private const val TAG = "SystemModule"
    }

    override val id: String = "system"
    override val name: String = "System Diagnostics"

    override fun onEnable() {
        Log.d(TAG, "System module enabled")
    }

    override fun onDisable() {
        Log.d(TAG, "System module disabled")
    }

    override fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val method = session.method

        return when (session.uri.lowercase()) {
            "/permissions/diagnose" -> if (method == NanoHTTPD.Method.GET || method == NanoHTTPD.Method.POST) diagnoseResponse() else null
            "/permissions/fix" -> if (method == NanoHTTPD.Method.POST) fixResponse(session) else null
            else -> null
        }
    }

    private fun diagnoseResponse(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            Json.writeValueAsString(Permissions.diagnose(context))
        )
    }

    private fun fixResponse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val key: String? = when (val requested = session.parameters["what"]?.firstOrNull()?.lowercase()) {
            null, "", "app", "status" -> null
            "next" -> Permissions.firstMissing(context)
                ?: return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    Json.writeValueAsString(mapOf("what" to "next", "ok" to true, "nothingMissing" to true))
                )
            in Permissions.FIXABLE_KEYS -> requested
            else -> return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "text/plain",
                "what must be one of ${Permissions.FIXABLE_KEYS}, 'next' or omitted"
            )
        }

        // Refusal checks logic from fork
        if (key != null && Permissions.opBlocked(context, key)) {
             return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
                "application/json",
                Json.writeValueAsString(mapOf(
                    "what" to key,
                    "ok" to false,
                    "reason" to "this device blocks granting this permission from its settings screen; grant it over adb instead",
                    "granted" to Permissions.granted(context, key),
                    "adb" to Permissions.adbCommand(key, context)
                ))
            )
        }

        if (!Permissions.canLaunchActivity(context)) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
                "application/json",
                Json.writeValueAsString(mapOf(
                    "what" to (key ?: "app"),
                    "ok" to false,
                    "reason" to Permissions.BLOCKED_ERROR,
                    "granted" to key?.let { Permissions.granted(context, it) },
                    "adb" to key?.let { Permissions.adbCommand(it, context) }
                ))
            )
        }

        // Wake screen before showing fix screen
        PowerController.wake(context)

        val success = if (key == null) Permissions.launchApp(context) else Permissions.launchFix(context, key)

        val body = Json.writeValueAsString(mapOf(
            "what" to (key ?: "app"),
            "ok" to success,
            "granted" to key?.let { Permissions.granted(context, it) },
            "adb" to key?.takeIf { !success }?.let { Permissions.adbCommand(it, context) }
        ))

        return NanoHTTPD.newFixedLengthResponse(
            if (success) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
            "application/json",
            body
        )
    }

    override fun augmentState(state: MutableMap<String, Any?>) {
        // Core state already includes permissions via PipUpService's stateResponse
    }
}
