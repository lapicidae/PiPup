package nl.rogro82.pipup.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
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
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.PayloadParser
import nl.rogro82.pipup.getScaledPixels
import nl.rogro82.pipup.isEmulator
import java.io.File
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

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
    /** Computed ExoPlayer buffer durations in milliseconds. */
    internal data class BufferDurations(
        val minMs: Int,
        val maxMs: Int,
        val startMs: Int,
        val rebufferMs: Int
    )

    /** Shared WebView clients, animation constants, and aspect-ratio calculation helpers. */
    companion object {
        private val DUMMY_CLIENT = WebViewClient()
        private val DUMMY_CHROME = WebChromeClient()
        private val CROSSFADE_INTERPOLATOR = LinearInterpolator()
        private const val CROSSFADE_DURATION_MS = 350L
        private const val POSTER_FADE_CAP_MS = 20_000L
        private const val MIN_BUFFER_FLOOR_MS = 1_000
        private const val MAX_BUFFER_FLOOR_MS = 5_000
        private const val VIDEO_RETRY_DELAY_MS = 1000L
        private const val WEB_RETRY_DELAY_MS = 1000L
        private const val GLIDE_RETRY_DELAY_MS = 500L
        private const val INVISIBLE_SURFACE_ALPHA = 0.01f
        private const val DEFAULT_MIN_BUF_MS = 500
        private const val DEFAULT_MAX_BUF_MS = 1000
        private const val DEFAULT_START_BUF_MS = 250
        private const val DEFAULT_REBUFFER_MS = 500
        private const val MAX_IMAGE_HEIGHT_RATIO = 0.40

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

        /**
         * Computes ExoPlayer buffer durations for [bufferMs] as [BufferDurations],
         * or null when absent/<=0 so defaults apply. Clamped to 100..10000 ms.
         */
        internal fun lowLatencyBufferDurations(bufferMs: Int?): BufferDurations? {
            val start = bufferMs?.takeIf { it > 0 }?.coerceIn(
                PopupProps.BUFFER_MS_MIN,
                PopupProps.BUFFER_MS_MAX
            ) ?: return null
            val rebuffer = start * 2
            val min = maxOf(rebuffer, MIN_BUFFER_FLOOR_MS)
            return BufferDurations(min, maxOf(min * 2, MAX_BUFFER_FLOOR_MS), start, rebuffer)
        }

        /**
         * Computes display height for [targetWidth] preserving the stream's display aspect ratio
         * including non-square [pixelWidthHeightRatio] on anamorphic streams.
         */
        internal fun heightForDisplayAspect(
            targetWidth: Int,
            width: Int,
            height: Int,
            pixelWidthHeightRatio: Float
        ): Int {
            val ratio = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
            val displayWidth = width * ratio
            return (targetWidth * height / displayWidth).roundToInt()
        }

        /**
         * Computes target dimensions `(width, height)` for a media source of `[sourceW] x [sourceH]`
         * inside a fullscreen available box `[availW] x [availH]`, optionally bounded by explicit
         * payload dimensions `[requestedW]` and `[requestedH]`, preserving display aspect ratio
         * (including `[pixelWidthHeightRatio]`). When `[upscale]` is false, dimensions are never scaled
         * beyond the native display size (`scale <= 1.0f`).
         */
        internal fun fitInFullscreenBox(
            availW: Int,
            availH: Int,
            sourceW: Int,
            sourceH: Int,
            pixelWidthHeightRatio: Float = 1f,
            upscale: Boolean = false,
            requestedW: Int? = null,
            requestedH: Int? = null
        ): Pair<Int, Int> {
            val safeAvailW = availW.coerceAtLeast(1)
            val safeAvailH = availH.coerceAtLeast(1)
            val boxW = requestedW?.takeIf { it > 0 }?.let { minOf(safeAvailW, it) } ?: safeAvailW
            val boxH = requestedH?.takeIf { it > 0 }?.let { minOf(safeAvailH, it) } ?: safeAvailH
            if (sourceW <= 0 || sourceH <= 0) {
                return fitFallbackAspect(boxW, boxH, requestedW != null, requestedH != null)
            }
            val ratio = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
            val nativeW = (sourceW * ratio).roundToInt().coerceAtLeast(1)
            val nativeH = sourceH.coerceAtLeast(1)
            val rawScale = minOf(boxW.toFloat() / nativeW, boxH.toFloat() / nativeH)
            val effectiveScale = if (upscale) rawScale else minOf(1f, rawScale)
            val fittedW = (nativeW * effectiveScale).roundToInt().coerceIn(1, boxW)
            val fittedH = (nativeH * effectiveScale).roundToInt().coerceIn(1, boxH)
            return fittedW to fittedH
        }

        private fun fitFallbackAspect(
            boxW: Int,
            boxH: Int,
            hasRequestedW: Boolean,
            hasRequestedH: Boolean,
        ): Pair<Int, Int> = when {
            hasRequestedW && hasRequestedH -> boxW to boxH
            hasRequestedW -> {
                val aspectH = (boxW * PopupProps.ASPECT_16_9_HEIGHT) / PopupProps.ASPECT_16_9_WIDTH
                val h = aspectH.coerceIn(1, boxH)
                val w = if (h < aspectH) {
                    ((h * PopupProps.ASPECT_16_9_WIDTH) / PopupProps.ASPECT_16_9_HEIGHT).coerceIn(1, boxW)
                } else {
                    boxW
                }
                w to h
            }
            hasRequestedH -> {
                val aspectW = (boxH * PopupProps.ASPECT_16_9_WIDTH) / PopupProps.ASPECT_16_9_HEIGHT
                val w = aspectW.coerceIn(1, boxW)
                val h = if (w < aspectW) {
                    ((w * PopupProps.ASPECT_16_9_HEIGHT) / PopupProps.ASPECT_16_9_WIDTH).coerceIn(1, boxH)
                } else {
                    boxH
                }
                w to h
            }
            else -> boxW to boxH
        }
    }

    /** Host callbacks invoked by [MediaRenderer] during media load, resize, and playback. */
    interface Callback {
        /** Notifies the host that media is ready (or skipped) so the popup can be presented. */
        fun notifyReady()

        /** Requests the host to recalculate text and scroll heights after media layout changes. */
        fun adjustHeights()

        /** Returns true if the hosting popup view has already been cleaned up. */
        fun isCleanedUp(): Boolean

        /** Returns true if [notifyReady] has already been invoked for the current popup. */
        fun isReadyCalled(): Boolean

        /** Displays an error placeholder state with optional [error] detail text. */
        fun showPlaceholder(error: String?)

        /** Reports updated media dimensions ([width] x [height]) in pixels to the host. */
        fun onDimensionsUpdated(width: Int, height: Int)

        /** Schedules a WHEP connection retry or falls back to error state after [error]. */
        fun handleWhepRetry(error: String, retryCount: Int)

        /** Reports elapsed time in milliseconds until the first stream frame rendered. */
        fun onFirstFrameRendered(firstFrameMs: Long)
    }

    private val appContext = context.applicationContext
    private val callbackRef = WeakReference(callback)
    private val callback: Callback? get() = callbackRef.get()

    private val rendererToken = Any()

    private var player: ExoPlayer? = null
    private var videoView: View? = null
    private var webView: WebView? = null
    private var lastMediaError: String? = null
    private var mediaStarted: Boolean = false
    internal var currentCoversScreen: Boolean = false
        private set
    internal var currentFullscreenUpscale: Boolean = false
        private set
    internal var currentRequestedMaxWidth: Int? = null
        private set
    internal var currentRequestedMaxHeight: Int? = null
        private set
    private var currentTargetWidth: Int = 0
    private var currentTargetHeight: Int = 0
    internal var lastSourceWidth: Int = 0
    internal var lastSourceHeight: Int = 0
    internal var lastSourcePixelRatio: Float = 1f

    private val isFullscreenActive: Boolean
        get() = currentCoversScreen && currentTargetWidth > 0 && currentTargetHeight > 0

    private fun updateSourceDimensions(width: Int, height: Int, pixelRatio: Float = 1f) {
        lastSourceWidth = width
        lastSourceHeight = height
        lastSourcePixelRatio = pixelRatio
    }

    private fun resetSourceDimensions() {
        updateSourceDimensions(0, 0, 1f)
    }

    private fun resolveRequestedCaps(media: PopupProps.Media?) {
        if (media == null) {
            currentRequestedMaxWidth = null
            currentRequestedMaxHeight = null
            return
        }
        val (scale, rawW, rawH) = when (media) {
            is PopupProps.Media.Video -> Triple(
                media.scale,
                media.width.takeIf { media.explicitWidth },
                media.height
            )
            is PopupProps.Media.Image -> Triple(
                media.scale,
                media.width.takeIf { media.explicitWidth },
                null
            )
            is PopupProps.Media.Web -> Triple(
                media.scale,
                media.width.takeIf { media.explicitWidth },
                media.height.takeIf { media.explicitHeight }
            )
            is PopupProps.Media.Whep -> Triple(
                media.scale,
                media.width.takeIf { media.explicitWidth },
                media.height
            )
            is PopupProps.Media.LocalFile -> Triple(
                media.scale,
                media.width.takeIf { media.explicitWidth },
                null
            )
            is PopupProps.Media.Bitmap -> Triple(media.scale, null, null)
        }
        currentRequestedMaxWidth = rawW?.let { if (scale) appContext.getScaledPixels(it) else it }
        currentRequestedMaxHeight = rawH?.let { if (scale) appContext.getScaledPixels(it) else it }
    }

    private fun fullscreenBoundingBox(): Pair<Int, Int> {
        val boxW = currentRequestedMaxWidth?.takeIf { it > 0 }
            ?.let { minOf(currentTargetWidth, it) }
            ?: currentTargetWidth
        val boxH = currentRequestedMaxHeight?.takeIf { it > 0 }
            ?.let { minOf(currentTargetHeight, it) }
            ?: currentTargetHeight
        return boxW to boxH
    }

    private fun resolveFullscreenFrameSize(): Pair<Int, Int> {
        return fitInFullscreenBox(
            availW = currentTargetWidth,
            availH = currentTargetHeight,
            sourceW = lastSourceWidth,
            sourceH = lastSourceHeight,
            pixelWidthHeightRatio = lastSourcePixelRatio,
            upscale = currentFullscreenUpscale,
            requestedW = currentRequestedMaxWidth,
            requestedH = currentRequestedMaxHeight
        )
    }

    private fun configureImageView(uiContext: Context, frame: FrameLayout, isFullscreen: Boolean): ImageView {
        val iv = (if (frame.isNotEmpty()) frame.getChildAt(0) else null) as? ImageView
            ?: ImageView(uiContext).apply { frame.addView(this) }
        iv.adjustViewBounds = !isFullscreen
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            if (isFullscreen) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        return iv
    }

    /** Initializes and renders the effective media declared in [props] into [frame]. */
    fun setup(
        uiContext: Context,
        frame: FrameLayout,
        props: PopupProps,
        retryCount: Int = 0,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ) {
        if (retryCount == 0) {
            mediaStarted = false
            resetSourceDimensions()
        }
        val media = props.resolveEffectiveMedia()
        currentCoversScreen = props.coversScreen
        currentFullscreenUpscale = props.fullscreenUpscale ?: appSettings.fullscreenUpscale
        resolveRequestedCaps(media)
        if (targetWidth > 0) currentTargetWidth = targetWidth
        if (targetHeight > 0) currentTargetHeight = targetHeight

        if (media == null) {
            handleNullMedia(frame)
            return
        }

        frame.isVisible = true
        if (!isReusableMediaChild(media, frame)) {
            cleanup()
            frame.removeAllViews()
        }

        when (media) {
            is PopupProps.Media.Image -> renderImage(
                uiContext,
                frame,
                media.uri,
                media.width,
                media.cache,
                media.scale,
                retryCount,
            )
            is PopupProps.Media.Video -> renderVideo(frame, media, retryCount, uiContext)
            is PopupProps.Media.Web -> renderWeb(uiContext, frame, media, retryCount)
            is PopupProps.Media.Whep -> renderWhep(frame, media, retryCount)
            is PopupProps.Media.LocalFile -> renderLocalFile(uiContext, frame, media.path, media.width, media.scale)
            is PopupProps.Media.Bitmap -> renderBitmap(uiContext, frame, media.bitmap, media.width, media.scale)
        }
    }

    private fun handleNullMedia(frame: FrameLayout) {
        frame.isVisible = false
        cleanup()
        frame.removeAllViews()
        Log.i("MediaRenderer", "No media in props, notifying ready immediately")
        val notify: () -> Unit = {
            callback?.notifyReady() ?: Log.e("MediaRenderer", "Callback is NULL in setup(null)")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notify()
        } else {
            mainHandler.postAtTime(notify, rendererToken, SystemClock.uptimeMillis())
        }
    }

    private fun isReusableMediaChild(media: PopupProps.Media, frame: FrameLayout): Boolean {
        if (!frame.isNotEmpty()) return false
        val child = frame.getChildAt(0)
        return when (media) {
            is PopupProps.Media.Image, is PopupProps.Media.Bitmap -> child is ImageView
            is PopupProps.Media.Whep, is PopupProps.Media.Web -> child is WebView
            is PopupProps.Media.Video -> child is TextureView
            is PopupProps.Media.LocalFile -> false
        }
    }

    /**
     * Synchronizes fullscreen and upscaling flags from updated [props] without reloading media.
     */
    fun updateFullscreenProps(props: PopupProps) {
        currentCoversScreen = props.coversScreen
        currentFullscreenUpscale = props.fullscreenUpscale ?: appSettings.fullscreenUpscale
        resolveRequestedCaps(props.resolveEffectiveMedia())
    }

    /**
     * Updates the available fullscreen media bounds and resizes [frame] accordingly.
     */
    fun updateFullscreenBounds(frame: FrameLayout, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        currentTargetWidth = width
        currentTargetHeight = height
        applyFullscreenMediaFrameSize(frame)
    }

    internal fun applyFullscreenMediaFrameSize(frame: FrameLayout) {
        val availW = currentTargetWidth
        val availH = currentTargetHeight
        if (availW <= 0 || availH <= 0) return
        val (fittedW, fittedH) = resolveFullscreenFrameSize()
        if (frame.layoutParams.width != fittedW || frame.layoutParams.height != fittedH) {
            frame.layoutParams.width = fittedW
            frame.layoutParams.height = fittedH
            frame.requestLayout()
            callback?.onDimensionsUpdated(fittedW, fittedH)
        }
        videoView?.let { tv ->
            val lp = tv.layoutParams as? FrameLayout.LayoutParams
            val needsMatchParent = lp != null && (
                lp.width != FrameLayout.LayoutParams.MATCH_PARENT ||
                    lp.height != FrameLayout.LayoutParams.MATCH_PARENT
                )
            if (needsMatchParent) {
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.CENTER
                tv.requestLayout()
            }
        }
    }

    private fun createLoadingPlaceholder(context: Context): View {
        val container = FrameLayout(context).apply {
            tag = "pipup_loading_placeholder"
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Static watermark banner in the background
        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_banner)
            alpha = 0.20f
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(
            iv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        // Circular loading indicator centered on top
        val indicatorSize = (48 * context.resources.displayMetrics.density).toInt()
        val primaryColor = ContextCompat.getColor(context, R.color.colorPrimary)
        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(primaryColor)
        }
        container.addView(progressBar, FrameLayout.LayoutParams(indicatorSize, indicatorSize, Gravity.CENTER))

        return container
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
            } catch (e: IllegalStateException) {
                Log.d("MediaRenderer", "WebView cleanup error: ${e.message}")
            } catch (e: android.util.AndroidRuntimeException) {
                Log.d("MediaRenderer", "WebView cleanup error: ${e.message}")
            }
        }
        webView = null
    }

    /** Releases active ExoPlayer, WebView, and pending handler callbacks. */
    fun cleanup() {
        mainHandler.removeCallbacksAndMessages(rendererToken)
        player?.let { it.stop(); it.release() }
        player = null
        videoView = null
        resetSourceDimensions()
        cleanupWebView()
    }

    /** Clears all child media views, Glide requests, active players, and temporary local files. */
    fun fullCleanup(frame: FrameLayout, media: PopupProps.Media?) {
        val runnable = Runnable {
            mediaStarted = false
            cleanup()
            try {
                for (i in 0 until frame.childCount) {
                    val child = frame.getChildAt(i)
                    if (child is ImageView) {
                        try {
                            Glide.with(appContext).clear(child)
                            child.setImageDrawable(null)
                        } catch (_: Exception) {}
                    }
                }
                frame.removeAllViews()
            } catch (e: IllegalStateException) {
                Log.d("MediaRenderer", "UI cleanup error: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Log.d("MediaRenderer", "UI cleanup error: ${e.message}")
            }
            (media as? PopupProps.Media.LocalFile)?.let {
                PayloadParser.deleteFileAsync(it.path)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }

    private fun renderImage(
        uiContext: Context,
        frame: FrameLayout,
        uri: String,
        width: Int,
        cache: Boolean,
        scale: Boolean,
        retryCount: Int = 0,
    ) {
        val strategy = if (cache) DiskCacheStrategy.DATA else DiskCacheStrategy.NONE
        renderGlide(uiContext, frame, uri, width, scale, strategy, !cache, retryCount)
    }

    private fun resolvePosterScaleType(posterMode: String?): ImageView.ScaleType {
        return when (PopupProps.parsePosterMode(posterMode)) {
            PopupProps.PosterMode.FIT -> ImageView.ScaleType.FIT_CENTER
            PopupProps.PosterMode.STRETCH -> ImageView.ScaleType.FIT_XY
            PopupProps.PosterMode.CENTER -> ImageView.ScaleType.CENTER
            PopupProps.PosterMode.FILL, null -> ImageView.ScaleType.CENTER_CROP
        }
    }

    private fun attachPosterOrPlaceholder(
        uiContext: Context,
        frame: FrameLayout,
        posterUrl: String?,
        posterMode: String?
    ) {
        if (!posterUrl.isNullOrBlank()) {
            val iv = ImageView(uiContext).apply {
                scaleType = resolvePosterScaleType(posterMode)
                setBackgroundColor(Color.TRANSPARENT)
            }
            frame.addView(
                iv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
            Log.d("PiPup-Media", "Starting poster load via Glide CustomTarget: $posterUrl")
            Glide.with(appContext)
                .asDrawable()
                .load(posterUrl)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        if (callback?.isCleanedUp() == true) return
                        iv.setImageDrawable(resource)
                        callback?.notifyReady()
                        callback?.adjustHeights()
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        iv.setImageDrawable(null)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        Log.w("PiPup-Media", "Poster load failed via CustomTarget: $posterUrl")
                        if (appSettings.mediaLoadingPlaceholder && callback?.isCleanedUp() == false) {
                            val placeholderView = createLoadingPlaceholder(uiContext)
                            frame.addView(
                                placeholderView,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    Gravity.CENTER
                                )
                            )
                            frame.removeView(iv)
                            callback?.notifyReady()
                            callback?.adjustHeights()
                        }
                    }
                })
        } else if (appSettings.mediaLoadingPlaceholder) {
            val placeholderView = createLoadingPlaceholder(uiContext)
            frame.addView(
                placeholderView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
            callback?.notifyReady()
            callback?.adjustHeights()
        }
    }

    internal fun crossfadeRevealStream(frame: FrameLayout, streamView: View, elapsedMs: Long) {
        if (callback?.isCleanedUp() == true) return
        streamView.isVisible = true
        var hasOverlay = false
        for (i in 0 until frame.childCount) {
            val child = frame.getChildAt(i)
            if (child != streamView) {
                hasOverlay = true
                child.animate()
                    .alpha(0f)
                    .setDuration(CROSSFADE_DURATION_MS)
                    .setInterpolator(CROSSFADE_INTERPOLATOR)
                    .withEndAction {
                        if (child is ImageView) {
                            try { Glide.with(appContext).clear(child) } catch (_: Exception) {}
                        }
                        frame.removeView(child)
                    }
                    .start()
            }
        }
        if (hasOverlay) {
            streamView.animate()
                .alpha(1f)
                .setDuration(CROSSFADE_DURATION_MS)
                .setInterpolator(CROSSFADE_INTERPOLATOR)
                .start()
        } else {
            streamView.alpha = 1f
        }
        callback?.onFirstFrameRendered(elapsedMs)
        callback?.notifyReady()
        callback?.adjustHeights()
    }

    private fun renderVideo(
        frame: FrameLayout,
        video: PopupProps.Media.Video,
        retryCount: Int = 0,
        videoContext: Context
    ) {
        val (tw, th) = resolveStreamFrameDimensions(video.width, video.height, video.scale)
        callback?.onDimensionsUpdated(tw, th)
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        player?.let { it.stop(); it.release() }
        player = null
        videoView?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val bufferMs = video.bufferMs ?: appSettings.defaultBufferMs.takeIf { it > 0 }
        val p = ExoPlayer.Builder(appContext)
            .setLoadControl(buildVideoLoadControl(bufferMs))
            .build().also { player = it }

        val tv = TextureView(videoContext).also {
            videoView = it
            // Use 0.01f (> 0f) so Android's RenderNode does not skip TextureView.draw(),
            // ensuring SurfaceTexture is created while remaining invisible during buffering.
            it.alpha = INVISIBLE_SURFACE_ALPHA
            it.isVisible = false
        }
        configurePlayerAudio(p, video.muted)
        p.setVideoTextureView(tv)
        p.repeatMode = Player.REPEAT_MODE_ONE
        preparePlayerMedia(p, video.uri, video.udp)

        p.addListener(VideoPlayerListener(this, WeakReference(frame), tw, th, video, retryCount, videoContext))
        attachPosterOrPlaceholder(videoContext, frame, video.poster, video.posterMode)
        frame.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        if (mediaStarted) {
            tv.isVisible = true
            p.play()
        }
    }

    private fun resolveStreamFrameDimensions(width: Int, height: Int?, scale: Boolean): Pair<Int, Int> {
        if (isFullscreenActive) return resolveFullscreenFrameSize()
        val w = if (scale) appContext.getScaledPixels(width) else width
        val h = height?.let { if (scale) appContext.getScaledPixels(it) else it }
            ?: ((w * PopupProps.ASPECT_16_9_HEIGHT) / PopupProps.ASPECT_16_9_WIDTH)
        return w to h
    }

    private fun buildVideoLoadControl(bufferMs: Int?): DefaultLoadControl {
        val buf = lowLatencyBufferDurations(bufferMs)
        return if (buf != null) {
            Log.d(
                "PiPup-Media",
                "Configuring ExoPlayer LoadControl with custom bufferMs=$bufferMs " +
                    "(minBuf=${buf.minMs}, maxBuf=${buf.maxMs}, " +
                    "playbackBuf=${buf.startMs}, rebufferBuf=${buf.rebufferMs})",
            )
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(buf.minMs, buf.maxMs, buf.startMs, buf.rebufferMs)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            Log.d("PiPup-Media", "Configuring ExoPlayer LoadControl with defaults (500, 1000, 250, 500)")
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(DEFAULT_MIN_BUF_MS, DEFAULT_MAX_BUF_MS, DEFAULT_START_BUF_MS, DEFAULT_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
    }

    private fun configurePlayerAudio(p: ExoPlayer, muted: Boolean) {
        if (muted) {
            // Disable audio entirely: avoids audio-codec failures (e.g. RTSP Opus) and keeps TV audio untouched.
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            p.volume = 0f
        } else {
            p.volume = 1f
        }
    }

    private fun preparePlayerMedia(p: ExoPlayer, uri: String, udp: Boolean) {
        val mediaItem = MediaItem.fromUri(uri)
        if (uri.startsWith("rtsp://", ignoreCase = true) || uri.startsWith("rtsps://", ignoreCase = true)) {
            val mediaSource = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                .setForceUseRtpTcp(!udp)
                .createMediaSource(mediaItem)
            p.setMediaSource(mediaSource)
        } else {
            p.setMediaItem(mediaItem)
        }
        p.prepare()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun renderWeb(uiContext: Context, frame: FrameLayout, web: PopupProps.Media.Web, retryCount: Int = 0) {
        val uri = web.uri
        val width = web.width
        val height = web.height
        val cache = web.cache
        val scale = web.scale
        val poster = web.poster

        val (tw, th) = if (isFullscreenActive) {
            resolveFullscreenFrameSize()
        } else {
            val w = if (scale) appContext.getScaledPixels(width) else width
            val h = if (scale) appContext.getScaledPixels(height) else height
            w to h
        }
        callback?.onDimensionsUpdated(tw, th)
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        cleanupWebView()

        val hasOverlay = !poster.isNullOrBlank() || appSettings.mediaLoadingPlaceholder
        attachPosterOrPlaceholder(uiContext, frame, poster, web.posterMode)

        val startTimeMs = SystemClock.elapsedRealtime()
        val client = WebRendererClient(this, WeakReference(frame), web, retryCount, uiContext, startTimeMs)

        Log.d("MediaRenderer", "Creating fresh WebView for Web content: $uri")
        val wv = WebView(uiContext).apply {
            visibility = View.VISIBLE
            alpha = if (hasOverlay) 0.01f else 1f
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
                cacheMode = if (cache) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
            }
        }
        frame.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        wv.onResume()
        wv.loadUrl(uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun renderWhep(frame: FrameLayout, whep: PopupProps.Media.Whep, retryCount: Int = 0) {
        val finalUri = if (isEmulator()) {
            whep.uri.replace("127.0.0.1", "10.0.2.2").replace("localhost", "10.0.2.2")
        } else {
            whep.uri
        }

        val injectedHtml = loadWhepHtml(finalUri, whep.videoFit)
        if (injectedHtml == null) {
            callback?.notifyReady()
            return
        }

        val (tw, th) = resolveStreamFrameDimensions(whep.width, whep.height, whep.scale)
        callback?.onDimensionsUpdated(tw, th)
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        cleanupWebView()
        attachPosterOrPlaceholder(frame.context, frame, whep.poster, whep.posterMode)

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
        } catch (e: android.util.AndroidRuntimeException) {
            Log.e("MediaRenderer", "Error setting up WHEP: ${e.message}")
            callback?.notifyReady()
        } catch (e: IllegalStateException) {
            Log.e("MediaRenderer", "Error setting up WHEP: ${e.message}")
            callback?.notifyReady()
        }
    }

    private fun loadWhepHtml(finalUri: String, videoFit: String): String? = try {
        val html = appContext.assets.open("whep.html").bufferedReader().use { it.readText() }
        html.replace("'{{STREAM_URL}}'", "'$finalUri'")
            .replace("object-fit: cover;", "object-fit: $videoFit !important;")
    } catch (e: java.io.IOException) {
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
        val hasOverlay = !whep.poster.isNullOrBlank() || appSettings.mediaLoadingPlaceholder
        val startTimeMs = SystemClock.elapsedRealtime()
        Log.d("MediaRenderer", "Creating fresh WebView for WHEP: ${whep.uri}")
        return WebView(frame.context).apply {
            visibility = View.VISIBLE
            alpha = if (hasOverlay) INVISIBLE_SURFACE_ALPHA else 1f
            setBackgroundColor(Color.TRANSPARENT)
            webView = this
            addJavascriptInterface(
                WhepJsBridge(
                    WeakReference(this@MediaRenderer),
                    WeakReference(frame),
                    tw,
                    whep.height != null,
                    retryCount,
                    startTimeMs,
                ),
                "PiPup",
            )
            webViewClient = WhepWebViewClient(this@MediaRenderer, retryCount)
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

    private fun renderLocalFile(uiContext: Context, frame: FrameLayout, path: String, width: Int, scale: Boolean) {
        renderGlide(uiContext, frame, File(path), width, scale, DiskCacheStrategy.NONE, true, 0)
    }

    private fun renderGlide(
        uiContext: Context,
        frame: FrameLayout,
        source: Any,
        width: Int,
        scale: Boolean,
        diskCache: DiskCacheStrategy,
        skipMemory: Boolean,
        retryCount: Int
    ) {
        val isFullscreen = isFullscreenActive
        val maxDim = if (scale) appContext.getScaledPixels(1280) else 1280
        val (tw, th) = if (isFullscreen) {
            fullscreenBoundingBox()
        } else {
            (if (scale) appContext.getScaledPixels(width) else width).coerceAtMost(maxDim) to 0
        }
        callback?.onDimensionsUpdated(tw, th)
        frame.layoutParams.width = tw
        if (isFullscreen) {
            frame.layoutParams.height = th
        }
        frame.requestLayout()

        val iv = configureImageView(uiContext, frame, isFullscreen)

        // Ensure any previous load on this view is cancelled before starting a new one
        Glide.with(appContext).clear(iv)

        val requestListener = GlideRequestListener(
            this,
            WeakReference(frame),
            WeakReference(iv),
            source,
            width,
            scale,
            diskCache,
            skipMemory,
            retryCount,
            tw,
            uiContext
        )

        // Standard Glide "no-cache" behavior:
        val effectiveStrategy = if (skipMemory) DiskCacheStrategy.NONE else diskCache

        var builder = Glide.with(appContext)
            .`as`(Drawable::class.java)
            .load(source)
            .diskCacheStrategy(effectiveStrategy)
            .skipMemoryCache(skipMemory)
            .override(tw, if (isFullscreen) th else Target.SIZE_ORIGINAL)
            .let { if (isFullscreen) (if (currentFullscreenUpscale) it.fitCenter() else it.centerInside()) else it }
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
        updateSourceDimensions(bitmap.width, bitmap.height)
        val isFullscreen = isFullscreenActive
        if (isFullscreen) {
            applyFullscreenMediaFrameSize(frame)
        } else {
            val tw = if (scale) appContext.getScaledPixels(width) else width
            val th = (tw * bitmap.height) / bitmap.width
            callback?.onDimensionsUpdated(tw, th)
            frame.layoutParams.width = tw
            frame.layoutParams.height = th
            frame.requestLayout()
        }

        val iv = configureImageView(uiContext, frame, isFullscreen)

        iv.setImageBitmap(bitmap)
        removeStaleViews(frame, iv)
        callback?.notifyReady()
    }

    /** Starts playback and reveals the active video surface once the popup is shown. */
    fun startMedia() {
        mediaStarted = true
        videoView?.isVisible = true
        player?.play()
    }

    /** Updates the CSS `object-fit` mode on an active WHEP video element in-place. */
    fun updateWhepVideoFit(videoFit: String) {
        webView?.evaluateJavascript("document.getElementById('v').style.objectFit = '$videoFit';", null)
    }

    /** Reveals the active WHEP WebView once playback begins after [elapsedMs]. */
    fun onWhepReady(elapsedMs: Long) {
        webView?.let { wv ->
            (wv.parent as? FrameLayout)?.let { parent ->
                crossfadeRevealStream(parent, wv, elapsedMs)
            }
        }
    }

    /** Forwards a WHEP playback error to the host retry handler. */
    fun onWhepError(error: String, retryCount: Int) {
        callback?.handleWhepRetry(error, retryCount)
    }

    internal class VideoPlayerListener(
        renderer: MediaRenderer,
        private val frame: WeakReference<FrameLayout>,
        private val tw: Int,
        private val th: Int,
        private val video: PopupProps.Media.Video,
        private val retryCount: Int,
        uiContext: Context
    ) : Player.Listener {
        private val rendererRef = WeakReference(renderer)
        private val uiContextRef = WeakReference(uiContext)
        private var ready = false
        private var hasRenderedFirstFrame = false
        private var isPlayerReady = false
        private val startTimeMs = SystemClock.elapsedRealtime()

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val renderer = rendererRef.get()
            val f = frame.get()
            if (renderer == null || f == null) return

            renderer.updateSourceDimensions(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            if (renderer.currentCoversScreen) {
                renderer.applyFullscreenMediaFrameSize(f)
                renderer.callback?.adjustHeights()
            } else {
                val finalHeight = heightForDisplayAspect(
                    tw,
                    videoSize.width,
                    videoSize.height,
                    videoSize.pixelWidthHeightRatio,
                )
                if (finalHeight > 0 && f.layoutParams.height != finalHeight) {
                    f.layoutParams.height = finalHeight
                    f.requestLayout()
                    renderer.callback?.onDimensionsUpdated(tw, finalHeight)
                    renderer.callback?.adjustHeights()
                }
            }
        }

        private fun syncVideoSizeBeforeReveal(renderer: MediaRenderer, f: FrameLayout) {
            val vs = renderer.player?.videoSize?.takeIf { it.width > 0 && it.height > 0 }
            if (renderer.currentCoversScreen) {
                vs?.let { renderer.updateSourceDimensions(it.width, it.height, it.pixelWidthHeightRatio) }
                renderer.applyFullscreenMediaFrameSize(f)
                return
            }
            val finalHeight = vs?.let {
                heightForDisplayAspect(tw, it.width, it.height, it.pixelWidthHeightRatio)
            } ?: f.layoutParams.height.takeIf { it > 0 } ?: th
            if (finalHeight > 0 && f.layoutParams.height != finalHeight) {
                f.layoutParams.height = finalHeight
                f.requestLayout()
                renderer.callback?.onDimensionsUpdated(tw, finalHeight)
            }
        }

        private fun revealVideo(renderer: MediaRenderer, f: FrameLayout) {
            if (ready || renderer.callback?.isCleanedUp() == true) return
            ready = true
            val revealStartElapsed = SystemClock.elapsedRealtime() - startTimeMs
            syncVideoSizeBeforeReveal(renderer, f)
            renderer.videoView?.let { keepView ->
                renderer.crossfadeRevealStream(f, keepView, revealStartElapsed)
            } ?: run {
                renderer.callback?.onFirstFrameRendered(revealStartElapsed)
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
        }

        override fun onRenderedFirstFrame() {
            val renderer = rendererRef.get() ?: return
            val f = frame.get() ?: return
            hasRenderedFirstFrame = true
            val elapsed = SystemClock.elapsedRealtime() - startTimeMs
            val playerReady = isPlayerReady || renderer.player?.playbackState == Player.STATE_READY
            Log.d("PiPup-Media", "onRenderedFirstFrame after $elapsed ms (isPlayerReady=$playerReady)")
            if (playerReady) {
                revealVideo(renderer, f)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            val renderer = rendererRef.get() ?: return
            val f = frame.get() ?: return
            isPlayerReady = (state == Player.STATE_READY)
            if (!ready && isPlayerReady) {
                val elapsed = SystemClock.elapsedRealtime() - startTimeMs
                val popupAlreadyVisible = renderer.callback?.isReadyCalled() == true
                Log.d(
                    "PiPup-Media",
                    "ExoPlayer reached STATE_READY after $elapsed ms " +
                        "(hasRenderedFirstFrame=$hasRenderedFirstFrame, popupAlreadyVisible=$popupAlreadyVisible)"
                )
                if (hasRenderedFirstFrame || !popupAlreadyVisible) {
                    revealVideo(renderer, f)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (ready) return
            val renderer = rendererRef.get()
            val f = frame.get()
            val ui = uiContextRef.get()
            if (renderer == null || f == null || ui == null) return

            if (retryCount < renderer.appSettings.mediaRetries && renderer.callback?.isCleanedUp() == false) {
                renderer.mainHandler.postAtTime({
                    val r = rendererRef.get() ?: return@postAtTime
                    if (r.callback?.isCleanedUp() == false) {
                        f.removeAllViews()
                        r.renderVideo(f, video, retryCount + 1, ui)
                    }
                }, renderer.rendererToken, SystemClock.uptimeMillis() + VIDEO_RETRY_DELAY_MS * (retryCount + 1))
            } else {
                renderer.callback?.showPlaceholder(error.errorCodeName)
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
        }
    }

    internal class WebRendererClient(
        renderer: MediaRenderer,
        private val frame: WeakReference<FrameLayout>,
        private val web: PopupProps.Media.Web,
        private val retryCount: Int,
        uiContext: Context,
        private val startTimeMs: Long
    ) : WebViewClient() {
        private val rendererRef = WeakReference(renderer)
        private val uiContextRef = WeakReference(uiContext)
        @Volatile private var videoElementFound = false
        private var revealed = false

        fun onVideoFound() {
            videoElementFound = true
        }

        fun revealWeb() {
            if (revealed) return
            val renderer = rendererRef.get()
            val f = frame.get()
            val wv = renderer?.webView
            if (renderer == null || f == null || wv == null) return
            if (renderer.callback?.isCleanedUp() != true) {
                revealed = true
                val elapsed = SystemClock.elapsedRealtime() - startTimeMs
                renderer.crossfadeRevealStream(f, wv, elapsed)
            }
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            view?.evaluateJavascript(VIDEO_WATCH_JS, null)
            val renderer = rendererRef.get() ?: return
            renderer.mainHandler.postAtTime({
                if (!videoElementFound) {
                    revealWeb()
                }
            }, renderer.rendererToken, SystemClock.uptimeMillis() + POSTER_FADE_CAP_MS)
        }

        override fun onPageFinished(v: WebView?, u: String?) {
            Log.d("MediaRenderer", "onPageFinished: $u")
            if (web.muted) {
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
            val renderer = rendererRef.get()
            val f = frame.get()
            val ui = uiContextRef.get()
            if (renderer == null || f == null || ui == null) return

            renderer.lastMediaError = description
            if (retryCount < renderer.appSettings.mediaRetries && renderer.callback?.isCleanedUp() == false) {
                renderer.mainHandler.postAtTime({
                    val r = rendererRef.get() ?: return@postAtTime
                    if (r.callback?.isCleanedUp() == false) r.renderWeb(ui, f, web, retryCount + 1)
                }, renderer.rendererToken, SystemClock.uptimeMillis() + WEB_RETRY_DELAY_MS * (retryCount + 1))
            } else {
                renderer.callback?.showPlaceholder(description)
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
        }
    }

    internal class WebStreamChromeClient(
        private val client: WebRendererClient
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

        override fun onLoadFailed(
            e: GlideException?,
            model: Any?,
            target: Target<Drawable>,
            isFirstResource: Boolean
        ): Boolean {
            Log.w("MediaRenderer", "Glide load failed: ${e?.message}, model: $model")
            e?.logRootCauses("MediaRenderer")

            val renderer = rendererRef.get()
            val f = frame.get()
            val ui = uiContextRef.get()
            if (renderer == null || f == null || ui == null) return false

            val shouldRetry = retryCount < renderer.appSettings.mediaRetries &&
                renderer.callback?.isCleanedUp() == false
            return if (shouldRetry) {
                renderer.mainHandler.postAtTime({
                    val r = rendererRef.get() ?: return@postAtTime
                    if (r.callback?.isCleanedUp() == false) {
                        r.renderGlide(ui, f, source, width, scale, diskCache, skipMemory, retryCount + 1)
                    }
                }, renderer.rendererToken, SystemClock.uptimeMillis() + GLIDE_RETRY_DELAY_MS * (retryCount + 1))
                true
            } else {
                renderer.callback?.showPlaceholder(renderer.appContext.getString(R.string.media_error_load_failed))
                renderer.callback?.notifyReady()
                false
            }
        }

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            target: Target<Drawable>?,
            dataSource: DataSource,
            isFirstResource: Boolean
        ): Boolean {
            val renderer = rendererRef.get()
            val f = frame.get()
            val imageView = iv.get()
            if (renderer == null || f == null || imageView == null) return false

            if (renderer.callback?.isReadyCalled() != true && renderer.callback?.isCleanedUp() != true) {
                if (resource.intrinsicWidth > 0 && resource.intrinsicHeight > 0) {
                    renderer.updateSourceDimensions(resource.intrinsicWidth, resource.intrinsicHeight)
                    if (renderer.currentCoversScreen) {
                        renderer.applyFullscreenMediaFrameSize(f)
                    } else {
                        val maxH = (renderer.appContext.resources.displayMetrics.heightPixels * MAX_IMAGE_HEIGHT_RATIO)
                            .toInt()
                        val targetH = ((tw * resource.intrinsicHeight) / resource.intrinsicWidth).coerceAtMost(maxH)
                        val fittedW = (targetH * resource.intrinsicWidth) / resource.intrinsicHeight
                        f.layoutParams.width = fittedW
                        f.layoutParams.height = targetH
                        f.requestLayout()
                        renderer.callback?.onDimensionsUpdated(fittedW, targetH)
                    }
                }
                renderer.removeStaleViews(f, imageView)
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
            return false
        }
    }

    /** Updates media container sizing when WHEP reports its intrinsic video dimensions. */
    fun onWhepSourceDimensions(frame: FrameLayout?, tw: Int, srcWidth: Int, srcHeight: Int) {
        if (srcWidth <= 0 || srcHeight <= 0 || frame == null) return
        updateSourceDimensions(srcWidth, srcHeight)
        if (currentCoversScreen) {
            applyFullscreenMediaFrameSize(frame)
            callback?.adjustHeights()
            return
        }
        val calculatedHeight = (tw * srcHeight) / srcWidth
        if (calculatedHeight > 0 && frame.layoutParams.height != calculatedHeight) {
            frame.layoutParams.height = calculatedHeight
            frame.requestLayout()
            callback?.onDimensionsUpdated(tw, calculatedHeight)
            callback?.adjustHeights()
        }
    }

    internal class WhepJsBridge(
        private val rendererRef: WeakReference<MediaRenderer>,
        private val frameRef: WeakReference<FrameLayout>,
        private val tw: Int,
        private val hasExplicitHeight: Boolean,
        private val retryCount: Int,
        private val startTimeMs: Long
    ) {
        @JavascriptInterface
        fun onSourceDimensions(sourceWidth: Int, sourceHeight: Int) {
            val renderer = rendererRef.get()
            val frame = frameRef.get()
            if (hasExplicitHeight || renderer == null || frame == null) return
            renderer.mainHandler.postAtTime({
                if (renderer.callback?.isCleanedUp() == false) {
                    renderer.onWhepSourceDimensions(frame, tw, sourceWidth, sourceHeight)
                }
            }, renderer.rendererToken, SystemClock.uptimeMillis())
        }

        @JavascriptInterface
        fun onMediaPlaying() {
            val renderer = rendererRef.get() ?: return
            val elapsed = SystemClock.elapsedRealtime() - startTimeMs
            renderer.mainHandler.postAtTime({
                if (renderer.callback?.isCleanedUp() == false) {
                    renderer.onWhepReady(elapsed)
                }
            }, renderer.rendererToken, SystemClock.uptimeMillis())
        }

        @JavascriptInterface
        fun onMediaError(error: String) {
            val renderer = rendererRef.get() ?: return
            renderer.mainHandler.postAtTime({
                if (renderer.callback?.isCleanedUp() == false) {
                    renderer.onWhepError(error, retryCount)
                }
            }, renderer.rendererToken, SystemClock.uptimeMillis())
        }
    }
}
