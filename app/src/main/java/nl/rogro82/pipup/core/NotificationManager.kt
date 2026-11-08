package nl.rogro82.pipup.core

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isEmpty
import androidx.media3.common.util.UnstableApi
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.UpdateManager
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.getLocalizedContext
import nl.rogro82.pipup.service.PipUpService
import nl.rogro82.pipup.showToast
import nl.rogro82.pipup.ui.PopupView

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
        private const val CANDIDATE_KEY = "__candidate__"
        private const val MAX_PARALLEL_PREPARING = 3
        private const val EXIT_FALLBACK_DURATION_MS = 300L
        private const val EXIT_WATCHDOG_PADDING_MS = 250L
    }

    private class ShownSlot(
        val key: String,
        var container: LinearLayout? = null,
        var view: PopupView,
        var props: PopupProps,
        var expire: Runnable? = null,
        var shownAt: Long = SystemClock.elapsedRealtime(),
        var removedEventFired: Boolean = false,
        val prepToken: Any = Any()
    )

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<PopupProps>()
    private val mPreparingSlots = LinkedHashMap<String, ShownSlot>()
    private val mReadyWaiting = LinkedHashMap<String, ShownSlot>()
    private val mShown = LinkedHashMap<String, ShownSlot>()
    private val mExiting = LinkedHashMap<String, ShownSlot>()
    private val internalUpdateKeys = mutableSetOf<String>()
    private var isProcessingQueue = false
    private var reprocessQueueRequested = false
    private data class ContainerKey(
        val position: PopupProps.Position,
        val direction: PopupProps.StackDirection
    )

    private val containers = mutableMapOf<ContainerKey, LinearLayout>()
    private var overlay: FrameLayout? = null

    val watchdogCleanups = java.util.concurrent.atomic.AtomicLong(0)
    val popupsShown = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile var lastPopup: PopupProps? = null
        private set
    @Volatile var lastPopupAt: Long = 0L
        private set

    var onPopupShown: ((PopupProps) -> Unit)? = null
    var onPopupRemoved: ((String, String) -> Unit)? = null
    /** Callback invoked when the overall notification display/queue active state changes. */
    var onStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Checks if a notification is currently being displayed on the screen or preparing.
     */
    fun isDisplaying(): Boolean = synchronized(this) {
        mShown.isNotEmpty() ||
            mExiting.isNotEmpty() ||
            mPreparingSlots.isNotEmpty() ||
            mReadyWaiting.isNotEmpty() ||
            queue.isNotEmpty()
    }

    /**
     * Returns the properties of the currently displayed notification, or null if none.
     */
    @Suppress("unused")
    fun getCurrentProps(): PopupProps? = synchronized(this) {
        mShown.values.lastOrNull()?.props
    }

    /**
     * Checks if a given ID or key belongs to an internal update popup.
     */
    fun isInternalUpdate(idOrKey: String): Boolean = synchronized(this) {
        internalUpdateKeys.contains(idOrKey) ||
            mShown[idOrKey]?.props?.isInternalUpdateAction == true ||
            mShown.values.any { it.props.id == idOrKey && it.props.isInternalUpdateAction } ||
            mPreparingSlots[idOrKey]?.props?.isInternalUpdateAction == true ||
            mReadyWaiting[idOrKey]?.props?.isInternalUpdateAction == true
    }

    /**
     * Returns active popup details (properties and individual shownAt timestamp).
     */
    fun getActiveSlotDetails(): List<Pair<PopupProps, Long>> = synchronized(this) {
        val list = mutableListOf<Pair<PopupProps, Long>>()
        for ((_, slot) in mShown) {
            list.add(Pair(slot.props, slot.shownAt))
        }
        list
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
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {}
        return result
    }

    private fun cleanupPendingSlot(key: String, notifyCancelled: Boolean = false): Boolean {
        var removed = false
        mPreparingSlots.remove(key)?.let { prep ->
            handler.removeCallbacksAndMessages(prep.prepToken)
            prep.view.cleanup()
            if (notifyCancelled) onPopupRemoved?.invoke("queue_cancelled", key)
            removed = true
        }
        mReadyWaiting.remove(key)?.let { ready ->
            handler.removeCallbacksAndMessages(ready.prepToken)
            ready.view.cleanup()
            if (notifyCancelled) onPopupRemoved?.invoke("queue_cancelled", key)
            removed = true
        }
        return removed
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

    private fun handleEnqueue(props: PopupProps, isInternal: Boolean = false) {
        synchronized(this) {
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

            val id = props.id

            if (!id.isNullOrEmpty() && mExiting.containsKey(id)) {
                val exiting = mExiting.remove(id)!!
                handler.removeCallbacksAndMessages(exiting)
                exiting.container?.removeView(exiting.view)
                exiting.view.cleanup()
            }

            // 1. If active on-screen unique ID popup exists
            if (!id.isNullOrEmpty() && mShown.containsKey(id)) {
                val current = mShown[id]!!
                if (canUpdateInPlace(current.props, props)) {
                    handleInPlaceUpdate(id, props)
                    return
                } else {
                    removePopupInstantly(id, "replaced")
                }
            }

            // 2. If unique ID popup is preparing or waiting ready, clean it up before replacing
            if (!id.isNullOrEmpty()) {
                cleanupPendingSlot(id)
            }

            // 3. If unique ID popup is waiting in the queue, update its payload in the queue
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
                if (updatedInQueue) {
                    processNextLocked()
                    return
                }
            }

            if (PopupProps.PopupLayoutMetrics.isFundamentallyUnplaceable(context)) {
                Log.w(TAG, "Discarding unplaceable popup: ${props.title ?: props.id}")
                (props.media as? PopupProps.Media.LocalFile)?.let { lf ->
                    PayloadParser.deleteFileAsync(lf.path)
                }
                return
            }

            if (props.overwrite) {
                queue.addFirst(props)
            } else {
                queue.addLast(props)
            }
            processNextLocked()
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
            oldContainer?.removeView(current.view)
            val newContainer = ensurePositionContainer(newPos, props.stack)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                applyStackingMargins(this, props)
            }

            newContainer.addView(current.view, lp)
            current.container = newContainer

            if (oldContainer != null && oldContainer.isEmpty()) {
                overlay?.removeView(oldContainer)
                containers.values.remove(oldContainer)
            }
        }

        current.props = props
        current.view.updateFromProps(props)
        measurePopupView(current.view)
        if (positionChanged) {
            adjustDualAxisContainerMargins(oldPos)
        }
        adjustDualAxisContainerMargins(newPos)
        resolveVisibleConflictsAfterResize(current)
        updateOverlayFocusability()

        if (positionChanged) {
            current.view.post {
                current.view.forceAnimateIn()
            }
        }

        current.view.startMedia()
        scheduleRemoval(current)
    }



    private enum class AdmissionStatus {
        ALLOWED,
        CAPACITY_FULL,
        GEOMETRY_BLOCKED,
        CAPACITY_AND_GEOMETRY_BLOCKED,
        UNPLACEABLE
    }

    private data class AdmissionDecision(
        val status: AdmissionStatus,
        val conflictingKeys: Set<String> = emptySet()
    )

    private data class LayoutItem(
        val key: String,
        val props: PopupProps,
        val width: Int,
        val height: Int
    )

    private fun measurePopupView(view: PopupView) {
        view.syncLayoutForMeasurement()
        val dm = context.resources.displayMetrics
        view.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST)
        )
    }

    private fun getSlotDimensions(slot: ShownSlot): Pair<Int, Int> {
        if (slot.view.measuredWidth <= 0 || slot.view.measuredHeight <= 0) {
            measurePopupView(slot.view)
        }
        val metrics = slot.props.calculateMetrics(context)
        return if (!mPreparingSlots.containsKey(slot.key) &&
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

    private fun computeSimulatedLayout(items: List<LayoutItem>): Map<String, Rect> {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val baseMargin = context.dpToPx(20)
        val gapPx = context.dpToPx(STACK_GAP_DP)

        val grouped = items.groupBy {
            ContainerKey(it.props.getPositionEnum(), it.props.getStackDirectionEnum())
        }
        val result = LinkedHashMap<String, Rect>(items.size)

        for (pos in PopupProps.Position.entries) {
            val vItems = grouped[ContainerKey(pos, PopupProps.StackDirection.VERTICAL)].orEmpty()
            val hItems = grouped[ContainerKey(pos, PopupProps.StackDirection.HORIZONTAL)].orEmpty()

            val vWidth = vItems.maxOfOrNull { it.width } ?: 0
            val vMeasuredHeight = vItems.sumOf { it.height + gapPx }

            if (vItems.isNotEmpty()) {
                val anchor = pos.getVerticalAnchor()
                if (anchor == PopupProps.VerticalAnchor.BOTTOM) {
                    var cursorY = screenHeight - baseMargin
                    for (item in vItems) {
                        val bottom = cursorY
                        val top = bottom - item.height
                        result[item.key] = computeVerticalItemRect(
                            pos, item.width, top, bottom, screenWidth, baseMargin
                        )
                        cursorY = top - gapPx
                    }
                } else {
                    var cursorY = if (anchor == PopupProps.VerticalAnchor.TOP) {
                        baseMargin
                    } else {
                        (screenHeight - vMeasuredHeight) / 2
                    }
                    for (item in vItems) {
                        val top = cursorY
                        val bottom = top + item.height
                        result[item.key] = computeVerticalItemRect(
                            pos, item.width, top, bottom, screenWidth, baseMargin
                        )
                        cursorY = bottom + gapPx
                    }
                }
            }

            if (hItems.isNotEmpty()) {
                val hHeight = hItems.maxOfOrNull { it.height } ?: 0
                val hMeasuredWidth = hItems.sumOf { it.width + gapPx }
                val side = pos.getScreenSide()

                if (side == PopupProps.ScreenSide.RIGHT) {
                    var cursorX = screenWidth - baseMargin - (if (vWidth > 0) vWidth + gapPx else 0)
                    for (item in hItems) {
                        val right = cursorX
                        val left = right - item.width
                        result[item.key] = computeHorizontalItemRect(
                            pos, item.height, left, right, hHeight, vMeasuredHeight, screenHeight, baseMargin, gapPx
                        )
                        cursorX = left - gapPx
                    }
                } else {
                    var cursorX = if (side == PopupProps.ScreenSide.LEFT) {
                        baseMargin + (if (vWidth > 0) vWidth + gapPx else 0)
                    } else {
                        (screenWidth - hMeasuredWidth) / 2
                    }
                    for (item in hItems) {
                        val left = cursorX
                        val right = left + item.width
                        result[item.key] = computeHorizontalItemRect(
                            pos, item.height, left, right, hHeight, vMeasuredHeight, screenHeight, baseMargin, gapPx
                        )
                        cursorX = right + gapPx
                    }
                }
            }
        }

        return result
    }

    private fun computeVerticalItemRect(
        pos: PopupProps.Position,
        itemWidth: Int,
        top: Int,
        bottom: Int,
        screenWidth: Int,
        baseMargin: Int
    ): Rect {
        val left = when (pos.getScreenSide()) {
            PopupProps.ScreenSide.LEFT -> baseMargin
            PopupProps.ScreenSide.RIGHT -> screenWidth - baseMargin - itemWidth
            PopupProps.ScreenSide.CENTER -> (screenWidth - itemWidth) / 2
        }
        return Rect(left, top, left + itemWidth, bottom)
    }

    private fun computeHorizontalItemRect(
        pos: PopupProps.Position,
        itemHeight: Int,
        left: Int,
        right: Int,
        hContainerHeight: Int,
        vMeasuredHeight: Int,
        screenHeight: Int,
        baseMargin: Int,
        gapPx: Int
    ): Rect {
        val top = when (pos) {
            PopupProps.Position.TopLeft, PopupProps.Position.TopRight -> baseMargin
            PopupProps.Position.TopCenter -> {
                baseMargin + (if (vMeasuredHeight > 0) vMeasuredHeight + gapPx else 0)
            }
            PopupProps.Position.BottomLeft, PopupProps.Position.BottomRight -> {
                screenHeight - baseMargin - itemHeight
            }
            PopupProps.Position.BottomCenter -> {
                screenHeight - baseMargin - (if (vMeasuredHeight > 0) vMeasuredHeight + gapPx else 0) - itemHeight
            }
            PopupProps.Position.CenterLeft, PopupProps.Position.CenterRight -> {
                (screenHeight - itemHeight) / 2
            }
            PopupProps.Position.Center -> {
                if (vMeasuredHeight == 0) {
                    (screenHeight - itemHeight) / 2
                } else {
                    val spaceBelow = (screenHeight - vMeasuredHeight) / 2 - gapPx
                    if (spaceBelow >= hContainerHeight) {
                        val containerTop = (screenHeight + vMeasuredHeight) / 2 + gapPx
                        containerTop + (hContainerHeight - itemHeight) / 2
                    } else {
                        val containerBottom = (screenHeight - vMeasuredHeight) / 2 - gapPx
                        containerBottom - (hContainerHeight - itemHeight) / 2 - itemHeight
                    }
                }
            }
        }
        return Rect(left, top, right, top + itemHeight)
    }

    private fun rectsOverlapWithGap(a: Rect, b: Rect): Boolean {
        val minGapPx = context.dpToPx(4)
        return a.left < b.right + minGapPx &&
            b.left < a.right + minGapPx &&
            a.top < b.bottom + minGapPx &&
            b.top < a.bottom + minGapPx
    }

    private fun evaluateAdmission(
        props: PopupProps,
        excludedKeys: Set<String> = emptySet(),
        activeSlotsOverride: Collection<ShownSlot>? = null,
        candidateMeasuredDimensions: Pair<Int, Int>? = null,
        replaceSlotKey: String? = null
    ): AdmissionDecision {
        if (PopupProps.PopupLayoutMetrics.isFundamentallyUnplaceable(context)) {
            return AdmissionDecision(AdmissionStatus.UNPLACEABLE)
        }

        val candidateMetrics = props.calculateMetrics(context)
        val (candidateW, candidateH) = candidateMeasuredDimensions
            ?: props.measuredSize
            ?: (candidateMetrics.width to candidateMetrics.height)

        val basePool = (activeSlotsOverride ?: (mShown.values + mExiting.values))
            .filter { it.key !in excludedKeys }
        val activeSlotsWithoutCandidate = basePool.filter { it.key != replaceSlotKey }
        val activeCount = activeSlotsWithoutCandidate.size
        val max = PiPupApp.settings.maxPopups
        val isCapacityFull = activeCount >= max

        val candidatePos = props.getPositionEnum()
        val candidateDir = props.getStackDirectionEnum()
        val candidateItem = LayoutItem(CANDIDATE_KEY, props, candidateW, candidateH)

        val layoutItems = ArrayList<LayoutItem>(basePool.size + 1)
        var replacedInPlace = false
        for (slot in basePool) {
            if (slot.key == replaceSlotKey) {
                layoutItems.add(candidateItem)
                replacedInPlace = true
            } else {
                val (w, h) = getSlotDimensions(slot)
                layoutItems.add(LayoutItem(slot.key, slot.props, w, h))
            }
        }
        if (!replacedInPlace) {
            layoutItems.add(candidateItem)
        }
        val existingItems = layoutItems.filter { it.key != CANDIDATE_KEY }

        val simulatedRects = computeSimulatedLayout(layoutItems)
        val candidateRect = simulatedRects[CANDIDATE_KEY] ?: candidateMetrics.boundingBox

        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val baseMargin = context.dpToPx(20)
        val maxColumnHeight = screenHeight - context.dpToPx(80)

        val conflictingKeys = LinkedHashSet<String>()

        // 1. Pairwise overlap check between candidate and all existing slots in other containers
        for ((otherKey, otherProps) in existingItems) {
            val sameContainer = otherProps.getPositionEnum() == candidatePos &&
                otherProps.getStackDirectionEnum() == candidateDir
            val otherRect = simulatedRects[otherKey]
            if (!sameContainer && otherRect != null &&
                rectsOverlapWithGap(candidateRect, otherRect)
            ) {
                conflictingKeys.add(otherKey)
            }
        }

        // 2. Induced overlap check: adding candidate may shift existing slots at candidatePos
        val shiftedExisting = existingItems.filter { it.props.getPositionEnum() == candidatePos }
        for ((keyA, propsA) in shiftedExisting) {
            val rectA = simulatedRects[keyA]
            if (rectA != null) {
                for ((keyB, propsB) in existingItems) {
                    val sameContainer = propsA.getPositionEnum() == propsB.getPositionEnum() &&
                        propsA.getStackDirectionEnum() == propsB.getStackDirectionEnum()
                    val rectB = simulatedRects[keyB]
                    if (keyA != keyB && !sameContainer && rectB != null &&
                        rectsOverlapWithGap(rectA, rectB)
                    ) {
                        conflictingKeys.add(keyB)
                        conflictingKeys.add(keyA)
                    }
                }
            }
        }

        // 3. Screen bounds & stack overflow check when at least one active slot exists
        var isBoundsOverflow = false
        if (existingItems.isNotEmpty()) {
            val candidateOutOfBounds = candidateRect.left < baseMargin ||
                candidateRect.right > screenWidth - baseMargin ||
                candidateRect.top < baseMargin ||
                candidateRect.bottom > screenHeight - baseMargin

            val shiftedOutOfBounds = shiftedExisting.any { item ->
                val r = simulatedRects[item.key] ?: return@any false
                r.left < baseMargin || r.right > screenWidth - baseMargin ||
                    r.top < baseMargin || r.bottom > screenHeight - baseMargin
            }

            val sameContainerItems = existingItems.filter {
                it.props.getPositionEnum() == candidatePos &&
                    it.props.getStackDirectionEnum() == candidateDir
            }

            val isVerticalStack = candidateDir == PopupProps.StackDirection.VERTICAL &&
                sameContainerItems.isNotEmpty()
            val verticalStackSpan = if (isVerticalStack) {
                val allInStack = sameContainerItems + candidateItem
                val minTop = allInStack.minOf { simulatedRects[it.key]?.top ?: baseMargin }
                val maxBottom = allInStack.maxOf { simulatedRects[it.key]?.bottom ?: baseMargin }
                maxBottom - minTop
            } else {
                0
            }

            val isCenterHorizontal = candidatePos == PopupProps.Position.Center &&
                candidateDir == PopupProps.StackDirection.HORIZONTAL
            val isCenterDualOverflow = if (isCenterHorizontal) {
                val vItemsAtCenter = existingItems.filter {
                    it.props.getPositionEnum() == PopupProps.Position.Center &&
                        it.props.getStackDirectionEnum() == PopupProps.StackDirection.VERTICAL
                }
                if (vItemsAtCenter.isNotEmpty()) {
                    val gapPx = context.dpToPx(STACK_GAP_DP)
                    val vH = vItemsAtCenter.sumOf { it.height + gapPx }
                    val spaceAvailable = (screenHeight - vH) / 2 - gapPx
                    candidateH > spaceAvailable || candidateOutOfBounds
                } else {
                    false
                }
            } else {
                false
            }

            isBoundsOverflow = candidateOutOfBounds ||
                shiftedOutOfBounds ||
                verticalStackSpan > maxColumnHeight ||
                isCenterDualOverflow

            if (isBoundsOverflow) {
                if (candidateDir == PopupProps.StackDirection.HORIZONTAL) {
                    existingItems.filter {
                        it.props.getPositionEnum() == candidatePos &&
                            it.props.getStackDirectionEnum() == PopupProps.StackDirection.VERTICAL
                    }.forEach { conflictingKeys.add(it.key) }
                }
                sameContainerItems.forEach { conflictingKeys.add(it.key) }
                existingItems.filter { it.props.getPositionEnum() == candidatePos }
                    .forEach { conflictingKeys.add(it.key) }
            }
        }

        val isGeometryBlocked = conflictingKeys.isNotEmpty() || isBoundsOverflow

        val status = when {
            isCapacityFull && isGeometryBlocked -> AdmissionStatus.CAPACITY_AND_GEOMETRY_BLOCKED
            isCapacityFull -> AdmissionStatus.CAPACITY_FULL
            isGeometryBlocked -> AdmissionStatus.GEOMETRY_BLOCKED
            else -> AdmissionStatus.ALLOWED
        }

        Log.d(
            TAG,
            "[ADMISSION] '${props.title ?: props.id}' at pos=${candidatePos.name}(${candidatePos.index}) " +
                "stack=${candidateDir.name} -> status=$status, candidateBox=$candidateRect, " +
                "conflicting=$conflictingKeys"
        )
        return AdmissionDecision(status, conflictingKeys)
    }

    private fun findAtomicEvictionPlan(
        props: PopupProps,
        decision: AdmissionDecision,
        activeSlotsOverride: Collection<ShownSlot>? = null,
        candidateMeasuredDimensions: Pair<Int, Int>? = null,
        replaceSlotKey: String? = null
    ): List<String>? {
        val activePool = (activeSlotsOverride ?: (mShown.values + mExiting.values)).toList()
        if (decision.status == AdmissionStatus.UNPLACEABLE || activePool.isEmpty()) return null

        val plan = LinkedHashSet<String>()
        var currentDecision = decision
        var possible = true

        while (possible && currentDecision.status != AdmissionStatus.ALLOWED) {
            if (currentDecision.status == AdmissionStatus.UNPLACEABLE) {
                possible = false
            } else {
                val conflictVictim = currentDecision.conflictingKeys.firstOrNull { key ->
                    key != replaceSlotKey && key !in plan && key !in internalUpdateKeys
                }
                val capacityVictim = if (conflictVictim == null) {
                    activePool.sortedBy { it.shownAt }
                        .map { it.key }
                        .firstOrNull { key ->
                            key != replaceSlotKey && key !in plan && key !in internalUpdateKeys
                        }
                } else {
                    null
                }

                val nextVictim = conflictVictim ?: capacityVictim
                if (nextVictim == null) {
                    possible = false
                } else {
                    plan.add(nextVictim)
                    currentDecision = evaluateAdmission(
                        props = props,
                        excludedKeys = plan,
                        activeSlotsOverride = activePool,
                        candidateMeasuredDimensions = candidateMeasuredDimensions,
                        replaceSlotKey = replaceSlotKey
                    )
                }
            }
        }

        return if (possible && plan.isNotEmpty()) plan.toList() else null
    }

    private fun resolveVisibleConflictsAfterResize(slot: ShownSlot) {
        if (mPreparingSlots.containsKey(slot.key) || mReadyWaiting.containsKey(slot.key)) return
        val visiblePool = (mShown.values + mExiting.values).toList()
        if (visiblePool.size <= 1) return

        val dims = getSlotDimensions(slot)
        val decision = evaluateAdmission(
            props = slot.props,
            activeSlotsOverride = visiblePool,
            candidateMeasuredDimensions = dims,
            replaceSlotKey = slot.key
        )
        if (decision.status != AdmissionStatus.ALLOWED) {
            val evictionPlan = if (slot.props.overwrite) {
                findAtomicEvictionPlan(
                    props = slot.props,
                    decision = decision,
                    activeSlotsOverride = visiblePool,
                    candidateMeasuredDimensions = dims,
                    replaceSlotKey = slot.key
                )
            } else {
                null
            }
            if (evictionPlan != null) {
                for (evictKey in evictionPlan) {
                    removePopupInstantly(evictKey, "evicted")
                }
                processNextLocked()
            } else if (slot.key !in internalUpdateKeys) {
                Log.d(
                    TAG,
                    "[LIFECYCLE] '${slot.props.title ?: slot.key}' (key=${slot.key}) " +
                        "removed after resize due to geometry conflict"
                )
                removePopupInstantly(slot.key, "evicted")
                checkIdleAndCleanupOverlay()
                processNextLocked()
            }
        }
    }

    private fun measureContainerSize(container: LinearLayout?): Pair<Int, Int> {
        if (container == null || container.isEmpty()) return 0 to 0
        val dm = context.resources.displayMetrics
        container.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST)
        )
        val fallbackMetrics by lazy {
            (0 until container.childCount).firstNotNullOfOrNull {
                (container.getChildAt(it) as? PopupView)?.props
            }?.calculateMetrics(context)
        }
        val width = if (container.measuredWidth > 0) {
            container.measuredWidth
        } else {
            fallbackMetrics?.width ?: context.dpToPx(300)
        }
        val height = if (container.measuredHeight > 0) {
            container.measuredHeight
        } else {
            fallbackMetrics?.height ?: context.dpToPx(200)
        }
        return width to height
    }

    private fun adjustDualAxisContainerMargins(position: PopupProps.Position) {
        val hContainer = containers[ContainerKey(position, PopupProps.StackDirection.HORIZONTAL)]
        val vContainer = containers[ContainerKey(position, PopupProps.StackDirection.VERTICAL)]
        val gapPx = context.dpToPx(STACK_GAP_DP)
        val baseMargin = context.dpToPx(20)

        val (vWidth, vHeight) = measureContainerSize(vContainer)
        val (_, hHeight) = measureContainerSize(hContainer)

        when (position) {
            PopupProps.Position.TopCenter -> {
                val hLp = hContainer?.layoutParams as? FrameLayout.LayoutParams
                if (hLp != null) {
                    hLp.topMargin = if (vHeight > 0) baseMargin + vHeight + gapPx else baseMargin
                    hContainer.requestLayout()
                }
            }
            PopupProps.Position.BottomCenter -> {
                val hLp = hContainer?.layoutParams as? FrameLayout.LayoutParams
                if (hLp != null) {
                    hLp.bottomMargin = if (vHeight > 0) baseMargin + vHeight + gapPx else baseMargin
                    hContainer.requestLayout()
                }
            }
            PopupProps.Position.Center -> {
                val hLp = hContainer?.layoutParams as? FrameLayout.LayoutParams
                if (hLp != null) {
                    if (vHeight > 0) {
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
                    } else {
                        hLp.gravity = Gravity.CENTER
                        hLp.topMargin = 0
                        hLp.bottomMargin = 0
                    }
                    hContainer.requestLayout()
                }
            }
            else -> {
                val lp = hContainer?.layoutParams as? FrameLayout.LayoutParams ?: return
                val extraShift = if (vWidth > 0) vWidth + gapPx else 0
                when (position.getScreenSide()) {
                    PopupProps.ScreenSide.RIGHT -> lp.rightMargin = baseMargin + extraShift
                    PopupProps.ScreenSide.LEFT -> lp.leftMargin = baseMargin + extraShift
                    PopupProps.ScreenSide.CENTER -> {}
                }
                hContainer.requestLayout()
            }
        }
    }

    private fun applyStackingMargins(lp: LinearLayout.LayoutParams, props: PopupProps) {
        val gapPx = context.dpToPx(STACK_GAP_DP)
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

    private fun applyPositionToLayoutParams(params: FrameLayout.LayoutParams, position: PopupProps.Position) {
        val margin = context.dpToPx(20)
        params.gravity = when (position) {
            PopupProps.Position.TopRight -> Gravity.TOP or Gravity.END
            PopupProps.Position.TopLeft -> Gravity.TOP or Gravity.START
            PopupProps.Position.BottomRight -> Gravity.BOTTOM or Gravity.END
            PopupProps.Position.BottomLeft -> Gravity.BOTTOM or Gravity.START
            PopupProps.Position.Center -> Gravity.CENTER
            PopupProps.Position.TopCenter -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            PopupProps.Position.BottomCenter -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            PopupProps.Position.CenterLeft -> Gravity.CENTER_VERTICAL or Gravity.START
            PopupProps.Position.CenterRight -> Gravity.CENTER_VERTICAL or Gravity.END
        }
        params.setMargins(
            if (position.getScreenSide() == PopupProps.ScreenSide.LEFT) margin else 0,
            if (position.getVerticalAnchor() == PopupProps.VerticalAnchor.TOP) margin else 0,
            if (position.getScreenSide() == PopupProps.ScreenSide.RIGHT) margin else 0,
            if (position.getVerticalAnchor() == PopupProps.VerticalAnchor.BOTTOM) margin else 0
        )
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

    private fun ensurePositionContainer(position: PopupProps.Position, stack: String? = null): LinearLayout {
        val overlayView = ensureOverlay()!!
        val direction = PopupProps.parseStackDirection(stack)
        val key = ContainerKey(position, direction)

        return containers.getOrPut(key) {
            PositionContainer(context).apply {
                orientation = if (direction == PopupProps.StackDirection.HORIZONTAL) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
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
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    applyPositionToLayoutParams(this, position)
                }
                overlayView.addView(this, params)
            }
        }
    }



    private fun startPreparingPopup(props: PopupProps) {
        val slotKey = props.id?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        if (props.isInternalUpdateAction) {
            internalUpdateKeys.add(slotKey)
            props.id?.let { internalUpdateKeys.add(it) }
        }

        val localizedContext = context.getLocalizedContext(PiPupApp.settings.language)
        val view = PopupView(localizedContext, props).apply {
            onButton = { btn ->
                PipUpService.getInstance()?.handleButtonClick(this.props, btn, slotKey)
            }
            onLayoutAdjusted = {
                synchronized(this@NotificationManager) {
                    val currentSlot = mShown[slotKey]
                    if (currentSlot != null) {
                        measurePopupView(currentSlot.view)
                        adjustDualAxisContainerMargins(props.getPositionEnum())
                        resolveVisibleConflictsAfterResize(currentSlot)
                    }
                }
            }
        }

        val shown = ShownSlot(key = slotKey, view = view, props = props)
        mPreparingSlots[slotKey] = shown

        handler.postAtTime({
            synchronized(this) {
                if (mPreparingSlots[slotKey] === shown) {
                    Log.w(TAG, "Popup preparation timed out for key: $slotKey")
                    mPreparingSlots.remove(slotKey)
                    watchdogCleanups.incrementAndGet()
                    shown.view.cleanup()
                    processNextLocked()
                    checkIdleAndCleanupOverlay()
                }
            }
        }, shown.prepToken, SystemClock.uptimeMillis() + PREP_TIMEOUT_MS)

        view.readyListener = object : PopupView.ReadyListener {
            override fun onReady() {
                handler.removeCallbacksAndMessages(shown.prepToken)
                synchronized(this@NotificationManager) {
                    if (mPreparingSlots.remove(slotKey) === shown) {
                        tryShowReadySlot(shown)
                        processNextLocked()
                        checkIdleAndCleanupOverlay()
                    }
                }
            }
        }
        view.create()
        if (mPreparingSlots.containsKey(slotKey)) {
            measurePopupView(view)
        }
    }

    private fun tryShowReadySlot(shown: ShownSlot): Boolean {
        val props = shown.props
        val view = shown.view
        measurePopupView(view)
        val dims = view.measuredWidth.coerceAtLeast(1) to view.measuredHeight.coerceAtLeast(1)
        props.measuredSize = dims

        val decision = evaluateAdmission(
            props = props,
            candidateMeasuredDimensions = dims
        )
        var handled = true
        when (decision.status) {
            AdmissionStatus.UNPLACEABLE -> {
                Log.w(TAG, "Discarding unplaceable ready popup: ${props.title ?: props.id}")
                mReadyWaiting.remove(shown.key)
                view.cleanup()
                (props.media as? PopupProps.Media.LocalFile)?.let { lf ->
                    PayloadParser.deleteFileAsync(lf.path)
                }
                checkIdleAndCleanupOverlay()
            }
            AdmissionStatus.ALLOWED -> {
                mReadyWaiting.remove(shown.key)
                displayReadySlot(shown)
            }
            else -> {
                val evictionPlan = if (props.overwrite) {
                    findAtomicEvictionPlan(
                        props = props,
                        decision = decision,
                        candidateMeasuredDimensions = dims
                    )
                } else {
                    null
                }
                if (!evictionPlan.isNullOrEmpty()) {
                    for (evictKey in evictionPlan) {
                        removePopupInstantly(evictKey, "evicted")
                    }
                    mReadyWaiting.remove(shown.key)
                    displayReadySlot(shown)
                } else {
                    if (!mReadyWaiting.containsKey(shown.key)) {
                        Log.d(
                            TAG,
                            "[LIFECYCLE] '${props.title ?: props.id}' (key=${shown.key}) " +
                                "ready and waiting for screen space (status=${decision.status})"
                        )
                        mReadyWaiting[shown.key] = shown
                    }
                    handled = false
                }
            }
        }
        return handled
    }

    private fun displayReadySlot(shown: ShownSlot) {
        val slotKey = shown.key
        val props = shown.props
        val view = shown.view
        val container = ensurePositionContainer(props.getPositionEnum(), props.stack)
        shown.container = container

        if (!props.id.isNullOrEmpty()) {
            mShown.remove(props.id)?.let { old ->
                old.expire?.let { handler.removeCallbacks(it) }
                old.container?.removeView(old.view)
                old.view.cleanup()
            }
        }

        shown.shownAt = SystemClock.elapsedRealtime()
        mShown[slotKey] = shown
        notifyStateChanged(true)

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            applyStackingMargins(this, props)
        }
        val pos = props.getPositionEnum()
        val isHorizontal = props.getStackDirectionEnum() == PopupProps.StackDirection.HORIZONTAL
        val shouldPrepend = if (isHorizontal) {
            pos in listOf(
                PopupProps.Position.TopRight,
                PopupProps.Position.BottomRight,
                PopupProps.Position.CenterRight
            )
        } else {
            pos in listOf(
                PopupProps.Position.BottomRight,
                PopupProps.Position.BottomLeft,
                PopupProps.Position.BottomCenter
            )
        }

        if (shouldPrepend) {
            container.addView(view, 0, lp)
        } else {
            container.addView(view, lp)
        }

        adjustDualAxisContainerMargins(props.getPositionEnum())
        updateOverlayFocusability()

        Log.d(
            TAG,
            "[LIFECYCLE] '${props.title ?: props.id}' (key=$slotKey) " +
                "ANIMATE_IN_START at ${System.currentTimeMillis()}ms (pos=${props.getPositionEnum().name})"
        )
        view.animateIn()
        view.startMedia()
        scheduleRemoval(shown)
        onPopupShown?.invoke(props)
    }

    private fun scheduleRemoval(shown: ShownSlot) {
        handler.removeCallbacksAndMessages(shown)
        if (shown.props.duration <= 0) return

        val runnable = Runnable {
            synchronized(this) {
                if (mShown[shown.key] === shown) {
                    removePopupByKey(shown.key, "expired")
                }
            }
        }
        shown.expire = runnable
        handler.postAtTime(runnable, shown, SystemClock.uptimeMillis() + (shown.props.duration * 1000L))
    }

    private fun triggerRemovalEvent(shown: ShownSlot, reason: String) {
        if (!shown.removedEventFired) {
            shown.removedEventFired = true
            onPopupRemoved?.invoke(reason, shown.props.id ?: shown.key)
        }
    }

    private fun removePopupInstantly(
        key: String,
        reason: String = "cancelled"
    ) {
        cleanupPendingSlot(key)
        val shown = mShown.remove(key) ?: mExiting.remove(key) ?: return
        Log.d(
            TAG,
            "[LIFECYCLE] '${shown.props.title ?: shown.key}' (key=$key) " +
                "REMOVED_INSTANTLY at ${System.currentTimeMillis()}ms (reason=$reason)"
        )
        handler.removeCallbacksAndMessages(shown)
        handler.removeCallbacksAndMessages(shown.prepToken)
        triggerRemovalEvent(shown, reason)
        shown.container?.removeView(shown.view)
        shown.view.cleanup()
        adjustDualAxisContainerMargins(shown.props.getPositionEnum())
        updateOverlayFocusability()
    }

    private fun removePopupByKey(key: String, reason: String = "cancelled") {
        cleanupPendingSlot(key)
        val shown = mShown.remove(key) ?: mExiting[key] ?: return
        if (mExiting.containsKey(key)) return

        Log.d(
            TAG,
            "[LIFECYCLE] '${shown.props.title ?: shown.key}' (key=$key) " +
                "ANIMATE_OUT_START at ${System.currentTimeMillis()}ms (reason=$reason)"
        )
        mExiting[key] = shown
        handler.removeCallbacksAndMessages(shown)
        handler.removeCallbacksAndMessages(shown.prepToken)
        triggerRemovalEvent(shown, reason)
        adjustDualAxisContainerMargins(shown.props.getPositionEnum())
        updateOverlayFocusability()

        var finalized = false
        val finalizeRemoval = {
            synchronized(this) {
                if (!finalized) {
                    finalized = true
                    handler.removeCallbacksAndMessages(shown)
                    if (mExiting.remove(key) != null) {
                        Log.d(
                            TAG,
                            "[LIFECYCLE] '${shown.props.title ?: shown.key}' (key=$key) " +
                                "REMOVED_FROM_VIEW_TREE at ${System.currentTimeMillis()}ms"
                        )
                        shown.container?.removeView(shown.view)
                        shown.view.cleanup()
                        adjustDualAxisContainerMargins(shown.props.getPositionEnum())
                        updateOverlayFocusability()
                    }

                    processNextLocked()
                    checkIdleAndCleanupOverlay()
                }
            }
        }

        val exitDurationMs = shown.props.animationDuration.toLong().coerceAtLeast(EXIT_FALLBACK_DURATION_MS)
        handler.postAtTime(
            finalizeRemoval,
            shown,
            SystemClock.uptimeMillis() + exitDurationMs + EXIT_WATCHDOG_PADDING_MS
        )
        shown.view.animateOut(finalizeRemoval)
    }

    private fun processNextLocked() {
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

    private fun processReadyWaitingPass(isStrictSequential: Boolean): Boolean {
        if (mReadyWaiting.isEmpty()) return false
        val snapshot = mReadyWaiting.values.toList()
        var mutated = false
        var stopPass = false
        val iterator = snapshot.iterator()

        while (!stopPass && iterator.hasNext()) {
            val readySlot = iterator.next()
            if (tryShowReadySlot(readySlot)) {
                mutated = true
                stopPass = true
            } else if (isStrictSequential) {
                stopPass = true
            }
        }
        return mutated
    }

    private fun processSingleQueuePass(isStrictSequential: Boolean): Boolean {
        val totalPending = mPreparingSlots.size + mReadyWaiting.size
        val cannotPrepare = queue.isEmpty() ||
            (isStrictSequential && totalPending > 0) ||
            (!isStrictSequential && totalPending >= MAX_PARALLEL_PREPARING)
        if (cannotPrepare) return false

        val snapshot = queue.toList()
        var mutated = false
        var stopPass = false
        val iterator = snapshot.iterator()

        while (!stopPass && iterator.hasNext()) {
            val item = iterator.next()
            val decision = evaluateAdmission(item)
            val canOverwrite = item.overwrite &&
                findAtomicEvictionPlan(item, decision)?.isNotEmpty() == true

            when {
                decision.status == AdmissionStatus.UNPLACEABLE -> {
                    queue.remove(item)
                    (item.media as? PopupProps.Media.LocalFile)?.let { lf ->
                        PayloadParser.deleteFileAsync(lf.path)
                    }
                    Log.w(TAG, "Discarded unplaceable queued popup: ${item.title ?: item.id}")
                    checkIdleAndCleanupOverlay()
                    mutated = true
                    stopPass = true
                }
                decision.status == AdmissionStatus.ALLOWED || canOverwrite -> {
                    queue.remove(item)
                    startPreparingPopup(item)
                    mutated = true
                    stopPass = true
                }
                isStrictSequential -> {
                    stopPass = true
                }
            }
        }

        return mutated
    }

    private fun canUpdateInPlace(oldProps: PopupProps, newProps: PopupProps): Boolean {
        return oldProps.id == newProps.id &&
               oldProps.getPositionEnum() == newProps.getPositionEnum() &&
               oldProps.stack == newProps.stack
    }

    /**
     * Outcome of a [cancelById] operation.
     */
    enum class CancelResult {
        CANCELLED_ACTIVE,
        REMOVED_FROM_QUEUE,
        NOT_FOUND
    }

    /**
     * Cancels a specific notification by ID.
     * If active on screen, it is closed and removed.
     * If waiting in queue, it is removed from the queue.
     * Blocks until UI thread processing is done if called from a background thread.
     */
    fun cancelById(id: String): CancelResult {
        return runOnMainSync(CancelResult.NOT_FOUND) {
            performCancelById(id)
        }
    }

    private fun performCancelById(id: String): CancelResult {
        synchronized(this) {
            if (mShown.containsKey(id) || mExiting.containsKey(id)) {
                removePopupByKey(id, "cancelled")
                removeWaitingOrQueuedById(id)
                return CancelResult.CANCELLED_ACTIVE
            }

            if (removeWaitingOrQueuedById(id)) {
                processNextLocked()
                checkIdleAndCleanupOverlay()
                return CancelResult.REMOVED_FROM_QUEUE
            }

            return CancelResult.NOT_FOUND
        }
    }

    private fun removeWaitingOrQueuedById(id: String): Boolean {
        var removed = cleanupPendingSlot(id, notifyCancelled = true)
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

    /**
     * Handles button-triggered removal securely with reason "button" and single event dispatch.
     */
    fun handleButtonRemoval(idOrKey: String) {
        runOnMainSync(Unit) {
            performButtonRemoval(idOrKey)
        }
    }

    private fun performButtonRemoval(idOrKey: String) {
        synchronized(this) {
            val key = when {
                idOrKey.isNotEmpty() && mShown.containsKey(idOrKey) -> idOrKey
                idOrKey.isNotEmpty() && mExiting.containsKey(idOrKey) -> idOrKey
                idOrKey.isNotEmpty() -> mShown.entries.firstOrNull { it.value.props.id == idOrKey }?.key
                    ?: mExiting.entries.firstOrNull { it.value.props.id == idOrKey }?.key
                mShown.size == 1 -> mShown.keys.first()
                mExiting.size == 1 -> mExiting.keys.first()
                else -> mShown.keys.lastOrNull()
            }
            if (key != null) {
                val shown = mShown.remove(key) ?: mExiting.remove(key)
                if (shown != null) {
                    handler.removeCallbacksAndMessages(shown)
                    if (!shown.removedEventFired) {
                        shown.removedEventFired = true
                        onPopupRemoved?.invoke("button", shown.props.id ?: shown.key)
                    }
                    shown.container?.removeView(shown.view)
                    shown.view.cleanup()
                    updateOverlayFocusability()
                    processNextLocked()
                    checkIdleAndCleanupOverlay()
                }
            }
        }
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

    private fun checkIdleAndCleanupOverlay() {
        if (mShown.isEmpty() && mExiting.isEmpty()) {
            notifyStateChanged(false)
            removeOverlay()
            if (mPreparingSlots.isEmpty() && mReadyWaiting.isEmpty() && queue.isEmpty()) {
                lastPopup = null
            }
        }
    }

    private fun notifyStateChanged(isDisplaying: Boolean) {
        (context.applicationContext as? PiPupApp)?.moduleManager?.notifyNotificationDisplayStateChanged(isDisplaying)
        onStateChanged?.invoke(isDisplaying)
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
            for ((_, s) in mPreparingSlots) {
                onPopupRemoved?.invoke("queue_cancelled", s.props.id ?: s.key)
                s.view.cleanup()
            }
            mPreparingSlots.clear()
            for ((_, s) in mReadyWaiting) {
                onPopupRemoved?.invoke("queue_cancelled", s.props.id ?: s.key)
                s.view.cleanup()
            }
            mReadyWaiting.clear()
            for ((_, s) in mShown) {
                s.expire?.let { handler.removeCallbacks(it) }
                triggerRemovalEvent(s, "cancelled")
                s.container?.removeView(s.view)
                s.view.cleanup()
            }
            mShown.clear()
            for ((_, s) in mExiting) {
                s.expire?.let { handler.removeCallbacks(it) }
                triggerRemovalEvent(s, "cancelled")
                s.container?.removeView(s.view)
                s.view.cleanup()
            }
            mExiting.clear()
            for ((_, container) in containers) {
                overlay?.removeView(container)
            }
            containers.clear()

            notifyStateChanged(false)
            removeOverlay()
            lastPopup = null
        }
    }

    private fun updateOverlayFocusability() {
        val overlayView = overlay ?: return
        val hasButtons = mShown.values.any { it.props.buttons.isNotEmpty() } || mExiting.values.any { it.props.buttons.isNotEmpty() }
        val params = overlayView.layoutParams as? WindowManager.LayoutParams ?: return

        val targetFlags = if (hasButtons) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        if (params.flags != targetFlags) {
            params.flags = targetFlags
            try {
                windowManager.updateViewLayout(overlayView, params)
            } catch (e: Exception) {
                Log.e(TAG, "WindowManager update flags error: ${e.message}")
            }
        }
    }

    private fun ensureOverlay(): FrameLayout? {
        overlay?.let { return it }
        val view = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val hasButtons = mShown.values.any { it.props.buttons.isNotEmpty() } || mExiting.values.any { it.props.buttons.isNotEmpty() }
        val initialFlags = if (hasButtons) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            initialFlags,
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
        containers.clear()
    }
}
