package nl.rogro82.pipup.core

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.getLocalizedContext
import nl.rogro82.pipup.showToast
import nl.rogro82.pipup.ui.PopupView

/**
 * Manages the WindowManager overlay root, position stack containers, stacking/dual-axis margins,
 * focusability flags, and Back/key interception for [NotificationManager].
 */
@UnstableApi
internal class PopupOverlayHost(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mainHandler: Handler,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun hasActiveFullscreen(): Boolean
        fun hasActiveFocusablePopup(): Boolean
        fun hasExitingFocusablePopup(): Boolean
        fun hasActiveBackDismissiblePopup(): Boolean
        fun onBackInvoked()
        fun dispatchKeyToActiveCloseButton(event: KeyEvent): Boolean
    }

    private data class ContainerKey(
        val position: PopupProps.Position,
        val direction: PopupProps.StackDirection,
        val fullscreen: Boolean = false,
    )

    private val containers = mutableMapOf<ContainerKey, LinearLayout>()
    private var overlay: FrameLayout? = null
    private var lastOverlayErrorToastAt: Long = 0L

    fun findFocus(): View? = overlay?.findFocus()

    fun measurePopupView(view: PopupView) {
        view.syncLayoutForMeasurement()
        val dm = context.resources.displayMetrics
        view.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST),
        )
    }

    fun ensurePositionContainer(
        position: PopupProps.Position,
        stack: String? = null,
        fullscreen: Boolean = false,
    ): LinearLayout? {
        val overlayView = ensureOverlay() ?: return null
        val direction = PopupProps.parseStackDirection(if (fullscreen) null else stack)
        val key = ContainerKey(position, direction, fullscreen)

        return containers.getOrPut(key) {
            PositionContainer(context).apply {
                orientation = if (direction == PopupProps.StackDirection.HORIZONTAL) {
                    LinearLayout.HORIZONTAL
                } else {
                    LinearLayout.VERTICAL
                }
                clipChildren = false
                clipToPadding = false
                gravity = if (direction == PopupProps.StackDirection.HORIZONTAL) {
                    when (position.getVerticalAnchor()) {
                        PopupProps.VerticalAnchor.BOTTOM -> Gravity.BOTTOM
                        PopupProps.VerticalAnchor.TOP -> Gravity.TOP
                        PopupProps.VerticalAnchor.CENTER -> Gravity.CENTER_VERTICAL
                    }
                } else {
                    when (position.getScreenSide()) {
                        PopupProps.ScreenSide.RIGHT -> Gravity.END
                        PopupProps.ScreenSide.LEFT -> Gravity.START
                        PopupProps.ScreenSide.CENTER -> Gravity.CENTER_HORIZONTAL
                    }
                }
                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    applyPositionToLayoutParams(this, position, fullscreen)
                }
                overlayView.addView(this, params)
            }
        }
    }

    fun removeContainerIfEmpty(container: LinearLayout?) {
        if (container != null && container.isEmpty()) {
            overlay?.removeView(container)
            containers.values.remove(container)
        }
    }

    fun clearContainers() {
        for ((_, container) in containers) {
            overlay?.removeView(container)
        }
        containers.clear()
    }

    fun adjustDualAxisContainerMargins(position: PopupProps.Position) {
        val hContainer = containers[ContainerKey(position, PopupProps.StackDirection.HORIZONTAL)]
        val hLp = hContainer?.layoutParams as? FrameLayout.LayoutParams ?: return
        val vContainer = containers[ContainerKey(position, PopupProps.StackDirection.VERTICAL)]
        val gapPx = context.dpToPx(PopupProps.STACK_GAP_DP)
        val baseMargin = context.dpToPx(PopupProps.BASE_MARGIN_DP)

        val (vWidth, vHeight) = measureContainerSize(vContainer)
        val (_, hHeight) = measureContainerSize(hContainer)

        when (position) {
            PopupProps.Position.TopCenter ->
                hLp.topMargin = if (vHeight > 0) baseMargin + vHeight + gapPx else baseMargin
            PopupProps.Position.BottomCenter ->
                hLp.bottomMargin = if (vHeight > 0) baseMargin + vHeight + gapPx else baseMargin
            PopupProps.Position.Center ->
                adjustCenterHorizontalMargins(hLp, vHeight, hHeight, gapPx)
            else -> {
                val extraShift = if (vWidth > 0) vWidth + gapPx else 0
                when (position.getScreenSide()) {
                    PopupProps.ScreenSide.RIGHT -> hLp.rightMargin = baseMargin + extraShift
                    PopupProps.ScreenSide.LEFT -> hLp.leftMargin = baseMargin + extraShift
                    PopupProps.ScreenSide.CENTER -> {}
                }
            }
        }
        hContainer.requestLayout()
    }

    fun applyStackingMargins(lp: LinearLayout.LayoutParams, props: PopupProps) {
        if (props.coversScreen) {
            lp.setMargins(0, 0, 0, 0)
            lp.gravity = Gravity.CENTER
            return
        }
        val gapPx = context.dpToPx(PopupProps.STACK_GAP_DP)
        val pos = props.getPositionEnum()
        val side = pos.getScreenSide()
        val anchor = pos.getVerticalAnchor()

        if (props.getStackDirectionEnum() == PopupProps.StackDirection.HORIZONTAL) {
            when (side) {
                PopupProps.ScreenSide.RIGHT -> lp.leftMargin = gapPx
                else -> lp.rightMargin = gapPx
            }
            lp.gravity = when (anchor) {
                PopupProps.VerticalAnchor.BOTTOM -> Gravity.BOTTOM
                PopupProps.VerticalAnchor.TOP -> Gravity.TOP
                PopupProps.VerticalAnchor.CENTER -> Gravity.CENTER_VERTICAL
            }
        } else {
            when (anchor) {
                PopupProps.VerticalAnchor.BOTTOM -> lp.topMargin = gapPx
                else -> lp.bottomMargin = gapPx
            }
            lp.gravity = when (side) {
                PopupProps.ScreenSide.RIGHT -> Gravity.END
                PopupProps.ScreenSide.LEFT -> Gravity.START
                PopupProps.ScreenSide.CENTER -> Gravity.CENTER_HORIZONTAL
            }
        }
    }

    fun updateOverlayFocusability() {
        val overlayView = overlay ?: return
        val params = overlayView.layoutParams as? WindowManager.LayoutParams ?: return
        val targetFlags = computeOverlayFlags()

        if (params.flags != targetFlags) {
            params.flags = targetFlags
            applyFullscreenWindowAttributes(params)
            applyOverlayLayoutParams(overlayView, params)
        }

        val needsFocus = callbacks.hasActiveFocusablePopup()
        overlayView.isFocusable = needsFocus
        overlayView.isFocusableInTouchMode = needsFocus
        if (needsFocus && !overlayView.hasFocus()) {
            overlayView.post {
                if (overlay === overlayView && callbacks.hasActiveFocusablePopup() && !overlayView.hasFocus()) {
                    overlayView.requestFocus()
                }
            }
        }
    }

    fun prepareIdleOverlay() {
        clearContainers()
        val overlayView = overlay ?: return
        overlayView.isVisible = false
        overlayView.isFocusable = false
        overlayView.isFocusableInTouchMode = false
        val params = overlayView.layoutParams as? WindowManager.LayoutParams ?: return
        val idleFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (params.flags != idleFlags) {
            params.flags = idleFlags
            applyOverlayLayoutParams(overlayView, params)
        }
    }

    fun removeOverlay() {
        overlay?.let { view ->
            if (view.parent != null) {
                try {
                    windowManager.removeView(view)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "WindowManager removeView error: ${e.message}")
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "WindowManager removeView error: ${e.message}")
                } catch (e: WindowManager.BadTokenException) {
                    Log.e(TAG, "WindowManager removeView error: ${e.message}")
                }
            }
        }
        overlay = null
        containers.clear()
    }

    private fun measureContainerSize(container: LinearLayout?): Pair<Int, Int> {
        if (container == null || container.isEmpty()) return 0 to 0
        val dm = context.resources.displayMetrics
        container.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST),
        )
        val fallbackMetrics by lazy {
            (0 until container.childCount).firstNotNullOfOrNull {
                (container.getChildAt(it) as? PopupView)?.props
            }?.calculateMetrics(context)
        }
        val width = if (container.measuredWidth > 0) {
            container.measuredWidth
        } else {
            fallbackMetrics?.width ?: context.dpToPx(FALLBACK_CONTAINER_WIDTH_DP)
        }
        val height = if (container.measuredHeight > 0) {
            container.measuredHeight
        } else {
            fallbackMetrics?.height ?: context.dpToPx(FALLBACK_CONTAINER_HEIGHT_DP)
        }
        return width to height
    }

    private fun adjustCenterHorizontalMargins(
        hLp: FrameLayout.LayoutParams,
        vHeight: Int,
        hHeight: Int,
        gapPx: Int,
    ) {
        if (vHeight <= 0) {
            hLp.gravity = Gravity.CENTER
            hLp.topMargin = 0
            hLp.bottomMargin = 0
            return
        }
        val screenHeight = context.resources.displayMetrics.heightPixels
        val spaceBelow = (screenHeight - vHeight) / 2 - gapPx
        if (spaceBelow >= hHeight) {
            hLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            hLp.topMargin = (screenHeight + vHeight) / 2 + gapPx
            hLp.bottomMargin = 0
        } else {
            hLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            hLp.bottomMargin = (screenHeight + vHeight) / 2 + gapPx
            hLp.topMargin = 0
        }
    }

    private fun applyPositionToLayoutParams(
        params: FrameLayout.LayoutParams,
        position: PopupProps.Position,
        fullscreen: Boolean = false,
    ) {
        val margin = if (fullscreen) 0 else context.dpToPx(PopupProps.BASE_MARGIN_DP)
        params.gravity = POSITION_GRAVITIES[position] ?: (Gravity.TOP or Gravity.END)
        params.setMargins(
            if (position.getScreenSide() == PopupProps.ScreenSide.LEFT) margin else 0,
            if (position.getVerticalAnchor() == PopupProps.VerticalAnchor.TOP) margin else 0,
            if (position.getScreenSide() == PopupProps.ScreenSide.RIGHT) margin else 0,
            if (position.getVerticalAnchor() == PopupProps.VerticalAnchor.BOTTOM) margin else 0,
        )
    }

    private fun computeOverlayFlags(): Int {
        val needsFocus = callbacks.hasActiveFocusablePopup() || callbacks.hasExitingFocusablePopup()
        var flags = if (needsFocus) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (callbacks.hasActiveFullscreen()) {
            flags = flags or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return flags
    }

    private fun applyFullscreenWindowAttributes(params: WindowManager.LayoutParams) {
        val hasFullscreen = callbacks.hasActiveFullscreen()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = if (hasFullscreen) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.fitInsetsTypes = if (hasFullscreen) 0 else WindowInsets.Type.systemBars()
        }
    }

    private fun applyOverlayLayoutParams(overlayView: FrameLayout, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(overlayView, params)
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "WindowManager update flags error: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "WindowManager update flags error: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "WindowManager update flags error: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "WindowManager update flags error: ${e.message}")
        }
    }

    private fun ensureOverlay(): FrameLayout? {
        overlay?.let { existing ->
            if (!existing.isVisible) {
                existing.isVisible = true
                updateOverlayFocusability()
            }
            return existing
        }
        val view = OverlayRootFrameLayout(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            computeOverlayFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            applyFullscreenWindowAttributes(this)
        }
        return try {
            windowManager.addView(view, params)
            overlay = view
            view
        } catch (e: WindowManager.BadTokenException) {
            handleOverlayAddError(e)
            null
        } catch (e: SecurityException) {
            handleOverlayAddError(e)
            null
        } catch (e: IllegalArgumentException) {
            handleOverlayAddError(e)
            null
        } catch (e: IllegalStateException) {
            handleOverlayAddError(e)
            null
        }
    }

    private fun handleOverlayAddError(e: Exception) {
        Log.e(TAG, "WindowManager error: ${e.message}")
        val now = SystemClock.elapsedRealtime()
        if (lastOverlayErrorToastAt == 0L || now - lastOverlayErrorToastAt >= OVERLAY_ERROR_TOAST_COOLDOWN_MS) {
            lastOverlayErrorToastAt = now
            context.showToast(
                context.getLocalizedContext(PiPupApp.settings.language)
                    .getString(R.string.error_permission_denied_overlay),
            )
        }
    }

    private class PositionContainer(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val safeWidthSpec = if (orientation == HORIZONTAL) {
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            } else {
                widthMeasureSpec
            }
            val safeHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            super.onMeasure(safeWidthSpec, safeHeightSpec)
        }
    }

    private inner class OverlayRootFrameLayout(context: Context) : FrameLayout(context) {
        private var backCallback: Any? = null

        init {
            clipChildren = false
            clipToPadding = false
            descendantFocusability = FOCUS_AFTER_DESCENDANTS
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val callback = OnBackInvokedCallback {
                    mainHandler.post { callbacks.onBackInvoked() }
                }
                findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    callback,
                )
                backCallback = callback
            }
        }

        override fun onDetachedFromWindow() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                (backCallback as? OnBackInvokedCallback)?.let { cb ->
                    findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(cb)
                }
                backCallback = null
            }
            super.onDetachedFromWindow()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && callbacks.hasActiveFocusablePopup()) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled &&
                    callbacks.hasActiveBackDismissiblePopup()
                ) {
                    mainHandler.post { callbacks.onBackInvoked() }
                }
                return true
            }
            val handled = super.dispatchKeyEvent(event)
            return handled || (findFocus() === this && callbacks.dispatchKeyToActiveCloseButton(event))
        }
    }

    companion object {
        private const val TAG = "NotificationManager"
        private const val FALLBACK_CONTAINER_WIDTH_DP = 300
        private const val FALLBACK_CONTAINER_HEIGHT_DP = 200
        private const val OVERLAY_ERROR_TOAST_COOLDOWN_MS = 5000L
        private val POSITION_GRAVITIES = mapOf(
            PopupProps.Position.TopRight to (Gravity.TOP or Gravity.END),
            PopupProps.Position.TopLeft to (Gravity.TOP or Gravity.START),
            PopupProps.Position.BottomRight to (Gravity.BOTTOM or Gravity.END),
            PopupProps.Position.BottomLeft to (Gravity.BOTTOM or Gravity.START),
            PopupProps.Position.Center to Gravity.CENTER,
            PopupProps.Position.TopCenter to (Gravity.TOP or Gravity.CENTER_HORIZONTAL),
            PopupProps.Position.BottomCenter to (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
            PopupProps.Position.CenterLeft to (Gravity.CENTER_VERTICAL or Gravity.START),
            PopupProps.Position.CenterRight to (Gravity.CENTER_VERTICAL or Gravity.END),
        )
    }
}
