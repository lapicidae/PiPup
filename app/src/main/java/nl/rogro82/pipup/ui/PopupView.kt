package nl.rogro82.pipup.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.toColorInt
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
    var onButton: ((PopupProps.Button) -> Unit)? = null
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
                targetMediaWidth = width
                targetMediaHeight = height
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

    private val mediaRendererCallback = MediaCallback()

    private val mediaRenderer = MediaRenderer(context, mainHandler, settings, mediaRendererCallback)

    private val animator = PopupAnimator(this)

    private var isScrolling = false
    private var targetMediaWidth = 0
    private var targetMediaHeight = 0
    private var isReadyCalled = false
    private var isCleanedUp = false
    private var lastMediaError: String? = null
    private var liveBadgeRevealed = false

    private fun handleWhepRetry(error: String, currentRetry: Int) {
        lastMediaError = error
        onMediaError?.invoke(error)
        val maxRetries = settings.mediaRetries
        if (currentRetry < maxRetries && !isCleanedUp) {
            val nextRetry = currentRetry + 1
            mainHandler.postDelayed({
                if (!isCleanedUp) {
                    mediaRenderer.setup(
                        context,
                        binding.popupMediaFrame,
                        props,
                        nextRetry,
                        targetMediaWidth,
                        targetMediaHeight
                    )
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
        errorMessage?.let { onMediaError?.invoke(it) }
        // Stop any active media loading to prevent late success callbacks from overriding the placeholder.
        mediaRenderer.cleanup()

        val frame = binding.popupMediaFrame
        val container = binding.popupMediaContainer
        frame.removeAllViews()
        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_banner)
            alpha = 0.5f
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        // Determine width once to keep layout stable
        val width = props.rawMediaWidth() ?: 480
        val tw = props.scaleValue(context, width)

        // Preserve original target height if already set (e.g. from WHEP props)
        // to prevent jumps during error state transitions.
        if (targetMediaHeight <= 0) {
            targetMediaHeight = (tw * 9) / 16
        }

        frame.layoutParams.width = tw
        frame.layoutParams.height = targetMediaHeight
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

        // Apply final heights synchronously so fullscreen/constrained bounds are accurate before onReady()
        applyHeightConstraints(notifyCallback = true)
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

        // The [popup.xml] layout already wraps [text_container] and [popup_media_frame]
        // inside the vertical [contentLayout] LinearLayout. This view group is the single
        // anchor used by [reorderViews] to arrange media and text (vertical for Top/
        // Bottom, horizontal for Left/Right). The outer [popupContainer] always stays
        // vertical so action buttons render below the content, centered horizontally.

        // Automatic cleanup when the view is detached from the window.
        addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                cleanup()
            }
        })
    }

    /**
     * Initializes the view, updates visuals, and sets up the media content.
     * @return The initialized [PopupView] instance.
     */
    fun create(): PopupView {
        calculateTargetDimensions(props.resolveEffectiveMedia(), props)
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
        calculateTargetDimensions(newMedia, newProps)
        updateVisuals()

        if (contentChanged || newMedia is PopupProps.Media.Bitmap) {
            setupMediaContent()
        } else {
            // Handle property-only updates (e.g. WHEP videoFit, fullscreenUpscale, or liveBadge config) without full reload
            mediaRenderer.updateFullscreenProps(newProps)
            if (oldMedia is PopupProps.Media.Whep && newMedia is PopupProps.Media.Whep) {
                if (oldMedia.videoFit != newMedia.videoFit) {
                    Log.d("PopupView", "Updating WHEP videoFit dynamically to ${newMedia.videoFit}")
                    mediaRenderer.updateWhepVideoFit(newMedia.videoFit)
                }
            }
            configureLiveBadgeAppearance()
            if (liveBadgeRevealed) {
                revealLiveBadge()
            }
            adjustHeights()
        }
    }

    private fun calculateTargetDimensions(media: PopupProps.Media?, props: PopupProps) {
        val width = props.rawMediaWidth() ?: 480
        targetMediaWidth = props.scaleValue(context, width)

        targetMediaHeight = when (media) {
            is PopupProps.Media.Video -> media.height?.let { props.scaleValue(context, it) } ?: ((targetMediaWidth * 9) / 16)
            is PopupProps.Media.Web -> props.scaleValue(context, media.height)
            is PopupProps.Media.Whep -> media.height?.let { props.scaleValue(context, it) } ?: ((targetMediaWidth * 9) / 16)
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
            is PopupProps.Media.Whep -> (m2 as PopupProps.Media.Whep).let {
                m1.uri == it.uri && m1.scale == it.scale && m1.height == it.height && m1.videoFit == it.videoFit
            }
            is PopupProps.Media.LocalFile -> m1.path == (m2 as PopupProps.Media.LocalFile).path
            else -> m1 == m2
        }
    }

    private fun isFullBleedActive(): Boolean {
        return (props.mediaFullBleed ?: settings.mediaFullBleed) && props.hasMedia
    }

    private fun updateVisuals() {
        if (layoutParams == null) {
            layoutParams = ViewGroup.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        }
        alpha = 1.0f
        if (background != null) background = null
        setPadding(0, 0, 0, 0)

        // 1. Padding & Full-Bleed Layout
        val fullBleed = isFullBleedActive()
        val hasAnyText = !props.title.isNullOrBlank() || !props.message.isNullOrBlank()
        val hasButtons = props.buttons.isNotEmpty()
        val paddingVal = props.contentPadding ?: settings.contentPadding
        val scaledPadding = if (props.scale) context.getScaledPixels(paddingVal) else paddingVal
        val borderPx = if (props.borderWidth > 0) {
            if (props.scale) context.getScaledPixels(props.borderWidth) else props.borderWidth
        } else {
            0
        }
        val radiusPx = if (props.scale) context.getScaledPixels(props.borderRadius).toFloat() else props.borderRadius.toFloat()

        if (fullBleed) {
            val gap = (scaledPadding * 2) / 3
            val pos = props.mediaPosition ?: 0
            val bottomContainerPad = if ((pos == 0) && (hasAnyText || hasButtons)) gap else borderPx
            val topContainerPad = if (pos == 1 && hasAnyText) gap else borderPx
            binding.popupContainer.setPadding(borderPx, topContainerPad, borderPx, bottomContainerPad)
            if (hasAnyText || (props.buttonsInline && hasButtons)) {
                val textTop = if (pos == 0) gap else scaledPadding
                val textBottom = when (pos) {
                    1 -> gap
                    2, 3 -> scaledPadding
                    else -> 0
                }
                binding.textContainer.setPadding(scaledPadding, textTop, scaledPadding, textBottom)
            } else {
                binding.textContainer.setPadding(0, 0, 0, 0)
            }
            applyFullBleedOutline(pos, radiusPx, borderPx, hasAnyText || hasButtons)
        } else {
            val effectivePad = maxOf(scaledPadding, borderPx)
            binding.popupContainer.setPadding(effectivePad, effectivePad, effectivePad, effectivePad)
            binding.textContainer.setPadding(0, 0, 0, 0)
            binding.popupMediaContainer.clipToOutline = false
            binding.popupMediaContainer.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        binding.popupMessage.maxLines = Integer.MAX_VALUE
        binding.popupMessage.ellipsize = null

        // 2. Background
        binding.popupContainer.background = GradientDrawable().apply {
            setColor(props.getBackgroundColorInt())
            cornerRadius = radiusPx
            if (borderPx > 0) {
                setStroke(borderPx, props.getBorderColorInt())
            }
        }

        // 3. Constraints
        val maxTextWidth = if (props.scale) context.getScaledPixels(500) else 500
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
        } ?: run {
            binding.popupMessage.isVisible = false
            binding.popupScrollView.isVisible = false
        }

        updateButtons()
        applyHeightConstraints(notifyCallback = false)
    }

    private fun applyFullBleedOutline(mediaPos: Int, radiusPx: Float, borderPx: Int, hasOtherContent: Boolean) {
        val clip = (radiusPx - borderPx).coerceAtLeast(0f)
        val extra = clip.toInt()
        val mediaContainer = binding.popupMediaContainer
        if (clip <= 0f) {
            mediaContainer.clipToOutline = false
            return
        }
        mediaContainer.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                if (!hasOtherContent) {
                    outline.setRoundRect(0, 0, view.width, view.height, clip)
                    return
                }
                when (mediaPos) {
                    1 -> outline.setRoundRect(0, -extra, view.width, view.height, clip) // Bottom corners rounded
                    2 -> outline.setRoundRect(0, 0, view.width + extra, view.height, clip) // Left corners rounded
                    3 -> outline.setRoundRect(-extra, 0, view.width, view.height, clip) // Right corners rounded
                    else -> outline.setRoundRect(0, 0, view.width, view.height + extra, clip) // Top corners rounded
                }
            }
        }
        mediaContainer.clipToOutline = true
    }

    private fun hasTextOrInlineButtons(): Boolean {
        val hasAnyText = !props.title.isNullOrBlank() || !props.message.isNullOrBlank()
        val hasInlineButtons = props.buttonsInline && props.buttons.isNotEmpty()
        return hasAnyText || hasInlineButtons
    }

    private fun reorderViews() {
        val container = binding.contentLayout
        val textContainer = binding.textContainer
        val mediaFrame = binding.popupMediaContainer

        val pos = props.mediaPosition ?: 0
        val hasTextSection = hasTextOrInlineButtons()
        textContainer.isVisible = hasTextSection
        val noMediaGap = isFullBleedActive() || !hasTextSection
        val marginV = if (noMediaGap) 0 else context.dpToPx(8)
        val marginH = if (noMediaGap) 0 else context.dpToPx(12)

        if (container.tag == pos && textContainer.parent == container && mediaFrame.parent == container) {
            // Already in correct order. Update existing layout params & margins.
            (mediaFrame.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                if (targetMediaWidth > 0 && targetMediaHeight > 0) {
                    lp.width = targetMediaWidth
                    lp.height = targetMediaHeight
                }
                when (pos) {
                    0 -> lp.setMargins(0, 0, 0, marginV)
                    1 -> lp.setMargins(0, marginV, 0, 0)
                    2 -> lp.setMargins(0, 0, marginH, 0)
                    3 -> lp.setMargins(marginH, 0, 0, 0)
                }
            }
            return
        }

        // Extremely careful reordering: only detach if absolutely necessary
        // to avoid WebView surface destruction.
        if (textContainer.parent != null && textContainer.parent != container) {
            (textContainer.parent as ViewGroup).removeView(textContainer)
        }
        if (mediaFrame.parent != null && mediaFrame.parent != container) {
            (mediaFrame.parent as ViewGroup).removeView(mediaFrame)
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

    private fun setupVertical(container: LinearLayout, first: View, second: View, mediaFirst: Boolean) {
        container.orientation = LinearLayout.VERTICAL
        val margin = if (isFullBleedActive() || !hasTextOrInlineButtons()) 0 else context.dpToPx(8)

        val firstWidth = if (first == binding.popupMediaContainer && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val firstHeight = if (first == binding.popupMediaContainer && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val firstParams = LinearLayout.LayoutParams(firstWidth, firstHeight).apply {
            if (first != binding.textContainer && mediaFirst) gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, if (mediaFirst) margin else 0)
        }

        val secondWidth = if (second == binding.popupMediaContainer && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val secondHeight = if (second == binding.popupMediaContainer && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val secondParams = LinearLayout.LayoutParams(secondWidth, secondHeight).apply {
            if (second != binding.textContainer && !mediaFirst) gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, if (!mediaFirst) margin else 0, 0, 0)
        }

        container.addView(first, firstParams)
        container.addView(second, secondParams)
    }

    private fun setupHorizontal(container: LinearLayout, first: View, second: View, mediaFirst: Boolean) {
        container.orientation = LinearLayout.HORIZONTAL
        val margin = if (isFullBleedActive() || !hasTextOrInlineButtons()) 0 else context.dpToPx(12)

        val firstWidth = if (first == binding.popupMediaContainer && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val firstHeight = if (first == binding.popupMediaContainer && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val firstParams = LinearLayout.LayoutParams(firstWidth, firstHeight).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(0, 0, if (mediaFirst) margin else 0, 0)
        }

        val secondWidth = if (second == binding.popupMediaContainer && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val secondHeight = if (second == binding.popupMediaContainer && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val secondParams = LinearLayout.LayoutParams(secondWidth, secondHeight).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(if (!mediaFirst) margin else 0, 0, 0, 0)
        }

        container.addView(first, firstParams)
        container.addView(second, secondParams)
    }

    private val adjustHeightsRunnable = Runnable {
        applyHeightConstraints(notifyCallback = true)
    }

    /**
     * Synchronously applies media and scroll height constraints prior to view measurement.
     */
    fun syncLayoutForMeasurement() {
        applyHeightConstraints(notifyCallback = false)
    }

    private fun measureViewHeightWithMargins(view: View?, maxWidth: Int, maxHeight: Int): Int {
        if (view == null || !view.isVisible) return 0
        view.measure(
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        )
        val lp = view.layoutParams as? MarginLayoutParams
        return view.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
    }

    private fun measureFullscreenTextSection(
        innerWidth: Int,
        contentAvailHeight: Int,
        hasMedia: Boolean,
        isHorizontal: Boolean
    ): Pair<Int, Int> {
        val maxTextColWidth = if (isHorizontal && hasMedia) {
            (innerWidth * 0.35f).toInt().coerceAtLeast(context.dpToPx(160))
        } else {
            innerWidth
        }
        (binding.textContainer.layoutParams as? LinearLayout.LayoutParams)?.width =
            if (!isHorizontal) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT

        val textInnerMaxWidth = (maxTextColWidth - binding.textContainer.paddingLeft - binding.textContainer.paddingRight)
            .coerceAtLeast(1)
        binding.popupTitle.maxWidth = textInnerMaxWidth
        binding.popupMessage.maxWidth = textInnerMaxWidth

        val titleHeight = if (binding.popupTitle.isVisible) {
            binding.popupTitle.measure(
                MeasureSpec.makeMeasureSpec(textInnerMaxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            binding.popupTitle.measuredHeight
        } else {
            0
        }

        val inlineButtonRow = binding.textContainer.findViewWithTag<ViewGroup>("popup_button_row")
        val inlineButtonHeight = measureViewHeightWithMargins(inlineButtonRow, textInnerMaxWidth, contentAvailHeight)

        if (binding.popupMessage.isVisible) {
            binding.popupMessage.measure(
                MeasureSpec.makeMeasureSpec(textInnerMaxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val scrollTopMargin = (binding.popupScrollView.layoutParams as? MarginLayoutParams)?.topMargin ?: 0
            val textPadV = binding.textContainer.paddingTop + binding.textContainer.paddingBottom
            val maxTextSectionHeight = if (hasMedia && !isHorizontal) {
                (contentAvailHeight * 0.35f).toInt()
            } else {
                contentAvailHeight
            }
            val maxScrollHeight = (maxTextSectionHeight - titleHeight - scrollTopMargin - textPadV - inlineButtonHeight)
                .coerceAtLeast(context.dpToPx(48))
            if (binding.popupMessage.measuredHeight > maxScrollHeight) {
                binding.popupScrollView.layoutParams.height = maxScrollHeight
                if (!isScrolling) startAutoScroll()
            } else {
                binding.popupScrollView.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            }
            binding.popupScrollView.requestLayout()
        }

        val textWidthSpec = if (!isHorizontal) {
            MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY)
        } else {
            MeasureSpec.makeMeasureSpec(maxTextColWidth, MeasureSpec.AT_MOST)
        }
        binding.textContainer.measure(
            textWidthSpec,
            MeasureSpec.makeMeasureSpec(contentAvailHeight, MeasureSpec.AT_MOST)
        )
        return binding.textContainer.measuredWidth to binding.textContainer.measuredHeight
    }

    private fun applyFullscreenMediaLayout(
        innerWidth: Int,
        contentAvailHeight: Int,
        textSize: Pair<Int, Int>,
        hasTextSection: Boolean,
        isHorizontal: Boolean
    ) {
        val (textMeasuredW, textMeasuredH) = textSize
        val mediaLp = binding.popupMediaContainer.layoutParams as? LinearLayout.LayoutParams
        if (!isHorizontal) {
            val gapV = if (hasTextSection) ((mediaLp?.topMargin ?: 0) + (mediaLp?.bottomMargin ?: 0)) else 0
            targetMediaWidth = innerWidth
            targetMediaHeight = (contentAvailHeight - textMeasuredH - gapV).coerceAtLeast(1)
        } else {
            val gapH = if (hasTextSection) ((mediaLp?.leftMargin ?: 0) + (mediaLp?.rightMargin ?: 0)) else 0
            targetMediaWidth = (innerWidth - textMeasuredW - gapH).coerceAtLeast(1)
            targetMediaHeight = contentAvailHeight
        }
        binding.popupMediaContainer.layoutParams.width = targetMediaWidth
        binding.popupMediaContainer.layoutParams.height = targetMediaHeight
        binding.popupMediaContainer.requestLayout()
        mediaRenderer.updateFullscreenProps(props)
        mediaRenderer.updateFullscreenBounds(binding.popupMediaFrame, targetMediaWidth, targetMediaHeight)
        updateLiveBadgeMargins()
    }

    private fun applyFullscreenLayoutConstraints() {
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val hasMedia = props.hasMedia
        val hasTextSection = hasTextOrInlineButtons()

        binding.popupContainer.layoutParams = (binding.popupContainer.layoutParams ?: LayoutParams(
            screenWidth,
            screenHeight
        )).apply {
            width = screenWidth
            height = screenHeight
        }

        (binding.contentLayout.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = LinearLayout.LayoutParams.MATCH_PARENT
            lp.height = if (hasMedia) LinearLayout.LayoutParams.WRAP_CONTENT else 0
            lp.weight = if (hasMedia) 0f else 1f
            binding.contentLayout.gravity = if (hasMedia) Gravity.NO_GRAVITY else Gravity.CENTER
        }

        val innerWidth = (screenWidth - binding.popupContainer.paddingLeft - binding.popupContainer.paddingRight)
            .coerceAtLeast(1)
        val innerHeight = (screenHeight - binding.popupContainer.paddingTop - binding.popupContainer.paddingBottom)
            .coerceAtLeast(1)

        val buttonRow = binding.popupContainer.findViewWithTag<ViewGroup>("popup_button_row")
        val buttonRowHeight = measureViewHeightWithMargins(buttonRow, innerWidth, innerHeight)
        val contentAvailHeight = (innerHeight - buttonRowHeight).coerceAtLeast(1)
        val isHorizontal = binding.contentLayout.orientation == LinearLayout.HORIZONTAL

        binding.textContainer.isVisible = hasTextSection
        val textSize = if (hasTextSection) {
            measureFullscreenTextSection(innerWidth, contentAvailHeight, hasMedia, isHorizontal)
        } else {
            0 to 0
        }

        if (hasMedia) {
            applyFullscreenMediaLayout(innerWidth, contentAvailHeight, textSize, hasTextSection, isHorizontal)
        }
    }

    private fun updateLiveBadgeMargins() {
        val baseMargin = context.dpToPx(8)
        val frameW = binding.popupMediaFrame.layoutParams.width
        val frameH = binding.popupMediaFrame.layoutParams.height
        val insetH = if (props.coversScreen && targetMediaWidth > frameW && frameW > 0) {
            (targetMediaWidth - frameW) / 2
        } else {
            0
        }
        val insetV = if (props.coversScreen && targetMediaHeight > frameH && frameH > 0) {
            (targetMediaHeight - frameH) / 2
        } else {
            0
        }
        (binding.popupLiveBadge.layoutParams as? LayoutParams)?.let { lp ->
            lp.setMargins(baseMargin + insetH, baseMargin + insetV, baseMargin + insetH, baseMargin + insetV)
            binding.popupLiveBadge.layoutParams = lp
        }
    }

    private fun applyHeightConstraints(notifyCallback: Boolean) {
        if (isCleanedUp) return
        if (props.coversScreen) {
            applyFullscreenLayoutConstraints()
            if (notifyCallback) {
                onLayoutAdjusted?.invoke()
            }
            return
        }
        updateLiveBadgeMargins()

        binding.popupContainer.layoutParams?.let { lp ->
            lp.width = LayoutParams.WRAP_CONTENT
            lp.height = LayoutParams.WRAP_CONTENT
        }
        (binding.contentLayout.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
            lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
            binding.contentLayout.gravity = Gravity.NO_GRAVITY
        }
        (binding.textContainer.layoutParams as? LinearLayout.LayoutParams)?.width =
            LinearLayout.LayoutParams.WRAP_CONTENT
        binding.textContainer.isVisible = hasTextOrInlineButtons()

        val screenHeight = resources.displayMetrics.heightPixels
        val maxPopupHeight = (screenHeight * 0.85).toInt()

        if (binding.popupMediaFrame.isVisible && targetMediaWidth > 0 && targetMediaHeight > 0) {
            if (binding.popupMediaFrame.layoutParams.width != targetMediaWidth || binding.popupMediaFrame.layoutParams.height != targetMediaHeight) {
                binding.popupMediaFrame.layoutParams.width = targetMediaWidth
                binding.popupMediaFrame.layoutParams.height = targetMediaHeight
                binding.popupMediaFrame.requestLayout()
            }
            if (binding.popupMediaContainer.layoutParams.width != targetMediaWidth || binding.popupMediaContainer.layoutParams.height != targetMediaHeight) {
                binding.popupMediaContainer.layoutParams.width = targetMediaWidth
                binding.popupMediaContainer.layoutParams.height = targetMediaHeight
                binding.popupMediaContainer.requestLayout()
            }
        }

        val maxTextWidth = if (props.scale) context.getScaledPixels(500) else 500
        binding.popupTitle.maxWidth = maxTextWidth
        binding.popupMessage.maxWidth = maxTextWidth
        if (binding.popupTitle.isVisible && binding.popupTitle.measuredHeight <= 0) {
            binding.popupTitle.measure(
                MeasureSpec.makeMeasureSpec(maxTextWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }

        val buttonRow = binding.popupContainer.findViewWithTag<ViewGroup>("popup_button_row")
        val buttonRowHeight = if (buttonRow != null && buttonRow.isVisible) {
            if (buttonRow.measuredHeight <= 0) {
                buttonRow.measure(
                    MeasureSpec.makeMeasureSpec(maxTextWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
            }
            buttonRow.measuredHeight + ((buttonRow.layoutParams as? MarginLayoutParams)?.topMargin ?: 0)
        } else 0

        val otherViewsHeight = (if (binding.popupTitle.isVisible) binding.popupTitle.measuredHeight else 0) +
                (if (binding.popupMediaFrame.isVisible) (if (targetMediaHeight > 0) targetMediaHeight else binding.popupMediaFrame.measuredHeight) else 0) +
                binding.popupContainer.paddingTop + binding.popupContainer.paddingBottom + context.dpToPx(12) +
                buttonRowHeight

        val maxScrollHeight = if (binding.contentLayout.orientation == LinearLayout.HORIZONTAL) (screenHeight * 0.45).toInt()
        else (maxPopupHeight - otherViewsHeight).coerceAtLeast(context.dpToPx(150))

        binding.popupMessage.measure(
            MeasureSpec.makeMeasureSpec(maxTextWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )

        val contentHeight = binding.popupMessage.measuredHeight
        if (contentHeight > maxScrollHeight) {
            binding.popupScrollView.layoutParams.height = maxScrollHeight.coerceAtLeast(context.dpToPx(100))
            binding.popupScrollView.requestLayout()
            if (!isScrolling) startAutoScroll()
        } else {
            binding.popupScrollView.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            binding.popupScrollView.requestLayout()
        }
        if (notifyCallback) {
            onLayoutAdjusted?.invoke()
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
                val msgH = if (binding.popupMessage.height > 0) binding.popupMessage.height else binding.popupMessage.measuredHeight
                val scrollH = if (binding.popupScrollView.height > 0) binding.popupScrollView.height else binding.popupScrollView.measuredHeight
                val maxScroll = msgH - scrollH
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

    private fun isStreamMedia(): Boolean {
        return props.media is PopupProps.Media.Video ||
            props.media is PopupProps.Media.Whep ||
            props.media is PopupProps.Media.Web
    }

    private fun configureLiveBadgeAppearance() {
        val badge = binding.popupLiveBadge
        val defaultBadgeColor = androidx.core.content.ContextCompat.getColor(context, R.color.live_badge_background)
        val badgeColor = props.liveBadgeColor?.let { colorStr ->
            try {
                val clean = if (colorStr.startsWith("#")) colorStr else "#$colorStr"
                clean.toColorInt()
            } catch (_: Exception) {
                defaultBadgeColor
            }
        } ?: defaultBadgeColor
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
            badge.animate().alpha(1f).setDuration(150L).start()
        }
    }

    private fun setupMediaContent() {
        val frame = binding.popupMediaFrame
        val container = binding.popupMediaContainer
        isReadyCalled = false
        liveBadgeRevealed = false
        mainHandler.removeCallbacks(timeoutRunnable)

        val timeoutSec = settings.mediaTimeout
        if (timeoutSec > 0) {
            mainHandler.postDelayed(timeoutRunnable, timeoutSec * 1000L)
        }

        // Live badge remains hidden until the stream's first frame renders (never shown on poster/placeholder).
        // In settings preview mode (Bitmap placeholder), show it immediately when enabled.
        binding.popupLiveBadge.animate().cancel()
        binding.popupLiveBadge.isVisible = false
        configureLiveBadgeAppearance()
        if (props.isPreview && (props.liveBadge ?: settings.liveBadge)) {
            revealLiveBadge()
        }

        container.isVisible = props.hasMedia
        mediaRenderer.setup(
            context,
            frame,
            props,
            targetWidth = targetMediaWidth,
            targetHeight = targetMediaHeight
        )
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

    private fun updateButtons() {
        val existingRowPopup = binding.popupContainer.findViewWithTag<ViewGroup>("popup_button_row")
        existingRowPopup?.let { binding.popupContainer.removeView(it) }
        val existingRowText = binding.textContainer.findViewWithTag<ViewGroup>("popup_button_row")
        existingRowText?.let { binding.textContainer.removeView(it) }

        if (props.buttons.isEmpty()) return

        val buttonRow = LinearLayout(context).apply {
            tag = "popup_button_row"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            if (isFullBleedActive() && !props.buttonsInline) {
                val contentPadding = (props.contentPadding ?: settings.contentPadding).coerceAtLeast(0)
                val scaledPadding = if (props.scale) {
                    context.getScaledPixels(contentPadding)
                } else {
                    contentPadding
                }
                setPadding(scaledPadding, 0, scaledPadding, 0)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.dpToPx(12)
            }
        }

        var firstButton: Button? = null

        for (i in props.buttons.indices) {
            val btn = props.buttons[i]
            val isFirst = i == 0
            val size = props.buttonSize?.takeIf { it >= 8f }
                ?: settings.defaultButtonSize.takeIf { it >= 8f }
                ?: (props.messageSize * 0.85f).coerceIn(12f, 24f)
            val button = Button(context).apply {
                text = btn.label
                isFocusable = !props.isPreview
                isClickable = !props.isPreview
                val normalTextColor = parseColorOrDefault(props.buttonTextColor ?: settings.defaultButtonTextColor, "#FFFFFF")
                val focusTextColor = parseColorOrDefault(props.buttonFocusTextColor ?: settings.defaultButtonFocusTextColor, "#FFFFFF")
                if (props.isPreview && isFirst) {
                    setTextColor(focusTextColor)
                } else {
                    setTextColor(createButtonTextColorStateList(normalTextColor, focusTextColor))
                }
                textSize = size
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                val scaleFactor = size / 14f
                val h = context.dpToPx((24f * scaleFactor).toInt()).coerceAtLeast(8)
                val v = context.dpToPx((10f * scaleFactor).toInt()).coerceAtLeast(4)
                setPadding(h, v, h, v)
                minHeight = context.dpToPx(40)
                background = if (props.isPreview && isFirst) {
                    createButtonBackground(props, forceFocused = true)
                } else {
                    createButtonBackground(props, forceFocused = false)
                }
                setOnClickListener { if (!props.isPreview) onButton?.invoke(btn) }
                val m = context.dpToPx((8f * scaleFactor).toInt()).coerceAtLeast(2)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    setMargins(m, m, m, context.dpToPx((4f * scaleFactor).toInt()).coerceAtLeast(2))
                }
            }
            if (firstButton == null) firstButton = button
            buttonRow.addView(button)
        }

        if (props.buttonsInline) {
            binding.textContainer.addView(buttonRow)
        } else {
            binding.popupContainer.addView(buttonRow)
        }
        if (!props.isPreview) {
            firstButton?.post { firstButton.requestFocus() }
        }
    }

    private fun createButtonBackground(props: PopupProps, forceFocused: Boolean = false): android.graphics.drawable.Drawable {
        val radius = props.buttonCornerRadius ?: settings.defaultButtonCornerRadius
        val radiusPx = if (props.scale) context.getScaledPixels(radius.toInt()).toFloat() else radius

        val normalColor = parseColorOrDefault(props.buttonColor ?: settings.defaultButtonColor, "#EAEEF2")
        val focusColor = parseColorOrDefault(props.buttonFocusColor ?: settings.defaultButtonFocusColor, "#196584")
        val borderColor = parseColorOrDefault(props.buttonBorderColor ?: settings.defaultButtonBorderColor, "#71787D")

        val normalDrawable = GradientDrawable().apply {
            setColor(normalColor)
            cornerRadius = radiusPx
            setStroke(context.dpToPx(1), borderColor)
        }

        val focusDrawable = GradientDrawable().apply {
            setColor(focusColor)
            cornerRadius = radiusPx
            setStroke(context.dpToPx(2), focusColor)
        }

        return if (forceFocused) {
            focusDrawable
        } else {
            StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusDrawable)
                addState(intArrayOf(android.R.attr.state_pressed), focusDrawable)
                addState(intArrayOf(), normalDrawable)
            }
        }
    }

    private fun createButtonTextColorStateList(normalColor: Int, focusColor: Int): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf()
            ),
            intArrayOf(focusColor, focusColor, normalColor)
        )
    }

    private fun parseColorOrDefault(hex: String, fallback: String): Int {
        return try {
            val clean = if (hex.startsWith("#")) hex else "#$hex"
            clean.toColorInt()
        } catch (_: Exception) {
            fallback.toColorInt()
        }
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
