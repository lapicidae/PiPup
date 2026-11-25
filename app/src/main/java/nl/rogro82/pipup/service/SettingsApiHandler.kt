package nl.rogro82.pipup.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.Pusher
import nl.rogro82.pipup.readExactBytes
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException

/**
 * Handles GET and POST `/settings` HTTP requests, including webhook validation and live sync.
 */
internal class SettingsApiHandler(
    private val context: Context,
    private val settings: AppSettings,
    private val mainHandler: Handler,
    private val onSettingsApplied: (AppSettings.SettingsData?) -> Unit,
    private val onEmitSettings: () -> Unit,
) {
    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = when (session.method) {
        NanoHTTPD.Method.GET -> {
            val json = settings.getAll().toJSONObject().toString()
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                json,
            ).apply { setGzipEncoding(false) }
        }
        NanoHTTPD.Method.POST -> handleSettingsPost(session)
        else -> NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
            "text/plain",
            "Method Not Allowed",
        )
    }

    private fun handleSettingsPost(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val (webhookUpdated, queryError) = applyWebhookQueryParams(session)
        if (queryError != null) return invalidRequest(queryError)

        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        return when {
            length > 0 -> applySettingsJsonBody(String(session.inputStream.readExactBytes(length), Charsets.UTF_8))
            webhookUpdated -> {
                onSettingsApplied(null)
                onEmitSettings()
                ok()
            }
            else -> invalidRequest("Empty")
        }
    }

    private fun applyWebhookQueryParams(session: NanoHTTPD.IHTTPSession): Pair<Boolean, String?> {
        var updated = false
        var error: String? = null
        session.parameters["webhook"]?.firstOrNull()?.let { raw ->
            error = applyWebhookUrl(raw)
            updated = true
        }
        if (error == null) {
            session.parameters["webhookHeartbeat"]?.firstOrNull()?.let { raw ->
                error = applyWebhookHeartbeat(raw)
                updated = true
            }
        }
        return updated to error
    }

    private fun applySettingsJsonBody(bodyText: String): NanoHTTPD.Response {
        val json = try {
            JSONObject(bodyText)
        } catch (e: JSONException) {
            return invalidRequest(e.message)
        }

        val (parsedHeartbeat, webhookError) = validateJsonWebhookFields(json)
        val keys = buildSet { json.keys().forEach { add(it) } }
        val isWebhookOnlyJson = keys.isNotEmpty() && keys.all { it == "webhook" || it == "webhookHeartbeat" }
        return when {
            webhookError != null -> invalidRequest(webhookError)
            isWebhookOnlyJson -> {
                if (parsedHeartbeat != null) settings.webhookHeartbeat = parsedHeartbeat
                onSettingsApplied(null)
                onEmitSettings()
                ok()
            }
            else -> applyFullSettingsJson(bodyText, parsedHeartbeat)
        }
    }

    private fun validateJsonWebhookFields(json: JSONObject): Pair<Int?, String?> {
        var parsedHeartbeat: Int? = null
        var error: String? = null
        if (json.has("webhookHeartbeat")) {
            val rawHb = if (json.isNull("webhookHeartbeat")) "0" else json.get("webhookHeartbeat").toString()
            parsedHeartbeat = parseWebhookHeartbeat(rawHb)
            if (parsedHeartbeat == null) error = "webhookHeartbeat must be a non-negative integer"
        }
        if (error == null && json.has("webhook")) {
            val rawUrl = if (json.isNull("webhook")) "" else json.optString("webhook", "")
            error = applyWebhookUrl(rawUrl)
        }
        return parsedHeartbeat to error
    }

    private fun applyFullSettingsJson(bodyText: String, parsedHeartbeat: Int?): NanoHTTPD.Response = try {
        val parsed = AppSettings.SettingsData.fromJson(bodyText, defaults = settings.getAll())
        val data = if (parsedHeartbeat != null) parsed.copy(webhookHeartbeat = parsedHeartbeat) else parsed
        mainHandler.post {
            settings.apply(data)
            onSettingsApplied(data)

            val intent = Intent(PiPupApp.ACTION_SETTINGS_CHANGED).apply {
                setPackage(PiPupApp.APP_PACKAGE)
                putExtra("origin", "remote")
            }
            context.sendBroadcast(intent)
        }
        onEmitSettings()
        ok()
    } catch (e: JSONException) {
        invalidRequest(e.message)
    } catch (e: IllegalArgumentException) {
        invalidRequest(e.message)
    }

    private fun applyWebhookUrl(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) {
            settings.webhook = null
            Pusher.url = null
            return null
        }
        val valid = try {
            val uri = URI(v)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        } catch (_: URISyntaxException) {
            false
        }
        if (valid) {
            settings.webhook = v
            Pusher.url = v
        }
        return if (valid) null else "webhook must be a valid http(s) URL"
    }

    private fun parseWebhookHeartbeat(raw: String): Int? {
        val parsed = raw.trim().toIntOrNull() ?: return null
        return AppSettings.normalizeWebhookHeartbeat(parsed)
    }

    private fun applyWebhookHeartbeat(raw: String): String? {
        val normalized = parseWebhookHeartbeat(raw)
            ?: return "webhookHeartbeat must be a non-negative integer"
        settings.webhookHeartbeat = normalized
        return null
    }

    private fun ok(): NanoHTTPD.Response {
        Log.d(TAG, "Response OK")
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", "OK")
    }

    private fun invalidRequest(message: String?): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            "text/plain",
            "invalid request: ${message ?: "unknown error"}",
        )
    }

    companion object {
        private const val TAG = "SettingsApiHandler"
    }
}
