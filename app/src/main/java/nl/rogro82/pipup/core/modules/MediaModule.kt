package nl.rogro82.pipup.core.modules

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import fi.iki.elonen.NanoHTTPD
import nl.rogro82.pipup.core.ModuleContext
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

    private val handler = Handler(Looper.getMainLooper())
    private var moduleContext: ModuleContext? = null

    @androidx.annotation.Keep
    internal var warmWebView: WebView? = null

    override fun onEnable(context: ModuleContext) {
        Log.d(TAG, "Media module enabled")
        this.moduleContext = context
        if (context.settings.preWarmWebView) {
            preWarmWebView(context)
        }
    }

    override fun onDisable() {
        Log.d(TAG, "Media module disabled, cleaning up WebView")
        destroyWebView()
        moduleContext = null
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

    /**
     * Refreshes the pre-warm state based on settings.
     */
    fun updatePreWarmState() {
        val context = moduleContext ?: return
        if (context.settings.preWarmWebView) {
            preWarmWebView(context)
        } else {
            destroyWebView()
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
