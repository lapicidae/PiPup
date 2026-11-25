package nl.rogro82.pipup.core

import android.content.Context
import android.graphics.Rect
import android.util.Log
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.dpToPx

/**
 * Simulates 2D multi-popup placement, checks screen bounds and overlap conflicts,
 * and computes atomic eviction plans when `overwrite = true`.
 */
internal class PopupLayoutSimulator(private val context: Context) {

    enum class AdmissionStatus {
        ALLOWED,
        CAPACITY_FULL,
        GEOMETRY_BLOCKED,
        CAPACITY_AND_GEOMETRY_BLOCKED,
        UNPLACEABLE,
    }

    data class AdmissionDecision(
        val status: AdmissionStatus,
        val conflictingKeys: Set<String> = emptySet(),
    )

    class LayoutItem(
        val key: String,
        val props: PopupProps,
        val width: Int,
        val height: Int,
        val shownAt: Long = 0L,
    )

    data class AdmissionRequest(
        val props: PopupProps,
        val candidateSize: Pair<Int, Int>,
        val activeItems: List<LayoutItem>,
        val excludedKeys: Set<String> = emptySet(),
        val replaceSlotKey: String? = null,
    )

    private data class SimulationKey(
        val position: PopupProps.Position,
        val direction: PopupProps.StackDirection,
    )

    private data class SimulationBounds(
        val screenWidth: Int,
        val screenHeight: Int,
        val baseMargin: Int,
        val gapPx: Int,
    )

    private data class AdmissionContext(
        val props: PopupProps,
        val candidateItem: LayoutItem,
        val existingItems: List<LayoutItem>,
        val simulatedRects: Map<String, Rect>,
        val bounds: SimulationBounds,
    )

