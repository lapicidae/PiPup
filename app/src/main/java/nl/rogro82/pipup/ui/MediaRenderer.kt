package nl.rogro82.pipup.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.TextureView
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
import android.widget.ImageView
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.*
import nl.rogro82.pipup.core.PayloadParser
import java.io.File
import java.lang.ref.WeakReference

/**
 * Manages media rendering logic for PopupView.
 * Optimized for reliability by creating fresh WebViews and performing aggressive cleanup.
 */
@UnstableApi
class MediaRenderer(
    context: Context,
    private val mainHandler: Handler,
    private val appSettings: AppSettings,
    callback: Callback
) {
    companion object {
        private val DUMMY_CLIENT = WebViewClient()
        private val DUMMY_CHROME = WebChromeClient()
    }

    interface Callback {
        fun notifyReady()
        fun adjustHeights()
        fun isCleanedUp(): Boolean
        fun isReadyCalled(): Boolean
        fun showPlaceholder(error: String?)
        fun onDimensionsUpdated(width: Int, height: Int)
        fun handleWhepRetry(error: String, retryCount: Int)
    }

    private val appContext = context.applicationContext
    private val callbackRef = WeakReference(callback)
    private val callback: Callback? get() = callbackRef.get()

    private val rendererToken = Any()

    private var player: ExoPlayer? = null
    private var videoView: View? = null
    private var webView: WebView? = null
    private var lastMediaError: String? = null

    fun setup(uiContext: Context, frame: FrameLayout, props: PopupProps, retryCount: Int = 0) {
        val media = props.media ?: props.image?.let { PopupProps.Media.Image(it, props.imageWidth ?: 480, cache = true, scale = true) }

        if (media == null) {
            frame.isVisible = false
            cleanup()
            frame.removeAllViews()
            Log.i("MediaRenderer", "No media in props, notifying ready immediately")
            mainHandler.postAtTime({
                callback?.notifyReady() ?: Log.e("MediaRenderer", "Callback is NULL in setup(null)")
            }, rendererToken, SystemClock.uptimeMillis())
            return
        }

        frame.isVisible = true
        val isSameType = if (frame.isNotEmpty()) {
            val child = frame.getChildAt(0)
            (media is PopupProps.Media.Image && child is ImageView) ||
            (media is PopupProps.Media.Bitmap && child is ImageView) ||
            (media is PopupProps.Media.Whep && child is WebView) ||
            (media is PopupProps.Media.Web && child is WebView) ||
            (media is PopupProps.Media.Video && child is TextureView)
        } else false

        if (!isSameType) {
            cleanup()
            frame.removeAllViews()
        }

        when (media) {
            is PopupProps.Media.Image -> renderImage(uiContext, frame, media.uri, media.width, media.cache, media.scale, retryCount)
            is PopupProps.Media.Video -> renderVideo(frame, media.uri, media.width, media.scale, media.udp, retryCount, uiContext)
            is PopupProps.Media.Web -> renderWeb(uiContext, frame, media.uri, media.width, media.height, media.cache, media.scale, retryCount)
            is PopupProps.Media.Whep -> renderWhep(frame, media.uri, media.width, media.height, media.scale, media.videoFit, retryCount)
            is PopupProps.Media.LocalFile -> renderLocalFile(uiContext, frame, media.path, media.width, media.scale)
            is PopupProps.Media.Bitmap -> renderBitmap(uiContext, frame, media.bitmap, media.width, media.scale)
        }
    }

    private fun removeStaleViews(frame: FrameLayout, keepView: View) {
        val stale = mutableListOf<View>()
        for (i in 0 until frame.childCount) {
            val v = frame.getChildAt(i)
            if (v != keepView) stale.add(v)
        }
        for (v in stale) {
            if (v is WebView) {
                (v.parent as? ViewGroup)?.removeView(v)
                v.stopLoading()
                v.webViewClient = DUMMY_CLIENT
                v.webChromeClient = DUMMY_CHROME
            } else if (v is ImageView) {
                try { Glide.with(appContext).clear(v) } catch (_: Exception) {}
            }
            frame.removeView(v)
        }
        if (keepView !is TextureView) {
            player?.stop()
            player?.release()
            player = null
            videoView = null
        }
    }

    private fun cleanupWebView() {
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
                // Use post to destroy after current event loop to avoid native crashes
                wv.post { try { wv.destroy() } catch (_: Exception) {} }
            } catch (e: Exception) {
                Log.d("MediaRenderer", "WebView cleanup error: ${e.message}")
            }
        }
        webView = null
    }

    fun cleanup() {
        mainHandler.removeCallbacksAndMessages(rendererToken)
        player?.let { it.stop(); it.release() }
        player = null
        videoView = null
        cleanupWebView()
    }

    fun fullCleanup(frame: FrameLayout, media: PopupProps.Media?) {
        cleanup()
        try {
            for (i in 0 until frame.childCount) {
                val child = frame.getChildAt(i)
                if (child is ImageView) {
                    Glide.with(appContext).clear(child)
                    child.setImageDrawable(null)
                }
            }
            frame.removeAllViews()
        } catch (e: Exception) {
            Log.d("MediaRenderer", "UI cleanup error: ${e.message}")
        }
        (media as? PopupProps.Media.LocalFile)?.let {
            PayloadParser.deleteFileAsync(it.path)
        }
    }

    private fun renderImage(uiContext: Context, frame: FrameLayout, uri: String, width: Int, cache: Boolean, scale: Boolean, retryCount: Int = 0) {
        renderGlide(uiContext, frame, uri, width, scale, if (cache) DiskCacheStrategy.DATA else DiskCacheStrategy.NONE, !cache, retryCount)
    }

    private fun renderVideo(frame: FrameLayout, uri: String, width: Int, scale: Boolean, udp: Boolean = false, retryCount: Int = 0, videoContext: Context) {
        val tw = if (scale) appContext.getScaledPixels(width) else appContext.dpToPx(width)
        val th = (tw * 9) / 16
        callback?.onDimensionsUpdated(tw, th)
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        player?.let { it.stop(); it.release() }
        player = null
        videoView?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val p = ExoPlayer.Builder(appContext)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 1000, 250, 500).build())
            .build().also { player = it }

        val tv = TextureView(videoContext).also { videoView = it; it.isVisible = false }
        p.setVideoTextureView(tv)
        p.repeatMode = Player.REPEAT_MODE_ONE

        val mediaItem = MediaItem.fromUri(uri)
        if (uri.startsWith("rtsp://", ignoreCase = true)) {
            val mediaSource = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                .setForceUseRtpTcp(!udp)
                .createMediaSource(mediaItem)
            p.setMediaSource(mediaSource)
        } else {
            p.setMediaItem(mediaItem)
        }
        p.prepare()

        p.addListener(VideoPlayerListener(this, WeakReference(frame), tw, th, uri, width, scale, udp, retryCount, videoContext))
        frame.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun renderWeb(uiContext: Context, frame: FrameLayout, uri: String, width: Int, height: Int, cache: Boolean, scale: Boolean, retryCount: Int = 0) {
        val tw = if (scale) appContext.getScaledPixels(width) else appContext.dpToPx(width)
        val th = if (scale) appContext.getScaledPixels(height) else appContext.dpToPx(height)
        callback?.onDimensionsUpdated(tw, th)
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        cleanupWebView()

        Log.d("MediaRenderer", "Creating fresh WebView for Web content: $uri")
        val wv = WebView(uiContext).apply {
            visibility = View.VISIBLE
            setBackgroundColor(Color.TRANSPARENT)
            webView = this
            webViewClient = WebRendererClient(this@MediaRenderer, WeakReference(frame), uri, width, height, cache, scale, retryCount, uiContext)
            webChromeClient = AppWebChromeClient()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = if (cache) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
            }
        }
        frame.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        wv.onResume()
        wv.loadUrl(uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun renderWhep(frame: FrameLayout, uri: String, width: Int, height: Int, scale: Boolean, videoFit: String, retryCount: Int = 0) {
        val finalUri = if (isEmulator()) uri.replace("127.0.0.1", "10.0.2.2").replace("localhost", "10.0.2.2") else uri

        try {
            val html = appContext.assets.open("whep.html").bufferedReader().use { it.readText() }
            val injectedHtml = html.replace("'{{STREAM_URL}}'", "'$finalUri'")
                .replace("object-fit: cover;", "object-fit: $videoFit !important;")

            val tw = if (scale) appContext.getScaledPixels(width) else appContext.dpToPx(width)
            val th = if (scale) appContext.getScaledPixels(height) else appContext.dpToPx(height)
            callback?.onDimensionsUpdated(tw, th)
            frame.layoutParams.width = tw
            frame.layoutParams.height = th

            cleanupWebView()

            Log.d("MediaRenderer", "Creating fresh WebView for WHEP: $finalUri")
            val wv = WebView(frame.context).apply {
                visibility = View.INVISIBLE
                setBackgroundColor(Color.TRANSPARENT)
                webView = this
                addJavascriptInterface(WhepJsBridge(WeakReference(this@MediaRenderer), retryCount), "PiPup")
                webViewClient = WhepWebViewClient(this@MediaRenderer, retryCount)
                webChromeClient = AppWebChromeClient()
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = false
                    useWideViewPort = false
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }
            frame.addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            wv.onResume()
            wv.loadDataWithBaseURL(finalUri, injectedHtml, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            Log.e("MediaRenderer", "Error setting up WHEP: ${e.message}")
            callback?.notifyReady()
        }
    }

    private fun renderLocalFile(uiContext: Context, frame: FrameLayout, path: String, width: Int, scale: Boolean) {
        renderGlide(uiContext, frame, File(path), width, scale, DiskCacheStrategy.NONE, true, 0)
    }

    private fun renderGlide(uiContext: Context, frame: FrameLayout, source: Any, width: Int, scale: Boolean, diskCache: DiskCacheStrategy, skipMemory: Boolean, retryCount: Int) {
        val maxDim = if (scale) appContext.getScaledPixels(1280) else appContext.dpToPx(1280)
        val tw = (if (scale) appContext.getScaledPixels(width) else appContext.dpToPx(width)).coerceAtMost(maxDim)
        callback?.onDimensionsUpdated(tw, 0)
        frame.layoutParams.width = tw
        frame.requestLayout()

        val iv = (if (frame.isNotEmpty()) frame.getChildAt(0) else null) as? ImageView ?: ImageView(uiContext).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            frame.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        // Ensure any previous load on this view is cancelled before starting a new one
        Glide.with(appContext).clear(iv)

        val requestListener = GlideRequestListener(
            this, WeakReference(frame), WeakReference(iv), source, width, scale, diskCache, skipMemory, retryCount, tw, uiContext
        )

        // Standard Glide "no-cache" behavior:
        val effectiveStrategy = if (skipMemory) DiskCacheStrategy.NONE else diskCache

        var builder = Glide.with(appContext)
            .`as`(Drawable::class.java)
            .load(source)
            .diskCacheStrategy(effectiveStrategy)
            .skipMemoryCache(skipMemory)
            .override(tw, Target.SIZE_ORIGINAL)
            .dontAnimate()
            .listener(requestListener)

        if (skipMemory) {
            // Use a signature to ensure Glide handles URL re-loads correctly even if URLs are identical
            builder = builder.signature(ObjectKey(System.currentTimeMillis().toString()))
        }

        builder.into(iv)
    }

    private fun renderBitmap(uiContext: Context, frame: FrameLayout, bitmap: Bitmap, width: Int, scale: Boolean) {
        if (bitmap.isRecycled) { callback?.notifyReady(); return }
        val tw = if (scale) appContext.getScaledPixels(width) else appContext.dpToPx(width)
        val th = (tw * bitmap.height) / bitmap.width
        callback?.onDimensionsUpdated(tw, th)
        frame.layoutParams.width = tw
        frame.layoutParams.height = th
        frame.requestLayout()

        val iv = (if (frame.isNotEmpty()) frame.getChildAt(0) else null) as? ImageView ?: ImageView(uiContext).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            frame.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        iv.setImageBitmap(bitmap)
        removeStaleViews(frame, iv)
        callback?.notifyReady()
    }

    fun startMedia() {
        videoView?.isVisible = true
        player?.play()
    }

    fun updateWhepVideoFit(videoFit: String) {
        webView?.evaluateJavascript("document.getElementById('v').style.objectFit = '$videoFit';", null)
    }

    fun onWhepReady() {
        webView?.let { wv ->
            wv.visibility = View.VISIBLE
            (wv.parent as? FrameLayout)?.let { parent -> removeStaleViews(parent, wv) }
            callback?.notifyReady()
            callback?.adjustHeights()
        }
    }

    fun onWhepError(error: String, retryCount: Int) {
        callback?.handleWhepRetry(error, retryCount)
    }

    internal class VideoPlayerListener(
        renderer: MediaRenderer,
        private val frame: WeakReference<FrameLayout>,
        private val tw: Int,
        private val th: Int,
        private val uri: String,
        private val width: Int,
        private val scale: Boolean,
        private val udp: Boolean,
        private val retryCount: Int,
        uiContext: Context
    ) : Player.Listener {
        private val rendererRef = WeakReference(renderer)
        private val uiContextRef = WeakReference(uiContext)
        private var ready = false

        override fun onPlaybackStateChanged(state: Int) {
            val renderer = rendererRef.get() ?: return
            val f = frame.get() ?: return
            if (!ready && state == Player.STATE_READY) {
                ready = true
                if (renderer.callback?.isReadyCalled() == true || renderer.callback?.isCleanedUp() == true) return
                var finalHeight = th
                renderer.player?.videoFormat?.let { if (it.width > 0) finalHeight = (tw * it.height) / it.width }
                if (finalHeight > 0 && finalHeight != th) {
                    f.layoutParams.height = finalHeight
                    f.requestLayout()
                    renderer.callback?.onDimensionsUpdated(tw, finalHeight)
                }
                renderer.videoView?.let { renderer.removeStaleViews(f, it) }
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val renderer = rendererRef.get() ?: return
            val f = frame.get() ?: return
            val ui = uiContextRef.get() ?: return
            if (!ready) {
                if (retryCount < renderer.appSettings.mediaRetries && renderer.callback?.isCleanedUp() == false) {
                    renderer.mainHandler.postAtTime({
                        val r = rendererRef.get() ?: return@postAtTime
                        if (r.callback?.isCleanedUp() == false) {
                            f.removeAllViews()
                            r.renderVideo(f, uri, width, scale, udp, retryCount + 1, ui)
                        }
                    }, renderer.rendererToken, SystemClock.uptimeMillis() + 1000L * (retryCount + 1))
                    return
                }
                renderer.callback?.showPlaceholder(error.errorCodeName)
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
        }
    }

    internal class WebRendererClient(
        renderer: MediaRenderer,
        private val frame: WeakReference<FrameLayout>,
        private val uri: String,
        private val width: Int,
        private val height: Int,
        private val cache: Boolean,
        private val scale: Boolean,
        private val retryCount: Int,
        uiContext: Context
    ) : WebViewClient() {
        private val rendererRef = WeakReference(renderer)
        private val uiContextRef = WeakReference(uiContext)

        override fun onPageFinished(v: WebView?, u: String?) {
            Log.d("MediaRenderer", "onPageFinished: $u")
            val renderer = rendererRef.get() ?: return
            val f = frame.get() ?: return
            if (renderer.callback?.isReadyCalled() == false && renderer.callback?.isCleanedUp() == false) {
                v?.visibility = View.VISIBLE
                renderer.webView?.let { renderer.removeStaleViews(f, it) }
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) handleWebError(error?.description?.toString() ?: "Unknown")
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(v: WebView?, r: Int, d: String?, u: String?) {
            handleWebError(d ?: "Unknown")
        }

        private fun handleWebError(description: String) {
            val renderer = rendererRef.get() ?: return
            val f = frame.get() ?: return
            val ui = uiContextRef.get() ?: return
            renderer.lastMediaError = description
            if (retryCount < renderer.appSettings.mediaRetries && renderer.callback?.isCleanedUp() == false) {
                renderer.mainHandler.postAtTime({
                    val r = rendererRef.get() ?: return@postAtTime
                    if (r.callback?.isCleanedUp() == false) r.renderWeb(ui, f, uri, width, height, cache, scale, retryCount + 1)
                }, renderer.rendererToken, SystemClock.uptimeMillis() + 1000L * (retryCount + 1))
            } else {
                renderer.callback?.showPlaceholder(description)
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
        }
    }

    internal class WhepWebViewClient(
        renderer: MediaRenderer,
        private val retryCount: Int
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

    internal class GlideRequestListener(
        renderer: MediaRenderer,
        private val frame: WeakReference<FrameLayout>,
        private val iv: WeakReference<ImageView>,
        private val source: Any,
        private val width: Int,
        private val scale: Boolean,
        private val diskCache: DiskCacheStrategy,
        private val skipMemory: Boolean,
        private val retryCount: Int,
        private val tw: Int,
        uiContext: Context
    ) : RequestListener<Drawable> {
        private val rendererRef = WeakReference(renderer)
        private val uiContextRef = WeakReference(uiContext)

        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
            Log.w("MediaRenderer", "Glide load failed: ${e?.message}, model: $model")
            e?.logRootCauses("MediaRenderer")

            val renderer = rendererRef.get() ?: return false
            val f = frame.get() ?: return false
            val ui = uiContextRef.get() ?: return false
            if (retryCount < renderer.appSettings.mediaRetries && renderer.callback?.isCleanedUp() == false) {
                renderer.mainHandler.postAtTime({
                    val r = rendererRef.get() ?: return@postAtTime
                    if (r.callback?.isCleanedUp() == false) {
                        r.renderGlide(ui, f, source, width, scale, diskCache, skipMemory, retryCount + 1)
                    }
                }, renderer.rendererToken, SystemClock.uptimeMillis() + 500L * (retryCount + 1))
                return true
            }
            renderer.callback?.showPlaceholder(renderer.appContext.getString(R.string.media_error_load_failed))
            renderer.callback?.notifyReady()
            return false
        }

        override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean): Boolean {
            val renderer = rendererRef.get() ?: return false
            val f = frame.get() ?: return false
            val imageView = iv.get() ?: return false
            if (renderer.callback?.isReadyCalled() == true || renderer.callback?.isCleanedUp() == true) return false
            if (resource.intrinsicWidth > 0) {
                renderer.callback?.onDimensionsUpdated(tw, (tw * resource.intrinsicHeight) / resource.intrinsicWidth)
            }
            renderer.removeStaleViews(f, imageView)
            renderer.callback?.notifyReady()
            renderer.callback?.adjustHeights()
            return false
        }
    }

    internal class WhepJsBridge(private val rendererRef: WeakReference<MediaRenderer>, private val retryCount: Int) {
        @JavascriptInterface
        fun onMediaPlaying() {
            val renderer = rendererRef.get() ?: return
            renderer.mainHandler.postAtTime({ if (renderer.callback?.isCleanedUp() == false) renderer.onWhepReady() }, renderer.rendererToken, SystemClock.uptimeMillis())
        }
        @JavascriptInterface
        fun onMediaError(error: String) {
            val renderer = rendererRef.get() ?: return
            renderer.mainHandler.postAtTime({ if (renderer.callback?.isCleanedUp() == false) renderer.onWhepError(error, retryCount) }, renderer.rendererToken, SystemClock.uptimeMillis())
        }
    }
}
