package nl.rogro82.pipup.core

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.ui.PopupView
import java.util.*

/**
 * Manages the lifecycle and queueing of notifications.
 */
@UnstableApi
class NotificationManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "NotificationManager"
        private val SAFETY_TIMEOUT_TOKEN = Any()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<PopupProps>()

    private var overlay: FrameLayout? = null
    private var currentPopup: PopupView? = null
    private var nextPopup: PopupView? = null
    private var nextProps: PopupProps? = null

    private var preparingView: PopupView? = null
    private var isPreparing = false

    private val durationToken = Any()

    fun enqueue(props: PopupProps) {
        handler.post {
            if (props.overwrite) {
                Log.d(TAG, "Overwrite requested, prioritizing new popup and interrupting current")
                handler.removeCallbacksAndMessages(SAFETY_TIMEOUT_TOKEN)

                isPreparing = false
                preparingView?.let { it.cleanup(); preparingView = null }

                nextPopup?.let { it.cleanup(); nextPopup = null }
                nextProps = null

                removeCurrentPopup(immediate = true, triggerNext = false)
                queue.addFirst(props)
            } else {
                queue.addLast(props)
            }
            processNext()
        }
    }

    fun cancelAll() {
        handler.post {
            queue.clear()
            handler.removeCallbacksAndMessages(SAFETY_TIMEOUT_TOKEN)
            handler.removeCallbacksAndMessages(durationToken)

            isPreparing = false
            preparingView?.let { overlay?.removeView(it); it.cleanup() }
            preparingView = null

            nextPopup?.let { overlay?.removeView(it); it.cleanup() }
            nextPopup = null
            nextProps = null

            removeCurrentPopup(immediate = true)
        }
    }

    private fun processNext() {
        if (isPreparing || currentPopup != null) return

        val props = queue.poll() ?: return
        preparePopup(props)
    }

    private fun preparePopup(props: PopupProps) {
        isPreparing = true

        // Safety timeout (longer than any media timeout)
        handler.postAtTime({
            Log.w(TAG, "Popup preparation timed out (hard safety)")
            isPreparing = false
            preparingView?.cleanup()
            preparingView = null
            processNext()
        }, SAFETY_TIMEOUT_TOKEN, android.os.SystemClock.uptimeMillis() + 45000)

        val view = PopupView(context, props)
        preparingView = view
        view.readyListener = object : PopupView.ReadyListener {
            override fun onReady() {
                handler.removeCallbacksAndMessages(SAFETY_TIMEOUT_TOKEN)
                handlePopupReady(view, props)
            }
        }
        view.create()
    }

    private fun handlePopupReady(view: PopupView, props: PopupProps) {
        isPreparing = false
        preparingView = null

        if (currentPopup == null) {
            showPopup(view, props)
        } else {
            nextPopup = view
            nextProps = props
        }
    }

    private fun showPopup(view: PopupView, props: PopupProps) {
        val overlayView = ensureOverlay() ?: run {
            Log.e(TAG, "Aborting popup: could not create overlay (check SYSTEM_ALERT_WINDOW permission)")
            view.cleanup()
            checkNextAfterRemoval()
            return
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            val margin = context.dpToPx(20)
            when (props.getPositionEnum()) {
                PopupProps.Position.TopRight -> { gravity = Gravity.TOP or Gravity.END; setMargins(0, margin, margin, 0) }
                PopupProps.Position.TopLeft -> { gravity = Gravity.TOP or Gravity.START; setMargins(margin, margin, 0, 0) }
                PopupProps.Position.BottomRight -> { gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, margin, margin) }
                PopupProps.Position.BottomLeft -> { gravity = Gravity.BOTTOM or Gravity.START; setMargins(margin, 0, 0, margin) }
                PopupProps.Position.Center -> { gravity = Gravity.CENTER }
            }
        }

        overlayView.addView(view, params)
        currentPopup = view
        view.animateIn()
        view.startMedia()

        handler.postAtTime({
            removeCurrentPopup()
        }, durationToken, android.os.SystemClock.uptimeMillis() + (props.duration * 1000L))
    }

    private fun removeCurrentPopup(immediate: Boolean = false, triggerNext: Boolean = true) {
        val popup = currentPopup ?: return
        currentPopup = null
        handler.removeCallbacksAndMessages(durationToken)

        if (immediate) {
            overlay?.removeView(popup)
            popup.cleanup()
            if (triggerNext) checkNextAfterRemoval()
        } else {
            popup.animateOut {
                overlay?.removeView(popup)
                popup.cleanup()
                if (triggerNext) checkNextAfterRemoval()
            }
        }
    }

    private fun checkNextAfterRemoval() {
        val next = nextPopup
        val props = nextProps

        if (next != null && props != null) {
            nextPopup = null
            nextProps = null
            showPopup(next, props)
        } else {
            processNext()
            if (queue.isEmpty() && currentPopup == null) {
                removeOverlay()
            }
        }
    }

    private fun ensureOverlay(): FrameLayout? {
        overlay?.let { return it }
        val view = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        return try {
            windowManager.addView(view, params)
            overlay = view
            view
        } catch (e: Exception) {
            Log.e(TAG, "WindowManager failed to add overlay view: ${e.message}")
            null
        }
    }

    private fun removeOverlay() {
        overlay?.let {
            if (it.parent != null) windowManager.removeView(it)
        }
        overlay = null
    }
}
