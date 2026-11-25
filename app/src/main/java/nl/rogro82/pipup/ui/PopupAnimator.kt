package nl.rogro82.pipup.ui

import android.view.View
import android.view.animation.OvershootInterpolator
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.dpToPx

/**
 * Handles entrance and exit animations for PopupView.
 */
class PopupAnimator(private val view: View) {

    private var isAnimatedIn = false
    private var isAnimatingOut = false

    init {
        view.alpha = 0f
    }

    /**
     * Resets any ongoing animation state and forces the entrance animation to play from the start.
     */
    fun forceAnimateIn(props: PopupProps) {
        isAnimatedIn = false
        isAnimatingOut = false
        view.animate().cancel()
        resetAnimationProps()
        animateIn(props)
    }

    /**
     * Plays the entrance animation for the popup.
     */
    fun animateIn(props: PopupProps) {
        val alreadyVisible = view.alpha == 1.0f &&
            view.scaleX == 1.0f &&
            view.translationX == 0f &&
            view.translationY == 0f &&
            isAnimatedIn
        if (isAnimatingOut || alreadyVisible) return
        isAnimatedIn = true
        val duration = props.animationDuration.toLong()
        view.animate().cancel()
        view.animate().interpolator = null
        resetAnimationProps()
        if (props.animationType == 0 || duration <= 0) return

        view.alpha = 0f
        val pos = props.getPositionEnum()
        dispatchEntranceAnimation(props, pos, duration)
    }

    private fun dispatchEntranceAnimation(props: PopupProps, pos: PopupProps.Position, duration: Long) {
        when (props.animationType) {
            ANIM_FADE -> view.animate()
                .alpha(1f).setDuration(duration).withEndAction { resetAnimationProps(props) }.start()
            ANIM_SLIDE, ANIM_SLIDE_OVERSHOOT, ANIM_SLIDE_TADA -> animateSlideIn(props, pos, duration)
            ANIM_ZOOM, ANIM_ZOOM_OVERSHOOT, ANIM_ZOOM_TADA -> animateZoomIn(props, duration)
            ANIM_SLIDE_SCALE -> {
                view.alpha = 1f
                view.scaleX = SLIDE_SCALE_START
                view.scaleY = SLIDE_SCALE_START
                applySlideStartOffset(pos)
                view.animate()
                    .translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(duration).withEndAction { resetAnimationProps(props) }.start()
            }
            ANIM_SLIDE_FLIP -> {
                view.alpha = 1f
                view.rotationY = FLIP_ROTATION_DEG
                applySlideStartOffset(pos)
                view.animate()
                    .translationX(0f).translationY(0f).rotationY(0f)
                    .setDuration(duration).withEndAction { resetAnimationProps() }.start()
            }
            ANIM_FLY_IN -> {
                view.alpha = 0f
                view.scaleX = 0f
                view.scaleY = 0f
                val (tx, ty) = computeFlyOffset(view, pos)
                if (pos != PopupProps.Position.Center) {
                    view.translationX = tx
                }
                view.translationY = ty
                view.animate()
                    .alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(duration).withEndAction { resetAnimationProps() }.start()
            }
        }
    }

    private fun animateSlideIn(props: PopupProps, pos: PopupProps.Position, duration: Long) {
        view.alpha = 1f
        applySlideStartOffset(pos)
        val anim = view.animate().translationX(0f).translationY(0f).setDuration(duration)
        if (props.animationType == ANIM_SLIDE_OVERSHOOT) {
            anim.interpolator = OvershootInterpolator(OVERSHOOT_TENSION)
        }
        val endAction = if (props.animationType == ANIM_SLIDE_TADA) {
            Runnable { executeTaDa() }
        } else {
            Runnable { resetAnimationProps(props) }
        }
        anim.withEndAction(endAction).start()
    }

