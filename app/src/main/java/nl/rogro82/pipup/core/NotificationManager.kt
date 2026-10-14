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
import android.widget.LinearLayout
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.PiPupApp
import androidx.core.view.isEmpty
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.getLocalizedContext
import nl.rogro82.pipup.showToast
import nl.rogro82.pipup.ui.PopupView
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Manages the lifecycle and queueing of notifications using a unified FIFO queue
 * and native LinearLayout position containers up to [nl.rogro82.pipup.AppSettings.maxPopups] concurrency.
 */
@UnstableApi
class NotificationManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "NotificationManager"
        private const val PREP_TIMEOUT_MS = 45000L
        private const val STACK_GAP_DP = 12
    }

    private class ShownSlot(
        val key: String,
        var container: LinearLayout,
        var view: PopupView,
        var props: PopupProps,
        var expire: Runnable? = null
    )

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<PopupProps>()
    private val mShown = LinkedHashMap<String, ShownSlot>()
    private val positionContainers = EnumMap<PopupProps.Position, LinearLayout>(PopupProps.Position::class.java)
    private var overlay: FrameLayout? = null

    val watchdogCleanups = java.util.concurrent.atomic.AtomicLong(0)
    val popupsShown = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile var lastPopup: PopupProps? = null
        private set
    @Volatile var lastPopupAt: Long = 0L
        private set

    /**
     * Checks if a notification is currently being displayed on the screen or preparing.
     */
    fun isDisplaying(): Boolean = synchronized(this) {
        mShown.isNotEmpty() || queue.isNotEmpty()
    }

    /**
     * Returns the properties of the currently displayed notification, or null if none.
     */
    fun getCurrentProps(): PopupProps? = synchronized(this) {
        mShown.values.lastOrNull()?.props
    }

    /**
     * Asynchronously enqueues a notification for display into the unified queue.
     */
    fun enqueue(props: PopupProps) {
        handler.post { handleEnqueue(props) }
    }

    private fun handleEnqueue(props: PopupProps) {
        synchronized(this) {
            popupsShown.incrementAndGet()
            lastPopup = props
            lastPopupAt = SystemClock.elapsedRealtime()

            val id = props.id

            // 1. If active on-screen unique ID popup exists
            if (!id.isNullOrEmpty() && mShown.containsKey(id)) {
                val current = mShown[id]!!
                if (canUpdateInPlace(current.props, props)) {
                    handleInPlaceUpdate(id, props)
                    return
                } else {
                    // Position changed: remove old instance immediately and fall through to create new one
                    removePopupByKey(id)
                }
            }

            // 2. If unique ID popup is waiting in the queue, update its payload in the queue
            if (!id.isNullOrEmpty()) {
                var updatedInQueue = false
                val updatedQueue = ArrayDeque<PopupProps>()
                while (queue.isNotEmpty()) {
                    val queued = queue.poll() ?: break
                    if (queued.id == id) {
                        updatedQueue.addLast(props)
                        updatedInQueue = true
                    } else {
                        updatedQueue.addLast(queued)
                    }
                }
                queue.addAll(updatedQueue)
                if (updatedInQueue) return
            }

            // 3. Handle capacity and overwrite
            val max = PiPupApp.settings.maxPopups
            if (mShown.size >= max) {
                if (props.overwrite) {
                    // Priority eviction: Evict oldest ID-less popup first before evicting a unique ID popup
                    val evictKey = mShown.entries.firstOrNull { it.value.props.id.isNullOrEmpty() }?.key
                        ?: mShown.keys.firstOrNull()
                    if (evictKey != null) {
                        removePopupByKey(evictKey)
                    }
                } else {
                    queue.addLast(props)
                    return
                }
            }

            createPopup(props)
        }
    }

    private fun handleInPlaceUpdate(id: String, props: PopupProps) {
        val current = mShown[id] ?: return
        val oldPos = current.props.getPositionEnum()
        val newPos = props.getPositionEnum()
        val positionChanged = oldPos != newPos

        current.expire?.let { handler.removeCallbacks(it) }
        handler.removeCallbacksAndMessages(current)

        if (positionChanged) {
            val oldContainer = current.container
            oldContainer.removeView(current.view)
            val newContainer = ensurePositionContainer(newPos)

            val gapPx = context.dpToPx(STACK_GAP_DP)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = gapPx
                gravity = when (newPos) {
                    PopupProps.Position.TopRight, PopupProps.Position.BottomRight -> Gravity.END
                    PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft -> Gravity.START
                    PopupProps.Position.Center -> Gravity.CENTER_HORIZONTAL
                }
            }

            newContainer.addView(current.view, lp)
            current.container = newContainer

            if (oldContainer.isEmpty()) {
                overlay?.removeView(oldContainer)
                positionContainers.remove(oldPos)
            }
        }

        current.props = props
        current.view.updateFromProps(props)

        if (positionChanged) {
            current.view.post {
                current.view.forceAnimateIn()
            }
        }

        current.view.startMedia()
        scheduleRemoval(current)
    }

    private fun applyPositionToLayoutParams(params: FrameLayout.LayoutParams, position: PopupProps.Position) {
        val margin = context.dpToPx(20)
        params.gravity = when (position) {
            PopupProps.Position.TopRight -> Gravity.TOP or Gravity.END
            PopupProps.Position.TopLeft -> Gravity.TOP or Gravity.START
            PopupProps.Position.BottomRight -> Gravity.BOTTOM or Gravity.END
            PopupProps.Position.BottomLeft -> Gravity.BOTTOM or Gravity.START
            PopupProps.Position.Center -> Gravity.CENTER
        }
        params.setMargins(
            if (position in listOf(PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft)) margin else 0,
            if (position in listOf(PopupProps.Position.TopRight, PopupProps.Position.TopLeft)) margin else 0,
            if (position in listOf(PopupProps.Position.TopRight, PopupProps.Position.BottomRight)) margin else 0,
            if (position in listOf(PopupProps.Position.BottomRight, PopupProps.Position.BottomLeft)) margin else 0
        )
    }

    private class PositionContainer(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val safeMaxHeight = resources.displayMetrics.heightPixels * 2
            val safeHeightSpec = MeasureSpec.makeMeasureSpec(safeMaxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, safeHeightSpec)
        }
    }

    private fun ensurePositionContainer(position: PopupProps.Position): LinearLayout {
        val overlayView = ensureOverlay()!!
        return positionContainers.getOrPut(position) {
            PositionContainer(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                gravity = when (position) {
                    PopupProps.Position.TopRight, PopupProps.Position.BottomRight -> Gravity.END
                    PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft -> Gravity.START
                    PopupProps.Position.Center -> Gravity.CENTER_HORIZONTAL
                }
                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    applyPositionToLayoutParams(this, position)
                }
                overlayView.addView(this, params)
            }
        }
    }

    private fun createPopup(props: PopupProps) {
        val slotKey = props.id?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val localizedContext = context.getLocalizedContext(PiPupApp.settings.language)
        val view = PopupView(localizedContext, props)
        val container = ensurePositionContainer(props.getPositionEnum())

        if (!props.id.isNullOrEmpty()) {
            mShown[props.id]?.let { old ->
                old.expire?.let { handler.removeCallbacks(it) }
                container.removeView(old.view)
                old.view.cleanup()
                mShown.remove(props.id)
            }
        }

        val shown = ShownSlot(key = slotKey, container = container, view = view, props = props)
        mShown[slotKey] = shown

        val popupToken = Any()
        handler.postAtTime({
            synchronized(this) {
                if (mShown[slotKey] === shown) {
                    Log.w(TAG, "Popup preparation timed out for key: $slotKey")
                    watchdogCleanups.incrementAndGet()
                    removePopupByKey(slotKey)
                }
            }
        }, popupToken, SystemClock.uptimeMillis() + PREP_TIMEOUT_MS)

        view.readyListener = object : PopupView.ReadyListener {
            override fun onReady() {
                handler.removeCallbacksAndMessages(popupToken)
                synchronized(this@NotificationManager) {
                    if (mShown[slotKey] === shown) {
                        notifyStateChanged(true)

                        val gapPx = context.dpToPx(STACK_GAP_DP)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = gapPx
                            gravity = when (props.getPositionEnum()) {
                                PopupProps.Position.TopRight, PopupProps.Position.BottomRight -> Gravity.END
                                PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft -> Gravity.START
                                PopupProps.Position.Center -> Gravity.CENTER_HORIZONTAL
                            }
                        }
                        container.addView(view, lp)

                        view.animateIn()
                        view.startMedia()
                        scheduleRemoval(shown)
                    }
                }
            }
        }
        view.create()
    }

    private fun scheduleRemoval(shown: ShownSlot) {
        handler.removeCallbacksAndMessages(shown)
        val runnable = Runnable {
            synchronized(this) {
                if (mShown[shown.key] === shown) {
                    removePopupByKey(shown.key)
                }
            }
        }
        shown.expire = runnable
        handler.postAtTime(runnable, shown, SystemClock.uptimeMillis() + (shown.props.duration * 1000L))
    }

    private fun removePopupByKey(key: String) {
        val shown = mShown.remove(key) ?: return
        handler.removeCallbacksAndMessages(shown)

        shown.view.animateOut {
            synchronized(this) {
                shown.container.removeView(shown.view)
                shown.view.cleanup()
                val pos = shown.props.getPositionEnum()

                if (shown.container.isEmpty()) {
                    overlay?.removeView(shown.container)
                    positionContainers.remove(pos)
                }

                if (mShown.isEmpty()) {
                    notifyStateChanged(false)
                    removeOverlay()
                    lastPopup = null
                }

                processNextLocked()
            }
        }
    }

    private fun processNextLocked() {
        synchronized(this) {
            val max = PiPupApp.settings.maxPopups
            while (mShown.size < max && queue.isNotEmpty()) {
                val props = queue.poll() ?: break
                createPopup(props)
            }
        }
    }

    private fun canUpdateInPlace(oldProps: PopupProps, newProps: PopupProps): Boolean {
        return oldProps.id == newProps.id && oldProps.getPositionEnum() == newProps.getPositionEnum()
    }

    /**
     * Cancels all notifications and clears the queue.
     * Blocks until UI thread cleanup is done if called from a background thread.
     */
    fun cancelAll() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            performCancelAll()
        } else {
            val latch = CountDownLatch(1)
            handler.post {
                performCancelAll()
                latch.countDown()
            }
            try {
                latch.await(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {}
        }
    }

    private fun notifyStateChanged(isDisplaying: Boolean) {
        (context.applicationContext as? PiPupApp)?.moduleManager?.notifyNotificationDisplayStateChanged(isDisplaying)
    }

    private fun performCancelAll() {
        synchronized(this) {
            queue.clear()
            handler.removeCallbacksAndMessages(null)
            for ((_, s) in mShown) {
                s.expire?.let { handler.removeCallbacks(it) }
                s.container.removeView(s.view)
                s.view.cleanup()
            }
            mShown.clear()
            for ((_, container) in positionContainers) {
                overlay?.removeView(container)
            }
            positionContainers.clear()

            notifyStateChanged(false)
            removeOverlay()
            lastPopup = null
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
            Log.e(TAG, "WindowManager error: ${e.message}")
            context.showToast(context.getLocalizedContext(PiPupApp.settings.language).getString(R.string.error_permission_denied_overlay))
            null
        }
    }

    private fun removeOverlay() {
        overlay?.let { if (it.parent != null) windowManager.removeView(it) }
        overlay = null
        positionContainers.clear()
    }
}