    fun evaluateAdmission(request: AdmissionRequest): AdmissionDecision {
        if (PopupProps.PopupLayoutMetrics.isFundamentallyUnplaceable(context)) {
            return AdmissionDecision(AdmissionStatus.UNPLACEABLE)
        }

        val props = request.props
        val (candidateW, candidateH) = request.candidateSize
        val basePool = request.activeItems.filter { it.key !in request.excludedKeys }
        val isCapacityFull = basePool.count { it.key != request.replaceSlotKey } >= PiPupApp.settings.maxPopups

        val candidateItem = LayoutItem(CANDIDATE_KEY, props, candidateW, candidateH)
        val layoutItems = buildAdmissionLayoutItems(basePool, candidateItem, request.replaceSlotKey)
        val simulatedRects = computeSimulatedLayout(layoutItems)
        val candidateRect = simulatedRects[CANDIDATE_KEY] ?: props.calculateMetrics(context).boundingBox

        val ctx = AdmissionContext(
            props = props,
            candidateItem = candidateItem,
            existingItems = layoutItems.filter { it.key != CANDIDATE_KEY },
            simulatedRects = simulatedRects,
            bounds = currentSimulationBounds(),
        )

        val conflictingKeys = LinkedHashSet<String>()
        collectDirectAndFullscreenConflicts(ctx, candidateRect, conflictingKeys)

        val candidatePos = props.getPositionEnum()
        val shiftedExisting = ctx.existingItems.filter {
            !it.props.coversScreen && it.props.getPositionEnum() == candidatePos
        }
        collectInducedOverlaps(ctx, shiftedExisting, conflictingKeys)
        val isBoundsOverflow = checkBoundsAndStackOverflow(ctx, candidateRect, shiftedExisting, conflictingKeys)

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
                "stack=${props.getStackDirectionEnum().name} -> status=$status, candidateBox=$candidateRect, " +
                "conflicting=$conflictingKeys",
        )
        return AdmissionDecision(status, conflictingKeys)
    }

    fun findAtomicEvictionPlan(
        request: AdmissionRequest,
        decision: AdmissionDecision,
        protectedKeys: Set<String>,
    ): List<String>? {
        val activePool = request.activeItems
        if (decision.status == AdmissionStatus.UNPLACEABLE || activePool.isEmpty()) return null

        val plan = LinkedHashSet<String>()
        var currentDecision = decision
        var possible = true

        while (possible && currentDecision.status != AdmissionStatus.ALLOWED) {
            val nextVictim = if (currentDecision.status == AdmissionStatus.UNPLACEABLE) {
                null
            } else {
                selectNextEvictionVictim(
                    currentDecision,
                    activePool,
                    plan,
                    protectedKeys,
                    request.replaceSlotKey,
                )
            }
            if (nextVictim == null) {
                possible = false
            } else {
                plan.add(nextVictim)
                currentDecision = evaluateAdmission(request.copy(excludedKeys = plan))
            }
        }

        return if (possible && plan.isNotEmpty()) plan.toList() else null
    }

    private fun currentSimulationBounds(): SimulationBounds {
        val dm = context.resources.displayMetrics
        return SimulationBounds(
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            baseMargin = context.dpToPx(PopupProps.BASE_MARGIN_DP),
            gapPx = context.dpToPx(PopupProps.STACK_GAP_DP),
        )
    }

    private fun computeSimulatedLayout(items: List<LayoutItem>): Map<String, Rect> {
        val bounds = currentSimulationBounds()
        val (fullscreenItems, normalItems) = items.partition { it.props.coversScreen }
        val grouped = normalItems.groupBy {
            SimulationKey(it.props.getPositionEnum(), it.props.getStackDirectionEnum())
        }
        val result = LinkedHashMap<String, Rect>(items.size)

        for (item in fullscreenItems) {
            val left = (bounds.screenWidth - item.width) / 2
            val top = (bounds.screenHeight - item.height) / 2
            result[item.key] = Rect(left, top, left + item.width, top + item.height)
        }

        for (pos in PopupProps.Position.entries) {
            val vItems = grouped[SimulationKey(pos, PopupProps.StackDirection.VERTICAL)].orEmpty()
            val hItems = grouped[SimulationKey(pos, PopupProps.StackDirection.HORIZONTAL)].orEmpty()
            val vWidth = vItems.maxOfOrNull { it.width } ?: 0
            val vMeasuredHeight = vItems.sumOf { it.height + bounds.gapPx }

            if (vItems.isNotEmpty()) {
                layoutVerticalItems(pos, vItems, bounds, result)
            }
            if (hItems.isNotEmpty()) {
                layoutHorizontalItems(pos, hItems, vWidth to vMeasuredHeight, bounds, result)
            }
        }

        return result
    }

    private fun layoutVerticalItems(
        pos: PopupProps.Position,
        vItems: List<LayoutItem>,
        bounds: SimulationBounds,
        result: MutableMap<String, Rect>,
    ) {
        val anchor = pos.getVerticalAnchor()
        if (anchor == PopupProps.VerticalAnchor.BOTTOM) {
            var cursorY = bounds.screenHeight - bounds.baseMargin
            for (item in vItems) {
                val top = cursorY - item.height
                result[item.key] = computeVerticalItemRect(pos, item.width, top, cursorY, bounds)
                cursorY = top - bounds.gapPx
            }
            return
        }
        val vMeasuredHeight = vItems.sumOf { it.height + bounds.gapPx }
        var cursorY = if (anchor == PopupProps.VerticalAnchor.TOP) {
            bounds.baseMargin
        } else {
            (bounds.screenHeight - vMeasuredHeight) / 2
        }
        for (item in vItems) {
            val bottom = cursorY + item.height
            result[item.key] = computeVerticalItemRect(pos, item.width, cursorY, bottom, bounds)
            cursorY = bottom + bounds.gapPx
        }
    }

    private fun layoutHorizontalItems(
        pos: PopupProps.Position,
        hItems: List<LayoutItem>,
        vDims: Pair<Int, Int>,
        bounds: SimulationBounds,
        result: MutableMap<String, Rect>,
    ) {
        val (vWidth, vMeasuredHeight) = vDims
        val hHeight = hItems.maxOfOrNull { it.height } ?: 0
        val side = pos.getScreenSide()
        val vShift = if (vWidth > 0) vWidth + bounds.gapPx else 0

        if (side == PopupProps.ScreenSide.RIGHT) {
            var cursorX = bounds.screenWidth - bounds.baseMargin - vShift
            for (item in hItems) {
                val left = cursorX - item.width
                result[item.key] = computeHorizontalItemRect(
                    pos, item.height, left..cursorX, hHeight to vMeasuredHeight, bounds,
                )
                cursorX = left - bounds.gapPx
            }
            return
        }
        val hMeasuredWidth = hItems.sumOf { it.width + bounds.gapPx }
        var cursorX = if (side == PopupProps.ScreenSide.LEFT) {
            bounds.baseMargin + vShift
        } else {
            (bounds.screenWidth - hMeasuredWidth) / 2
        }
        for (item in hItems) {
            val right = cursorX + item.width
            result[item.key] = computeHorizontalItemRect(
                pos, item.height, cursorX..right, hHeight to vMeasuredHeight, bounds,
            )
            cursorX = right + bounds.gapPx
        }
    }

    private fun computeVerticalItemRect(
        pos: PopupProps.Position,
        itemWidth: Int,
        top: Int,
        bottom: Int,
        bounds: SimulationBounds,
    ): Rect {
        val left = when (pos.getScreenSide()) {
            PopupProps.ScreenSide.LEFT -> bounds.baseMargin
            PopupProps.ScreenSide.RIGHT -> bounds.screenWidth - bounds.baseMargin - itemWidth
            PopupProps.ScreenSide.CENTER -> (bounds.screenWidth - itemWidth) / 2
        }
        return Rect(left, top, left + itemWidth, bottom)
    }

    private fun computeHorizontalItemRect(
        pos: PopupProps.Position,
        itemHeight: Int,
        xRange: IntRange,
        stackHeights: Pair<Int, Int>,
        bounds: SimulationBounds,
    ): Rect {
        val (hContainerHeight, vMeasuredHeight) = stackHeights
        val top = when (pos) {
            PopupProps.Position.TopLeft, PopupProps.Position.TopRight -> bounds.baseMargin
            PopupProps.Position.TopCenter -> {
                bounds.baseMargin + (if (vMeasuredHeight > 0) vMeasuredHeight + bounds.gapPx else 0)
            }
            PopupProps.Position.BottomLeft, PopupProps.Position.BottomRight -> {
                bounds.screenHeight - bounds.baseMargin - itemHeight
            }
            PopupProps.Position.BottomCenter -> {
                val vOffset = if (vMeasuredHeight > 0) vMeasuredHeight + bounds.gapPx else 0
                bounds.screenHeight - bounds.baseMargin - vOffset - itemHeight
            }
            PopupProps.Position.CenterLeft, PopupProps.Position.CenterRight -> {
                (bounds.screenHeight - itemHeight) / 2
            }
            PopupProps.Position.Center -> {
                if (vMeasuredHeight == 0) {
                    (bounds.screenHeight - itemHeight) / 2
                } else {
                    val spaceBelow = (bounds.screenHeight - vMeasuredHeight) / 2 - bounds.gapPx
                    if (spaceBelow >= hContainerHeight) {
                        val containerTop = (bounds.screenHeight + vMeasuredHeight) / 2 + bounds.gapPx
                        containerTop + (hContainerHeight - itemHeight) / 2
                    } else {
                        val containerBottom = (bounds.screenHeight - vMeasuredHeight) / 2 - bounds.gapPx
                        containerBottom - (hContainerHeight - itemHeight) / 2 - itemHeight
                    }
                }
            }
        }
        return Rect(xRange.first, top, xRange.last, top + itemHeight)
    }

    private fun rectsOverlapWithGap(a: Rect, b: Rect): Boolean {
        val minGapPx = context.dpToPx(MIN_OVERLAP_GAP_DP)
        return a.left < b.right + minGapPx &&
            b.left < a.right + minGapPx &&
            a.top < b.bottom + minGapPx &&
            b.top < a.bottom + minGapPx
    }

    private fun sharesContainer(a: PopupProps, b: PopupProps): Boolean =
        !a.coversScreen && !b.coversScreen &&
            a.getPositionEnum() == b.getPositionEnum() &&
            a.getStackDirectionEnum() == b.getStackDirectionEnum()

    private fun isOutOfBounds(rect: Rect, coversScreen: Boolean, bounds: SimulationBounds): Boolean {
        val margin = if (coversScreen) 0 else bounds.baseMargin
        return rect.left < margin || rect.right > bounds.screenWidth - margin ||
            rect.top < margin || rect.bottom > bounds.screenHeight - margin
    }

    private fun buildAdmissionLayoutItems(
        basePool: List<LayoutItem>,
        candidateItem: LayoutItem,
        replaceSlotKey: String?,
    ): List<LayoutItem> {
        val layoutItems = ArrayList<LayoutItem>(basePool.size + 1)
        var replacedInPlace = false
        for (item in basePool) {
            if (item.key == replaceSlotKey) {
                layoutItems.add(candidateItem)
                replacedInPlace = true
            } else {
                layoutItems.add(item)
            }
        }
        if (!replacedInPlace) layoutItems.add(candidateItem)
        return layoutItems
    }

    private fun collectDirectAndFullscreenConflicts(
        ctx: AdmissionContext,
        candidateRect: Rect,
        conflictingKeys: MutableSet<String>,
    ) {
        if (ctx.props.coversScreen) {
            ctx.existingItems.forEach { conflictingKeys.add(it.key) }
        } else {
            ctx.existingItems.filter { it.props.coversScreen }.forEach { conflictingKeys.add(it.key) }
        }

        for (other in ctx.existingItems) {
            val otherRect = ctx.simulatedRects[other.key]
            if (!sharesContainer(ctx.props, other.props) && otherRect != null &&
                rectsOverlapWithGap(candidateRect, otherRect)
            ) {
                conflictingKeys.add(other.key)
            }
        }
    }

    private fun collectInducedOverlaps(
        ctx: AdmissionContext,
        shiftedExisting: List<LayoutItem>,
        conflictingKeys: MutableSet<String>,
    ) {
        for (itemA in shiftedExisting) {
            val rectA = ctx.simulatedRects[itemA.key] ?: continue
            for (itemB in ctx.existingItems) {
                if (isDistinctContainerOverlap(
                        itemA.key, itemA.props, itemB.key, itemB.props, rectA to ctx.simulatedRects[itemB.key],
                    )
                ) {
                    conflictingKeys.add(itemB.key)
                    conflictingKeys.add(itemA.key)
                }
            }
        }
    }

    private fun isDistinctContainerOverlap(
        keyA: String,
        propsA: PopupProps,
        keyB: String,
        propsB: PopupProps,
        rects: Pair<Rect, Rect?>,
    ): Boolean {
        val (rectA, rectB) = rects
        return keyA != keyB && !sharesContainer(propsA, propsB) && rectB != null && rectsOverlapWithGap(rectA, rectB)
    }

    private fun checkBoundsAndStackOverflow(
        ctx: AdmissionContext,
        candidateRect: Rect,
        shiftedExisting: List<LayoutItem>,
        conflictingKeys: MutableSet<String>,
    ): Boolean {
        if (ctx.existingItems.isEmpty()) return false

        val candidateOutOfBounds = isOutOfBounds(candidateRect, ctx.props.coversScreen, ctx.bounds)
        val shiftedOutOfBounds = shiftedExisting.any { item ->
            val r = ctx.simulatedRects[item.key] ?: return@any false
            isOutOfBounds(r, item.props.coversScreen, ctx.bounds)
        }

        val sameContainerItems = ctx.existingItems.filter { sharesContainer(ctx.props, it.props) }
        val maxColumnHeight = ctx.bounds.screenHeight - context.dpToPx(MAX_COLUMN_MARGIN_DP)
        val verticalStackSpan = computeVerticalStackSpan(ctx, sameContainerItems)
        val isCenterDualOverflow = isCenterDualAxisOverflow(ctx, candidateOutOfBounds)

        val isBoundsOverflow = candidateOutOfBounds ||
            shiftedOutOfBounds ||
            verticalStackSpan > maxColumnHeight ||
            isCenterDualOverflow

        if (isBoundsOverflow) {
            addBoundsOverflowConflicts(ctx, sameContainerItems, conflictingKeys)
        }
        return isBoundsOverflow
    }

    private fun computeVerticalStackSpan(ctx: AdmissionContext, sameContainerItems: List<LayoutItem>): Int {
        if (ctx.props.getStackDirectionEnum() != PopupProps.StackDirection.VERTICAL || sameContainerItems.isEmpty()) {
            return 0
        }
        val allInStack = sameContainerItems + ctx.candidateItem
        val minTop = allInStack.minOf { ctx.simulatedRects[it.key]?.top ?: ctx.bounds.baseMargin }
        val maxBottom = allInStack.maxOf { ctx.simulatedRects[it.key]?.bottom ?: ctx.bounds.baseMargin }
        return maxBottom - minTop
    }

    private fun isCenterDualAxisOverflow(ctx: AdmissionContext, candidateOutOfBounds: Boolean): Boolean {
        val isCenterHorizontal = ctx.props.getPositionEnum() == PopupProps.Position.Center &&
            ctx.props.getStackDirectionEnum() == PopupProps.StackDirection.HORIZONTAL
        if (!isCenterHorizontal) return false

        val vItemsAtCenter = ctx.existingItems.filter {
            it.props.getPositionEnum() == PopupProps.Position.Center &&
                it.props.getStackDirectionEnum() == PopupProps.StackDirection.VERTICAL
        }
        return vItemsAtCenter.isNotEmpty() && run {
            val vH = vItemsAtCenter.sumOf { it.height + ctx.bounds.gapPx }
            val spaceAvailable = (ctx.bounds.screenHeight - vH) / 2 - ctx.bounds.gapPx
            ctx.candidateItem.height > spaceAvailable || candidateOutOfBounds
        }
    }

    private fun addBoundsOverflowConflicts(
        ctx: AdmissionContext,
        sameContainerItems: List<LayoutItem>,
        conflictingKeys: MutableSet<String>,
    ) {
        val candidatePos = ctx.props.getPositionEnum()
        if (ctx.props.getStackDirectionEnum() == PopupProps.StackDirection.HORIZONTAL) {
            ctx.existingItems.filter {
                it.props.getPositionEnum() == candidatePos &&
                    it.props.getStackDirectionEnum() == PopupProps.StackDirection.VERTICAL
            }.forEach { conflictingKeys.add(it.key) }
        }
        sameContainerItems.forEach { conflictingKeys.add(it.key) }
        ctx.existingItems.filter { it.props.getPositionEnum() == candidatePos }
            .forEach { conflictingKeys.add(it.key) }
    }

    private fun selectNextEvictionVictim(
        decision: AdmissionDecision,
        activePool: List<LayoutItem>,
        plan: Set<String>,
        protectedKeys: Set<String>,
        replaceSlotKey: String?,
    ): String? {
        fun isEvictable(key: String) = key != replaceSlotKey && key !in plan && key !in protectedKeys
        return decision.conflictingKeys.firstOrNull(::isEvictable)
            ?: activePool.sortedBy { it.shownAt }.map { it.key }.firstOrNull(::isEvictable)
    }

    companion object {
        private const val TAG = "NotificationManager"
        private const val CANDIDATE_KEY = "__candidate__"
        private const val MAX_COLUMN_MARGIN_DP = 80
        private const val MIN_OVERLAP_GAP_DP = 4
    }
}
