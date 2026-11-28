package nl.rogro82.pipup.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.media3.common.util.UnstableApi
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.UpdateManager

/**
 * Manages the lifecycle and queueing of notifications using a unified FIFO queue
 * and native LinearLayout position containers up to [AppSettings.maxPopups] concurrency.
 */
@UnstableApi
class NotificationManager(
    private val context: Context,
    windowManager: WindowManager,
) {
    /** Queueing constants for [NotificationManager]. */
    companion object {
        private const val TAG = "NotificationManager"
        private const val MAX_PARALLEL_PREPARING = 3
        private const val MAIN_SYNC_TIMEOUT_SECONDS = 2L
        private const val OVERLAY_IDLE_GRACE_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val idleOverlayRemovalRunnable = Runnable {
        synchronized(this) {
            if (!isDisplaying()) {
                overlayHost.removeOverlay()
            }
        }
    }
    private val queue = ArrayDeque<PopupProps>()
    internal val preparingSlots = LinkedHashMap<String, ShownSlot>()
    internal val readyWaiting = LinkedHashMap<String, ShownSlot>()
    internal val shownSlots = LinkedHashMap<String, ShownSlot>()
    internal val exitingSlots = LinkedHashMap<String, ShownSlot>()
    internal val internalUpdateKeys = mutableSetOf<String>()
    private var isProcessingQueue = false
    private var reprocessQueueRequested = false

    private val overlayHost = PopupOverlayHost(
        context = context,
        windowManager = windowManager,
        mainHandler = handler,
        callbacks = object : PopupOverlayHost.Callbacks {
            override fun hasActiveFullscreen(): Boolean =
                shownSlots.values.any { it.props.coversScreen } || exitingSlots.values.any { it.props.coversScreen }

            override fun hasActiveFocusablePopup(): Boolean =
                shownSlots.values.any { it.props.requiresInputFocus }

            override fun hasExitingFocusablePopup(): Boolean =
                exitingSlots.values.any { it.props.requiresInputFocus }

            override fun hasActiveBackDismissiblePopup(): Boolean =
                shownSlots.values.any { it.props.dismissibleByBack }

            override fun onBackInvoked() {
                handleBackDismissal()
            }

            override fun dispatchKeyToActiveCloseButton(event: KeyEvent): Boolean {
                val closeSlot = synchronized(this@NotificationManager) {
                    shownSlots.values.filter { it.props.hasCloseButton }.maxByOrNull { it.shownAt }
                }
                return closeSlot?.view?.dispatchKeyEvent(event) == true
            }
        },
    )

    private val simulator = PopupLayoutSimulator(context)
    private val presenter = PopupSlotPresenter(this, context, handler, overlayHost, simulator)

    val watchdogCleanups = AtomicLong(0)
    val popupsShown = AtomicLong(0)
    @Volatile var lastPopup: PopupProps? = null
        private set
    @Volatile var lastPopupAt: Long = 0L
        private set
    @Volatile var lastFirstFrameMs: Long? = null
        private set
    @Volatile var lastMediaError: String? = null
        private set

    var onPopupShown: ((PopupProps) -> Unit)? = null
    /** Callback invoked when an active popup is replaced in-place with updated props. */
    var onPopupReplaced: ((PopupProps, String?) -> Unit)? = null
    var onPopupRemoved: ((String, String) -> Unit)? = null
    /** Callback invoked when the overall notification display/queue active state changes. */
    var onStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Outcome of a [cancelById] operation.
     */
    enum class CancelResult {
        CANCELLED_ACTIVE,
        REMOVED_FROM_QUEUE,
        NOT_FOUND,
    }

    /**
     * Checks if a notification is currently being displayed on the screen or preparing.
     */
    fun isDisplaying(): Boolean = synchronized(this) {
        shownSlots.isNotEmpty() ||
            exitingSlots.isNotEmpty() ||
            preparingSlots.isNotEmpty() ||
            readyWaiting.isNotEmpty() ||
            queue.isNotEmpty()
    }

    /**
     * Checks if a given ID or key belongs to an internal update popup.
     */
    fun isInternalUpdate(idOrKey: String): Boolean = synchronized(this) {
        internalUpdateKeys.contains(idOrKey) ||
            shownSlots[idOrKey]?.props?.isInternalUpdateAction == true ||
            shownSlots.values.any { it.props.id == idOrKey && it.props.isInternalUpdateAction } ||
            preparingSlots[idOrKey]?.props?.isInternalUpdateAction == true ||
            readyWaiting[idOrKey]?.props?.isInternalUpdateAction == true
    }

    /**
     * Returns active popup details (properties and individual shownAt timestamp).
     */
    fun getActiveSlotDetails(): List<Pair<PopupProps, Long>> = synchronized(this) {
        shownSlots.values.map { Pair(it.props, it.shownAt) }
    }

    /**
     * Enqueues a popup notification, marking it as an internal system action when [isInternal] is true.
     * Executes synchronously on the main thread or blocks up to 2 seconds when called from a background thread.
     */
    fun enqueueInternalPopup(props: PopupProps, isInternal: Boolean = true) {
        val internalProps = if (isInternal) props.copy(isInternalUpdateAction = true) else props
        runOnMainSync(Unit) {
            handleEnqueue(internalProps, isInternal = isInternal)
        }
    }

    /**
     * Asynchronously enqueues a notification for display into the unified queue.
     */
    fun enqueue(props: PopupProps) {
        enqueueInternalPopup(props, isInternal = props.isInternalUpdateAction)
    }

    /**
     * Cancels a specific notification by ID.
     * If active on screen, it is closed and removed.
     * If waiting in queue, it is removed from the queue.
     * Blocks until UI thread processing is done if called from a background thread.
     */
    fun cancelById(id: String): CancelResult = runOnMainSync(CancelResult.NOT_FOUND) {
        performCancelById(id)
    }

    /**
     * Handles button-triggered removal securely with reason "button" and single event dispatch.
     */
    fun handleButtonRemoval(idOrKey: String) {
        runOnMainSync(Unit) {
            performButtonRemoval(idOrKey)
        }
    }

    /**
     * Cancels only the currently active (most recently shown) popup, leaving any queued or
     * remaining stacked popups intact so the next item in the queue can advance.
     *
     * @return True if an active popup was found and cancelled.
     */
    fun cancelCurrent(): Boolean = runOnMainSync(false) {
        performCancelCurrent()
    }

    /**
     * Cancels all notifications and clears the queue.
     * Blocks until UI thread cleanup is done if called from a background thread.
     */
    fun cancelAll() {
        runOnMainSync(Unit) {
            performCancelAll()
        }
    }

    /**
     * Dismisses the focused (or most recently shown) focusable popup with reason `"back"`.
     * Button callbacks are never invoked.
     * @return `true` if a popup was dismissed.
     */
    fun handleBackDismissal(): Boolean = runOnMainSync(false) {
        performBackDismissal()
    }

    internal fun recordMediaEvent(firstFrameMs: Long? = null, error: String? = null) {
        if (firstFrameMs != null) lastFirstFrameMs = firstFrameMs
        if (error != null) lastMediaError = error
    }

    internal fun checkIdleAndCleanupOverlay() {
        if (shownSlots.isEmpty() && exitingSlots.isEmpty()) {
            notifyStateChanged(false)
            overlayHost.prepareIdleOverlay()
            handler.removeCallbacks(idleOverlayRemovalRunnable)
            if (preparingSlots.isEmpty() && readyWaiting.isEmpty() && queue.isEmpty()) {
                handler.postDelayed(idleOverlayRemovalRunnable, OVERLAY_IDLE_GRACE_MS)
            }
        }
    }

    internal fun notifyStateChanged(isDisplaying: Boolean) {
        if (isDisplaying) {
            handler.removeCallbacks(idleOverlayRemovalRunnable)
        }
        (context.applicationContext as? PiPupApp)?.moduleManager?.notifyNotificationDisplayStateChanged(isDisplaying)
        onStateChanged?.invoke(isDisplaying)
    }

    internal fun processNextLocked() {
        synchronized(this) {
            if (isProcessingQueue) {
                reprocessQueueRequested = true
                return
            }
            isProcessingQueue = true
            try {
                var keepProcessing = true
                while (keepProcessing) {
                    reprocessQueueRequested = false
                    val isStrictSequential = PiPupApp.settings.popupLoadingBehavior == 0
                    val readyMutated = processReadyWaitingPass(isStrictSequential)
                    val queueMutated = processSingleQueuePass(isStrictSequential)
                    keepProcessing = readyMutated || queueMutated || reprocessQueueRequested
                }
            } finally {
                isProcessingQueue = false
            }
        }
    }

    private fun <T> runOnMainSync(defaultValue: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        var result = defaultValue
        val latch = CountDownLatch(1)
        handler.post {
            try {
                result = block()
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(MAIN_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}
        return result
    }

    private fun handleEnqueue(props: PopupProps, isInternal: Boolean = false) {
        synchronized(this) {
            handler.removeCallbacks(idleOverlayRemovalRunnable)
            if (props.id == UpdateManager.CONFIRM_POPUP_ID && !isInternal && !props.isInternalUpdateAction) {
                Log.w(TAG, "Rejecting external attempt to use reserved update popup ID: ${props.id}")
                return
            }
            if (isInternal || props.isInternalUpdateAction) {
                props.id?.let { internalUpdateKeys.add(it) }
            }

            popupsShown.incrementAndGet()
            lastPopup = props
            lastPopupAt = SystemClock.elapsedRealtime()
            lastFirstFrameMs = null
            lastMediaError = null

            val id = props.id
            val handledExisting = !id.isNullOrEmpty() && handleExistingPopupById(id, props)
            if (!handledExisting) {
                if (PopupProps.PopupLayoutMetrics.isFundamentallyUnplaceable(context)) {
                    Log.w(TAG, "Discarding unplaceable popup: ${props.title ?: props.id}")
                    (props.media as? PopupProps.Media.LocalFile)?.let { PayloadParser.deleteFileAsync(it.path) }
                } else {
                    if (props.overwrite) queue.addFirst(props) else queue.addLast(props)
                    processNextLocked()
                }
            }
        }
    }

    private fun handleExistingPopupById(id: String, props: PopupProps): Boolean {
        exitingSlots.remove(id)?.let { exiting ->
            handler.removeCallbacksAndMessages(exiting)
            exiting.container?.removeView(exiting.view)
            exiting.view.cleanup()
        }

        shownSlots[id]?.let { current ->
            if (canUpdateInPlace(current.props, props)) {
                presenter.handleInPlaceUpdate(id, props)
                return true
            }
            presenter.removePopupInstantly(id, "replaced")
        }

        presenter.cleanupPendingSlot(id)
        return replaceQueuedPayloadById(id, props)
    }

    private fun replaceQueuedPayloadById(id: String, props: PopupProps): Boolean {
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
        if (updatedInQueue) processNextLocked()
        return updatedInQueue
    }

    private fun processReadyWaitingPass(isStrictSequential: Boolean): Boolean {
        if (readyWaiting.isEmpty()) return false
        val snapshot = readyWaiting.values.toList()
        var mutated = false
        var stopPass = false
        val iterator = snapshot.iterator()

        while (!stopPass && iterator.hasNext()) {
            val readySlot = iterator.next()
            if (presenter.tryShowReadySlot(readySlot)) {
                mutated = true
                stopPass = true
            } else if (isStrictSequential) {
                stopPass = true
            }
        }
        return mutated
    }

    private fun processSingleQueuePass(isStrictSequential: Boolean): Boolean {
        val totalPending = preparingSlots.size + readyWaiting.size
        val cannotPrepare = queue.isEmpty() ||
            (isStrictSequential && totalPending > 0) ||
            (!isStrictSequential && totalPending >= MAX_PARALLEL_PREPARING)
        if (cannotPrepare) return false

        var mutated = false
        var stopPass = false
        val iterator = queue.toList().iterator()
        while (!stopPass && iterator.hasNext()) {
            val (itemMutated, shouldStop) = attemptProcessQueuedItem(iterator.next(), isStrictSequential)
            mutated = mutated || itemMutated
            stopPass = shouldStop
        }
        return mutated
    }

    private fun attemptProcessQueuedItem(item: PopupProps, isStrictSequential: Boolean): Pair<Boolean, Boolean> {
        val decision = presenter.evaluateAdmission(item)
        val canOverwrite = item.overwrite && presenter.findAtomicEvictionPlan(item, decision)?.isNotEmpty() == true
        return when {
            decision.status == PopupLayoutSimulator.AdmissionStatus.UNPLACEABLE -> {
                queue.remove(item)
                (item.media as? PopupProps.Media.LocalFile)?.let { PayloadParser.deleteFileAsync(it.path) }
                Log.w(TAG, "Discarded unplaceable queued popup: ${item.title ?: item.id}")
                checkIdleAndCleanupOverlay()
                true to true
            }
            decision.status == PopupLayoutSimulator.AdmissionStatus.ALLOWED || canOverwrite -> {
                queue.remove(item)
                presenter.startPreparingPopup(item)
                true to true
            }
            else -> false to isStrictSequential
        }
    }

    private fun canUpdateInPlace(oldProps: PopupProps, newProps: PopupProps): Boolean =
        oldProps.id == newProps.id &&
            oldProps.coversScreen == newProps.coversScreen &&
            oldProps.getPositionEnum() == newProps.getPositionEnum() &&
            oldProps.effectiveStack == newProps.effectiveStack

    private fun performCancelCurrent(): Boolean = synchronized(this) {
        val target = shownSlots.values.maxByOrNull { it.shownAt } ?: return false
        presenter.removePopupByKey(target.key, "cancelled")
        true
    }

    private fun performCancelById(id: String): CancelResult = synchronized(this) {
        when {
            shownSlots.containsKey(id) || exitingSlots.containsKey(id) -> {
                presenter.removePopupByKey(id, "cancelled")
                removeWaitingOrQueuedById(id)
                CancelResult.CANCELLED_ACTIVE
            }
            removeWaitingOrQueuedById(id) -> {
                processNextLocked()
                checkIdleAndCleanupOverlay()
                CancelResult.REMOVED_FROM_QUEUE
            }
            else -> CancelResult.NOT_FOUND
        }
    }

    private fun removeWaitingOrQueuedById(id: String): Boolean {
        var removed = presenter.cleanupPendingSlot(id, notifyCancelled = true)
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val qItem = iterator.next()
            if (qItem.id == id) {
                iterator.remove()
                onPopupRemoved?.invoke("queue_cancelled", id)
                removed = true
            }
        }
        return removed
    }

    private fun resolveButtonRemovalKey(idOrKey: String): String? = when {
        idOrKey.isNotEmpty() && (shownSlots.containsKey(idOrKey) || exitingSlots.containsKey(idOrKey)) -> idOrKey
        idOrKey.isNotEmpty() -> shownSlots.entries.firstOrNull { it.value.props.id == idOrKey }?.key
            ?: exitingSlots.entries.firstOrNull { it.value.props.id == idOrKey }?.key
        shownSlots.size == 1 -> shownSlots.keys.first()
        exitingSlots.size == 1 -> exitingSlots.keys.first()
        else -> shownSlots.keys.lastOrNull()
    }

    private fun performButtonRemoval(idOrKey: String) {
        synchronized(this) {
            val key = resolveButtonRemovalKey(idOrKey) ?: return
            val shown = shownSlots.remove(key) ?: exitingSlots.remove(key) ?: return
            handler.removeCallbacksAndMessages(shown)
            presenter.triggerRemovalEvent(shown, "button")
            shown.container?.removeView(shown.view)
            shown.view.cleanup()
            overlayHost.updateOverlayFocusability()
            processNextLocked()
            checkIdleAndCleanupOverlay()
        }
    }

    private fun performCancelAll() {
        synchronized(this) {
            val qIter = queue.iterator()
            while (qIter.hasNext()) {
                val qItem = qIter.next()
                onPopupRemoved?.invoke("queue_cancelled", qItem.id ?: "queued")
            }
            queue.clear()
            handler.removeCallbacksAndMessages(null)
            for ((_, s) in preparingSlots) {
                onPopupRemoved?.invoke("queue_cancelled", s.props.id ?: s.key)
                s.view.cleanup()
            }
            preparingSlots.clear()
            for ((_, s) in readyWaiting) {
                onPopupRemoved?.invoke("queue_cancelled", s.props.id ?: s.key)
                s.view.cleanup()
            }
            readyWaiting.clear()
            for ((_, s) in shownSlots) {
                s.expire?.let { handler.removeCallbacks(it) }
                presenter.triggerRemovalEvent(s, "cancelled")
                s.container?.removeView(s.view)
                s.view.cleanup()
            }
            shownSlots.clear()
            for ((_, s) in exitingSlots) {
                s.expire?.let { handler.removeCallbacks(it) }
                presenter.triggerRemovalEvent(s, "cancelled")
                s.container?.removeView(s.view)
                s.view.cleanup()
            }
            exitingSlots.clear()
            overlayHost.clearContainers()

            notifyStateChanged(false)
            overlayHost.removeOverlay()
        }
    }

    private fun performBackDismissal(): Boolean {
        synchronized(this) {
            val focusedView = overlayHost.findFocus()
            val target = shownSlots.values.firstOrNull { slot ->
                slot.props.dismissibleByBack &&
                    focusedView != null &&
                    isDescendantOf(focusedView, slot.view)
            } ?: shownSlots.values
                .filter { it.props.dismissibleByBack }
                .maxByOrNull { it.shownAt }
                ?: return false

            presenter.removePopupByKey(target.key, "back")
            return true
        }
    }

    private fun isDescendantOf(child: View, ancestor: View): Boolean {
        var current: Any? = child
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }
}
