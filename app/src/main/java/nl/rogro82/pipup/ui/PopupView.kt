package nl.rogro82.pipup.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
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
    private var closeButtonHidden = false
    private var closeButtonWakeConsumedDown = false
    private var lastCloseButtonPosition: String? = null
    private val closeButtonAutoHideRunnable = Runnable { fadeOutCloseButton() }

    /**
     * Returns true while the overlay close button is currently faded out by its auto-hide timer.
     */
    fun isCloseButtonHidden(): Boolean = closeButtonHidden

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleCloseButtonKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val closeBtn = binding.popupCloseButton
        if (ev.action == MotionEvent.ACTION_DOWN && closeBtn.isVisible && !props.isPreview) {
            if (closeButtonHidden) {
                revealCloseButton()
                val x = ev.x.toInt()
                val y = ev.y.toInt()
                if (x in closeBtn.left..closeBtn.right && y in closeBtn.top..closeBtn.bottom) {
                    return true
                }
            } else {
                scheduleCloseButtonAutoHide()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleCloseButtonKeyEvent(event: KeyEvent): Boolean {
        val closeBtn = binding.popupCloseButton
        return closeBtn.isVisible && !props.isPreview && when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val wasHidden = closeButtonHidden
                    revealCloseButton()
                    wasHidden && props.buttons.isEmpty()
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> handleCloseButtonConfirmKey(event, closeBtn)
            else -> false
        }
    }

    private fun handleCloseButtonConfirmKey(event: KeyEvent, closeBtn: View): Boolean = when {
        !closeBtn.hasFocus() && props.buttons.isNotEmpty() -> false
        event.action == KeyEvent.ACTION_DOWN && closeButtonHidden -> {
            closeButtonWakeConsumedDown = true
            revealCloseButton()
            true
        }
        event.action == KeyEvent.ACTION_UP && closeButtonWakeConsumedDown -> {
            closeButtonWakeConsumedDown = false
            true
        }
        else -> false
    }

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
            }, MS_PER_SECOND * nextRetry)
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
            targetMediaHeight = (tw * PopupProps.ASPECT_16_9_HEIGHT) / PopupProps.ASPECT_16_9_WIDTH
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
            override fun onViewAttachedToWindow(v: View) {
                // Only detachment requires resource cleanup.
            }
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
            // Handle property-only updates (e.g. WHEP videoFit, fullscreenUpscale, liveBadge) without reload
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

        val default16By9Height = (targetMediaWidth * PopupProps.ASPECT_16_9_HEIGHT) / PopupProps.ASPECT_16_9_WIDTH
        targetMediaHeight = when (media) {
            is PopupProps.Media.Video ->
                media.height?.let { props.scaleValue(context, it) } ?: default16By9Height
            is PopupProps.Media.Web -> props.scaleValue(context, media.height)
            is PopupProps.Media.Whep ->
                media.height?.let { props.scaleValue(context, it) } ?: default16By9Height
            is PopupProps.Media.Bitmap -> (targetMediaWidth * media.bitmap.height) / media.bitmap.width
            else -> 0 // For images, height is determined after load in renderGlide
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

    private fun isFullBleedActive(): Boolean {
        return (props.mediaFullBleed ?: settings.mediaFullBleed) && props.hasMedia
    }

    private fun scalePx(px: Int): Int = if (props.scale) context.getScaledPixels(px) else px

    private fun updateVisuals() {
        if (layoutParams == null) {
            layoutParams = ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        alpha = 1.0f
        if (background != null) background = null
        setPadding(0, 0, 0, 0)

        val scaledPadding = scalePx(props.contentPadding ?: settings.contentPadding)
        val borderPx = if (props.borderWidth > 0) scalePx(props.borderWidth) else 0
        val radiusPx = scalePx(props.borderRadius).toFloat()

        applyContainerPaddingAndOutline(scaledPadding, borderPx, radiusPx)
        binding.popupMessage.maxLines = Integer.MAX_VALUE
        binding.popupMessage.ellipsize = null

        binding.popupContainer.background = GradientDrawable().apply {
            setColor(props.getBackgroundColorInt())
            cornerRadius = radiusPx
            if (borderPx > 0) setStroke(borderPx, props.getBorderColorInt())
        }

        val maxTextWidth = scalePx(MAX_TEXT_WIDTH_PX)
        binding.popupTitle.maxWidth = maxTextWidth
        binding.popupMessage.maxWidth = maxTextWidth

        reorderViews()
        bindTextContent()
        updateButtons()
        updateCloseButton()
        applyHeightConstraints(notifyCallback = false)
    }

    private fun applyContainerPaddingAndOutline(scaledPadding: Int, borderPx: Int, radiusPx: Float) {
        if (!isFullBleedActive()) {
            val effectivePad = maxOf(scaledPadding, borderPx)
            binding.popupContainer.setPadding(effectivePad, effectivePad, effectivePad, effectivePad)
            binding.textContainer.setPadding(0, 0, 0, 0)
            binding.popupMediaContainer.clipToOutline = false
            binding.popupMediaContainer.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            return
        }

        val hasAnyText = !props.title.isNullOrBlank() || !props.message.isNullOrBlank()
        val hasButtons = props.buttons.isNotEmpty()
        val gap = (scaledPadding * 2) / FULL_BLEED_GAP_DIVISOR
        val pos = props.mediaPosition ?: MEDIA_POS_TOP
        val bottomPad = if (pos == MEDIA_POS_TOP && (hasAnyText || hasButtons)) gap else borderPx
        val topPad = if (pos == MEDIA_POS_BOTTOM && hasAnyText) gap else borderPx
        binding.popupContainer.setPadding(borderPx, topPad, borderPx, bottomPad)

        applyFullBleedTextPadding(pos, scaledPadding, gap, hasAnyText || (props.buttonsInline && hasButtons))
        applyFullBleedOutline(pos, radiusPx, borderPx, hasAnyText || hasButtons)
    }

    private fun applyFullBleedTextPadding(pos: Int, scaledPadding: Int, gap: Int, hasPaddedContent: Boolean) {
        if (!hasPaddedContent) {
            binding.textContainer.setPadding(0, 0, 0, 0)
            return
        }
        val textTop = if (pos == MEDIA_POS_TOP) gap else scaledPadding
        val textBottom = when (pos) {
            MEDIA_POS_BOTTOM -> gap
            MEDIA_POS_LEFT, MEDIA_POS_RIGHT -> scaledPadding
            else -> 0
        }
        binding.textContainer.setPadding(scaledPadding, textTop, scaledPadding, textBottom)
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
                    MEDIA_POS_BOTTOM -> outline.setRoundRect(0, -extra, view.width, view.height, clip)
                    MEDIA_POS_LEFT -> outline.setRoundRect(0, 0, view.width + extra, view.height, clip)
                    MEDIA_POS_RIGHT -> outline.setRoundRect(-extra, 0, view.width, view.height, clip)
                    else -> outline.setRoundRect(0, 0, view.width, view.height + extra, clip)
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

        val pos = props.mediaPosition ?: MEDIA_POS_TOP
        val hasTextSection = hasTextOrInlineButtons()
        textContainer.isVisible = hasTextSection

        if (container.tag == pos && textContainer.parent == container && mediaFrame.parent == container) {
            updateExistingMediaMargins(mediaFrame, pos, isFullBleedActive() || !hasTextSection)
            return
        }

        // Extremely careful reordering: only detach if absolutely necessary
        // to avoid WebView surface destruction.
        detachFromParent(textContainer)
        detachFromParent(mediaFrame)

        container.tag = pos
        when (pos) {
            MEDIA_POS_TOP -> setupVertical(container, mediaFrame, textContainer, true)
            MEDIA_POS_BOTTOM -> setupVertical(container, textContainer, mediaFrame, false)
            MEDIA_POS_LEFT -> setupHorizontal(container, mediaFrame, textContainer, true)
            MEDIA_POS_RIGHT -> setupHorizontal(container, textContainer, mediaFrame, false)
        }
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun updateExistingMediaMargins(mediaFrame: View, pos: Int, noMediaGap: Boolean) {
        val marginV = if (noMediaGap) 0 else context.dpToPx(MEDIA_GAP_VERTICAL_DP)
        val marginH = if (noMediaGap) 0 else context.dpToPx(MEDIA_GAP_HORIZONTAL_DP)
        val lp = mediaFrame.layoutParams as? LinearLayout.LayoutParams ?: return
        if (targetMediaWidth > 0 && targetMediaHeight > 0) {
            lp.width = targetMediaWidth
            lp.height = targetMediaHeight
        }
        when (pos) {
            MEDIA_POS_TOP -> lp.setMargins(0, 0, 0, marginV)
            MEDIA_POS_BOTTOM -> lp.setMargins(0, marginV, 0, 0)
            MEDIA_POS_LEFT -> lp.setMargins(0, 0, marginH, 0)
            MEDIA_POS_RIGHT -> lp.setMargins(marginH, 0, 0, 0)
        }
    }

    private fun createChildLayoutParams(view: View): LinearLayout.LayoutParams {
        val isMedia = view == binding.popupMediaContainer
        val w = if (isMedia && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val h = if (isMedia && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT
        return LinearLayout.LayoutParams(w, h)
    }

    private fun setupVertical(container: LinearLayout, first: View, second: View, mediaFirst: Boolean) {
        container.orientation = LinearLayout.VERTICAL
        val margin = if (isFullBleedActive() || !hasTextOrInlineButtons()) 0 else context.dpToPx(MEDIA_GAP_VERTICAL_DP)

        val firstParams = createChildLayoutParams(first).apply {
            if (first != binding.textContainer && mediaFirst) gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, if (mediaFirst) margin else 0)
        }
        val secondParams = createChildLayoutParams(second).apply {
            if (second != binding.textContainer && !mediaFirst) gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, if (!mediaFirst) margin else 0, 0, 0)
        }

        container.addView(first, firstParams)
        container.addView(second, secondParams)
    }

    private fun setupHorizontal(container: LinearLayout, first: View, second: View, mediaFirst: Boolean) {
        container.orientation = LinearLayout.HORIZONTAL
        val margin = if (isFullBleedActive() || !hasTextOrInlineButtons()) {
            0
        } else {
            context.dpToPx(MEDIA_GAP_HORIZONTAL_DP)
        }

        val firstParams = createChildLayoutParams(first).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(0, 0, if (mediaFirst) margin else 0, 0)
        }
        val secondParams = createChildLayoutParams(second).apply {
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

        val textInnerMaxWidth = (
            maxTextColWidth - binding.textContainer.paddingLeft - binding.textContainer.paddingRight
            ).coerceAtLeast(1)
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
            if (notifyCallback) onLayoutAdjusted?.invoke()
            return
        }
        updateLiveBadgeMargins()
        resetNonFullscreenContainers()
        syncTargetMediaDimensions()

        val maxTextWidth = scalePx(MAX_TEXT_WIDTH_PX)
        binding.popupTitle.maxWidth = maxTextWidth
        binding.popupMessage.maxWidth = maxTextWidth
        measureIfUnmeasured(binding.popupTitle, maxTextWidth)

        val screenHeight = resources.displayMetrics.heightPixels
        val maxScrollHeight = computeMaxScrollHeight(screenHeight, maxTextWidth)
        applyMessageScrollConstraints(maxTextWidth, maxScrollHeight)

        if (notifyCallback) onLayoutAdjusted?.invoke()
    }

    private fun resetNonFullscreenContainers() {
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
    }

    private fun syncTargetMediaDimensions() {
        if (!binding.popupMediaFrame.isVisible || targetMediaWidth <= 0 || targetMediaHeight <= 0) return
        syncViewSize(binding.popupMediaFrame, targetMediaWidth, targetMediaHeight)
        syncViewSize(binding.popupMediaContainer, targetMediaWidth, targetMediaHeight)
    }

    private fun syncViewSize(view: View, width: Int, height: Int) {
        val lp = view.layoutParams
        if (lp.width != width || lp.height != height) {
            lp.width = width
            lp.height = height
            view.requestLayout()
        }
    }

    private fun measureIfUnmeasured(view: View, maxWidth: Int) {
        if (view.isVisible && view.measuredHeight <= 0) {
            view.measure(
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }
    }

    private fun computeMaxScrollHeight(screenHeight: Int, maxTextWidth: Int): Int {
        if (binding.contentLayout.orientation == LinearLayout.HORIZONTAL) {
            return (screenHeight * MAX_HORIZONTAL_SCROLL_RATIO).toInt()
        }
        val buttonRow = binding.popupContainer.findViewWithTag<ViewGroup>("popup_button_row")
        val buttonRowHeight = if (buttonRow != null && buttonRow.isVisible) {
            measureIfUnmeasured(buttonRow, maxTextWidth)
            buttonRow.measuredHeight + ((buttonRow.layoutParams as? MarginLayoutParams)?.topMargin ?: 0)
        } else {
            0
        }
        val mediaH = when {
            !binding.popupMediaFrame.isVisible -> 0
            targetMediaHeight > 0 -> targetMediaHeight
            else -> binding.popupMediaFrame.measuredHeight
        }
        val titleH = if (binding.popupTitle.isVisible) binding.popupTitle.measuredHeight else 0
        val otherViewsHeight = titleH + mediaH +
            binding.popupContainer.paddingTop + binding.popupContainer.paddingBottom +
            context.dpToPx(HEIGHT_PADDING_EXTRA_DP) + buttonRowHeight
        val maxPopupHeight = (screenHeight * MAX_POPUP_HEIGHT_RATIO).toInt()
        return (maxPopupHeight - otherViewsHeight).coerceAtLeast(context.dpToPx(MIN_VERTICAL_SCROLL_HEIGHT_DP))
    }

    private fun applyMessageScrollConstraints(maxTextWidth: Int, maxScrollHeight: Int) {
        binding.popupMessage.measure(
            MeasureSpec.makeMeasureSpec(maxTextWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        if (binding.popupMessage.measuredHeight > maxScrollHeight) {
            binding.popupScrollView.layoutParams.height = maxScrollHeight.coerceAtLeast(
                context.dpToPx(MIN_SCROLL_HEIGHT_DP),
            )
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
                val msgH = if (binding.popupMessage.height > 0) {
                    binding.popupMessage.height
                } else {
                    binding.popupMessage.measuredHeight
                }
                val scrollH = if (binding.popupScrollView.height > 0) {
                    binding.popupScrollView.height
                } else {
                    binding.popupScrollView.measuredHeight
                }
                val maxScroll = msgH - scrollH
                if (maxScroll <= 0) { isScrolling = false; return }

                scrollPos += 1
                if (scrollPos > maxScroll) {
                    binding.popupScrollView.postDelayed({
                        scrollPos = 0
                        binding.popupScrollView.scrollTo(0, 0)
                        binding.popupScrollView.postDelayed(this, AUTO_SCROLL_START_DELAY_MS)
                    }, AUTO_SCROLL_PAUSE_END_MS)
                    return
                }
                binding.popupScrollView.scrollTo(0, scrollPos)
                binding.popupScrollView.postDelayed(this, AUTO_SCROLL_TICK_MS)
            }
        }
        binding.popupScrollView.postDelayed(runnable, AUTO_SCROLL_START_DELAY_MS)
    }

    private fun isStreamMedia(): Boolean {
        return props.media is PopupProps.Media.Video ||
            props.media is PopupProps.Media.Whep ||
            props.media is PopupProps.Media.Web
    }

    private fun configureLiveBadgeAppearance() {
        val badge = binding.popupLiveBadge
        val defaultBadgeColor = ContextCompat.getColor(context, R.color.live_badge_background)
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
            badge.animate().alpha(1f).setDuration(LIVE_BADGE_FADE_IN_MS).start()
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
            mainHandler.postDelayed(timeoutRunnable, timeoutSec * MS_PER_SECOND)
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
        binding.popupCloseButton.animate().cancel()
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
        binding.popupContainer.findViewWithTag<ViewGroup>("popup_button_row")
            ?.let { binding.popupContainer.removeView(it) }
        binding.textContainer.findViewWithTag<ViewGroup>("popup_button_row")
            ?.let { binding.textContainer.removeView(it) }

        if (props.buttons.isEmpty()) return

        val buttonRow = createButtonRowContainer()
        var firstButton: Button? = null
        props.buttons.forEachIndexed { index, btn ->
            val button = createActionButton(btn, isFirst = index == 0)
            if (firstButton == null) firstButton = button
            buttonRow.addView(button)
        }

        val targetParent = if (props.buttonsInline) binding.textContainer else binding.popupContainer
        targetParent.addView(buttonRow)
        if (!props.isPreview) {
            firstButton?.post { firstButton.requestFocus() }
        }
    }

    private fun createButtonRowContainer(): LinearLayout = LinearLayout(context).apply {
        tag = "popup_button_row"
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        if (isFullBleedActive() && !props.buttonsInline) {
            val scaledPadding = scalePx((props.contentPadding ?: settings.contentPadding).coerceAtLeast(0))
            setPadding(scaledPadding, 0, scaledPadding, 0)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = context.dpToPx(BUTTON_ROW_TOP_MARGIN_DP)
        }
    }

    private fun createActionButton(btn: PopupProps.Button, isFirst: Boolean): Button {
        val size = props.buttonSize?.takeIf { it >= MIN_BUTTON_SIZE_SP }
            ?: settings.defaultButtonSize.takeIf { it >= MIN_BUTTON_SIZE_SP }
            ?: (props.messageSize * FALLBACK_BUTTON_SIZE_RATIO)
                .coerceIn(FALLBACK_BUTTON_SIZE_MIN_SP, FALLBACK_BUTTON_SIZE_MAX_SP)
        val normalTextColor = parseColorOrDefault(
            props.buttonTextColor ?: settings.defaultButtonTextColor,
            "#FFFFFF",
        )
        val focusTextColor = parseColorOrDefault(
            props.buttonFocusTextColor ?: settings.defaultButtonFocusTextColor,
            "#FFFFFF",
        )
        val scaleFactor = size / BUTTON_BASE_SIZE_SP
        val padH = context.dpToPx((BUTTON_PAD_H_DP * scaleFactor).toInt()).coerceAtLeast(BUTTON_MIN_PAD_H_PX)
        val padV = context.dpToPx((BUTTON_PAD_V_DP * scaleFactor).toInt()).coerceAtLeast(BUTTON_MIN_PAD_V_PX)
        val margin = context.dpToPx((BUTTON_MARGIN_DP * scaleFactor).toInt()).coerceAtLeast(2)
        val bottomMargin = context.dpToPx((BUTTON_MARGIN_BOTTOM_DP * scaleFactor).toInt()).coerceAtLeast(2)
        val previewFocused = props.isPreview && isFirst

        return Button(context).apply {
            text = btn.label
            isFocusable = !props.isPreview
            isClickable = !props.isPreview
            if (previewFocused) {
                setTextColor(focusTextColor)
            } else {
                setTextColor(createButtonTextColorStateList(normalTextColor, focusTextColor))
            }
            textSize = size
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            minHeight = context.dpToPx(BUTTON_MIN_HEIGHT_DP)
            background = createButtonBackground(props, forceFocused = previewFocused)
            setOnClickListener { if (!props.isPreview) onButton?.invoke(btn) }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(margin, margin, margin, bottomMargin)
            }
        }
    }

    private fun updateCloseButton() {
        val btn = binding.popupCloseButton
        val pos = PopupProps.normalizeCloseButtonPosition(props.closeButton ?: settings.closeButton)
        if (pos == "none") {
            hideCloseButton(btn)
            return
        }

        val wasVisible = btn.isVisible
        val positionChanged = lastCloseButtonPosition != pos
        lastCloseButtonPosition = pos

        applyCloseButtonLayout(btn, pos)
        applyCloseButtonStyleAndListeners(btn)
        btn.isVisible = true
        btn.bringToFront()

        if (props.isPreview) {
            showCloseButtonPreviewState(btn)
            return
        }
        if (!wasVisible || positionChanged || !closeButtonHidden) {
            revealCloseButton()
        }
        if (props.buttons.isEmpty() && !btn.hasFocus()) {
            btn.post {
                if (!isCleanedUp && btn.isVisible) {
                    btn.requestFocus()
                }
            }
        }
    }

    private fun hideCloseButton(btn: ImageView) {
        mainHandler.removeCallbacks(closeButtonAutoHideRunnable)
        btn.animate().cancel()
        btn.isVisible = false
        closeButtonHidden = false
        closeButtonWakeConsumedDown = false
        lastCloseButtonPosition = null
    }

    private fun showCloseButtonPreviewState(btn: ImageView) {
        mainHandler.removeCallbacks(closeButtonAutoHideRunnable)
        btn.animate().cancel()
        btn.alpha = 1f
        btn.scaleX = 1f
        btn.scaleY = 1f
        closeButtonHidden = false
    }

    private fun applyCloseButtonLayout(btn: ImageView, pos: String) {
        val gravity = when (pos) {
            "top_left" -> Gravity.TOP or Gravity.START
            "bottom_left" -> Gravity.BOTTOM or Gravity.START
            "bottom_right" -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.TOP or Gravity.END
        }
        val sizeDp = (props.closeButtonSize ?: settings.closeButtonSize).coerceIn(
            PopupProps.CLOSE_BUTTON_SIZE_MIN,
            PopupProps.CLOSE_BUTTON_SIZE_MAX
        )
        val sizePx = context.dpToPx(sizeDp)
        val marginDp = if (props.coversScreen) {
            CLOSE_BUTTON_MARGIN_FULLSCREEN_DP
        } else {
            CLOSE_BUTTON_MARGIN_COMPACT_DP
        }
        val margin = context.dpToPx(marginDp)
        (btn.layoutParams as? LayoutParams)?.let { lp ->
            lp.width = sizePx
            lp.height = sizePx
            lp.gravity = gravity
            lp.setMargins(margin, margin, margin, margin)
            btn.layoutParams = lp
        }
    }

    private fun applyCloseButtonStyleAndListeners(btn: ImageView) {
        val previewFocused = props.isPreview && props.buttons.isEmpty()
        val normalColor = parseColorOrDefault(
            props.buttonColor ?: settings.defaultButtonColor,
            ContextCompat.getColor(context, R.color.preset_platinum)
        )
        val focusColor = parseColorOrDefault(
            props.buttonFocusColor ?: settings.defaultButtonFocusColor,
            ContextCompat.getColor(context, R.color.preset_sky_blue)
        )
        btn.background = null
        btn.imageTintList = if (previewFocused) {
            ColorStateList.valueOf(focusColor)
        } else {
            createButtonTextColorStateList(normalColor, focusColor)
        }
        btn.isFocusable = !props.isPreview
        btn.isClickable = !props.isPreview
        btn.setOnFocusChangeListener { v, hasFocus ->
            if (!props.isPreview) {
                val scale = if (hasFocus) CLOSE_BUTTON_FOCUS_SCALE else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(CLOSE_BUTTON_FOCUS_ANIM_MS).start()
                if (hasFocus && closeButtonHidden) {
                    revealCloseButton()
                }
            }
        }
        btn.setOnClickListener {
            if (props.isPreview) return@setOnClickListener
            if (closeButtonHidden) {
                revealCloseButton()
            } else {
                onClose?.invoke()
            }
        }
    }

    private fun revealCloseButton() {
        val btn = binding.popupCloseButton
        if (!btn.isVisible || isCleanedUp) return
        mainHandler.removeCallbacks(closeButtonAutoHideRunnable)
        closeButtonHidden = false
        btn.animate().cancel()
        if (btn.alpha < 1f) {
            btn.animate().alpha(1f).setDuration(CLOSE_BUTTON_REVEAL_ANIM_MS).start()
        }
        scheduleCloseButtonAutoHide()
    }

    private fun scheduleCloseButtonAutoHide() {
        mainHandler.removeCallbacks(closeButtonAutoHideRunnable)
        if (props.isPreview || isCleanedUp || !binding.popupCloseButton.isVisible) return
        val timeoutSec = props.closeButtonTimeout ?: settings.closeButtonTimeout
        if (timeoutSec > 0) {
            mainHandler.postDelayed(closeButtonAutoHideRunnable, timeoutSec * MS_PER_SECOND)
        }
    }

    private fun fadeOutCloseButton() {
        val btn = binding.popupCloseButton
        if (props.isPreview || isCleanedUp || !btn.isVisible) return
        closeButtonHidden = true
        btn.animate().cancel()
        btn.animate().alpha(0f).setDuration(CLOSE_BUTTON_FADE_OUT_ANIM_MS).start()
    }

    private fun createButtonBackground(
        props: PopupProps,
        forceFocused: Boolean = false,
    ): android.graphics.drawable.Drawable {
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

    private fun parseColorOrDefault(hex: String, fallback: String): Int =
        parseColorOrDefault(hex, fallback.toColorInt())

    private fun parseColorOrDefault(hex: String, fallbackColor: Int): Int {
        return try {
            val clean = if (hex.startsWith("#")) hex else "#$hex"
            clean.toColorInt()
        } catch (_: IllegalArgumentException) {
            fallbackColor
        }
    }

    /**
     * Convenience builder method to create and initialize a [PopupView].
     */
    companion object {
        private const val CLOSE_BUTTON_MARGIN_FULLSCREEN_DP = 16
        private const val CLOSE_BUTTON_MARGIN_COMPACT_DP = 8
        private const val CLOSE_BUTTON_FOCUS_SCALE = 1.15f
        private const val CLOSE_BUTTON_FOCUS_ANIM_MS = 120L
        private const val CLOSE_BUTTON_REVEAL_ANIM_MS = 150L
        private const val CLOSE_BUTTON_FADE_OUT_ANIM_MS = 300L
        private const val LIVE_BADGE_FADE_IN_MS = 150L
        private const val MS_PER_SECOND = 1000L
        private const val MEDIA_POS_TOP = 0
        private const val MEDIA_POS_BOTTOM = 1
        private const val MEDIA_POS_LEFT = 2
        private const val MEDIA_POS_RIGHT = 3
        private const val FULL_BLEED_GAP_DIVISOR = 3
        private const val MAX_TEXT_WIDTH_PX = 500
        private const val MEDIA_GAP_VERTICAL_DP = 8
        private const val MEDIA_GAP_HORIZONTAL_DP = 12
        private const val HEIGHT_PADDING_EXTRA_DP = 12
        private const val MAX_POPUP_HEIGHT_RATIO = 0.85
        private const val MAX_HORIZONTAL_SCROLL_RATIO = 0.45
        private const val MIN_VERTICAL_SCROLL_HEIGHT_DP = 150
        private const val MIN_SCROLL_HEIGHT_DP = 100
        private const val BUTTON_ROW_TOP_MARGIN_DP = 12
        private const val MIN_BUTTON_SIZE_SP = 8f
        private const val FALLBACK_BUTTON_SIZE_RATIO = 0.85f
        private const val FALLBACK_BUTTON_SIZE_MIN_SP = 12f
        private const val FALLBACK_BUTTON_SIZE_MAX_SP = 24f
        private const val BUTTON_BASE_SIZE_SP = 14f
        private const val BUTTON_PAD_H_DP = 24f
        private const val BUTTON_PAD_V_DP = 10f
        private const val BUTTON_MIN_PAD_H_PX = 8
        private const val BUTTON_MIN_PAD_V_PX = 4
        private const val BUTTON_MIN_HEIGHT_DP = 40
        private const val BUTTON_MARGIN_DP = 8f
        private const val BUTTON_MARGIN_BOTTOM_DP = 4f
        private const val AUTO_SCROLL_START_DELAY_MS = 2000L
        private const val AUTO_SCROLL_PAUSE_END_MS = 3000L
        private const val AUTO_SCROLL_TICK_MS = 30L

        /** Creates, binds, and initializes a [PopupView] with optional [listener]. */
        fun build(context: Context, props: PopupProps, listener: ReadyListener? = null): PopupView {
            return PopupView(context, props).apply { readyListener = listener }.create()
        }
    }
}
