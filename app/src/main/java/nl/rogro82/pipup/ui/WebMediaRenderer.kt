package nl.rogro82.pipup.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.AndroidRuntimeException
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.getScaledPixels
import nl.rogro82.pipup.isEmulator
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Handles WebView and WebRTC WHEP stream rendering and lifecycle cleanup inside [MediaRenderer].
 */
@UnstableApi
internal class WebMediaRenderer(private val host: MediaRenderer) {

    internal data class WebClientSpec(
        val web: PopupProps.Media.Web,
        val retryCount: Int,
        val startTimeMs: Long,
    )

    internal data class WhepBridgeSpec(
        val tw: Int,
        val hasExplicitHeight: Boolean,
        val retryCount: Int,
        val startTimeMs: Long,
    )

    internal var webView: WebView? = null
        private set
    internal var lastMediaError: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun renderWeb(uiContext: Context, frame: FrameLayout, web: PopupProps.Media.Web, retryCount: Int = 0) {
        val uri = web.uri
        val (tw, th) = if (host.isFullscreenActive) {
            host.fullscreenFrameSize
        } else {
            val w = if (web.scale) host.appContext.getScaledPixels(web.width) else web.width
            val h = if (web.scale) host.appContext.getScaledPixels(web.height) else web.height
            w to h
        }
        host.callback?.onDimensionsUpdated(tw, th)
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        cleanupWebView()

        val hasOverlay = !web.poster.isNullOrBlank() || host.appSettings.mediaLoadingPlaceholder
        host.imageRenderer.attachPosterOrPlaceholder(uiContext, frame, web.poster, web.posterMode)

        val startTimeMs = SystemClock.elapsedRealtime()
        val client = WebRendererClient(
            this,
            WeakReference(frame),
            WebClientSpec(web, retryCount, startTimeMs),
            uiContext,
        )

        Log.d("MediaRenderer", "Creating fresh WebView for Web content: $uri")
        val wv = WebView(uiContext).apply {
            visibility = View.VISIBLE
            alpha = if (hasOverlay) MediaRenderer.INVISIBLE_SURFACE_ALPHA else 1f
            setBackgroundColor(Color.TRANSPARENT)
            webView = this
            webViewClient = client
            webChromeClient = WebStreamChromeClient(client)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = if (web.cache) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
            }
        }
        frame.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        wv.onResume()
        wv.loadUrl(uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun renderWhep(frame: FrameLayout, whep: PopupProps.Media.Whep, retryCount: Int = 0) {
        val finalUri = if (isEmulator()) {
            whep.uri.replace("127.0.0.1", "10.0.2.2").replace("localhost", "10.0.2.2")
        } else {
            whep.uri
        }

        val injectedHtml = loadWhepHtml(finalUri, whep.videoFit)
        if (injectedHtml == null) {
            host.callback?.notifyReady()
            return
        }

        val (tw, th) = host.resolveStreamFrameDimensions(whep.width, whep.height, whep.scale)
        host.callback?.onDimensionsUpdated(tw, th)
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        cleanupWebView()
        host.imageRenderer.attachPosterOrPlaceholder(frame.context, frame, whep.poster, whep.posterMode)

        try {
            val wv = createWhepWebView(frame, whep, tw, retryCount)
            frame.addView(
                wv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
            wv.onResume()
            wv.loadDataWithBaseURL(finalUri, injectedHtml, "text/html", "UTF-8", null)
        } catch (e: AndroidRuntimeException) {
            Log.e("MediaRenderer", "Error setting up WHEP: ${e.message}")
            host.callback?.notifyReady()
        } catch (e: IllegalStateException) {
            Log.e("MediaRenderer", "Error setting up WHEP: ${e.message}")
            host.callback?.notifyReady()
        }
    }

    fun updateWhepVideoFit(videoFit: String) {
        webView?.evaluateJavascript("document.getElementById('v').style.objectFit = '$videoFit';", null)
    }

    fun cleanupWebView() {
        webView?.let { wv ->
            try {
                Log.d("MediaRenderer", "Strict destruction of WebView")
                wv.stopLoading()
                wv.onPause()
                wv.webViewClient = DUMMY_CLIENT
                wv.webChromeClient = DUMMY_CHROME
                wv.removeJavascriptInterface("PiPup")
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.loadUrl("about:blank")
                wv.post { try { wv.destroy() } catch (_: Exception) {} }
            } catch (e: IllegalStateException) {
                Log.d("MediaRenderer", "WebView cleanup error: ${e.message}")
            } catch (e: AndroidRuntimeException) {
                Log.d("MediaRenderer", "WebView cleanup error: ${e.message}")
            }
        }
        webView = null
    }

    fun detachStaleWebView(wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.webViewClient = DUMMY_CLIENT
        wv.webChromeClient = DUMMY_CHROME
    }

    private fun onWhepReady(elapsedMs: Long) {
        webView?.let { wv ->
            (wv.parent as? FrameLayout)?.let { parent ->
                host.crossfadeRevealStream(parent, wv, elapsedMs)
            }
        }
    }

    private fun onWhepError(error: String, retryCount: Int) {
        host.callback?.handleWhepRetry(error, retryCount)
    }

    private fun onWhepSourceDimensions(frame: FrameLayout?, tw: Int, srcWidth: Int, srcHeight: Int) {
        if (srcWidth <= 0 || srcHeight <= 0 || frame == null) return
        host.updateSourceDimensions(srcWidth, srcHeight)
        if (host.currentCoversScreen) {
            host.applyFullscreenMediaFrameSize(frame)
            host.callback?.adjustHeights()
            return
        }
        val calculatedHeight = (tw * srcHeight) / srcWidth
        if (calculatedHeight > 0 && frame.layoutParams.height != calculatedHeight) {
            frame.layoutParams.height = calculatedHeight
            frame.requestLayout()
            host.callback?.onDimensionsUpdated(tw, calculatedHeight)
            host.callback?.adjustHeights()
        }
    }

    private fun loadWhepHtml(finalUri: String, videoFit: String): String? = try {
        val html = host.appContext.assets.open("whep.html").bufferedReader().use { it.readText() }
        html.replace("'{{STREAM_URL}}'", "'$finalUri'")
            .replace("object-fit: cover;", "object-fit: $videoFit !important;")
    } catch (e: IOException) {
        Log.e("MediaRenderer", "Error loading WHEP template: ${e.message}")
        null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWhepWebView(
        frame: FrameLayout,
        whep: PopupProps.Media.Whep,
        tw: Int,
        retryCount: Int,
    ): WebView {
        val hasOverlay = !whep.poster.isNullOrBlank() || host.appSettings.mediaLoadingPlaceholder
        val startTimeMs = SystemClock.elapsedRealtime()
        Log.d("MediaRenderer", "Creating fresh WebView for WHEP: ${whep.uri}")
        return WebView(frame.context).apply {
            visibility = View.VISIBLE
            alpha = if (hasOverlay) MediaRenderer.INVISIBLE_SURFACE_ALPHA else 1f
            setBackgroundColor(Color.TRANSPARENT)
            webView = this
            addJavascriptInterface(
                WhepJsBridge(
                    WeakReference(this@WebMediaRenderer),
                    WeakReference(frame),
                    WhepBridgeSpec(tw, whep.height != null, retryCount, startTimeMs),
                ),
                "PiPup",
            )
            webViewClient = WhepWebViewClient(host, retryCount)
            webChromeClient = AppWebChromeClient()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = false
                useWideViewPort = false
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
        }
    }

    internal class WebRendererClient(
        webRenderer: WebMediaRenderer,
        private val frame: WeakReference<FrameLayout>,
        private val spec: WebClientSpec,
        uiContext: Context,
    ) : WebViewClient() {
        private val webRendererRef = WeakReference(webRenderer)
        private val uiContextRef = WeakReference(uiContext)
        @Volatile private var videoElementFound = false
        private var revealed = false

        fun onVideoFound() {
            videoElementFound = true
        }

        fun revealWeb() {
            if (revealed) return
            val webRenderer = webRendererRef.get()
            val renderer = webRenderer?.host
            val f = frame.get()
            val wv = webRenderer?.webView
            if (renderer == null || f == null || wv == null) return
            if (renderer.callback?.isCleanedUp() != true) {
                revealed = true
                val elapsed = SystemClock.elapsedRealtime() - spec.startTimeMs
                renderer.crossfadeRevealStream(f, wv, elapsed)
            }
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            view?.evaluateJavascript(VIDEO_WATCH_JS, null)
            val renderer = webRendererRef.get()?.host ?: return
            renderer.mainHandler.postAtTime({
                if (!videoElementFound) {
                    revealWeb()
                }
            }, renderer.rendererToken, SystemClock.uptimeMillis() + POSTER_FADE_CAP_MS)
        }

        override fun onPageFinished(v: WebView?, u: String?) {
            Log.d("MediaRenderer", "onPageFinished: $u")
            if (spec.web.muted) {
                v?.evaluateJavascript(MUTE_JS, null)
            }
            v?.evaluateJavascript(VIDEO_WATCH_JS, null)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) handleWebError(error?.description?.toString() ?: "Unknown")
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(v: WebView?, r: Int, d: String?, u: String?) {
            handleWebError(d ?: "Unknown")
        }

        private fun handleWebError(description: String) {
            val webRenderer = webRendererRef.get()
            val renderer = webRenderer?.host
            val f = frame.get()
            val ui = uiContextRef.get()
            if (renderer == null || f == null || ui == null) return

            webRenderer.lastMediaError = description
            if (spec.retryCount < renderer.appSettings.mediaRetries && renderer.callback?.isCleanedUp() == false) {
                renderer.mainHandler.postAtTime({
                    val wr = webRendererRef.get() ?: return@postAtTime
                    if (wr.host.callback?.isCleanedUp() == false) {
                        wr.renderWeb(ui, f, spec.web, spec.retryCount + 1)
                    }
                }, renderer.rendererToken, SystemClock.uptimeMillis() + WEB_RETRY_DELAY_MS * (spec.retryCount + 1))
            } else {
                renderer.callback?.showPlaceholder(description)
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
        }
    }

    internal class WebStreamChromeClient(
        private val client: WebRendererClient,
    ) : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            request.grant(request.resources)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            when (title) {
                "pipup:videofound" -> client.onVideoFound()
                "pipup:playing", "pipup:novideo" -> client.revealWeb()
            }
        }
    }

    internal class WhepWebViewClient(
        renderer: MediaRenderer,
        private val retryCount: Int,
    ) : WebViewClient() {
        private val rendererRef = WeakReference(renderer)

        override fun onPageFinished(v: WebView?, u: String?) {
            val renderer = rendererRef.get() ?: return
            if (renderer.callback?.isReadyCalled() == false && renderer.callback?.isCleanedUp() == false) {
                renderer.callback?.adjustHeights()
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            val renderer = rendererRef.get() ?: return
            if (request?.isForMainFrame == true) {
                renderer.callback?.handleWhepRetry(error?.description?.toString() ?: "Unknown", retryCount)
            }
        }
    }

    internal class AppWebChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            request.grant(request.resources)
        }
    }

    internal class WhepJsBridge(
        private val webRendererRef: WeakReference<WebMediaRenderer>,
        private val frameRef: WeakReference<FrameLayout>,
        private val spec: WhepBridgeSpec,
    ) {
        @JavascriptInterface
        fun onSourceDimensions(sourceWidth: Int, sourceHeight: Int) {
            val webRenderer = webRendererRef.get()
            val frame = frameRef.get()
            if (spec.hasExplicitHeight || webRenderer == null || frame == null) return
            val host = webRenderer.host
            host.mainHandler.postAtTime({
                if (host.callback?.isCleanedUp() == false) {
                    webRenderer.onWhepSourceDimensions(frame, spec.tw, sourceWidth, sourceHeight)
                }
            }, host.rendererToken, SystemClock.uptimeMillis())
        }

        @JavascriptInterface
        fun onMediaPlaying() {
            val webRenderer = webRendererRef.get() ?: return
            val host = webRenderer.host
            val elapsed = SystemClock.elapsedRealtime() - spec.startTimeMs
            host.mainHandler.postAtTime({
                if (host.callback?.isCleanedUp() == false) {
                    webRenderer.onWhepReady(elapsed)
                }
            }, host.rendererToken, SystemClock.uptimeMillis())
        }

        @JavascriptInterface
        fun onMediaError(error: String) {
            val webRenderer = webRendererRef.get() ?: return
            val host = webRenderer.host
            host.mainHandler.postAtTime({
                if (host.callback?.isCleanedUp() == false) {
                    webRenderer.onWhepError(error, spec.retryCount)
                }
            }, host.rendererToken, SystemClock.uptimeMillis())
        }
    }

    companion object {
        private val DUMMY_CLIENT = WebViewClient()
        private val DUMMY_CHROME = WebChromeClient()
        private const val POSTER_FADE_CAP_MS = 20_000L
        private const val WEB_RETRY_DELAY_MS = 1000L

        private const val VIDEO_WATCH_JS = """
            (function() {
                if (window.__pipupWatch) return;
                window.__pipupWatch = true;
                var done = false;
                function signal(what) {
                    if (done) return;
                    done = true;
                    document.title = 'pipup:' + what;
                }
                function hook(v) {
                    if (v.__pipupHooked) return;
                    v.__pipupHooked = true;
                    if (!done) document.title = 'pipup:videofound';
                    if (!v.paused && v.currentTime > 0 && v.readyState >= 2) { signal('playing'); return; }
                    ['playing', 'timeupdate', 'loadeddata'].forEach(function(ev) {
                        v.addEventListener(ev, function() {
                            if (v.currentTime > 0 || ev === 'playing') signal('playing');
                        });
                    });
                }
                function scan(root) {
                    (root.querySelectorAll ? root.querySelectorAll('video') : []).forEach(hook);
                    (root.querySelectorAll ? root.querySelectorAll('*') : []).forEach(function(el) {
                        if (el.shadowRoot) scan(el.shadowRoot);
                    });
                }
                scan(document);
                new MutationObserver(function() { scan(document); }).observe(
                    document.documentElement, { childList: true, subtree: true });
                setTimeout(function() {
                    if (!done && document.querySelectorAll('video').length === 0) {
                        var shadowVideo = false;
                        try {
                            document.querySelectorAll('*').forEach(function(el) {
                                if (el.shadowRoot && el.shadowRoot.querySelector('video')) shadowVideo = true;
                            });
                        } catch (e) {}
                        if (!shadowVideo) signal('novideo');
                    }
                }, 400);
            })();
        """

        private const val MUTE_JS = """
            (function() {
                function muteAll() {
                    document.querySelectorAll('video,audio').forEach(function(m) {
                        m.muted = true;
                        m.volume = 0;
                    });
                }
                muteAll();
                new MutationObserver(muteAll).observe(document.documentElement, { childList: true, subtree: true });
            })();
        """
    }
}
