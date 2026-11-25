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
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.UpdateManager
import nl.rogro82.pipup.core.ModuleMode
import nl.rogro82.pipup.core.ModuleContext
import nl.rogro82.pipup.core.NotificationManager
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

    /** Logging constants and stateless HTTP helpers for [SystemModule]. */
    companion object {
        private const val TAG = "SystemModule"

        private fun jsonResponse(status: NanoHTTPD.Response.Status, payload: Map<String, Any?>): NanoHTTPD.Response =
            NanoHTTPD.newFixedLengthResponse(status, "application/json", Json.writeValueAsString(payload))

        private fun parseNotifyPayload(
            context: android.content.Context,
            session: NanoHTTPD.IHTTPSession,
        ): Pair<PopupProps?, NanoHTTPD.Response?> {
            return try {
                val props = PayloadParser(context).parse(session)
                if (props != null) {
                    props to null
                } else {
                    null to NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.BAD_REQUEST,
                        "text/plain",
                        "Failed to parse input",
                    )
                }
            } catch (e: IllegalArgumentException) {
                null to NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    "text/plain",
                    "invalid request: ${e.message}",
                )
            }
        }

        private fun resolveFixTarget(
            context: android.content.Context,
            requested: String?,
        ): Pair<String?, NanoHTTPD.Response?> = when (requested) {
            null, "", "app", "status" -> null to null
            "next" -> {
                val missing = Permissions.firstMissing(context)
                if (missing != null) {
                    missing to null
                } else {
                    null to jsonResponse(
                        NanoHTTPD.Response.Status.OK,
                        mapOf("what" to "next", "ok" to true, "nothingMissing" to true),
                    )
                }
            }
            in Permissions.FIXABLE_KEYS -> requested to null
            else -> null to NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "text/plain",
                "what must be one of ${Permissions.FIXABLE_KEYS}, 'next' or omitted",
            )
        }

        private fun checkFixPreconditions(
            context: android.content.Context,
            key: String?,
        ): NanoHTTPD.Response? = when {
            key != null && Permissions.opBlocked(context, key) -> jsonResponse(
                NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
                mapOf(
                    "what" to key,
                    "ok" to false,
                    "reason" to "this device blocks granting this permission from its settings screen; " +
                        "grant it over adb instead",
                    "granted" to Permissions.granted(context, key),
                    "adb" to Permissions.adbCommand(key, context),
                ),
            )
            !Permissions.canLaunchActivity(context) -> jsonResponse(
                NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
                mapOf(
                    "what" to (key ?: "app"),
                    "ok" to false,
                    "reason" to Permissions.BLOCKED_ERROR,
                    "granted" to key?.let { Permissions.granted(context, it) },
                    "adb" to key?.let { Permissions.adbCommand(it, context) },
                ),
            )
            else -> null
        }
    }

    override val id: String = "system"
    override val nameRes: Int = R.string.settings_module_system
    override val descriptionRes: Int = R.string.settings_nav_permissions // Use a relevant existing string

    override val isUserConfigurable: Boolean = false
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
            "/permissions/diagnose" -> if (
                method == NanoHTTPD.Method.GET || method == NanoHTTPD.Method.POST
            ) {
                diagnoseResponse()
            } else {
                null
            }
            "/permissions/fix" -> if (method == NanoHTTPD.Method.POST) fixResponse(session) else null
            "/update" -> if (method == NanoHTTPD.Method.POST) updateResponse() else null
            "/notify" -> if (method == NanoHTTPD.Method.POST) notifyResponse(session) else null
            "/cancel" -> if (method == NanoHTTPD.Method.POST) cancelResponse(session) else null
            else -> null
        }
    }

    private fun updateResponse(): NanoHTTPD.Response {
        val mc = moduleContext
        val context = mc?.androidContext
        val settings = mc?.settings
        if (context == null || settings == null) {
            return NanoHTTPD.newFixedLengthResponse(if (context == null) "Missing context" else "Missing settings")
        }
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
        return jsonResponse(NanoHTTPD.Response.Status.OK, Permissions.diagnose(context))
    }

    private fun fixResponse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val context = moduleContext?.androidContext ?: return NanoHTTPD.newFixedLengthResponse("Missing context")
        val requested = session.parameters["what"]?.firstOrNull()?.lowercase()
        val (key, resolutionError) = resolveFixTarget(context, requested)
        val rejection = resolutionError ?: checkFixPreconditions(context, key)
        return rejection ?: run {
            PowerController.wake(context)
            val success = if (key == null) Permissions.launchApp(context) else Permissions.launchFix(context, key)
            val body = mapOf(
                "what" to (key ?: "app"),
                "ok" to success,
                "granted" to key?.let { Permissions.granted(context, it) },
                "adb" to key?.takeIf { !success }?.let { Permissions.adbCommand(it, context) },
            )
            val status = if (success) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.NOT_IMPLEMENTED
            jsonResponse(status, body)
        }
    }

    private fun notifyResponse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val mc = moduleContext
        val context = mc?.androidContext
        val nm = mc?.notificationManager
        val settings = mc?.settings
        if (context == null || nm == null || settings == null) {
            val err = when {
                context == null -> "Missing context"
                nm == null -> "Service not ready"
                else -> "Settings not ready"
            }
            return NanoHTTPD.newFixedLengthResponse(err)
        }

        val (parsedProps, parseError) = parseNotifyPayload(context, session)
        val rejection = parseError ?: enforceMediaModulePolicy(context, settings, parsedProps!!)
        return rejection ?: run {
            nm.enqueue(settings.applyDefaults(parsedProps!!))
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "OK: Enqueued")
        }
    }

    private fun enforceMediaModulePolicy(
        context: android.content.Context,
        settings: nl.rogro82.pipup.AppSettings,
        props: PopupProps,
    ): NanoHTTPD.Response? {
        val isRichMedia = props.media is PopupProps.Media.Web || props.media is PopupProps.Media.Whep
        if (!isRichMedia) return null

        val mediaMode = settings.getModuleMode("media")
        return if (mediaMode == ModuleMode.OFF) {
            val localizedContext = context.getLocalizedContext(settings.language)
            val msg = localizedContext.getString(
                R.string.error_module_disabled,
                localizedContext.getString(R.string.settings_module_media),
            )
            context.showToast(msg)
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                "text/plain",
                "Media module is disabled",
            )
        } else {
            if (mediaMode == ModuleMode.ECO) {
                (context.applicationContext as? PiPupApp)?.moduleManager?.activateModule("media")
            }
            null
        }
    }

    private fun cancelResponse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val id = session.parameters["id"]?.firstOrNull()
        val all = session.parameters["all"]?.firstOrNull()?.lowercase() in setOf("true", "1", "on")
        val nm = moduleContext?.notificationManager ?: return NanoHTTPD.newFixedLengthResponse("Service not ready")

        val msg = if (!all && id != null) {
            when (nm.cancelById(id)) {
                NotificationManager.CancelResult.CANCELLED_ACTIVE -> "OK: Cancelled"
                NotificationManager.CancelResult.REMOVED_FROM_QUEUE -> "OK: Removed from queue"
                NotificationManager.CancelResult.NOT_FOUND -> {
                    if (!nm.isDisplaying()) "OK: Nothing to cancel" else "ID not found: $id"
                }
            }
        } else {
            val isDisplaying = nm.isDisplaying()
            nm.cancelAll()
            if (isDisplaying) "OK: Cancelled" else "OK: Nothing to cancel"
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", msg)
    }
}
