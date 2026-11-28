package nl.rogro82.pipup.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.Pusher
import nl.rogro82.pipup.core.PayloadParser
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException

/**
 * Handles GET and POST `/settings` HTTP requests, including webhook validation,
 * Home Assistant `updateChecks` / read-only `updateSource` compatibility, and live sync.
 */
internal class SettingsApiHandler(
    private val context: Context,
    private val settings: AppSettings,
    private val mainHandler: Handler,
    private val onSettingsApplied: (AppSettings.SettingsData?) -> Unit,
    private val onEmitSettings: () -> Unit,
) {
    private data class ParsedMeta(
        val webhook: String? = null,
        val hasWebhook: Boolean = false,
        val webhookHeartbeat: Int? = null,
        val updateChecks: Boolean? = null,
        val hasReadOnlyMeta: Boolean = false,
        val error: String? = null,
    ) {
        val hasAnyUpdate: Boolean
            get() = hasWebhook || webhookHeartbeat != null || updateChecks != null || hasReadOnlyMeta
    }

    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = when (session.method) {
        NanoHTTPD.Method.GET -> jsonSettingsResponse(settings.getAll())
        NanoHTTPD.Method.POST -> handleSettingsPost(session)
        else -> NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
            "text/plain",
            "Method Not Allowed",
        )
    }

    private fun handleSettingsPost(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val queryMeta = parseQueryMeta(session)
        if (queryMeta.error != null) return invalidRequest(queryMeta.error)

        val bodyText = PayloadParser.readJsonBody(session, requireChunkedHeader = true)
        return when {
            bodyText.isNotEmpty() -> applySettingsJsonBody(bodyText, queryMeta)
            queryMeta.hasAnyUpdate -> {
                applyMeta(queryMeta)
                onSettingsApplied(null)
                onEmitSettings()
                jsonSettingsResponse(settings.getAll())
            }
            else -> invalidRequest("Empty")
        }
    }

    private data class RawMeta(
        val hasWebhook: Boolean = false,
        val rawWebhook: String? = null,
        val hasHeartbeat: Boolean = false,
        val rawHeartbeat: String? = null,
        val hasUpdateChecks: Boolean = false,
        val rawUpdateChecks: String? = null,
        val hasReadOnlySource: Boolean = false,
    )

    private fun parseQueryMeta(session: NanoHTTPD.IHTTPSession): ParsedMeta = parseMetaFields(
        RawMeta(
            hasWebhook = session.parameters.containsKey("webhook"),
            rawWebhook = session.parameters["webhook"]?.firstOrNull(),
            hasHeartbeat = session.parameters.containsKey("webhookHeartbeat"),
            rawHeartbeat = session.parameters["webhookHeartbeat"]?.firstOrNull(),
            hasUpdateChecks = session.parameters.containsKey("updateChecks"),
            rawUpdateChecks = session.parameters["updateChecks"]?.firstOrNull(),
            hasReadOnlySource = session.parameters.containsKey("updateSource"),
        ),
    )

    private fun applySettingsJsonBody(bodyText: String, queryMeta: ParsedMeta): NanoHTTPD.Response {
        val json = try {
            JSONObject(bodyText)
        } catch (e: JSONException) {
            return invalidRequest(e.message)
        }

        val jsonMeta = parseMetaFields(
            RawMeta(
                hasWebhook = json.has("webhook"),
                rawWebhook = if (json.isNull("webhook")) "" else json.optString("webhook", ""),
                hasHeartbeat = json.has("webhookHeartbeat"),
                rawHeartbeat = if (json.isNull("webhookHeartbeat")) "0" else json.get("webhookHeartbeat").toString(),
                hasUpdateChecks = json.has("updateChecks") && !json.isNull("updateChecks"),
                rawUpdateChecks = if (json.has("updateChecks")) json.get("updateChecks").toString() else null,
                hasReadOnlySource = json.has("updateSource"),
            ),
        )
        val keys = buildSet { json.keys().forEach { add(it) } }
        val isMetaOnlyJson = keys.isNotEmpty() && keys.all { it in META_ONLY_KEYS }
        return when {
            jsonMeta.error != null -> invalidRequest(jsonMeta.error)
            isMetaOnlyJson -> {
                applyMeta(queryMeta)
                applyMeta(jsonMeta)
                onSettingsApplied(null)
                onEmitSettings()
                jsonSettingsResponse(settings.getAll())
            }
            else -> applyFullSettingsJson(bodyText, queryMeta, jsonMeta)
        }
    }

    private fun parseMetaFields(raw: RawMeta): ParsedMeta {
        val webhook = if (raw.hasWebhook) validateWebhookUrl(raw.rawWebhook.orEmpty()) else (null to null)
        val heartbeat = if (raw.hasHeartbeat) validateHeartbeat(raw.rawHeartbeat.orEmpty()) else (null to null)
        val checks = if (raw.hasUpdateChecks) validateUpdateChecks(raw.rawUpdateChecks) else (null to null)
        val error = webhook.second ?: heartbeat.second ?: checks.second
        return ParsedMeta(
            webhook = webhook.first,
            hasWebhook = raw.hasWebhook,
            webhookHeartbeat = heartbeat.first,
            updateChecks = checks.first,
            hasReadOnlyMeta = raw.hasReadOnlySource,
            error = error,
        )
    }

    private fun applyWebhook(meta: ParsedMeta) {
        if (!meta.hasWebhook) return
        val url = meta.webhook.orEmpty()
        settings.webhook = url
        Pusher.url = url.ifEmpty { null }
    }

    private fun resolveUpdateInterval(currentInterval: Int, enabled: Boolean?): Int = when (enabled) {
        true -> if (currentInterval <= 0) AppSettings.DEFAULT_UPDATE_INTERVAL else currentInterval
        false -> 0
        null -> currentInterval
    }

    private fun applyMeta(meta: ParsedMeta) {
        applyWebhook(meta)
        meta.webhookHeartbeat?.let { settings.webhookHeartbeat = it }
        meta.updateChecks?.let {
            settings.updateInterval = resolveUpdateInterval(settings.updateInterval, it)
        }
    }

    private fun applyFullSettingsJson(
        bodyText: String,
        queryMeta: ParsedMeta,
        jsonMeta: ParsedMeta,
    ): NanoHTTPD.Response = try {
        val parsed = AppSettings.SettingsData.fromJson(bodyText, defaults = settings.getAll())
        val effectiveHeartbeat = jsonMeta.webhookHeartbeat ?: queryMeta.webhookHeartbeat ?: parsed.webhookHeartbeat
        val effectiveChecks = jsonMeta.updateChecks ?: queryMeta.updateChecks
        val data = parsed.copy(
            webhookHeartbeat = effectiveHeartbeat,
            updateInterval = resolveUpdateInterval(parsed.updateInterval, effectiveChecks),
        )
        applyWebhook(queryMeta)
        applyWebhook(jsonMeta)
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
        jsonSettingsResponse(data)
    } catch (e: JSONException) {
        invalidRequest(e.message)
    } catch (e: IllegalArgumentException) {
        invalidRequest(e.message)
    }

    private fun validateWebhookUrl(raw: String): Pair<String?, String?> {
        val v = raw.trim()
        if (v.isEmpty()) return "" to null
        val valid = try {
            val uri = URI(v)
            uri.scheme in HTTP_SCHEMES && !uri.host.isNullOrBlank()
        } catch (_: URISyntaxException) {
            false
        }
        return if (valid) v to null else null to "webhook must be a valid http(s) URL"
    }

    private fun validateHeartbeat(raw: String): Pair<Int?, String?> {
        val parsed = raw.trim().toIntOrNull()?.let { AppSettings.normalizeWebhookHeartbeat(it) }
        return if (parsed != null) parsed to null else null to "webhookHeartbeat must be a non-negative integer"
    }

    private fun validateUpdateChecks(raw: String?): Pair<Boolean?, String?> {
        val parsed = PayloadParser.parseFlexibleBoolean(raw)
        return if (parsed != null) parsed to null else null to "updateChecks must be true or false"
    }

    private fun jsonSettingsResponse(data: AppSettings.SettingsData): NanoHTTPD.Response {
        val jsonObj = data.toJSONObject().apply {
            put("updateChecks", data.updateInterval > 0)
            put("updateSource", AppSettings.DEFAULT_UPDATE_SOURCE)
            put("webhook", !Pusher.url.isNullOrBlank())
        }
        Log.d(TAG, "Response OK (JSON)")
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            jsonObj.toString(),
        ).apply { setGzipEncoding(false) }
    }

    private fun invalidRequest(message: String?): NanoHTTPD.Response {
        Log.w(TAG, "Invalid settings request: $message")
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            "text/plain",
            "Invalid request: $message",
        )
    }

    companion object {
        private const val TAG = "SettingsApiHandler"
        private val HTTP_SCHEMES = setOf("http", "https")
        private val META_ONLY_KEYS = setOf("webhook", "webhookHeartbeat", "updateChecks", "updateSource")
    }
}
