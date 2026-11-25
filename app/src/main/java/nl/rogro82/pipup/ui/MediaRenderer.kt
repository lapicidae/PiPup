package nl.rogro82.pipup.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.animation.LinearInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.core.PayloadParser
import nl.rogro82.pipup.getScaledPixels
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * Coordinates media rendering across image, video, and web/WHEP renderers for [PopupView].
 */
@UnstableApi
class MediaRenderer(
    context: Context,
    internal val mainHandler: Handler,
    internal val appSettings: AppSettings,
    callback: Callback,
) {
    /** Computed ExoPlayer buffer durations in milliseconds. */
    internal data class BufferDurations(
        val minMs: Int,
        val maxMs: Int,
        val startMs: Int,
        val rebufferMs: Int,
    )

    /** Shared animation constants and aspect-ratio calculation helpers. */
    companion object {
        private val CROSSFADE_INTERPOLATOR = LinearInterpolator()
        private const val CROSSFADE_DURATION_MS = 350L
        private const val MIN_BUFFER_FLOOR_MS = 1_000
        private const val MAX_BUFFER_FLOOR_MS = 5_000
        internal const val INVISIBLE_SURFACE_ALPHA = 0.01f

        /**
         * Computes ExoPlayer buffer durations for [bufferMs] as [BufferDurations],
         * or null when absent/<=0 so defaults apply. Clamped to 100..10000 ms.
         */
        internal fun lowLatencyBufferDurations(bufferMs: Int?): BufferDurations? {
            val start = bufferMs?.takeIf { it > 0 }?.coerceIn(
                PopupProps.BUFFER_MS_MIN,
                PopupProps.BUFFER_MS_MAX,
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
            pixelWidthHeightRatio: Float,
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
            requestedH: Int? = null,
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

    internal val appContext: Context = context.applicationContext
    private val callbackRef = WeakReference(callback)
    internal val callback: Callback? get() = callbackRef.get()
    internal val rendererToken = Any()

    private var imageRendererInstance: ImageMediaRenderer? = null
    private var videoRendererInstance: VideoMediaRenderer? = null
    private var webRendererInstance: WebMediaRenderer? = null

    internal val imageRenderer: ImageMediaRenderer
        get() = imageRendererInstance ?: ImageMediaRenderer(this).also { imageRendererInstance = it }

    private fun videoRenderer(): VideoMediaRenderer =
        videoRendererInstance ?: VideoMediaRenderer(this).also { videoRendererInstance = it }

    private fun webRenderer(): WebMediaRenderer =
        webRendererInstance ?: WebMediaRenderer(this).also { webRendererInstance = it }

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

    internal val isFullscreenActive: Boolean
        get() = currentCoversScreen && currentTargetWidth > 0 && currentTargetHeight > 0

    internal val fullscreenBoundingBox: Pair<Int, Int>
        get() {
            val boxW = currentRequestedMaxWidth?.takeIf { it > 0 }
                ?.let { minOf(currentTargetWidth, it) }
                ?: currentTargetWidth
            val boxH = currentRequestedMaxHeight?.takeIf { it > 0 }
                ?.let { minOf(currentTargetHeight, it) }
                ?: currentTargetHeight
            return boxW to boxH
        }

    internal val fullscreenFrameSize: Pair<Int, Int>
        get() = fitInFullscreenBox(
            availW = currentTargetWidth,
            availH = currentTargetHeight,
            sourceW = lastSourceWidth,
            sourceH = lastSourceHeight,
            pixelWidthHeightRatio = lastSourcePixelRatio,
            upscale = currentFullscreenUpscale,
            requestedW = currentRequestedMaxWidth,
            requestedH = currentRequestedMaxHeight,
        )

    internal fun updateSourceDimensions(width: Int, height: Int, pixelRatio: Float = 1f) {
        lastSourceWidth = width
        lastSourceHeight = height
        lastSourcePixelRatio = pixelRatio
    }

    internal fun resolveStreamFrameDimensions(width: Int, height: Int?, scale: Boolean): Pair<Int, Int> {
        if (isFullscreenActive) return fullscreenFrameSize
        val w = if (scale) appContext.getScaledPixels(width) else width
        val h = height?.let { if (scale) appContext.getScaledPixels(it) else it }
            ?: ((w * PopupProps.ASPECT_16_9_HEIGHT) / PopupProps.ASPECT_16_9_WIDTH)
        return w to h
    }

    /** Initializes and renders the effective media declared in [props] into [frame]. */
    fun setup(
        uiContext: Context,
        frame: FrameLayout,
        props: PopupProps,
        retryCount: Int = 0,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
    ) {
        if (retryCount == 0) {
            videoRendererInstance?.mediaStarted = false
            updateSourceDimensions(0, 0, 1f)
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
            is PopupProps.Media.Image -> imageRenderer.renderImage(uiContext, frame, media, retryCount)
            is PopupProps.Media.Video -> videoRenderer().renderVideo(frame, media, retryCount, uiContext)
            is PopupProps.Media.Web -> webRenderer().renderWeb(uiContext, frame, media, retryCount)
            is PopupProps.Media.Whep -> webRenderer().renderWhep(frame, media, retryCount)
            is PopupProps.Media.LocalFile ->
                imageRenderer.renderLocalFile(uiContext, frame, media.path, media.width, media.scale)
            is PopupProps.Media.Bitmap ->
                imageRenderer.renderBitmap(uiContext, frame, media.bitmap, media.width, media.scale)
        }
    }

    /** Synchronizes fullscreen and upscaling flags from updated [props] without reloading media. */
    fun updateFullscreenProps(props: PopupProps) {
        currentCoversScreen = props.coversScreen
        currentFullscreenUpscale = props.fullscreenUpscale ?: appSettings.fullscreenUpscale
        resolveRequestedCaps(props.resolveEffectiveMedia())
    }

    /** Updates the available fullscreen media bounds and resizes [frame] accordingly. */
    fun updateFullscreenBounds(frame: FrameLayout, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        currentTargetWidth = width
        currentTargetHeight = height
        applyFullscreenMediaFrameSize(frame)
    }

    internal fun applyFullscreenMediaFrameSize(frame: FrameLayout) {
        if (currentTargetWidth <= 0 || currentTargetHeight <= 0) return
        val (fittedW, fittedH) = fullscreenFrameSize
        if (frame.layoutParams.width != fittedW || frame.layoutParams.height != fittedH) {
            frame.layoutParams.width = fittedW
            frame.layoutParams.height = fittedH
            frame.requestLayout()
            callback?.onDimensionsUpdated(fittedW, fittedH)
        }
        videoRendererInstance?.syncTextureViewMatchParent()
    }

    internal fun removeStaleViews(frame: FrameLayout, keepView: View) {
        val stale = mutableListOf<View>()
        for (i in 0 until frame.childCount) {
            val v = frame.getChildAt(i)
            if (v != keepView) stale.add(v)
        }
        for (v in stale) {
            if (v is WebView) {
                webRendererInstance?.detachStaleWebView(v)
            } else if (v is ImageView) {
                try { Glide.with(appContext).clear(v) } catch (_: Exception) {}
            }
            frame.removeView(v)
        }
        videoRendererInstance?.releaseIfNotKept(keepView)
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

    /** Releases active ExoPlayer, WebView, and pending handler callbacks. */
    fun cleanup() {
        mainHandler.removeCallbacksAndMessages(rendererToken)
        videoRendererInstance?.cleanup()
        updateSourceDimensions(0, 0, 1f)
        webRendererInstance?.cleanupWebView()
    }

    /** Clears all child media views, Glide requests, active players, and temporary local files. */
    fun fullCleanup(frame: FrameLayout, media: PopupProps.Media?) {
        val runnable = Runnable {
            videoRendererInstance?.mediaStarted = false
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

    /** Starts playback and reveals the active video surface once the popup is shown. */
    fun startMedia() {
        videoRendererInstance?.startMedia()
    }

    /** Updates the CSS `object-fit` mode on an active WHEP video element in-place. */
    fun updateWhepVideoFit(videoFit: String) {
        webRendererInstance?.updateWhepVideoFit(videoFit)
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
                media.height,
            )
            is PopupProps.Media.Image -> Triple(
                media.scale,
                media.width.takeIf { media.explicitWidth },
                null,
            )
            is PopupProps.Media.Web -> Triple(
                media.scale,
                media.width.takeIf { media.explicitWidth },
                media.height.takeIf { media.explicitHeight },
            )
            is PopupProps.Media.Whep -> Triple(
                media.scale,
                media.width.takeIf { media.explicitWidth },
                media.height,
            )
            is PopupProps.Media.LocalFile -> Triple(
                media.scale,
                media.width.takeIf { media.explicitWidth },
                null,
            )
            is PopupProps.Media.Bitmap -> Triple(media.scale, null, null)
        }
        currentRequestedMaxWidth = rawW?.let { if (scale) appContext.getScaledPixels(it) else it }
        currentRequestedMaxHeight = rawH?.let { if (scale) appContext.getScaledPixels(it) else it }
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
}
