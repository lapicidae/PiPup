package nl.rogro82.pipup.core

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.getLocalizedContext
import nl.rogro82.pipup.showToast
import nl.rogro82.pipup.ui.PopupView
import java.util.*

/**
 * Manages the lifecycle and queueing of notifications using a state machine.
 */
@UnstableApi
class NotificationManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "NotificationManager"
        private val SAFETY_TIMEOUT_TOKEN = Any()
        private val ENQUEUE_OVERWRITE_TOKEN = Any()
        private const val PREP_TIMEOUT_MS = 45000L
        private const val DEBOUNCE_DELAY_MS = 250L
    }

    /**
     * Sealed class representing the possible states of the notification system.
     */
    private sealed class NotificationState {
        object Idle : NotificationState()
        data class Preparing(val props: PopupProps, val view: PopupView) : NotificationState()
        data class Displaying(val props: PopupProps, val view: PopupView) : NotificationState()
        data class DisplayingAndPreparing(
            val displayProps: PopupProps, val displayView: PopupView,
            val prepProps: PopupProps, val prepView: PopupView
        ) : NotificationState()
        data class DisplayingAndReady(
            val displayProps: PopupProps, val displayView: PopupView,
            val readyProps: PopupProps, val readyView: PopupView
        ) : NotificationState()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<PopupProps>()
    @Volatile private var state: NotificationState = NotificationState.Idle
    private var overlay: FrameLayout? = null

    private val durationToken = Any()
    val watchdogCleanups = java.util.concurrent.atomic.AtomicLong(0)
    val popupsShown = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile var lastPopup: PopupProps? = null
        private set
    @Volatile var lastPopupAt: Long = 0L
        private set

    /**
     * Checks if a notification is currently being displayed on the screen.
     */
    fun isDisplaying(): Boolean = synchronized(this) {
        state !is NotificationState.Idle && state !is NotificationState.Preparing
    }

    /**
     * Returns the properties of the currently displayed notification, or null if none.
     */
    fun getCurrentProps(): PopupProps? = synchronized(this) {
        when (val s = state) {
            is NotificationState.Displaying -> s.props
            is NotificationState.DisplayingAndPreparing -> s.displayProps
            is NotificationState.DisplayingAndReady -> s.displayProps
            else -> null
        }
    }

    /**
     * Asynchronously enqueues a notification for display.
     * Handles smart-updates for notifications with the overwrite flag using debouncing.
     */
    fun enqueue(props: PopupProps) {
        if (props.overwrite) {
            // Cancel any pending enqueues for overwrite to avoid main thread bombardment
            handler.removeCallbacksAndMessages(ENQUEUE_OVERWRITE_TOKEN)
            handler.postAtTime({
                handleEnqueue(props)
            }, ENQUEUE_OVERWRITE_TOKEN, SystemClock.uptimeMillis() + DEBOUNCE_DELAY_MS)
        } else {
            handler.post {
                handleEnqueue(props)
            }
        }
    }

    private fun handleEnqueue(props: PopupProps) {
        synchronized(this) {
            popupsShown.incrementAndGet()
            lastPopup = props
            lastPopupAt = SystemClock.elapsedRealtime()

            if (props.overwrite) {
                val currentProps = getCurrentPropsLocked()
                val currentView = when (val s = state) {
                    is NotificationState.Displaying -> s.view
                    is NotificationState.DisplayingAndPreparing -> s.displayView
                    is NotificationState.DisplayingAndReady -> s.displayView
                    else -> null
                }

                if (currentView != null && currentProps != null && canUpdateInPlace(currentProps, props)) {
                    Log.d(TAG, "Overwrite: Updating existing visible popup (Identical Media)")
                    handler.removeCallbacksAndMessages(durationToken)
                    currentView.updateFromProps(props)
                    applyPositionToLayoutParams(currentView.layoutParams as FrameLayout.LayoutParams, props)
                    currentView.animateIn()
                    currentView.startMedia()
                    scheduleRemoval(props.duration)
                    cancelPendingPreparationLocked()
                } else {
                    Log.d(TAG, "Overwrite: Media changed or no popup, preparing replacement")
                    cancelPendingPreparationLocked()
                    preparePopupLocked(props)
                }
            } else {
                queue.addLast(props)
                processNextLocked()
            }
        }
    }

    private fun preparePopupLocked(props: PopupProps) {
        // Ensure the popup uses the currently selected language
        val localizedContext = context.getLocalizedContext(PiPupApp.settings.language)
        val view = PopupView(localizedContext, props)

        state = when (val s = state) {
            is NotificationState.Idle -> NotificationState.Preparing(props, view)
            is NotificationState.Displaying -> NotificationState.DisplayingAndPreparing(s.props, s.view, props, view)
            else -> s // Should not happen due to processNext guard
        }

        // Safety timeout
        handler.postAtTime({
            synchronized(this) {
                Log.w(TAG, "Popup preparation timed out (hard safety)")
                watchdogCleanups.incrementAndGet()
                handlePrepTimeoutLocked(view)
            }
        }, SAFETY_TIMEOUT_TOKEN, SystemClock.uptimeMillis() + PREP_TIMEOUT_MS)

        view.readyListener = object : PopupView.ReadyListener {
            override fun onReady() {
                handler.removeCallbacksAndMessages(SAFETY_TIMEOUT_TOKEN)
                synchronized(this@NotificationManager) {
                    handlePopupReadyLocked(view)
                }
            }
        }
        view.create()
    }

    private fun handlePrepTimeoutLocked(view: PopupView) {
        if (state is NotificationState.Preparing && (state as NotificationState.Preparing).view == view) {
            view.cleanup()
            state = NotificationState.Idle
            processNextLocked()
        } else if (state is NotificationState.DisplayingAndPreparing && (state as NotificationState.DisplayingAndPreparing).prepView == view) {
            view.cleanup()
            val s = state as NotificationState.DisplayingAndPreparing
            state = NotificationState.Displaying(s.displayProps, s.displayView)
        }
    }

    private fun handlePopupReadyLocked(view: PopupView) {
        val nextState = when (val s = state) {
            is NotificationState.Preparing -> {
                if (s.view == view) {
                    showPopup(view, s.props)
                    NotificationState.Displaying(s.props, view)
                } else { view.cleanup(); s }
            }
            is NotificationState.DisplayingAndPreparing -> {
                if (s.prepView == view) {
                    if (s.prepProps.overwrite) {
                        replaceCurrentPopup(s.displayView, view, s.prepProps)
                        NotificationState.Displaying(s.prepProps, view)
                    } else {
                        NotificationState.DisplayingAndReady(s.displayProps, s.displayView, s.prepProps, view)
                    }
                } else { view.cleanup(); s }
            }
            else -> {
                Log.d(TAG, "Ignoring ready signal from stale/cancelled view")
                view.cleanup()
                s
            }
        }
        state = nextState

        // After a popup becomes Displaying, check if we can start preparing the next one in queue
        if (state is NotificationState.Displaying) {
            processNextLocked()
        }
    }

    private fun getCurrentPropsLocked(): PopupProps? = when (val s = state) {
        is NotificationState.Displaying -> s.props
        is NotificationState.DisplayingAndPreparing -> s.displayProps
        is NotificationState.DisplayingAndReady -> s.displayProps
        else -> null
    }

    private fun cancelPendingPreparationLocked() {
        handler.removeCallbacksAndMessages(SAFETY_TIMEOUT_TOKEN)
        val nextState = when (val s = state) {
            is NotificationState.Preparing -> {
                s.view.cleanup()
                NotificationState.Idle
            }
            is NotificationState.DisplayingAndPreparing -> {
                s.prepView.cleanup()
                NotificationState.Displaying(s.displayProps, s.displayView)
            }
            is NotificationState.DisplayingAndReady -> {
                s.readyView.cleanup()
                NotificationState.Displaying(s.displayProps, s.displayView)
            }
            else -> s
        }
        state = nextState
    }

    private fun canUpdateInPlace(oldProps: PopupProps, newProps: PopupProps): Boolean {
        if (oldProps.animationType != newProps.animationType ||
            oldProps.animationDuration != newProps.animationDuration) return false

        val m1 = oldProps.media ?: oldProps.image?.let { PopupProps.Media.Image(it, oldProps.imageWidth ?: 480) }
        val m2 = newProps.media ?: newProps.image?.let { PopupProps.Media.Image(it, newProps.imageWidth ?: 480) }

        if (m1 == null && m2 == null) return true
        if (m1 == null || m2 == null) return false
        if (m1::class != m2::class) return false

        return when (m1) {
            is PopupProps.Media.Image -> (m2 as PopupProps.Media.Image).let { m1.uri == it.uri && m1.cache == it.cache && m1.scale == it.scale }
            is PopupProps.Media.Video -> (m2 as PopupProps.Media.Video).let { m1.uri == it.uri && m1.scale == it.scale }
            is PopupProps.Media.Web -> (m2 as PopupProps.Media.Web).let { m1.uri == it.uri && m1.cache == it.cache && m1.scale == it.scale }
            is PopupProps.Media.Whep -> (m2 as PopupProps.Media.Whep).let { m1.uri == it.uri && m1.scale == it.scale }
            is PopupProps.Media.LocalFile -> m1.path == (m2 as PopupProps.Media.LocalFile).path
            else -> false
        }
    }

    /**
     * Cancels all notifications and clears the queue.
     */
    fun cancelAll() {
        handler.post {
            synchronized(this) {
                queue.clear()
                handler.removeCallbacksAndMessages(SAFETY_TIMEOUT_TOKEN)
                handler.removeCallbacksAndMessages(durationToken)
                handler.removeCallbacksAndMessages(ENQUEUE_OVERWRITE_TOKEN)

                when (val s = state) {
                    is NotificationState.Preparing -> s.view.cleanup()
                    is NotificationState.Displaying -> {
                        overlay?.removeView(s.view)
                        s.view.cleanup()
                    }
                    is NotificationState.DisplayingAndPreparing -> {
                        overlay?.removeView(s.displayView)
                        s.displayView.cleanup()
                        s.prepView.cleanup()
                    }
                    is NotificationState.DisplayingAndReady -> {
                        overlay?.removeView(s.displayView)
                        s.displayView.cleanup()
                        s.readyView.cleanup()
                    }
                    else -> {}
                }

                state = NotificationState.Idle
                removeOverlay()
            }
        }
    }

    private fun processNextLocked() {
        if (state !is NotificationState.Idle && state !is NotificationState.Displaying) return

        val props = queue.poll() ?: return
        preparePopupLocked(props)
    }

    private fun replaceCurrentPopup(oldView: PopupView, newView: PopupView, props: PopupProps) {
        val overlayView = ensureOverlay() ?: run {
            newView.cleanup()
            return
        }
        Log.d(TAG, "Overwrite: Swapping visible popup with new prepared one")

        val params = getLayoutParams(props)
        overlayView.addView(newView, params)
        newView.animateIn()
        newView.startMedia()

        overlayView.removeView(oldView)
        oldView.cleanup()

        handler.removeCallbacksAndMessages(durationToken)
        scheduleRemoval(props.duration)
    }

    private fun showPopup(view: PopupView, props: PopupProps) {
        val overlayView = ensureOverlay() ?: run {
            Log.e(TAG, "Aborting popup: could not create overlay")
            context.showToast(context.getLocalizedContext(PiPupApp.settings.language).getString(R.string.error_permission_denied_overlay))
            view.cleanup()
            state = NotificationState.Idle
            processNextLocked()
            return
        }

        val params = getLayoutParams(props)
        overlayView.addView(view, params)
        view.animateIn()
        view.startMedia()

        scheduleRemoval(props.duration)
    }

    private fun scheduleRemoval(duration: Int) {
        handler.postAtTime({
            synchronized(this) {
                removeCurrentPopupLocked()
            }
        }, durationToken, SystemClock.uptimeMillis() + (duration * 1000L))
    }

    private fun getLayoutParams(props: PopupProps): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            applyPositionToLayoutParams(this, props)
        }
    }

    private fun applyPositionToLayoutParams(params: FrameLayout.LayoutParams, props: PopupProps) {
        val margin = context.dpToPx(20)
        params.gravity = when (props.getPositionEnum()) {
            PopupProps.Position.TopRight -> Gravity.TOP or Gravity.END
            PopupProps.Position.TopLeft -> Gravity.TOP or Gravity.START
            PopupProps.Position.BottomRight -> Gravity.BOTTOM or Gravity.END
            PopupProps.Position.BottomLeft -> Gravity.BOTTOM or Gravity.START
            PopupProps.Position.Center -> Gravity.CENTER
        }
        params.setMargins(
            if (props.getPositionEnum() in listOf(PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft)) margin else 0,
            if (props.getPositionEnum() in listOf(PopupProps.Position.TopRight, PopupProps.Position.TopLeft)) margin else 0,
            if (props.getPositionEnum() in listOf(PopupProps.Position.TopRight, PopupProps.Position.BottomRight)) margin else 0,
            if (props.getPositionEnum() in listOf(PopupProps.Position.BottomRight, PopupProps.Position.BottomLeft)) margin else 0
        )
    }

    private fun removeCurrentPopupLocked() {
        val currentState = state
        val currentView = when (currentState) {
            is NotificationState.Displaying -> currentState.view
            is NotificationState.DisplayingAndPreparing -> currentState.displayView
            is NotificationState.DisplayingAndReady -> currentState.displayView
            else -> null
        } ?: return

        handler.removeCallbacksAndMessages(durationToken)

        currentView.animateOut {
            synchronized(this) {
                // Guard: If state changed while animating (e.g. by overwrite), don't clean up the new state
                if (state !== currentState) {
                    Log.d(TAG, "Ignoring animateOut completion: state already transitioned")
                    return@synchronized
                }
                overlay?.removeView(currentView)
                currentView.cleanup()
                checkNextAfterRemovalLocked()
            }
        }
    }

    private fun checkNextAfterRemovalLocked() {
        val nextState = when (val s = state) {
            is NotificationState.Displaying -> NotificationState.Idle
            is NotificationState.DisplayingAndPreparing -> NotificationState.Preparing(s.prepProps, s.prepView)
            is NotificationState.DisplayingAndReady -> {
                showPopup(s.readyView, s.readyProps)
                NotificationState.Displaying(s.readyProps, s.readyView)
            }
            else -> s
        }
        state = nextState

        if (state is NotificationState.Idle || state is NotificationState.Displaying) {
            processNextLocked()
            if (state is NotificationState.Idle && queue.isEmpty()) removeOverlay()
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
