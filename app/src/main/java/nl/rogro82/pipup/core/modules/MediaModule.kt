package nl.rogro82.pipup.core.modules

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.ModuleContext
import nl.rogro82.pipup.core.ModuleMenuDefinition
import nl.rogro82.pipup.core.ModuleSettingDefinition
import nl.rogro82.pipup.core.PiPupModule

/**
 * Module responsible for rich media support (WebView, WHEP).
 * Manages background WebView pre-warming to improve notification speed.
 */
class MediaModule : PiPupModule {

    companion object {
        private const val TAG = "MediaModule"
    }

    override val id: String = "media"
    override val name: String = "Rich Media Support"
    override val supportedRoutes: List<String> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private var moduleContext: ModuleContext? = null

    @androidx.annotation.Keep
    internal var warmWebView: WebView? = null

    override fun onEnable(context: ModuleContext) {
        Log.d(TAG, "Media module enabled")
        this.moduleContext = context
        // Always pre-warm when enabled.
        // If in PERFORMANCE mode, this happens at boot.
        // If in ECO mode, this happens on the first request and stays warm until onIdle.
        preWarmWebView(context)
    }

    override fun onDisable() {
        Log.d(TAG, "Media module disabled, cleaning up WebView")
        destroyWebView()
        moduleContext = null
    }

    override fun onIdle() {
        Log.d(TAG, "Media module idle, destroying warm WebView to save RAM")
        destroyWebView()
    }

    override fun getSettingsMetadata(): List<ModuleSettingDefinition> = listOf(
        ModuleSettingDefinition(
            key = "resource_mode",
            type = nl.rogro82.pipup.core.SettingType.STRING_SELECT,
            labelRes = R.string.settings_resource_mode,
            defaultValue = "eco",
            options = mapOf(
                "eco" to R.string.resource_mode_eco,
                "performance" to R.string.resource_mode_performance
            ),
            category = nl.rogro82.pipup.core.SettingCategory.PERFORMANCE
        )
    )

    override fun getSettingsMenu(): ModuleMenuDefinition? {
        // Only show a dedicated menu tab if there are settings for the protected area (category == null).
        if (getSettingsMetadata().none { it.category == null }) {
            return null
        }

        return ModuleMenuDefinition(
            iconRes = R.drawable.ic_module_rmedia,
            labelRes = R.string.settings_module_media,
            priority = 75
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun preWarmWebView(context: ModuleContext) {
        handler.post {
            try {
                if (warmWebView != null) return@post

                val wv = WebView(context.androidContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = WebViewClient()
                    loadUrl("about:blank")
                }
                warmWebView = wv
                Log.d(TAG, "WebView engine pre-warmed (ref: ${wv.hashCode()})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-warm WebView: ${e.message}")
            }
        }
    }

    private fun destroyWebView() {
        warmWebView?.let { wv ->
            wv.post {
                try {
                    Log.d(TAG, "Destroying pre-warmed WebView")
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {}
            }
            warmWebView = null
        }
    }

    override fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? = null

    override fun augmentState(state: MutableMap<String, Any?>) {
        state["media_engine"] = mapOf(
            "active" to true,
            "preWarmed" to (warmWebView != null)
        )
    }
}
