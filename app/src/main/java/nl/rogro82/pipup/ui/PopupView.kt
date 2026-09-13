package nl.rogro82.pipup.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.databinding.PopupBinding
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.getScaledPixels

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
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val settings = PiPupApp.settings
    /** The listener to be notified when the media is fully loaded and ready to be displayed. */
    var readyListener: ReadyListener? = null

    private inner class MediaCallback : MediaRenderer.Callback {
        override fun notifyReady() = this@PopupView.notifyReady()
        override fun adjustHeights() = this@PopupView.adjustHeights()
        override fun isCleanedUp(): Boolean = this@PopupView.isCleanedUp
        override fun isReadyCalled(): Boolean = this@PopupView.isReadyCalled
        override fun showPlaceholder(error: String?) = this@PopupView.showPlaceholder(error)
        override fun onDimensionsUpdated(width: Int, height: Int) {
            targetMediaWidth = width
            targetMediaHeight = height
        }
        override fun handleWhepRetry(error: String, retryCount: Int) = this@PopupView.handleWhepRetry(error, retryCount)
    }

    private val mediaRendererCallback = MediaCallback()

    private val mediaRenderer = MediaRenderer(context, mainHandler, settings, mediaRendererCallback)

    private val animator = PopupAnimator(this)

    private var isScrolling = false
    private var targetMediaWidth = 0
    private var targetMediaHeight = 0
    private var isReadyCalled = false
    private var isCleanedUp = false
    private var lastMediaError: String? = null

    private fun handleWhepRetry(error: String, currentRetry: Int) {
        lastMediaError = error
        val maxRetries = settings.mediaRetries
        if (currentRetry < maxRetries && !isCleanedUp) {
            val nextRetry = currentRetry + 1
            mainHandler.postDelayed({
                if (!isCleanedUp) {
                    mediaRenderer.setup(context, binding.popupMediaFrame, props, nextRetry)
                }
            }, 1000L * nextRetry)
        } else {
            mainHandler.post {
                showPlaceholder(error)
                notifyReady()
            }
        }
    }

    private val timeoutRunnable = Runnable {
        if (!isReadyCalled) {
            Log.w("PopupView", "Media loading timed out, showing placeholder (last error: $lastMediaError)")
            // Use the specific error message if available, otherwise fallback to generic timeout
            showPlaceholder(lastMediaError ?: context.getString(R.string.media_error_timeout))
            notifyReady()
        }
    }

    private fun notifyReady() {
        if (isReadyCalled || isCleanedUp) return
        isReadyCalled = true
        Log.i("PopupView", "Media ready, dismissing timeout")
        mainHandler.removeCallbacks(timeoutRunnable)
        readyListener?.onReady()
    }

    /**
     * Displays a placeholder image and an error message in case media loading fails.
     * @param errorMessage The error message to display.
     */
    fun showPlaceholder(errorMessage: String? = null) {
        // Stop any active media loading to prevent late success callbacks from overriding the placeholder.
        mediaRenderer.cleanup()

        val frame = binding.popupMediaFrame
        frame.removeAllViews()
        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_banner)
            alpha = 0.5f
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        // Determine width once to keep layout stable
        val width = when (val m = props.media) {
            is PopupProps.Media.Image -> m.width
            is PopupProps.Media.Video -> m.width
            is PopupProps.Media.Web -> m.width
            is PopupProps.Media.Whep -> m.width
            is PopupProps.Media.LocalFile -> m.width
            is PopupProps.Media.Bitmap -> m.width
            else -> props.imageWidth ?: 480
        }
        val tw = if (props.scale) context.getScaledPixels(width) else context.dpToPx(width)

        // Preserve original target height if already set (e.g. from WHEP props)
        // to prevent jumps during error state transitions.
        if (targetMediaHeight <= 0) {
            targetMediaHeight = (tw * 9) / 16
        }

        frame.layoutParams.width = tw
        frame.layoutParams.height = targetMediaHeight
        frame.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        frame.isVisible = true

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

        // Apply final heights but avoid re-calculation jumps
        mainHandler.post { adjustHeights() }
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

        // Automatic cleanup when the view is detached from the window.
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {}
            override fun onViewDetachedFromWindow(v: android.view.View) {
                cleanup()
            }
        })
    }

    /**
     * Initializes the view, updates visuals, and sets up the media content.
     * @return The initialized [PopupView] instance.
     */
    fun create(): PopupView {
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

        val oldMedia = props.media ?: props.image?.let { PopupProps.Media.Image(it, props.imageWidth ?: 480) }
        val newMedia = newProps.media ?: newProps.image?.let { PopupProps.Media.Image(it, newProps.imageWidth ?: 480) }

        val contentChanged = !isMediaContentSame(oldMedia, newMedia)
        if (contentChanged) {
            Log.d("PopupView", "Media content changed, triggering reload")
        }

        this.props = newProps
        calculateTargetDimensions(newMedia, newProps)
        updateVisuals()

        if (contentChanged || newMedia is PopupProps.Media.Bitmap) {
            setupMediaContent()
        } else {
            // Handle property-only updates (e.g. WHEP videoFit) without full reload
            if (oldMedia is PopupProps.Media.Whep && newMedia is PopupProps.Media.Whep) {
                if (oldMedia.videoFit != newMedia.videoFit) {
                    Log.d("PopupView", "Updating WHEP videoFit dynamically to ${newMedia.videoFit}")
                    mediaRenderer.updateWhepVideoFit(newMedia.videoFit)
                }
            }
            adjustHeights()
        }
    }

    private fun calculateTargetDimensions(media: PopupProps.Media?, props: PopupProps) {
        val width = when (media) {
            is PopupProps.Media.Image -> media.width
            is PopupProps.Media.Video -> media.width
            is PopupProps.Media.Web -> media.width
            is PopupProps.Media.Whep -> media.width
            is PopupProps.Media.LocalFile -> media.width
            is PopupProps.Media.Bitmap -> media.width
            else -> props.imageWidth ?: 480
        }
        targetMediaWidth = if (props.scale) context.getScaledPixels(width) else context.dpToPx(width)

        targetMediaHeight = when (media) {
            is PopupProps.Media.Video -> (targetMediaWidth * 9) / 16
            is PopupProps.Media.Web -> if (props.scale) context.getScaledPixels(media.height) else context.dpToPx(media.height)
            is PopupProps.Media.Whep -> if (props.scale) context.getScaledPixels(media.height) else context.dpToPx(media.height)
            is PopupProps.Media.Bitmap -> (targetMediaWidth * media.bitmap.height) / media.bitmap.width
            else -> 0 // For images, height is determined after load in renderGlide
        }
    }

    private fun isMediaContentSame(m1: PopupProps.Media?, m2: PopupProps.Media?): Boolean {
        if (m1 == null || m2 == null) return m1 == m2
        if (m1::class != m2::class) return false
        return when (m1) {
            is PopupProps.Media.Image -> (m2 as PopupProps.Media.Image).let { m1.uri == it.uri && m1.cache == it.cache && m1.scale == it.scale }
            is PopupProps.Media.Video -> (m2 as PopupProps.Media.Video).let { m1.uri == it.uri && m1.scale == it.scale }
            is PopupProps.Media.Web -> (m2 as PopupProps.Media.Web).let { m1.uri == it.uri && m1.cache == it.cache && m1.scale == it.scale }
            is PopupProps.Media.Whep -> (m2 as PopupProps.Media.Whep).let { m1.uri == it.uri && m1.scale == it.scale }
            is PopupProps.Media.LocalFile -> m1.path == (m2 as PopupProps.Media.LocalFile).path
            else -> m1 == m2
        }
    }

    private fun updateVisuals() {
        if (layoutParams == null) {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        alpha = 1.0f
        if (background != null) background = null
        setPadding(0, 0, 0, 0)

        // 1. Padding
        val paddingVal = props.contentPadding ?: settings.contentPadding
        val scaledPadding = if (props.scale) context.getScaledPixels(paddingVal) else context.dpToPx(paddingVal)
        binding.popupContainer.setPadding(scaledPadding, scaledPadding, scaledPadding, scaledPadding)

        // 2. Background
        val radiusPx = if (props.scale) context.getScaledPixels(props.borderRadius).toFloat() else context.dpToPx(props.borderRadius).toFloat()
        binding.popupContainer.background = GradientDrawable().apply {
            setColor(props.getBackgroundColorInt())
            cornerRadius = radiusPx
            if (props.borderWidth > 0) {
                val bw = if (props.scale) context.getScaledPixels(props.borderWidth) else context.dpToPx(props.borderWidth)
                setStroke(bw, props.getBorderColorInt())
            }
        }

        // 3. Constraints
        val maxTextWidth = if (props.scale) context.getScaledPixels(500) else context.dpToPx(500)
        binding.popupTitle.maxWidth = maxTextWidth
        binding.popupMessage.maxWidth = maxTextWidth

        reorderViews()

        // 4. Content
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

            // Additionally align the text container content block within the popup
            (binding.textContainer.layoutParams as? LinearLayout.LayoutParams)?.gravity = gravity

            binding.popupContainer.post { adjustHeights() }
        } ?: run {
            binding.popupMessage.isVisible = false
            binding.popupScrollView.isVisible = false
        }
    }

    private fun reorderViews() {
        val container = binding.popupContainer
        val textContainer = binding.textContainer
        val mediaFrame = binding.popupMediaFrame

        val pos = props.mediaPosition ?: 0
        if (container.tag == pos && textContainer.parent == container && mediaFrame.parent == container) {
            // Already in correct order. However, since props might have changed,
            // update existing layout params to keep them in sync with targetMediaWidth/Height.
            mediaFrame.layoutParams?.let { lp ->
                if (targetMediaWidth > 0 && targetMediaHeight > 0) {
                    lp.width = targetMediaWidth
                    lp.height = targetMediaHeight
                }
            }
            return
        }

        // Extremely careful reordering: only detach if absolutely necessary
        // to avoid WebView surface destruction.
        if (textContainer.parent != null && textContainer.parent != container) {
            (textContainer.parent as android.view.ViewGroup).removeView(textContainer)
        }
        if (mediaFrame.parent != null && mediaFrame.parent != container) {
            (mediaFrame.parent as android.view.ViewGroup).removeView(mediaFrame)
        }

        if (textContainer.parent == container) container.removeView(textContainer)
        if (mediaFrame.parent == container) container.removeView(mediaFrame)

        container.tag = pos
        when (pos) {
            0 -> setupVertical(container, mediaFrame, textContainer, true) // Top
            1 -> setupVertical(container, textContainer, mediaFrame, false) // Bottom
            2 -> setupHorizontal(container, mediaFrame, textContainer, true) // Left
            3 -> setupHorizontal(container, textContainer, mediaFrame, false) // Right
        }
    }

    private fun setupVertical(container: LinearLayout, first: android.view.View, second: android.view.View, mediaFirst: Boolean) {
        container.orientation = LinearLayout.VERTICAL
        val margin = context.dpToPx(8)

        val firstWidth = if (first == binding.popupMediaFrame && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val firstHeight = if (first == binding.popupMediaFrame && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val firstParams = LinearLayout.LayoutParams(firstWidth, firstHeight).apply {
            if (first != binding.textContainer && mediaFirst) gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, if (mediaFirst) margin else 0)
        }

        val secondWidth = if (second == binding.popupMediaFrame && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val secondHeight = if (second == binding.popupMediaFrame && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val secondParams = LinearLayout.LayoutParams(secondWidth, secondHeight).apply {
            if (second != binding.textContainer && !mediaFirst) gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, if (!mediaFirst) margin else 0, 0, 0)
        }

        container.addView(first, firstParams)
        container.addView(second, secondParams)
    }

    private fun setupHorizontal(container: LinearLayout, first: android.view.View, second: android.view.View, mediaFirst: Boolean) {
        container.orientation = LinearLayout.HORIZONTAL
        val margin = context.dpToPx(12)

        val firstWidth = if (first == binding.popupMediaFrame && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val firstHeight = if (first == binding.popupMediaFrame && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val firstParams = LinearLayout.LayoutParams(firstWidth, firstHeight).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(0, 0, if (mediaFirst) margin else 0, 0)
        }

        val secondWidth = if (second == binding.popupMediaFrame && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val secondHeight = if (second == binding.popupMediaFrame && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val secondParams = LinearLayout.LayoutParams(secondWidth, secondHeight).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(if (!mediaFirst) margin else 0, 0, 0, 0)
        }

        container.addView(first, firstParams)
        container.addView(second, secondParams)
    }

    private val adjustHeightsRunnable = Runnable {
        if (isCleanedUp) return@Runnable
        val screenHeight = resources.displayMetrics.heightPixels
        val maxPopupHeight = (screenHeight * 0.85).toInt()

        if (binding.popupMediaFrame.isVisible && targetMediaWidth > 0 && targetMediaHeight > 0) {
            binding.popupMediaFrame.layoutParams.width = targetMediaWidth
            binding.popupMediaFrame.layoutParams.height = targetMediaHeight
            binding.popupMediaFrame.requestLayout()
        }

        val otherViewsHeight = (if (binding.popupTitle.isVisible) binding.popupTitle.measuredHeight else 0) +
                (if (binding.popupMediaFrame.isVisible) (if (targetMediaHeight > 0) targetMediaHeight else binding.popupMediaFrame.measuredHeight) else 0) +
                binding.popupContainer.paddingTop + binding.popupContainer.paddingBottom + context.dpToPx(12)

        val maxScrollHeight = if (binding.popupContainer.orientation == LinearLayout.HORIZONTAL) (screenHeight * 0.7).toInt()
        else maxPopupHeight - otherViewsHeight

        val contentHeight = binding.popupMessage.measuredHeight
        if (contentHeight > maxScrollHeight) {
            binding.popupScrollView.layoutParams.height = maxScrollHeight.coerceAtLeast(context.dpToPx(100))
            binding.popupScrollView.requestLayout()
            if (!isScrolling) startAutoScroll()
        } else {
            binding.popupScrollView.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            binding.popupScrollView.requestLayout()
        }
    }

    private fun adjustHeights() {
        mainHandler.removeCallbacks(adjustHeightsRunnable)
        mainHandler.post(adjustHeightsRunnable)
    }

    private fun startAutoScroll() {
        if (isScrolling) return
        isScrolling = true

        val runnable = object : Runnable {
            var scrollPos = 0
            override fun run() {
                val maxScroll = binding.popupMessage.height - binding.popupScrollView.height
                if (maxScroll <= 0) { isScrolling = false; return }

                scrollPos += 1
                if (scrollPos > maxScroll) {
                    binding.popupScrollView.postDelayed({
                        scrollPos = 0
                        binding.popupScrollView.scrollTo(0, 0)
                        binding.popupScrollView.postDelayed(this, 2000)
                    }, 3000)
                    return
                }
                binding.popupScrollView.scrollTo(0, scrollPos)
                binding.popupScrollView.postDelayed(this, 30)
            }
        }
        binding.popupScrollView.postDelayed(runnable, 2000)
    }

    private fun setupMediaContent() {
        val frame = binding.popupMediaFrame
        isReadyCalled = false
        mainHandler.removeCallbacks(timeoutRunnable)

        val timeoutSec = settings.mediaTimeout
        if (timeoutSec > 0) {
            mainHandler.postDelayed(timeoutRunnable, timeoutSec * 1000L)
        }
        mediaRenderer.setup(context, frame, props)
    }

    /**
     * Starts media playback if the popup contains a video.
     */
    fun startMedia() {
        if (isCleanedUp) return
        mediaRenderer.startMedia()
    }

    /**
     * Cleans up resources, including stopping players and destroying WebViews.
     */
    fun cleanup() {
        if (isCleanedUp) return
        isCleanedUp = true
        mainHandler.removeCallbacksAndMessages(null)
        mediaRenderer.fullCleanup(binding.popupMediaFrame, props.media)
    }

    /**
     * Plays the entrance animation for the popup based on the configured animation type.
     */
    fun animateIn() {
        if (isCleanedUp) return
        animator.animateIn(props)
    }

    /**
     * Plays the exit animation for the popup and executes a completion callback.
     * @param completion The callback to execute when the animation finishes.
     */
    fun animateOut(completion: () -> Unit) {
        animator.animateOut(props, completion)
    }

    /**
     * Convenience builder method to create and initialize a [PopupView].
     */
    companion object {
        fun build(context: Context, props: PopupProps, listener: ReadyListener? = null): PopupView {
            return PopupView(context, props).apply { readyListener = listener }.create()
        }
    }
}
