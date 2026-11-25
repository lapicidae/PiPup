package nl.rogro82.pipup.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.databinding.PopupBinding
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.parseColorOrDefault

/**
 * Modern PopupView using ViewBinding and modular rendering logic.
 *
 * This view handles the display of notifications, including text and various media types
 * like images, videos, and WebRTC streams (WHEP).
 */
@SuppressLint("ViewConstructor")
@UnstableApi
class PopupView(context: Context, var props: PopupProps) : FrameLayout(context) {

    private val binding: PopupBinding = PopupBinding.inflate(LayoutInflater.from(context), this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val settings = PiPupApp.settings

    /** The listener to be notified when the media is fully loaded and ready to be displayed. */
    var readyListener: ReadyListener? = null
    var onButton: ((PopupProps.Button) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onLayoutAdjusted: (() -> Unit)? = null
    var onFirstFrame: ((Long) -> Unit)? = null
    var onMediaError: ((String) -> Unit)? = null

    private inner class MediaCallback : MediaRenderer.Callback {
        override fun notifyReady() = this@PopupView.notifyReady()
        override fun adjustHeights() = this@PopupView.adjustHeights()
        override fun isCleanedUp(): Boolean = this@PopupView.isCleanedUp
        override fun isReadyCalled(): Boolean = this@PopupView.isReadyCalled
        override fun showPlaceholder(error: String?) = this@PopupView.showPlaceholder(error)
        override fun onDimensionsUpdated(width: Int, height: Int) {
            if (!props.coversScreen) {
                layoutController.targetMediaWidth = width
                layoutController.targetMediaHeight = height
            } else {
                updateLiveBadgeMargins()
            }
        }
        override fun handleWhepRetry(error: String, retryCount: Int) = this@PopupView.handleWhepRetry(error, retryCount)
        override fun onFirstFrameRendered(firstFrameMs: Long) {
            onFirstFrame?.invoke(firstFrameMs)
            revealLiveBadge()
        }
    }

    private var mediaCallbackInstance: MediaCallback? = null
    private var mediaRendererInstance: MediaRenderer? = null
    private val animator = PopupAnimator(this)
    private val layoutController = PopupLayoutController(context, settings, binding)

    private fun getOrCreateMediaRenderer(): MediaRenderer =
        mediaRendererInstance ?: run {
            val callback = mediaCallbackInstance ?: MediaCallback().also { mediaCallbackInstance = it }
            MediaRenderer(context, mainHandler, settings, callback).also { mediaRendererInstance = it }
        }
    private val buttonController = PopupButtonController(
        context = context,
        settings = settings,
        binding = binding,
        mainHandler = mainHandler,
        isCleanedUp = { isCleanedUp },
    )

    private var isReadyCalled = false
    private var isCleanedUp = false
    private var lastMediaError: String? = null
    private var liveBadgeRevealed = false

    private val adjustHeightsRunnable = Runnable {
        applyHeightConstraints(notifyCallback = true)
    }

    private val timeoutRunnable = Runnable {
        if (!isReadyCalled) {
            Log.w("PopupView", "Media loading timed out, showing placeholder (last error: $lastMediaError)")
            showPlaceholder(lastMediaError ?: context.getString(R.string.media_error_timeout))
            notifyReady()
        }
    }

    /**
     * Interface for listening to the readiness state of the popup.
     */
    interface ReadyListener {
        /**
         * Called when the popup and its media are ready to be shown.
         */
        fun onReady()
    }

    init {
        clipChildren = false
        clipToPadding = false

        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // Only detachment requires resource cleanup.
            }
            override fun onViewDetachedFromWindow(v: View) {
                cleanup()
            }
        })
    }

    /**
     * Returns true while the overlay close button is currently faded out by its auto-hide timer.
     */
    fun isCloseButtonHidden(): Boolean = buttonController.isCloseButtonHidden()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        buttonController.handleKeyEvent(event, props) || super.dispatchKeyEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        buttonController.handleTouchEvent(ev, props) || super.dispatchTouchEvent(ev)

    /**
     * Initializes the view, updates visuals, and sets up the media content.
     * @return The initialized [PopupView] instance.
     */
    fun create(): PopupView {
        layoutController.calculateTargetDimensions(props.resolveEffectiveMedia(), props)
        updateVisuals()
        setupMediaContent()
        return this
    }

    /**
     * Updates the popup with new properties, intelligently reloading media only if necessary.
     * @param newProps The new [PopupProps] to apply.
     */
    fun updateFromProps(newProps: PopupProps) {
        if (isCleanedUp) {
            Log.w("PopupView", "updateFromProps called on cleaned up view")
            return
        }

        val oldMedia = props.resolveEffectiveMedia()
        val newMedia = newProps.resolveEffectiveMedia()

        val contentChanged = !isMediaContentSame(oldMedia, newMedia)
        if (contentChanged) {
            Log.d("PopupView", "Media content changed, triggering reload")
        }

        this.props = newProps
        layoutController.calculateTargetDimensions(newMedia, newProps)
        updateVisuals()

        if (contentChanged || newMedia is PopupProps.Media.Bitmap) {
            setupMediaContent()
        } else {
            mediaRendererInstance?.updateFullscreenProps(newProps)
            if (oldMedia is PopupProps.Media.Whep && newMedia is PopupProps.Media.Whep) {
                if (oldMedia.videoFit != newMedia.videoFit) {
                    Log.d("PopupView", "Updating WHEP videoFit dynamically to ${newMedia.videoFit}")
                    mediaRendererInstance?.updateWhepVideoFit(newMedia.videoFit)
                }
            }
            configureLiveBadgeAppearance()
            if (liveBadgeRevealed) {
                revealLiveBadge()
            }
            adjustHeights()
        }
    }

    /**
     * Synchronously applies media and scroll height constraints prior to view measurement.
     */
    fun syncLayoutForMeasurement() {
        applyHeightConstraints(notifyCallback = false)
    }

    /**
     * Displays a placeholder image and an error message in case media loading fails.
     * @param errorMessage The error message to display.
     */
    fun showPlaceholder(errorMessage: String? = null) {
        errorMessage?.let { onMediaError?.invoke(it) }
        mediaRendererInstance?.cleanup()

        val frame = binding.popupMediaFrame
        val container = binding.popupMediaContainer
        frame.removeAllViews()
        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_banner)
            alpha = 0.5f
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val width = props.rawMediaWidth() ?: PopupProps.DEFAULT_MEDIA_WIDTH
        val tw = props.scaleValue(context, width)

        if (layoutController.targetMediaHeight <= 0) {
            layoutController.targetMediaHeight = (tw * PopupProps.ASPECT_16_9_HEIGHT) / PopupProps.ASPECT_16_9_WIDTH
        }

        frame.layoutParams.width = tw
        frame.layoutParams.height = layoutController.targetMediaHeight
        frame.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        frame.isVisible = true
        container.isVisible = true
        binding.popupLiveBadge.isVisible = false

        errorMessage?.let { rawMsg ->
            val prettyError = beautifyErrorMessage(rawMsg)
            val mainMessage = props.message

            binding.popupMessage.text = if (mainMessage.isNullOrBlank()) {
                context.getString(R.string.media_error_only, prettyError)
            } else {
                context.getString(R.string.media_error_with_message, mainMessage, prettyError)
            }
            binding.popupMessage.isVisible = true
            binding.popupScrollView.isVisible = true
        }

        applyHeightConstraints(notifyCallback = true)
    }

    /**
     * Starts media playback if the popup contains a video.
     */
    fun startMedia() {
        if (isCleanedUp) return
        mediaRendererInstance?.startMedia()
    }

    /**
     * Cleans up resources, including stopping players and destroying WebViews.
     */
    fun cleanup() {
        if (isCleanedUp) return
        isCleanedUp = true
        mainHandler.removeCallbacksAndMessages(null)
        layoutController.cleanup()
        buttonController.cleanup()
        mediaRendererInstance?.fullCleanup(binding.popupMediaFrame, props.media)
        mediaCallbackInstance = null
    }

    /**
     * Plays the entrance animation for the popup based on the configured animation type.
     */
    fun animateIn() {
        if (isCleanedUp) return
        animator.animateIn(props)
    }

    /**
     * Forces playing the entrance animation regardless of current view state.
     */
    fun forceAnimateIn() {
        if (isCleanedUp) return
        animator.forceAnimateIn(props)
    }

    /**
     * Plays the exit animation for the popup and executes a completion callback.
     * @param completion The callback to execute when the animation finishes.
     */
    fun animateOut(completion: () -> Unit) {
        animator.animateOut(props, completion)
    }

    private fun handleWhepRetry(error: String, currentRetry: Int) {
        lastMediaError = error
        onMediaError?.invoke(error)
        val maxRetries = settings.mediaRetries
        if (currentRetry < maxRetries && !isCleanedUp) {
            val nextRetry = currentRetry + 1
            mainHandler.postDelayed({
                if (!isCleanedUp) {
                    getOrCreateMediaRenderer().setup(
                        context,
                        binding.popupMediaFrame,
                        props,
                        nextRetry,
                        layoutController.targetMediaWidth,
                        layoutController.targetMediaHeight,
                    )
                }
            }, MS_PER_SECOND * nextRetry)
        } else {
            mainHandler.post {
                showPlaceholder(error)
                notifyReady()
            }
        }
    }

    private fun notifyReady() {
        if (isReadyCalled || isCleanedUp) return
        isReadyCalled = true
        Log.i("PopupView", "Media ready, dismissing timeout")
        mainHandler.removeCallbacks(timeoutRunnable)
        readyListener?.onReady()
    }

    private fun beautifyErrorMessage(rawError: String): String {
        return when {
            rawError.contains("codecs not matched", ignoreCase = true) ->
                context.getString(R.string.media_error_codec_mismatch)
            rawError.contains("404") || rawError.contains("not found", ignoreCase = true) ->
                context.getString(R.string.media_error_not_found)
            rawError.contains("ICE", ignoreCase = true) || rawError.contains("connection", ignoreCase = true) ->
                context.getString(R.string.media_error_connection)
            rawError.contains("timeout", ignoreCase = true) ->
                context.getString(R.string.media_error_timeout)
            else -> rawError
        }
    }

    private fun mediaContentKey(m: PopupProps.Media): Any = when (m) {
        is PopupProps.Media.Image -> listOf("img", m.uri, m.cache, m.scale)
        is PopupProps.Media.Video -> listOf("vid", m.uri, m.scale)
        is PopupProps.Media.Web -> listOf("web", m.uri, m.cache, m.scale)
        is PopupProps.Media.Whep -> listOf("whep", m.uri, m.scale, m.height, m.videoFit)
        is PopupProps.Media.LocalFile -> listOf("file", m.path)
        is PopupProps.Media.Bitmap -> m
    }

    private fun isMediaContentSame(m1: PopupProps.Media?, m2: PopupProps.Media?): Boolean =
        if (m1 == null || m2 == null) m1 == m2 else mediaContentKey(m1) == mediaContentKey(m2)

    private fun updateVisuals() {
        if (layoutParams == null) {
            layoutParams = ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        alpha = 1.0f
        if (background != null) background = null
        setPadding(0, 0, 0, 0)

        val scaledPadding = props.scaleValue(context, props.contentPadding ?: settings.contentPadding)
        val borderPx = if (props.borderWidth > 0) props.scaleValue(context, props.borderWidth) else 0
        val radiusPx = props.scaleValue(context, props.borderRadius).toFloat()

        layoutController.applyContainerPaddingAndOutline(props, scaledPadding, borderPx, radiusPx)
        binding.popupMessage.maxLines = Integer.MAX_VALUE
        binding.popupMessage.ellipsize = null

        binding.popupContainer.background = GradientDrawable().apply {
            setColor(props.getBackgroundColorInt())
            cornerRadius = radiusPx
            if (borderPx > 0) setStroke(borderPx, props.getBorderColorInt())
        }

        val maxTextWidth = props.scaleValue(context, PopupProps.MAX_TEXT_WIDTH_PX)
        binding.popupTitle.maxWidth = maxTextWidth
        binding.popupMessage.maxWidth = maxTextWidth

        layoutController.reorderViews(props)
        bindTextContent()
        buttonController.updateButtons(props, layoutController.isFullBleedActive(props)) { onButton?.invoke(it) }
        buttonController.updateCloseButton(props) { onClose?.invoke() }
        applyHeightConstraints(notifyCallback = false)
    }

    private fun bindTextContent() {
        props.title?.let {
            binding.popupTitle.text = it
            binding.popupTitle.setTextColor(props.getTitleColorInt())
            binding.popupTitle.textSize = props.titleSize
            val gravity = props.getTitleGravity()
            binding.popupTitle.gravity = gravity
            binding.popupTitle.isVisible = true
            (binding.popupTitle.layoutParams as? LinearLayout.LayoutParams)?.gravity = gravity
        } ?: run { binding.popupTitle.isVisible = false }

        props.message?.let {
            binding.popupMessage.text = it
            binding.popupMessage.setTextColor(props.getMessageColorInt())
            binding.popupMessage.textSize = props.messageSize
            val gravity = props.getMessageGravity()
            binding.popupMessage.gravity = gravity
            binding.popupMessage.isVisible = true
            binding.popupScrollView.isVisible = true
            (binding.popupScrollView.layoutParams as? LinearLayout.LayoutParams)?.gravity = gravity
            (binding.textContainer.layoutParams as? LinearLayout.LayoutParams)?.gravity = gravity
        } ?: run {
            binding.popupMessage.isVisible = false
            binding.popupScrollView.isVisible = false
        }
    }

    private fun applyHeightConstraints(notifyCallback: Boolean) {
        if (isCleanedUp) return
        layoutController.applyHeightConstraints(props, mediaRendererInstance, ::updateLiveBadgeMargins)
        if (notifyCallback) onLayoutAdjusted?.invoke()
    }

    private fun adjustHeights() {
        mainHandler.removeCallbacks(adjustHeightsRunnable)
        mainHandler.post(adjustHeightsRunnable)
    }

    private fun isStreamMedia(): Boolean =
        props.media is PopupProps.Media.Video ||
            props.media is PopupProps.Media.Whep ||
            props.media is PopupProps.Media.Web

    private fun updateLiveBadgeMargins() {
        val baseMargin = context.dpToPx(LIVE_BADGE_MARGIN_DP)
        val frameW = binding.popupMediaFrame.layoutParams.width
        val frameH = binding.popupMediaFrame.layoutParams.height
        val targetW = layoutController.targetMediaWidth
        val targetH = layoutController.targetMediaHeight
        val insetH = if (props.coversScreen && targetW > frameW && frameW > 0) (targetW - frameW) / 2 else 0
        val insetV = if (props.coversScreen && targetH > frameH && frameH > 0) (targetH - frameH) / 2 else 0
        (binding.popupLiveBadge.layoutParams as? LayoutParams)?.let { lp ->
            lp.setMargins(baseMargin + insetH, baseMargin + insetV, baseMargin + insetH, baseMargin + insetV)
            binding.popupLiveBadge.layoutParams = lp
        }
    }

    private fun configureLiveBadgeAppearance() {
        val badge = binding.popupLiveBadge
        val defaultBadgeColor = ContextCompat.getColor(context, R.color.live_badge_background)
        val badgeColor = parseColorOrDefault(props.liveBadgeColor, defaultBadgeColor)
        (badge.background?.mutate() as? GradientDrawable)?.setColor(badgeColor)

        val pos = props.liveBadgePosition ?: settings.liveBadgePosition
        val gravity = when (pos.lowercase()) {
            "top_left", "0" -> Gravity.TOP or Gravity.START
            "top_right", "1" -> Gravity.TOP or Gravity.END
            "bottom_left", "2" -> Gravity.BOTTOM or Gravity.START
            "bottom_right", "3" -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.BOTTOM or Gravity.END
        }
        (badge.layoutParams as? LayoutParams)?.let { lp ->
            lp.gravity = gravity
            badge.layoutParams = lp
        }
        updateLiveBadgeMargins()
    }

    private fun revealLiveBadge() {
        liveBadgeRevealed = true
        val showLiveBadge = props.liveBadge ?: settings.liveBadge
        val badge = binding.popupLiveBadge
        if (!showLiveBadge || (!isStreamMedia() && !props.isPreview)) {
            badge.isVisible = false
            return
        }
        configureLiveBadgeAppearance()
        if (!badge.isVisible) {
            badge.alpha = 0f
            badge.isVisible = true
            badge.bringToFront()
            badge.animate().alpha(1f).setDuration(LIVE_BADGE_FADE_IN_MS).start()
        }
    }

    private fun setupMediaContent() {
        val frame = binding.popupMediaFrame
        val container = binding.popupMediaContainer
        isReadyCalled = false
        liveBadgeRevealed = false
        mainHandler.removeCallbacks(timeoutRunnable)

        binding.popupLiveBadge.animate().cancel()
        binding.popupLiveBadge.isVisible = false
        configureLiveBadgeAppearance()
        if (props.isPreview && (props.liveBadge ?: settings.liveBadge)) {
            revealLiveBadge()
        }

        container.isVisible = props.hasMedia
        if (!props.hasMedia) {
            frame.isVisible = false
            mediaRendererInstance?.cleanup()
            frame.removeAllViews()
            if (Looper.myLooper() == Looper.getMainLooper()) {
                notifyReady()
            } else {
                mainHandler.post { notifyReady() }
            }
            return
        }

        val timeoutSec = settings.mediaTimeout
        if (timeoutSec > 0) {
            mainHandler.postDelayed(timeoutRunnable, timeoutSec * MS_PER_SECOND)
        }
        getOrCreateMediaRenderer().setup(
            context,
            frame,
            props,
            targetWidth = layoutController.targetMediaWidth,
            targetHeight = layoutController.targetMediaHeight,
        )
    }

    /**
     * Convenience builder method to create and initialize a [PopupView].
     */
    companion object {
        private const val LIVE_BADGE_MARGIN_DP = 8
        private const val LIVE_BADGE_FADE_IN_MS = 150L
        private const val MS_PER_SECOND = 1000L

        /** Creates, binds, and initializes a [PopupView] with optional [listener]. */
        fun build(context: Context, props: PopupProps, listener: ReadyListener? = null): PopupView {
            return PopupView(context, props).apply { readyListener = listener }.create()
        }
    }
}
