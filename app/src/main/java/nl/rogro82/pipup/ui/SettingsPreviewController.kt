package nl.rogro82.pipup.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R

/**
 * Manages the live [PopupView] preview overlay inside [SettingsActivity], including debounced
 * updates, placeholder media rendering, focus-driven icon preview toggling, and gravity flips.
 */
@UnstableApi
internal class SettingsPreviewController(
    private val activity: SettingsActivity,
    private val settings: AppSettings,
    private val previewArea: FrameLayout,
    private val allRailIds: List<Int>,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var cachedPlaceholder: Bitmap? = null
    private var showPreviewIcon = false

    fun setIconVisible(visible: Boolean) {
        if (showPreviewIcon == visible) return
        showPreviewIcon = visible
        update()
    }

    fun update(animate: Boolean = false) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            val props = buildPreviewProps()
            val existingPreview = previewArea.getChildAt(0) as? PopupView
            if (existingPreview != null && !animate) {
                updateExistingPreview(existingPreview, props)
            } else {
                recreatePreview(props, animate)
            }
        }, PREVIEW_UPDATE_DELAY_MS)
    }

    fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        // Note: We don't manually recycle the placeholder here to avoid "recycled bitmap" errors
        // if the UI performs a final layout pass after activity destruction.
        cachedPlaceholder = null
    }

    private fun getOrCreatePreviewPlaceholder(): Bitmap {
        return cachedPlaceholder ?: createBitmap(PREVIEW_PLACEHOLDER_WIDTH, PREVIEW_PLACEHOLDER_HEIGHT).applyCanvas {
            drawColor(ContextCompat.getColor(activity, R.color.preview_placeholder_bg))
            val paint = Paint().apply {
                color = ContextCompat.getColor(activity, R.color.preview_placeholder_text)
                textSize = PREVIEW_PLACEHOLDER_TEXT_SIZE
                textAlign = Paint.Align.CENTER
            }
            drawText(
                activity.getString(R.string.settings_preview_media),
                PREVIEW_PLACEHOLDER_TEXT_X,
                PREVIEW_PLACEHOLDER_TEXT_Y,
                paint,
            )
        }.also { cachedPlaceholder = it }
    }

    private fun buildPreviewProps(): PopupProps {
        val placeholder = getOrCreatePreviewPlaceholder()
        val sampleButtons = if (activity.currentNavId == R.id.nav_item_button) {
            listOf(
                PopupProps.Button("preview_1", activity.getString(android.R.string.ok)),
                PopupProps.Button("preview_2", activity.getString(android.R.string.cancel)),
            )
        } else {
            emptyList()
        }
        return PopupProps(
            title = activity.getString(R.string.settings_preview_title),
            message = activity.getString(R.string.settings_preview_message),
            backgroundColor = settings.getFullBackgroundColor(),
            borderRadius = settings.borderRadius,
            borderWidth = settings.borderWidth,
            borderColor = settings.borderColor,
            contentPadding = settings.contentPadding,
            titleColor = settings.titleColor,
            titleSize = settings.titleSize,
            messageColor = settings.messageColor,
            messageSize = settings.messageSize,
            titleAlignment = settings.titleAlignment,
            messageAlignment = settings.messageAlignment,
            mediaPosition = settings.mediaPosition,
            mediaFullBleed = settings.mediaFullBleed,
            liveBadge = settings.liveBadge,
            liveBadgePosition = settings.liveBadgePosition,
            icon = if (showPreviewIcon) PREVIEW_ICON_EMOJI else null,
            iconPosition = settings.iconPosition,
            iconWidth = settings.iconWidth,
            animationType = settings.animationType,
            animationDuration = settings.animationDuration,
            animationExit = settings.animationExit,
            media = PopupProps.Media.Bitmap(placeholder, PREVIEW_MEDIA_WIDTH),
            buttons = sampleButtons,
            buttonSize = settings.defaultButtonSize,
            buttonColor = settings.defaultButtonColor,
            buttonFocusColor = settings.defaultButtonFocusColor,
            buttonTextColor = settings.defaultButtonTextColor,
            buttonFocusTextColor = settings.defaultButtonFocusTextColor,
            buttonBorderColor = settings.defaultButtonBorderColor,
            buttonCornerRadius = settings.defaultButtonCornerRadius,
            closeButton = settings.closeButton,
            closeButtonSize = settings.closeButtonSize,
            isPreview = true,
        )
    }

    private fun updateExistingPreview(existingPreview: PopupView, props: PopupProps) {
        existingPreview.updateFromProps(props)
        applyPosition(existingPreview, currentSubmenuFocus())
    }

    private fun recreatePreview(props: PopupProps, animate: Boolean) {
        previewArea.removeAllViews()
        val preview = PopupView.build(activity, props)
        previewArea.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        applyPosition(preview, currentSubmenuFocus())

        if (animate && props.effectiveAnimationType != PopupProps.ANIM_NONE) {
            preview.postDelayed(
                { if (preview.parent != null) preview.animateIn() },
                PREVIEW_ANIMATE_DELAY_MS,
            )
        }
    }

    private fun currentSubmenuFocus(): View? =
        activity.currentFocus?.takeIf { it.id != View.NO_ID && it.id !in allRailIds }

    companion object {
        private const val PREVIEW_UPDATE_DELAY_MS = 10L
        private const val PREVIEW_ANIMATE_DELAY_MS = 500L
        private const val PREVIEW_MARGIN_DP = 10
        private const val PREVIEW_TOP_MARGIN_DP = 40
        private const val SCREEN_LOCATION_COORDS = 2
        private const val PREVIEW_FLIP_THRESHOLD = 0.6
        private const val PREVIEW_PLACEHOLDER_WIDTH = 320
        private const val PREVIEW_PLACEHOLDER_HEIGHT = 180
        private const val PREVIEW_PLACEHOLDER_TEXT_SIZE = 40f
        private const val PREVIEW_PLACEHOLDER_TEXT_X = 160f
        private const val PREVIEW_PLACEHOLDER_TEXT_Y = 100f
        private const val PREVIEW_MEDIA_WIDTH = 180
        private const val PREVIEW_ICON_EMOJI = "\uD83D\uDD14"

        /** Submenu control IDs whose focus enables the header icon in the live preview. */
        val ICON_PREVIEW_VIEW_IDS = setOf(R.id.spinner_icon_position, R.id.seekbar_icon_width)

        /**
         * Positions [popup] in the bottom-right corner by default, flipping to top-right when
         * [focusedView] lies in the lower 40% of the screen (`y > 60%`).
         */
        fun applyPosition(popup: View, focusedView: View?) {
            val params = popup.layoutParams as? FrameLayout.LayoutParams ?: return
            val metrics = popup.resources.displayMetrics
            val shouldBeAtTop = if (focusedView != null) {
                val location = IntArray(SCREEN_LOCATION_COORDS)
                focusedView.getLocationOnScreen(location)
                location[1] > metrics.heightPixels * PREVIEW_FLIP_THRESHOLD
            } else {
                false
            }
            val newGravity = (if (shouldBeAtTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.END
            val marginSide = (metrics.density * PREVIEW_MARGIN_DP).toInt()
            val marginTop = if (shouldBeAtTop) (metrics.density * PREVIEW_TOP_MARGIN_DP).toInt() else 0
            val marginBottom = if (shouldBeAtTop) 0 else marginSide

            val marginsChanged = params.topMargin != marginTop ||
                params.rightMargin != marginSide ||
                params.bottomMargin != marginBottom
            if (params.gravity != newGravity || marginsChanged) {
                params.gravity = newGravity
                params.setMargins(0, marginTop, marginSide, marginBottom)
                popup.layoutParams = params
            }
        }
    }
}
