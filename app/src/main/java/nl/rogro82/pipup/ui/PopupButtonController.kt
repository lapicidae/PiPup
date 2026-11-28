package nl.rogro82.pipup.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.databinding.PopupBinding
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.parseColorOrDefault

/**
 * Manages action buttons and the overlay close button (positioning, styling, auto-hide,
 * and D-pad/touch wake interactions) inside a [PopupView].
 */
internal class PopupButtonController(
    private val context: Context,
    private val settings: AppSettings,
    private val binding: PopupBinding,
    private val mainHandler: Handler,
    private val isCleanedUp: () -> Boolean,
) {

    private val closeButton: ImageView = binding.popupCloseButton
    private var currentProps: PopupProps? = null
    private var closeButtonHidden = false
    private var closeButtonWakeConsumedDown = false
    private var lastCloseButtonPosition: String? = null
    private val autoHideRunnable = Runnable { fadeOutCloseButton() }

    fun isCloseButtonHidden(): Boolean = closeButtonHidden

    fun cleanup() {
        mainHandler.removeCallbacks(autoHideRunnable)
        closeButton.animate().cancel()
    }

    fun handleTouchEvent(ev: MotionEvent, props: PopupProps): Boolean = when {
        ev.action != MotionEvent.ACTION_DOWN || !closeButton.isVisible || props.isPreview -> false
        closeButtonHidden -> {
            revealCloseButton(props)
            val x = ev.x.toInt()
            val y = ev.y.toInt()
            x in closeButton.left..closeButton.right && y in closeButton.top..closeButton.bottom
        }
        else -> {
            scheduleAutoHide(props)
            false
        }
    }

    fun handleKeyEvent(event: KeyEvent, props: PopupProps): Boolean {
        return closeButton.isVisible && !props.isPreview && when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val wasHidden = closeButtonHidden
                    revealCloseButton(props)
                    wasHidden && props.buttons.isEmpty()
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> handleConfirmKey(event, props)
            else -> false
        }
    }

    fun updateButtons(
        props: PopupProps,
        isFullBleed: Boolean,
        onButton: ((PopupProps.Button) -> Unit)?,
    ) {
        binding.popupContainer.findViewWithTag<ViewGroup>(BUTTON_ROW_TAG)
            ?.let { binding.popupContainer.removeView(it) }
        binding.textContainer.findViewWithTag<ViewGroup>(BUTTON_ROW_TAG)
            ?.let { binding.textContainer.removeView(it) }

        if (props.buttons.isEmpty()) return

        val buttonRow = createButtonRowContainer(props, isFullBleed)
        var firstButton: Button? = null
        props.buttons.forEachIndexed { index, btn ->
            val button = createActionButton(props, btn, isFirst = index == 0, onButton = onButton)
            if (firstButton == null) firstButton = button
            buttonRow.addView(button)
        }

        val targetParent = if (props.buttonsInline) binding.textContainer else binding.popupContainer
        if (targetParent === binding.popupContainer) {
            val progressIdx = targetParent.indexOfChild(binding.popupProgressBar)
            if (progressIdx >= 0) targetParent.addView(buttonRow, progressIdx) else targetParent.addView(buttonRow)
        } else {
            targetParent.addView(buttonRow)
        }
        if (!props.isPreview) {
            firstButton?.post { firstButton.requestFocus() }
        }
    }

    fun updateCloseButton(props: PopupProps, onClose: (() -> Unit)?) {
        currentProps = props
        val pos = PopupProps.normalizeCloseButtonPosition(props.closeButton ?: settings.closeButton)
        if (pos == "none") {
            hideCloseButton()
            return
        }

        val wasVisible = closeButton.isVisible
        val positionChanged = lastCloseButtonPosition != pos
        lastCloseButtonPosition = pos

        applyCloseButtonLayout(props, pos)
        applyCloseButtonStyleAndListeners(props, onClose)
        closeButton.isVisible = true
        closeButton.bringToFront()

        if (props.isPreview) {
            showCloseButtonPreviewState()
            return
        }
        if (!wasVisible || positionChanged || !closeButtonHidden) {
            revealCloseButton(props)
        }
        if (props.buttons.isEmpty() && !closeButton.hasFocus()) {
            closeButton.post {
                if (!isCleanedUp() && closeButton.isVisible) {
                    closeButton.requestFocus()
                }
            }
        }
    }

    private fun createButtonRowContainer(props: PopupProps, isFullBleed: Boolean): LinearLayout =
        LinearLayout(context).apply {
            tag = BUTTON_ROW_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            if (isFullBleed && !props.buttonsInline) {
                val scaledPadding = props.scaleValue(
                    context,
                    (props.contentPadding ?: settings.contentPadding).coerceAtLeast(0),
                )
                setPadding(scaledPadding, 0, scaledPadding, 0)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = context.dpToPx(BUTTON_ROW_TOP_MARGIN_DP)
            }
        }

    private fun createActionButton(
        props: PopupProps,
        btn: PopupProps.Button,
        isFirst: Boolean,
        onButton: ((PopupProps.Button) -> Unit)?,
    ): Button {
        val size = props.buttonSize?.takeIf { it >= MIN_BUTTON_SIZE_SP }
            ?: settings.defaultButtonSize.takeIf { it >= MIN_BUTTON_SIZE_SP }
            ?: (props.messageSize * FALLBACK_BUTTON_SIZE_RATIO)
                .coerceIn(FALLBACK_BUTTON_SIZE_MIN_SP, FALLBACK_BUTTON_SIZE_MAX_SP)
        val normalTextColor = parseColorOrDefault(
            props.buttonTextColor ?: settings.defaultButtonTextColor,
            Color.WHITE,
        )
        val focusTextColor = parseColorOrDefault(
            props.buttonFocusTextColor ?: settings.defaultButtonFocusTextColor,
            Color.WHITE,
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
                setTextColor(createFocusColorStateList(normalTextColor, focusTextColor))
            }
            textSize = size
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
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

    private fun createButtonBackground(
        props: PopupProps,
        forceFocused: Boolean = false,
    ): Drawable {
        val radius = props.buttonCornerRadius ?: settings.defaultButtonCornerRadius
        val radiusPx = props.scaleValue(context, radius.toInt()).toFloat()

        val normalColor = parseColorOrDefault(
            props.buttonColor ?: settings.defaultButtonColor,
            DEFAULT_BUTTON_COLOR,
        )
        val focusColor = parseColorOrDefault(
            props.buttonFocusColor ?: settings.defaultButtonFocusColor,
            DEFAULT_BUTTON_FOCUS_COLOR,
        )
        val borderColor = parseColorOrDefault(
            props.buttonBorderColor ?: settings.defaultButtonBorderColor,
            DEFAULT_BUTTON_BORDER_COLOR,
        )

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

    private fun handleConfirmKey(event: KeyEvent, props: PopupProps): Boolean = when {
        !closeButton.hasFocus() && props.buttons.isNotEmpty() -> false
        event.action == KeyEvent.ACTION_DOWN && closeButtonHidden -> {
            closeButtonWakeConsumedDown = true
            revealCloseButton(props)
            true
        }
        event.action == KeyEvent.ACTION_UP && closeButtonWakeConsumedDown -> {
            closeButtonWakeConsumedDown = false
            true
        }
        else -> false
    }

    private fun hideCloseButton() {
        mainHandler.removeCallbacks(autoHideRunnable)
        closeButton.animate().cancel()
        closeButton.isVisible = false
        closeButtonHidden = false
        closeButtonWakeConsumedDown = false
        lastCloseButtonPosition = null
    }

    private fun showCloseButtonPreviewState() {
        mainHandler.removeCallbacks(autoHideRunnable)
        closeButton.animate().cancel()
        closeButton.alpha = 1f
        closeButton.scaleX = 1f
        closeButton.scaleY = 1f
        closeButtonHidden = false
    }

    private fun applyCloseButtonLayout(props: PopupProps, pos: String) {
        val gravity = when (pos) {
            "top_left" -> Gravity.TOP or Gravity.START
            "bottom_left" -> Gravity.BOTTOM or Gravity.START
            "bottom_right" -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.TOP or Gravity.END
        }
        val sizeDp = (props.closeButtonSize ?: settings.closeButtonSize).coerceIn(
            PopupProps.CLOSE_BUTTON_SIZE_MIN,
            PopupProps.CLOSE_BUTTON_SIZE_MAX,
        )
        val sizePx = context.dpToPx(sizeDp)
        val marginDp = if (props.coversScreen) {
            CLOSE_BUTTON_MARGIN_FULLSCREEN_DP
        } else {
            CLOSE_BUTTON_MARGIN_COMPACT_DP
        }
        val margin = context.dpToPx(marginDp)
        (closeButton.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = sizePx
            lp.height = sizePx
            lp.gravity = gravity
            lp.setMargins(margin, margin, margin, margin)
            closeButton.layoutParams = lp
        }
    }

    private fun applyCloseButtonStyleAndListeners(props: PopupProps, onClose: (() -> Unit)?) {
        val previewFocused = props.isPreview && props.buttons.isEmpty()
        val normalColor = parseColorOrDefault(
            props.buttonColor ?: settings.defaultButtonColor,
            ContextCompat.getColor(context, R.color.preset_platinum),
        )
        val focusColor = parseColorOrDefault(
            props.buttonFocusColor ?: settings.defaultButtonFocusColor,
            ContextCompat.getColor(context, R.color.preset_sky_blue),
        )
        closeButton.background = null
        closeButton.imageTintList = if (previewFocused) {
            ColorStateList.valueOf(focusColor)
        } else {
            createFocusColorStateList(normalColor, focusColor)
        }
        closeButton.isFocusable = !props.isPreview
        closeButton.isClickable = !props.isPreview
        closeButton.setOnFocusChangeListener { v: View, hasFocus ->
            if (!props.isPreview) {
                val scale = if (hasFocus) CLOSE_BUTTON_FOCUS_SCALE else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(CLOSE_BUTTON_FOCUS_ANIM_MS).start()
                if (hasFocus && closeButtonHidden) {
                    revealCloseButton(props)
                }
            }
        }
        closeButton.setOnClickListener {
            if (props.isPreview) return@setOnClickListener
            if (closeButtonHidden) {
                revealCloseButton(props)
            } else {
                onClose?.invoke()
            }
        }
    }

    private fun revealCloseButton(props: PopupProps) {
        if (!closeButton.isVisible || isCleanedUp()) return
        mainHandler.removeCallbacks(autoHideRunnable)
        closeButtonHidden = false
        closeButton.animate().cancel()
        if (closeButton.alpha < 1f) {
            closeButton.animate().alpha(1f).setDuration(CLOSE_BUTTON_REVEAL_ANIM_MS).start()
        }
        scheduleAutoHide(props)
    }

    private fun scheduleAutoHide(props: PopupProps) {
        mainHandler.removeCallbacks(autoHideRunnable)
        if (props.isPreview || isCleanedUp() || !closeButton.isVisible) return
        val timeoutSec = props.closeButtonTimeout ?: settings.closeButtonTimeout
        if (timeoutSec > 0) {
            mainHandler.postDelayed(autoHideRunnable, timeoutSec * MS_PER_SECOND)
        }
    }

    private fun fadeOutCloseButton() {
        val props = currentProps ?: return
        if (props.isPreview || isCleanedUp() || !closeButton.isVisible) return
        closeButtonHidden = true
        closeButton.animate().cancel()
        closeButton.animate().alpha(0f).setDuration(CLOSE_BUTTON_FADE_OUT_ANIM_MS).start()
    }

    companion object {
        const val BUTTON_ROW_TAG = "popup_button_row"
        private const val DEFAULT_BUTTON_COLOR = 0xFFEAEEF2.toInt()
        private const val DEFAULT_BUTTON_FOCUS_COLOR = 0xFF196584.toInt()
        private const val DEFAULT_BUTTON_BORDER_COLOR = 0xFF71787D.toInt()
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
        private const val CLOSE_BUTTON_MARGIN_FULLSCREEN_DP = 16
        private const val CLOSE_BUTTON_MARGIN_COMPACT_DP = 8
        private const val CLOSE_BUTTON_FOCUS_SCALE = 1.15f
        private const val CLOSE_BUTTON_FOCUS_ANIM_MS = 120L
        private const val CLOSE_BUTTON_REVEAL_ANIM_MS = 150L
        private const val CLOSE_BUTTON_FADE_OUT_ANIM_MS = 300L
        private const val MS_PER_SECOND = 1000L

        private fun createFocusColorStateList(normalColor: Int, focusColor: Int): ColorStateList {
            return ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf(),
                ),
                intArrayOf(focusColor, focusColor, normalColor),
            )
        }
    }
}
