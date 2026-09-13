package nl.rogro82.pipup.core.modules

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.rogro82.pipup.Json
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.R
import nl.rogro82.pipup.UpdateManager
import nl.rogro82.pipup.core.ModuleMode
import nl.rogro82.pipup.core.ModuleContext
import nl.rogro82.pipup.core.PayloadParser
import nl.rogro82.pipup.core.PiPupModule
import nl.rogro82.pipup.core.PowerController
import nl.rogro82.pipup.getLocalizedContext
import nl.rogro82.pipup.showToast

/**
 * Module responsible for system diagnostics and permission management.
 * This module is always active and cannot be disabled.
 */
@OptIn(UnstableApi::class)
class SystemModule : PiPupModule {

    companion object {
        private const val TAG = "SystemModule"
    }

    override val id: String = "system"
    override val name: String = "System Diagnostics"

    override val supportedModes: List<ModuleMode> = listOf(ModuleMode.ON)
    override val defaultMode: ModuleMode = ModuleMode.ON

    override val supportedRoutes: List<String> = listOf(
        "/notify", "/cancel", "/update", "/permissions/diagnose", "/permissions/fix"
    )

    private var moduleContext: ModuleContext? = null

    override fun onEnable(context: ModuleContext) {
        Log.d(TAG, "System module enabled")
        this.moduleContext = context
    }

    override fun onDisable() {
        Log.d(TAG, "System module disabled")
        moduleContext = null
    }

    override fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val method = session.method
        val uri = session.uri.lowercase()

        return when (uri) {
            "/permissions/diagnose" -> if (method == NanoHTTPD.Method.GET || method == NanoHTTPD.Method.POST) diagnoseResponse() else null
            "/permissions/fix" -> if (method == NanoHTTPD.Method.POST) fixResponse(session) else null
            "/update" -> if (method == NanoHTTPD.Method.POST) updateResponse() else null
            "/notify" -> if (method == NanoHTTPD.Method.POST) notifyResponse(session) else null
            "/cancel" -> if (method == NanoHTTPD.Method.POST) cancelResponse(session) else null
            else -> null
        }
    }

    private fun updateResponse(): NanoHTTPD.Response {
        val context = moduleContext?.androidContext ?: return NanoHTTPD.newFixedLengthResponse("Missing context")
        val settings = moduleContext?.settings ?: return NanoHTTPD.newFixedLengthResponse("Missing settings")
        val scope = (context.applicationContext as? PiPupApp)?.applicationScope ?: kotlinx.coroutines.MainScope()

        scope.launch(Dispatchers.Main) {
            val updateManager = UpdateManager(context)
            updateManager.checkForUpdates(settings.updateChannel == 1).onSuccess { release ->
                if (release != null) {
                    updateManager.downloadAndInstall(release)
                }
            }
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "Update check started")
    }

    private fun diagnoseResponse(): NanoHTTPD.Response {
        val context = moduleContext?.androidContext ?: return NanoHTTPD.newFixedLengthResponse("Missing context")
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            Json.writeValueAsString(Permissions.diagnose(context))
        )
    }

    private fun fixResponse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val context = moduleContext?.androidContext ?: return NanoHTTPD.newFixedLengthResponse("Missing context")

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

        // Exact JSON structure matching the reference fork
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

    private fun notifyResponse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val context = moduleContext?.androidContext ?: return NanoHTTPD.newFixedLengthResponse("Missing context")
        val nm = moduleContext?.notificationManager ?: return NanoHTTPD.newFixedLengthResponse("Service not ready")
        val settings = moduleContext?.settings ?: return NanoHTTPD.newFixedLengthResponse("Settings not ready")

        val parser = PayloadParser(context)
        var props = parser.parse(session) ?: return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "Failed to parse input")

        // Guard: Check if media module is required and enabled
        val isRichMedia = props.media is nl.rogro82.pipup.PopupProps.Media.Web || props.media is nl.rogro82.pipup.PopupProps.Media.Whep
        val mediaMode = settings.getModuleMode("media")
        if (isRichMedia) {
            if (mediaMode == ModuleMode.OFF) {
                val localizedContext = context.getLocalizedContext(settings.language)
                val msg = localizedContext.getString(R.string.error_module_disabled, localizedContext.getString(R.string.settings_module_media))
                context.showToast(msg)
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "Media module is disabled")
            }

            // If ECO mode, ensure module is activated
            (context.applicationContext as? PiPupApp)?.moduleManager?.let { mm ->
                if (mediaMode == ModuleMode.ECO) {
                    mm.activateModule("media")
                }
            }
        }

        props = settings.applyDefaults(props)

        nm.enqueue(props)
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "OK: Enqueued")
    }

    private fun cancelResponse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val id = session.parameters["id"]?.firstOrNull()
        val nm = moduleContext?.notificationManager ?: return NanoHTTPD.newFixedLengthResponse("Service not ready")

        val current = nm.getCurrentProps()
        val isDisplaying = nm.isDisplaying()

        // Match fork logic: Only report mismatch if a popup is visible AND an ID was provided AND it doesn't match
        if (id != null && isDisplaying && current?.id != id) {
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "ID mismatch: visible is ${current?.id}")
        }

        nm.cancelAll()

        val msg = if (isDisplaying) "OK: Cancelled" else "OK: Nothing to cancel"
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", msg)
    }
}