    private fun animateZoomIn(props: PopupProps, duration: Long) {
        view.scaleX = 0f
        view.scaleY = 0f
        val anim = view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration)
        if (props.animationType == ANIM_ZOOM_OVERSHOOT) {
            anim.interpolator = OvershootInterpolator(OVERSHOOT_TENSION)
        }
        val endAction = if (props.animationType == ANIM_ZOOM_TADA) {
            Runnable {
                executeTaDa()
                resetAnimationProps(props)
            }
        } else {
            Runnable { resetAnimationProps(props) }
        }
        anim.withEndAction(endAction).start()
    }

    private fun applySlideStartOffset(pos: PopupProps.Position) {
        val margin = view.context.dpToPx(20).toFloat()
        val baseW = if (view.width > 0) view.width.toFloat() else view.context.dpToPx(400).toFloat()
        val baseH = if (view.height > 0) view.height.toFloat() else view.context.dpToPx(300).toFloat()
        val viewWidth = baseW + margin + SLIDE_EXTRA_OFFSET_PX
        val viewHeight = baseH + margin + SLIDE_EXTRA_OFFSET_PX

        when (pos) {
            PopupProps.Position.TopRight,
            PopupProps.Position.BottomRight,
            PopupProps.Position.CenterRight -> view.translationX = viewWidth
            PopupProps.Position.TopLeft,
            PopupProps.Position.BottomLeft,
            PopupProps.Position.CenterLeft -> view.translationX = -viewWidth
            PopupProps.Position.TopCenter -> view.translationY = -viewHeight
            PopupProps.Position.BottomCenter, PopupProps.Position.Center -> view.translationY = viewHeight
        }
    }

    /**
     * Plays the exit animation for the popup.
     */
    fun animateOut(props: PopupProps, completion: () -> Unit) {
        val duration = props.animationDuration.toLong()
        isAnimatedIn = false
        isAnimatingOut = true
        view.animate().cancel()
        view.animate().interpolator = null
        if (props.animationType == 0 || duration <= 0 || !props.animationExit) {
            val exitDuration = if (duration > 0) duration else DEFAULT_EXIT_DURATION_MS
            view.animate().alpha(0f).setDuration(exitDuration).withEndAction(completion).start()
            return
        }

        val pos = props.getPositionEnum()
        when (props.animationType) {
            ANIM_FADE -> view.animate().alpha(0f).setDuration(duration).withEndAction(completion).start()
            ANIM_SLIDE, ANIM_SLIDE_OVERSHOOT, ANIM_SLIDE_TADA -> {
                val (tx, ty) = computeExitSlideOffset(pos)
                view.animate().translationX(tx).translationY(ty).setDuration(duration).withEndAction(completion).start()
            }
            ANIM_ZOOM, ANIM_ZOOM_OVERSHOOT, ANIM_ZOOM_TADA -> view.animate()
                .alpha(0f).scaleX(0f).scaleY(0f)
                .setDuration(duration).withEndAction(completion).start()
            ANIM_SLIDE_SCALE -> {
                val (tx, ty) = computeExitSlideOffset(pos)
                view.animate()
                    .translationX(tx).translationY(ty)
                    .scaleX(SLIDE_SCALE_START).scaleY(SLIDE_SCALE_START)
                    .setDuration(duration).withEndAction(completion).start()
            }
            ANIM_SLIDE_FLIP -> {
                val (tx, ty) = computeExitSlideOffset(pos)
                view.animate()
                    .translationX(tx).translationY(ty)
                    .rotationY(FLIP_ROTATION_DEG)
                    .setDuration(duration).withEndAction(completion).start()
            }
            ANIM_FLY_IN -> {
                val (tx, ty) = computeFlyOffset(view, pos)
                view.animate()
                    .alpha(0f).translationX(tx).translationY(ty).scaleX(0f).scaleY(0f)
                    .setDuration(duration).withEndAction(completion).start()
            }
            else -> view.animate().alpha(0f).setDuration(duration).withEndAction(completion).start()
        }
    }

    private fun computeExitSlideOffset(pos: PopupProps.Position): Pair<Float, Float> {
        val margin = view.context.dpToPx(20).toFloat()
        val slideX = view.width + margin + SLIDE_EXTRA_OFFSET_PX
        val baseH = if (view.height > 0) view.height.toFloat() else view.context.dpToPx(300).toFloat()
        val viewHeight = baseH + margin + SLIDE_EXTRA_OFFSET_PX
        val screenHeight = view.resources.displayMetrics.heightPixels.toFloat()
        return when (pos) {
            PopupProps.Position.TopRight,
            PopupProps.Position.BottomRight,
            PopupProps.Position.CenterRight -> slideX to 0f
            PopupProps.Position.TopLeft,
            PopupProps.Position.BottomLeft,
            PopupProps.Position.CenterLeft -> -slideX to 0f
            PopupProps.Position.TopCenter -> 0f to -viewHeight
            PopupProps.Position.BottomCenter -> 0f to viewHeight
            PopupProps.Position.Center -> 0f to ((screenHeight / 2f) + (viewHeight / 2f))
        }
    }

    private fun executeTaDa() {
        if (isAnimatingOut) return
        view.animate()
            .scaleX(TADA_SCALE)
            .scaleY(TADA_SCALE)
            .rotation(TADA_ROTATION_DEG)
            .setDuration(TADA_STEP_DURATION_MS)
            .withEndAction {
                if (isAnimatingOut) return@withEndAction
                view.animate()
                    .rotation(-TADA_ROTATION_DEG)
                    .setDuration(TADA_STEP_DURATION_MS)
                    .withEndAction {
                        if (isAnimatingOut) return@withEndAction
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .rotation(0f)
                            .setDuration(TADA_STEP_DURATION_MS)
                            .start()
                    }.start()
            }.start()
    }

    private fun resetAnimationProps(props: PopupProps? = null) {
        if (isAnimatingOut) return
        view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f; view.translationX = 0f; view.translationY = 0f
        view.rotationY = 0f; view.rotation = 0f
        props?.let {
            android.util.Log.d(
                "PopupAnimator",
                "[LIFECYCLE] '${it.title ?: it.id}' ANIMATE_IN_END at ${System.currentTimeMillis()}ms",
            )
        }
    }

    /** Animation type identifiers and motion constants for [PopupAnimator]. */
    companion object {
        private const val ANIM_FADE = 1
        private const val ANIM_SLIDE = 2
        private const val ANIM_SLIDE_OVERSHOOT = 3
        private const val ANIM_ZOOM = 4
        private const val ANIM_ZOOM_OVERSHOOT = 5
        private const val ANIM_ZOOM_TADA = 6
        private const val ANIM_SLIDE_SCALE = 7
        private const val ANIM_SLIDE_FLIP = 8
        private const val ANIM_SLIDE_TADA = 9
        private const val ANIM_FLY_IN = 10

        private const val OVERSHOOT_TENSION = 1.5f
        private const val SLIDE_SCALE_START = 0.5f
        private const val FLIP_ROTATION_DEG = -90f
        private const val SLIDE_EXTRA_OFFSET_PX = 100f
        private const val FLY_IN_TOP_OFFSET_PX = -500f
        private const val DEFAULT_EXIT_DURATION_MS = 300L
        private const val TADA_SCALE = 1.1f
        private const val TADA_ROTATION_DEG = 3f
        private const val TADA_STEP_DURATION_MS = 150L

        private fun computeFlyOffset(view: View, pos: PopupProps.Position): Pair<Float, Float> {
            val metrics = view.resources.displayMetrics
            val screenW = metrics.widthPixels.toFloat()
            val screenH = metrics.heightPixels.toFloat()
            return when (pos) {
                PopupProps.Position.TopRight -> screenW to FLY_IN_TOP_OFFSET_PX
                PopupProps.Position.TopLeft -> -screenW to FLY_IN_TOP_OFFSET_PX
                PopupProps.Position.BottomRight -> screenW to screenH
                PopupProps.Position.BottomLeft -> -screenW to screenH
                PopupProps.Position.TopCenter -> 0f to -screenH
                PopupProps.Position.BottomCenter -> 0f to screenH
                PopupProps.Position.CenterLeft -> -screenW to 0f
                PopupProps.Position.CenterRight -> screenW to 0f
                PopupProps.Position.Center -> 0f to screenH
            }
        }
    }
}
