package nl.rogro82.pipup.ui

import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.databinding.PopupBinding
import nl.rogro82.pipup.dpToPx

/**
 * Handles popup layout geometry, full-bleed padding/clipping, media/text ordering,
 * fullscreen and non-fullscreen height constraints, and message auto-scrolling.
 */
@UnstableApi
internal class PopupLayoutController(
    private val context: Context,
    private val settings: AppSettings,
    private val binding: PopupBinding,
) {

    var targetMediaWidth: Int = 0
    var targetMediaHeight: Int = 0
    private var isScrolling: Boolean = false
    private var autoScrollRunnable: Runnable? = null
    private var autoScrollResetRunnable: Runnable? = null

    fun cleanup() {
        isScrolling = false
        autoScrollRunnable?.let { binding.popupScrollView.removeCallbacks(it) }
        autoScrollResetRunnable?.let { binding.popupScrollView.removeCallbacks(it) }
        autoScrollRunnable = null
        autoScrollResetRunnable = null
    }

    fun isFullBleedActive(props: PopupProps): Boolean =
        (props.mediaFullBleed ?: settings.mediaFullBleed) && props.hasMedia

    fun calculateTargetDimensions(media: PopupProps.Media?, props: PopupProps) {
        val width = props.rawMediaWidth() ?: PopupProps.DEFAULT_MEDIA_WIDTH
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

    fun applyContainerPaddingAndOutline(
        props: PopupProps,
        scaledPadding: Int,
        borderPx: Int,
        radiusPx: Float,
    ) {
        if (!isFullBleedActive(props)) {
            val effectivePad = maxOf(scaledPadding, borderPx)
            binding.popupContainer.setPadding(effectivePad, effectivePad, effectivePad, effectivePad)
            binding.textContainer.setPadding(0, 0, 0, 0)
            binding.popupMediaContainer.clipToOutline = false
            binding.popupMediaContainer.outlineProvider = ViewOutlineProvider.BACKGROUND
            return
        }

        val hasAnyText = !props.title.isNullOrBlank() || !props.message.isNullOrBlank()
        val hasButtons = props.buttons.isNotEmpty()
        val gap = (scaledPadding * 2) / FULL_BLEED_GAP_DIVISOR
        val pos = props.mediaPosition ?: PopupProps.MEDIA_POS_TOP
        val bottomPad = if (pos == PopupProps.MEDIA_POS_TOP && (hasAnyText || hasButtons)) gap else borderPx
        val topPad = if (pos == PopupProps.MEDIA_POS_BOTTOM && hasAnyText) gap else borderPx
        binding.popupContainer.setPadding(borderPx, topPad, borderPx, bottomPad)

        applyFullBleedTextPadding(pos, scaledPadding, gap, hasAnyText || (props.buttonsInline && hasButtons))
        applyFullBleedOutline(pos, radiusPx, borderPx, hasAnyText || hasButtons)
    }

    fun reorderViews(props: PopupProps) {
        val container = binding.contentLayout
        val textContainer = binding.textContainer
        val mediaFrame = binding.popupMediaContainer

        val pos = props.mediaPosition ?: PopupProps.MEDIA_POS_TOP
        val hasTextSection = hasTextOrInlineButtons(props)
        textContainer.isVisible = hasTextSection

        if (container.tag == pos && textContainer.parent == container && mediaFrame.parent == container) {
            updateExistingMediaMargins(mediaFrame, pos, isFullBleedActive(props) || !hasTextSection)
            return
        }

        // Extremely careful reordering: only detach if absolutely necessary
        // to avoid WebView surface destruction.
        detachFromParent(textContainer)
        detachFromParent(mediaFrame)

        container.tag = pos
        val noMediaGap = isFullBleedActive(props) || !hasTextSection
        when (pos) {
            PopupProps.MEDIA_POS_TOP ->
                setupVertical(container, mediaFrame, textContainer, mediaFirst = true, noMediaGap)
            PopupProps.MEDIA_POS_BOTTOM ->
                setupVertical(container, textContainer, mediaFrame, mediaFirst = false, noMediaGap)
            PopupProps.MEDIA_POS_LEFT ->
                setupHorizontal(container, mediaFrame, textContainer, mediaFirst = true, noMediaGap)
            PopupProps.MEDIA_POS_RIGHT ->
                setupHorizontal(container, textContainer, mediaFrame, mediaFirst = false, noMediaGap)
        }
    }

    fun applyHeightConstraints(
        props: PopupProps,
        mediaRenderer: MediaRenderer?,
        updateLiveBadgeMargins: () -> Unit,
    ) {
        if (props.coversScreen) {
            applyFullscreenLayoutConstraints(props, mediaRenderer, updateLiveBadgeMargins)
            return
        }
        updateLiveBadgeMargins()
        resetNonFullscreenContainers(props)
        syncTargetMediaDimensions()

        val maxTextWidth = props.scaleValue(context, PopupProps.MAX_TEXT_WIDTH_PX)
        binding.popupTitle.maxWidth = maxTextWidth
        binding.popupMessage.maxWidth = maxTextWidth
        measureIfUnmeasured(binding.popupTitle, maxTextWidth)

        val screenHeight = context.resources.displayMetrics.heightPixels
        val maxScrollHeight = computeMaxScrollHeight(screenHeight, maxTextWidth)
        applyMessageScrollConstraints(maxTextWidth, maxScrollHeight)
    }

    private fun hasTextOrInlineButtons(props: PopupProps): Boolean {
        val hasAnyText = !props.title.isNullOrBlank() || !props.message.isNullOrBlank()
        val hasInlineButtons = props.buttonsInline && props.buttons.isNotEmpty()
        return hasAnyText || hasInlineButtons
    }

    private fun applyFullBleedTextPadding(pos: Int, scaledPadding: Int, gap: Int, hasPaddedContent: Boolean) {
        if (!hasPaddedContent) {
            binding.textContainer.setPadding(0, 0, 0, 0)
            return
        }
        val textTop = if (pos == PopupProps.MEDIA_POS_TOP) gap else scaledPadding
        val textBottom = when (pos) {
            PopupProps.MEDIA_POS_BOTTOM -> gap
            PopupProps.MEDIA_POS_LEFT, PopupProps.MEDIA_POS_RIGHT -> scaledPadding
            else -> 0
        }
        binding.textContainer.setPadding(scaledPadding, textTop, scaledPadding, textBottom)
    }

    private fun applyFullBleedOutline(mediaPos: Int, radiusPx: Float, borderPx: Int, hasOtherContent: Boolean) {
        val clip = (radiusPx - borderPx).coerceAtLeast(0f)
        val extra = clip.toInt()
        val mediaContainer = binding.popupMediaContainer
        if (clip <= 0f) {
            mediaContainer.clipToOutline = false
            return
        }
        mediaContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (!hasOtherContent) {
                    outline.setRoundRect(0, 0, view.width, view.height, clip)
                    return
                }
                when (mediaPos) {
                    PopupProps.MEDIA_POS_BOTTOM -> outline.setRoundRect(0, -extra, view.width, view.height, clip)
                    PopupProps.MEDIA_POS_LEFT -> outline.setRoundRect(0, 0, view.width + extra, view.height, clip)
                    PopupProps.MEDIA_POS_RIGHT -> outline.setRoundRect(-extra, 0, view.width, view.height, clip)
                    else -> outline.setRoundRect(0, 0, view.width, view.height + extra, clip)
                }
            }
        }
        mediaContainer.clipToOutline = true
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun updateExistingMediaMargins(mediaFrame: View, pos: Int, noMediaGap: Boolean) {
        val marginV = if (noMediaGap) 0 else context.dpToPx(PopupProps.VERTICAL_MEDIA_GAP_DP)
        val marginH = if (noMediaGap) 0 else context.dpToPx(PopupProps.HORIZONTAL_MEDIA_GAP_DP)
        val lp = mediaFrame.layoutParams as? LinearLayout.LayoutParams ?: return
        if (targetMediaWidth > 0 && targetMediaHeight > 0) {
            lp.width = targetMediaWidth
            lp.height = targetMediaHeight
        }
        when (pos) {
            PopupProps.MEDIA_POS_TOP -> lp.setMargins(0, 0, 0, marginV)
            PopupProps.MEDIA_POS_BOTTOM -> lp.setMargins(0, marginV, 0, 0)
            PopupProps.MEDIA_POS_LEFT -> lp.setMargins(0, 0, marginH, 0)
            PopupProps.MEDIA_POS_RIGHT -> lp.setMargins(marginH, 0, 0, 0)
        }
    }

    private fun createChildLayoutParams(view: View): LinearLayout.LayoutParams {
        val isMedia = view == binding.popupMediaContainer
        val w = if (isMedia && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val h = if (isMedia && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT
        return LinearLayout.LayoutParams(w, h)
    }

    private fun setupVertical(
        container: LinearLayout,
        first: View,
        second: View,
        mediaFirst: Boolean,
        noMediaGap: Boolean,
    ) {
        container.orientation = LinearLayout.VERTICAL
        val margin = if (noMediaGap) 0 else context.dpToPx(PopupProps.VERTICAL_MEDIA_GAP_DP)

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

    private fun setupHorizontal(
        container: LinearLayout,
        first: View,
        second: View,
        mediaFirst: Boolean,
        noMediaGap: Boolean,
    ) {
        container.orientation = LinearLayout.HORIZONTAL
        val margin = if (noMediaGap) 0 else context.dpToPx(PopupProps.HORIZONTAL_MEDIA_GAP_DP)

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

    private fun measureViewHeightWithMargins(view: View?, maxWidth: Int, maxHeight: Int): Int {
        if (view == null || !view.isVisible) return 0
        view.measure(
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST),
        )
        val lp = view.layoutParams as? MarginLayoutParams
        return view.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
    }

    private fun measureFullscreenTextSection(
        innerWidth: Int,
        contentAvailHeight: Int,
        hasMedia: Boolean,
        isHorizontal: Boolean,
    ): Pair<Int, Int> {
        val maxTextColWidth = if (isHorizontal && hasMedia) {
            (innerWidth * FULLSCREEN_TEXT_COLUMN_RATIO).toInt().coerceAtLeast(context.dpToPx(MIN_TEXT_COL_WIDTH_DP))
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
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            binding.popupTitle.measuredHeight
        } else {
            0
        }

        val inlineButtonRow = binding.textContainer.findViewWithTag<ViewGroup>(PopupButtonController.BUTTON_ROW_TAG)
        val inlineButtonHeight = measureViewHeightWithMargins(inlineButtonRow, textInnerMaxWidth, contentAvailHeight)

        if (binding.popupMessage.isVisible) {
            binding.popupMessage.measure(
                MeasureSpec.makeMeasureSpec(textInnerMaxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val scrollTopMargin = (binding.popupScrollView.layoutParams as? MarginLayoutParams)?.topMargin ?: 0
            val textPadV = binding.textContainer.paddingTop + binding.textContainer.paddingBottom
            val maxTextSectionHeight = if (hasMedia && !isHorizontal) {
                (contentAvailHeight * FULLSCREEN_TEXT_COLUMN_RATIO).toInt()
            } else {
                contentAvailHeight
            }
            val maxScrollHeight = (maxTextSectionHeight - titleHeight - scrollTopMargin - textPadV - inlineButtonHeight)
                .coerceAtLeast(context.dpToPx(MIN_FULLSCREEN_SCROLL_HEIGHT_DP))
            applyScrollHeight(maxScrollHeight)
        }

        val textWidthSpec = if (!isHorizontal) {
            MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY)
        } else {
            MeasureSpec.makeMeasureSpec(maxTextColWidth, MeasureSpec.AT_MOST)
        }
        binding.textContainer.measure(
            textWidthSpec,
            MeasureSpec.makeMeasureSpec(contentAvailHeight, MeasureSpec.AT_MOST),
        )
        return binding.textContainer.measuredWidth to binding.textContainer.measuredHeight
    }

    private fun applyFullscreenMediaLayout(
        props: PopupProps,
        mediaRenderer: MediaRenderer?,
        layoutBounds: FullscreenBounds,
        updateLiveBadgeMargins: () -> Unit,
    ) {
        val (textMeasuredW, textMeasuredH) = layoutBounds.textSize
        val mediaLp = binding.popupMediaContainer.layoutParams as? LinearLayout.LayoutParams
        if (!layoutBounds.isHorizontal) {
            val gapV = if (layoutBounds.hasTextSection) {
                (mediaLp?.topMargin ?: 0) + (mediaLp?.bottomMargin ?: 0)
            } else {
                0
            }
            targetMediaWidth = layoutBounds.innerWidth
            targetMediaHeight = (layoutBounds.contentAvailHeight - textMeasuredH - gapV).coerceAtLeast(1)
        } else {
            val gapH = if (layoutBounds.hasTextSection) {
                (mediaLp?.leftMargin ?: 0) + (mediaLp?.rightMargin ?: 0)
            } else {
                0
            }
            targetMediaWidth = (layoutBounds.innerWidth - textMeasuredW - gapH).coerceAtLeast(1)
            targetMediaHeight = layoutBounds.contentAvailHeight
        }
        binding.popupMediaContainer.layoutParams.width = targetMediaWidth
        binding.popupMediaContainer.layoutParams.height = targetMediaHeight
        binding.popupMediaContainer.requestLayout()
        mediaRenderer?.updateFullscreenProps(props)
        mediaRenderer?.updateFullscreenBounds(binding.popupMediaFrame, targetMediaWidth, targetMediaHeight)
        updateLiveBadgeMargins()
    }

    private fun applyFullscreenLayoutConstraints(
        props: PopupProps,
        mediaRenderer: MediaRenderer?,
        updateLiveBadgeMargins: () -> Unit,
    ) {
        val dm = context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val hasMedia = props.hasMedia
        val hasTextSection = hasTextOrInlineButtons(props)

        binding.popupContainer.layoutParams = (binding.popupContainer.layoutParams ?: FrameLayout.LayoutParams(
            screenWidth,
            screenHeight,
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

        val buttonRow = binding.popupContainer.findViewWithTag<ViewGroup>(PopupButtonController.BUTTON_ROW_TAG)
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
            val bounds = FullscreenBounds(innerWidth, contentAvailHeight, textSize, hasTextSection, isHorizontal)
            applyFullscreenMediaLayout(props, mediaRenderer, bounds, updateLiveBadgeMargins)
        }
    }

    private fun resetNonFullscreenContainers(props: PopupProps) {
        binding.popupContainer.layoutParams?.let { lp ->
            lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
            lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
        }
        (binding.contentLayout.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
            lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
            binding.contentLayout.gravity = Gravity.NO_GRAVITY
        }
        (binding.textContainer.layoutParams as? LinearLayout.LayoutParams)?.width =
            LinearLayout.LayoutParams.WRAP_CONTENT
        binding.textContainer.isVisible = hasTextOrInlineButtons(props)
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
        val buttonRow = binding.popupContainer.findViewWithTag<ViewGroup>(PopupButtonController.BUTTON_ROW_TAG)
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
        applyScrollHeight(maxScrollHeight.coerceAtLeast(context.dpToPx(MIN_SCROLL_HEIGHT_DP)))
    }

    private fun applyScrollHeight(maxScrollHeight: Int) {
        if (binding.popupMessage.measuredHeight > maxScrollHeight) {
            binding.popupScrollView.layoutParams.height = maxScrollHeight
            if (!isScrolling) startAutoScroll()
        } else {
            if (isScrolling) {
                cleanup()
                binding.popupScrollView.scrollTo(0, 0)
            }
            binding.popupScrollView.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
        }
        binding.popupScrollView.requestLayout()
    }

    private fun startAutoScroll() {
        if (isScrolling) return
        isScrolling = true

        val runnable = object : Runnable {
            var scrollPos = 0
            override fun run() {
                val maxScroll = computeAutoScrollMax()
                if (!isScrolling || !binding.popupScrollView.isAttachedToWindow || maxScroll <= 0) {
                    isScrolling = false
                    return
                }
                scrollPos += 1
                if (scrollPos > maxScroll) {
                    scheduleAutoScrollReset(this) { scrollPos = 0 }
                } else {
                    binding.popupScrollView.scrollTo(0, scrollPos)
                    binding.popupScrollView.postDelayed(this, AUTO_SCROLL_TICK_MS)
                }
            }
        }
        autoScrollRunnable = runnable
        binding.popupScrollView.postDelayed(runnable, AUTO_SCROLL_START_DELAY_MS)
    }

    private fun computeAutoScrollMax(): Int {
        val msgH = binding.popupMessage.height.takeIf { it > 0 } ?: binding.popupMessage.measuredHeight
        val scrollH = binding.popupScrollView.height.takeIf { it > 0 } ?: binding.popupScrollView.measuredHeight
        return msgH - scrollH
    }

    private fun scheduleAutoScrollReset(scrollTick: Runnable, resetPosition: () -> Unit) {
        val reset = Runnable {
            if (!isScrolling || !binding.popupScrollView.isAttachedToWindow) {
                isScrolling = false
                return@Runnable
            }
            resetPosition()
            binding.popupScrollView.scrollTo(0, 0)
            binding.popupScrollView.postDelayed(scrollTick, AUTO_SCROLL_START_DELAY_MS)
        }
        autoScrollResetRunnable = reset
        binding.popupScrollView.postDelayed(reset, AUTO_SCROLL_PAUSE_END_MS)
    }

    private class FullscreenBounds(
        val innerWidth: Int,
        val contentAvailHeight: Int,
        val textSize: Pair<Int, Int>,
        val hasTextSection: Boolean,
        val isHorizontal: Boolean,
    )

    companion object {
        private const val FULL_BLEED_GAP_DIVISOR = 3
        private const val FULLSCREEN_TEXT_COLUMN_RATIO = 0.35f
        private const val MIN_TEXT_COL_WIDTH_DP = 160
        private const val MIN_FULLSCREEN_SCROLL_HEIGHT_DP = 48
        private const val HEIGHT_PADDING_EXTRA_DP = 12
        private const val MAX_POPUP_HEIGHT_RATIO = 0.85
        private const val MAX_HORIZONTAL_SCROLL_RATIO = 0.45
        private const val MIN_VERTICAL_SCROLL_HEIGHT_DP = 150
        private const val MIN_SCROLL_HEIGHT_DP = 100
        private const val AUTO_SCROLL_START_DELAY_MS = 2000L
        private const val AUTO_SCROLL_PAUSE_END_MS = 3000L
        private const val AUTO_SCROLL_TICK_MS = 30L
    }
}
