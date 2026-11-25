package nl.rogro82.pipup.core

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.widget.LinearLayout
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.getLocalizedContext
import nl.rogro82.pipup.service.PipUpService
import nl.rogro82.pipup.ui.PopupView
import java.util.UUID

/**
 * Represents a single prepared, active, or exiting popup instance tracked by [NotificationManager].
 */
@UnstableApi
internal class ShownSlot(
    val key: String,
    var container: LinearLayout? = null,
    var view: PopupView,
    var props: PopupProps,
    val prepToken: Any = Any(),
) {
    var expire: Runnable? = null
    var shownAt: Long = SystemClock.elapsedRealtime()
    var removedEventFired: Boolean = false
}

/**
 * Handles popup view preparation, ready-slot admission/display, in-place updates,
 * resize conflict resolution, expiration timers, and animated or instant removal.
 */
@UnstableApi
internal class PopupSlotPresenter(
    private val host: NotificationManager,
    private val context: Context,
    private val handler: Handler,
    private val overlayHost: PopupOverlayHost,
    private val simulator: PopupLayoutSimulator,
) {

    fun cleanupPendingSlot(key: String, notifyCancelled: Boolean = false): Boolean {
        var removed = false
        host.preparingSlots.remove(key)?.let { prep ->
            handler.removeCallbacksAndMessages(prep.prepToken)
            prep.view.cleanup()
            if (notifyCancelled) host.onPopupRemoved?.invoke("queue_cancelled", key)
            removed = true
        }
        host.readyWaiting.remove(key)?.let { ready ->
            handler.removeCallbacksAndMessages(ready.prepToken)
            ready.view.cleanup()
            if (notifyCancelled) host.onPopupRemoved?.invoke("queue_cancelled", key)
            removed = true
        }
        return removed
    }

    fun evaluateAdmission(
        props: PopupProps,
        activeSlotsOverride: Collection<ShownSlot>? = null,
        candidateMeasuredDimensions: Pair<Int, Int>? = null,
        replaceSlotKey: String? = null,
    ): PopupLayoutSimulator.AdmissionDecision {
        val request = buildAdmissionRequest(props, activeSlotsOverride, candidateMeasuredDimensions, replaceSlotKey)
        return simulator.evaluateAdmission(request)
    }

    fun findAtomicEvictionPlan(
        props: PopupProps,
        decision: PopupLayoutSimulator.AdmissionDecision,
        activeSlotsOverride: Collection<ShownSlot>? = null,
        candidateMeasuredDimensions: Pair<Int, Int>? = null,
        replaceSlotKey: String? = null,
    ): List<String>? {
        val request = buildAdmissionRequest(props, activeSlotsOverride, candidateMeasuredDimensions, replaceSlotKey)
        return simulator.findAtomicEvictionPlan(request, decision, host.internalUpdateKeys)
    }

    fun handleInPlaceUpdate(id: String, props: PopupProps) {
        val current = host.shownSlots[id] ?: return
        val oldProps = current.props
        val oldPos = oldProps.getPositionEnum()
        val newPos = props.getPositionEnum()
        val positionChanged = oldPos != newPos || oldProps.coversScreen != props.coversScreen

        current.expire?.let { handler.removeCallbacks(it) }
        current.expire = null
        handler.removeCallbacksAndMessages(current)

        if (positionChanged && !reattachSlotToNewPosition(id, current, props, newPos)) {
            return
        }

        current.props = props
        current.shownAt = SystemClock.elapsedRealtime()
        current.view.updateFromProps(props)
        overlayHost.measurePopupView(current.view)
        if (positionChanged) {
            overlayHost.adjustDualAxisContainerMargins(oldPos)
        }
        overlayHost.adjustDualAxisContainerMargins(newPos)
        resolveVisibleConflictsAfterResize(current)
        overlayHost.updateOverlayFocusability()

        if (positionChanged) {
            current.view.post { current.view.forceAnimateIn() }
        }

        current.view.startMedia()
        scheduleRemoval(current)
        host.onPopupReplaced?.invoke(props, oldProps.id)
    }

    fun startPreparingPopup(props: PopupProps) {
        val slotKey = props.id?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        if (props.isInternalUpdateAction) {
            host.internalUpdateKeys.add(slotKey)
            props.id?.let { host.internalUpdateKeys.add(it) }
        }

        val localizedContext = context.getLocalizedContext(PiPupApp.settings.language)
        val view = PopupView(localizedContext, props).apply {
            onButton = { btn ->
                PipUpService.getInstance()?.handleButtonClick(this.props, btn, slotKey)
            }
            onClose = {
                removePopupByKey(slotKey, "close")
            }
            onFirstFrame = { ms ->
                host.recordMediaEvent(firstFrameMs = ms)
            }
            onMediaError = { err ->
                host.recordMediaEvent(error = err)
            }
            onLayoutAdjusted = {
                synchronized(host) {
                    val currentSlot = host.shownSlots[slotKey]
                    if (currentSlot != null) {
                        overlayHost.measurePopupView(currentSlot.view)
                        overlayHost.adjustDualAxisContainerMargins(props.getPositionEnum())
                        resolveVisibleConflictsAfterResize(currentSlot)
                    }
                }
            }
        }

        val shown = ShownSlot(key = slotKey, view = view, props = props)
        host.preparingSlots[slotKey] = shown

        handler.postAtTime({
            synchronized(host) {
                if (host.preparingSlots[slotKey] === shown) {
                    Log.w(TAG, "Popup preparation timed out for key: $slotKey")
                    host.preparingSlots.remove(slotKey)
                    host.watchdogCleanups.incrementAndGet()
                    shown.view.cleanup()
                    host.processNextLocked()
                    host.checkIdleAndCleanupOverlay()
                }
            }
        }, shown.prepToken, SystemClock.uptimeMillis() + PREP_TIMEOUT_MS)

        view.readyListener = object : PopupView.ReadyListener {
            override fun onReady() {
                handler.removeCallbacksAndMessages(shown.prepToken)
                synchronized(host) {
                    if (host.preparingSlots.remove(slotKey) === shown) {
                        tryShowReadySlot(shown)
                        host.processNextLocked()
                        host.checkIdleAndCleanupOverlay()
                    }
                }
            }
        }
        view.create()
        if (host.preparingSlots.containsKey(slotKey)) {
            overlayHost.measurePopupView(view)
        }
    }

    fun tryShowReadySlot(shown: ShownSlot): Boolean {
        val props = shown.props
        val view = shown.view
        overlayHost.measurePopupView(view)
        val dims = view.measuredWidth.coerceAtLeast(1) to view.measuredHeight.coerceAtLeast(1)
        props.measuredSize = dims

        val decision = evaluateAdmission(props = props, candidateMeasuredDimensions = dims)
        var handled = true
        when (decision.status) {
            PopupLayoutSimulator.AdmissionStatus.UNPLACEABLE -> {
                Log.w(TAG, "Discarding unplaceable ready popup: ${props.title ?: props.id}")
                host.readyWaiting.remove(shown.key)
                discardSlotResources(shown)
            }
            PopupLayoutSimulator.AdmissionStatus.ALLOWED -> {
                host.readyWaiting.remove(shown.key)
                displayReadySlot(shown)
            }
            else -> {
                val evictionPlan = if (props.overwrite) {
                    findAtomicEvictionPlan(
                        props = props,
                        decision = decision,
                        candidateMeasuredDimensions = dims,
                    )
                } else {
                    null
                }
                if (!evictionPlan.isNullOrEmpty()) {
                    for (evictKey in evictionPlan) {
                        removePopupInstantly(evictKey, "evicted")
                    }
                    host.readyWaiting.remove(shown.key)
                    displayReadySlot(shown)
                } else {
                    if (!host.readyWaiting.containsKey(shown.key)) {
                        Log.d(
                            TAG,
                            "[LIFECYCLE] '${props.title ?: props.id}' (key=${shown.key}) " +
                                "ready and waiting for screen space (status=${decision.status})",
                        )
                        host.readyWaiting[shown.key] = shown
                    }
                    handled = false
                }
            }
        }
        return handled
    }

    fun discardSlotResources(shown: ShownSlot) {
        handler.removeCallbacksAndMessages(shown)
        shown.expire = null
        shown.container?.removeView(shown.view)
        shown.container = null
        shown.view.cleanup()
        (shown.props.media as? PopupProps.Media.LocalFile)?.let { lf ->
            PayloadParser.deleteFileAsync(lf.path)
        }
        host.internalUpdateKeys.remove(shown.key)
        shown.props.id?.let { host.internalUpdateKeys.remove(it) }
        host.checkIdleAndCleanupOverlay()
    }

    fun triggerRemovalEvent(shown: ShownSlot, reason: String) {
        if (!shown.removedEventFired) {
            shown.removedEventFired = true
            host.onPopupRemoved?.invoke(reason, shown.props.id ?: shown.key)
        }
    }

    fun removePopupInstantly(key: String, reason: String = "cancelled") {
        cleanupPendingSlot(key)
        val shown = host.shownSlots.remove(key) ?: host.exitingSlots.remove(key) ?: return
        Log.d(
            TAG,
            "[LIFECYCLE] '${shown.props.title ?: shown.key}' (key=$key) " +
                "REMOVED_INSTANTLY at ${System.currentTimeMillis()}ms (reason=$reason)",
        )
        handler.removeCallbacksAndMessages(shown)
        handler.removeCallbacksAndMessages(shown.prepToken)
        triggerRemovalEvent(shown, reason)
        shown.container?.removeView(shown.view)
        shown.view.cleanup()
        overlayHost.adjustDualAxisContainerMargins(shown.props.getPositionEnum())
        overlayHost.updateOverlayFocusability()
    }

    fun removePopupByKey(key: String, reason: String = "cancelled") {
        cleanupPendingSlot(key)
        val shown = host.shownSlots.remove(key) ?: host.exitingSlots[key] ?: return
        if (host.exitingSlots.containsKey(key)) return

        Log.d(
            TAG,
            "[LIFECYCLE] '${shown.props.title ?: shown.key}' (key=$key) " +
                "ANIMATE_OUT_START at ${System.currentTimeMillis()}ms (reason=$reason)",
        )
        host.exitingSlots[key] = shown
        handler.removeCallbacksAndMessages(shown)
        handler.removeCallbacksAndMessages(shown.prepToken)
        triggerRemovalEvent(shown, reason)
        overlayHost.adjustDualAxisContainerMargins(shown.props.getPositionEnum())
        overlayHost.updateOverlayFocusability()

        var finalized = false
        val finalizeRemoval = {
            synchronized(host) {
                if (!finalized) {
                    finalized = true
                    handler.removeCallbacksAndMessages(shown)
                    if (host.exitingSlots.remove(key) != null) {
                        Log.d(
                            TAG,
                            "[LIFECYCLE] '${shown.props.title ?: shown.key}' (key=$key) " +
                                "REMOVED_FROM_VIEW_TREE at ${System.currentTimeMillis()}ms",
                        )
                        shown.container?.removeView(shown.view)
                        shown.view.cleanup()
                        overlayHost.adjustDualAxisContainerMargins(shown.props.getPositionEnum())
                        overlayHost.updateOverlayFocusability()
                    }
                    host.processNextLocked()
                    host.checkIdleAndCleanupOverlay()
                }
            }
        }

        val exitDurationMs = shown.props.animationDuration.toLong().coerceAtLeast(EXIT_FALLBACK_DURATION_MS)
        handler.postAtTime(
            finalizeRemoval,
            shown,
            SystemClock.uptimeMillis() + exitDurationMs + EXIT_WATCHDOG_PADDING_MS,
        )
        shown.view.animateOut(finalizeRemoval)
    }

    private fun reattachSlotToNewPosition(
        id: String,
        current: ShownSlot,
        props: PopupProps,
        newPos: PopupProps.Position,
    ): Boolean {
        val oldContainer = current.container
        oldContainer?.removeView(current.view)
        val newContainer = overlayHost.ensurePositionContainer(newPos, props.effectiveStack, props.coversScreen)
        if (newContainer == null) {
            Log.w(TAG, "Overlay unavailable during in-place update; removing popup '$id'")
            host.shownSlots.remove(id)
            overlayHost.removeContainerIfEmpty(oldContainer)
            (props.media as? PopupProps.Media.LocalFile)?.let { lf ->
                PayloadParser.deleteFileAsync(lf.path)
            }
            discardSlotResources(current)
            triggerRemovalEvent(current, "error")
            return false
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            overlayHost.applyStackingMargins(this, props)
        }
        newContainer.addView(current.view, lp)
        current.container = newContainer
        overlayHost.removeContainerIfEmpty(oldContainer)
        return true
    }

    private fun displayReadySlot(shown: ShownSlot) {
        val slotKey = shown.key
        val props = shown.props
        val view = shown.view
        val container = overlayHost.ensurePositionContainer(
            props.getPositionEnum(),
            props.effectiveStack,
            props.coversScreen,
        )
        if (container == null) {
            Log.w(TAG, "Overlay unavailable; discarding popup '${props.title ?: slotKey}' (key=$slotKey)")
            discardSlotResources(shown)
            return
        }
        shown.container = container

        if (!props.id.isNullOrEmpty()) {
            host.shownSlots.remove(props.id)?.let { old ->
                old.expire?.let { handler.removeCallbacks(it) }
                old.container?.removeView(old.view)
                old.view.cleanup()
            }
        }

        shown.shownAt = SystemClock.elapsedRealtime()
        host.shownSlots[slotKey] = shown
        host.notifyStateChanged(true)

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            overlayHost.applyStackingMargins(this, props)
        }
        if (shouldPrependToContainer(props)) {
            container.addView(view, 0, lp)
        } else {
            container.addView(view, lp)
        }

        overlayHost.adjustDualAxisContainerMargins(props.getPositionEnum())
        overlayHost.updateOverlayFocusability()

        Log.d(
            TAG,
            "[LIFECYCLE] '${props.title ?: props.id}' (key=$slotKey) " +
                "ANIMATE_IN_START at ${System.currentTimeMillis()}ms (pos=${props.getPositionEnum().name})",
        )
        view.animateIn()
        view.startMedia()
        scheduleRemoval(shown)
        host.onPopupShown?.invoke(props)
    }

    private fun shouldPrependToContainer(props: PopupProps): Boolean {
        val pos = props.getPositionEnum()
        return if (props.getStackDirectionEnum() == PopupProps.StackDirection.HORIZONTAL) {
            pos == PopupProps.Position.TopRight ||
                pos == PopupProps.Position.BottomRight ||
                pos == PopupProps.Position.CenterRight
        } else {
            pos == PopupProps.Position.BottomRight ||
                pos == PopupProps.Position.BottomLeft ||
                pos == PopupProps.Position.BottomCenter
        }
    }

    private fun scheduleRemoval(shown: ShownSlot) {
        handler.removeCallbacksAndMessages(shown)
        if (shown.props.duration <= 0) return

        val runnable = Runnable {
            synchronized(host) {
                if (host.shownSlots[shown.key] === shown) {
                    removePopupByKey(shown.key, "expired")
                }
            }
        }
        shown.expire = runnable
        handler.postAtTime(runnable, shown, SystemClock.uptimeMillis() + (shown.props.duration * MS_PER_SECOND))
    }

    private fun resolveVisibleConflictsAfterResize(slot: ShownSlot) {
        if (host.preparingSlots.containsKey(slot.key) || host.readyWaiting.containsKey(slot.key)) return
        val visiblePool = (host.shownSlots.values + host.exitingSlots.values).toList()
        if (visiblePool.size <= 1) return

        val dims = getSlotDimensions(slot)
        val decision = evaluateAdmission(
            props = slot.props,
            activeSlotsOverride = visiblePool,
            candidateMeasuredDimensions = dims,
            replaceSlotKey = slot.key,
        )
        if (decision.status != PopupLayoutSimulator.AdmissionStatus.ALLOWED) {
            val evictionPlan = if (slot.props.overwrite) {
                findAtomicEvictionPlan(
                    props = slot.props,
                    decision = decision,
                    activeSlotsOverride = visiblePool,
                    candidateMeasuredDimensions = dims,
                    replaceSlotKey = slot.key,
                )
            } else {
                null
            }
            if (evictionPlan != null) {
                for (evictKey in evictionPlan) {
                    removePopupInstantly(evictKey, "evicted")
                }
                host.processNextLocked()
            } else if (slot.key !in host.internalUpdateKeys) {
                Log.d(
                    TAG,
                    "[LIFECYCLE] '${slot.props.title ?: slot.key}' (key=${slot.key}) " +
                        "removed after resize due to geometry conflict",
                )
                removePopupInstantly(slot.key, "evicted")
                host.checkIdleAndCleanupOverlay()
                host.processNextLocked()
            }
        }
    }

    private fun getSlotDimensions(slot: ShownSlot): Pair<Int, Int> {
        if (slot.view.measuredWidth <= 0 || slot.view.measuredHeight <= 0) {
            overlayHost.measurePopupView(slot.view)
        }
        val metrics = slot.props.calculateMetrics(context)
        return if (!host.preparingSlots.containsKey(slot.key) &&
            slot.view.measuredWidth > 0 &&
            slot.view.measuredHeight > 0
        ) {
            val size = slot.view.measuredWidth to slot.view.measuredHeight
            slot.props.measuredSize = size
            size
        } else {
            val cached = slot.props.measuredSize
            val estW = maxOf(slot.view.measuredWidth, metrics.width, cached?.first ?: 0)
            val estH = maxOf(slot.view.measuredHeight, metrics.height, cached?.second ?: 0)
            estW to estH
        }
    }

    private fun buildAdmissionRequest(
        props: PopupProps,
        activeSlotsOverride: Collection<ShownSlot>?,
        candidateMeasuredDimensions: Pair<Int, Int>?,
        replaceSlotKey: String?,
    ): PopupLayoutSimulator.AdmissionRequest {
        val candidateSize = candidateMeasuredDimensions
            ?: props.measuredSize
            ?: props.calculateMetrics(context).let { it.width to it.height }
        val pool = activeSlotsOverride ?: (host.shownSlots.values + host.exitingSlots.values)
        val activeItems = pool.map { slot ->
            val (w, h) = getSlotDimensions(slot)
            PopupLayoutSimulator.LayoutItem(slot.key, slot.props, w, h, slot.shownAt)
        }
        return PopupLayoutSimulator.AdmissionRequest(
            props = props,
            candidateSize = candidateSize,
            activeItems = activeItems,
            replaceSlotKey = replaceSlotKey,
        )
    }

    companion object {
        private const val TAG = "NotificationManager"
        private const val PREP_TIMEOUT_MS = 45000L
        private const val EXIT_FALLBACK_DURATION_MS = 300L
        private const val EXIT_WATCHDOG_PADDING_MS = 250L
        private const val MS_PER_SECOND = 1000L
    }
}
