package nl.rogro82.pipup.service

import android.content.Context
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.BuildConfig
import nl.rogro82.pipup.R
import nl.rogro82.pipup.colorToHex
import nl.rogro82.pipup.getLocalizedContext

/**
 * Renders and caches the theme-aware HTML status landing page and SVG favicon served by [PipUpService].
 */
internal class LandingPageRenderer(
    private val context: Context,
    private val settings: AppSettings,
) {
    private data class LandingPalette(
        val bg: String,
        val cardBg: String,
        val primary: String,
        val text: String,
        val textSecondary: String,
        val accent: String,
        val outline: String,
        val statusGreen: String,
    )

    @Volatile
    private var cachedHtml: String? = null

    fun invalidateCache() {
        cachedHtml = null
    }

    fun renderResponse(): NanoHTTPD.Response {
        val html = cachedHtml ?: buildLandingHtml().also { cachedHtml = it }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", html).apply {
            setGzipEncoding(false)
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            addHeader("Pragma", "no-cache")
            addHeader("Expires", "0")
        }
    }

    fun renderFaviconResponse(): NanoHTTPD.Response {
        return try {
            val stream = context.assets.open("logo.svg")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "image/svg+xml",
                stream,
                stream.available().toLong(),
            )
        } catch (_: Exception) {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "")
        }
    }

    private fun loadLandingLogoSvg(): String = try {
        context.assets.open("logo.svg").bufferedReader().use { it.readText() }
            .replace(Regex("<\\?xml.*?\\?>"), "")
            .replace(Regex("<!DOCTYPE.*?>", RegexOption.DOT_MATCHES_ALL), "")
    } catch (_: Exception) {
        ""
    }

    private fun resolveLandingPalette(): LandingPalette {
        val themedContext = context.getLocalizedContext(settings.language, settings.appTheme)
        return LandingPalette(
            bg = themedContext.colorToHex(R.color.colorSurface),
            cardBg = themedContext.colorToHex(R.color.colorSurfaceVariant),
            primary = themedContext.colorToHex(R.color.colorPrimary),
            text = themedContext.colorToHex(R.color.colorOnSurface),
            textSecondary = themedContext.colorToHex(R.color.colorOnSurfaceVariant),
            accent = themedContext.colorToHex(R.color.colorOnPrimaryContainer),
            outline = themedContext.colorToHex(R.color.colorOutline),
            statusGreen = themedContext.colorToHex(R.color.status_green),
        )
    }

    private fun buildLandingHtml(): String {
        val appName = context.getString(R.string.app_name)
        val versionName = BuildConfig.VERSION_NAME
        val logoSvg = loadLandingLogoSvg()
        val p = resolveLandingPalette()
        Log.d(TAG, "Generating landing page. Theme: ${settings.appTheme}, Resolved BG: ${p.bg}")
        val headingHtml = if (logoSvg.isNotEmpty()) {
            "<div class=\"logo-container\">$logoSvg</div>"
        } else {
            "<h1>$appName</h1>"
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <link rel="icon" type="image/svg+xml" href="/favicon.svg">
                <title>${context.getString(R.string.server_landing_title, appName)}</title>
                <style>
                    body { font-family: sans-serif; background-color: ${p.bg}; color: ${p.text}; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                    .card { background-color: ${p.cardBg}; padding: 2.5rem; border-radius: 20px; box-shadow: 0 10px 40px rgba(0,0,0,0.4); text-align: center; max-width: 450px; border: 1px solid ${p.outline}; }
                    .logo-container { width: 120px; height: auto; margin: 0 auto 1.5rem; }
                    .logo-container svg { width: 100%; height: auto; display: block; }
                    .logo-container .currentColor { color: ${p.primary} !important; }
                    h1 { color: ${p.primary}; margin: 0.5rem 0; font-size: 2.5rem; letter-spacing: -1px; }
                    p { color: ${p.textSecondary}; line-height: 1.6; font-size: 1.1rem; }
                    code { background-color: ${p.bg}; padding: 2px 6px; border-radius: 4px; color: ${p.accent}; font-family: monospace; border: 1px solid ${p.outline}; }
                    .status { display: inline-flex; align-items: center; padding: 6px 14px; background-color: ${p.bg}; color: ${p.statusGreen}; border-radius: 20px; font-size: 0.85rem; font-weight: bold; margin-bottom: 1rem; border: 1px solid ${p.statusGreen}; }
                    .status::before { content: ""; width: 8px; height: 8px; background-color: ${p.statusGreen}; border-radius: 50%; margin-right: 8px; box-shadow: 0 0 8px ${p.statusGreen}; }
                    .version { font-size: 0.8rem; color: ${p.textSecondary}; margin-top: 2.5rem; border-top: 1px solid ${p.outline}; paddingTop: 1.5rem; }
                    a { color: ${p.accent}; text-decoration: none; font-weight: 500; }
                    a:hover { text-decoration: underline; color: ${p.primary}; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="status">${context.getString(R.string.server_landing_status)}</div>
                    $headingHtml
                    <p>${context.getString(R.string.server_landing_description)}</p>
                    <p><a href="https://github.com/lapicidae/PiPup" target="_blank">${context.getString(R.string.server_landing_docs)}</a></p>
                    <div class="version">
                        $appName v$versionName<br>
                        ${context.getString(R.string.server_landing_running_on, Build.MODEL, Build.VERSION.RELEASE)}
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "LandingPageRenderer"
    }
}
