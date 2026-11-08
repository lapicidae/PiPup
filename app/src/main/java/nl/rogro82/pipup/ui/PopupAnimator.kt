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

        fun applySlide() {
            val margin = view.context.dpToPx(20).toFloat()
            val viewWidth = (if (view.width > 0) view.width.toFloat() else view.context.dpToPx(400).toFloat()) + margin + 100f
            val viewHeight = (if (view.height > 0) view.height.toFloat() else view.context.dpToPx(300).toFloat()) + margin + 100f

            when (pos) {
                PopupProps.Position.TopRight, PopupProps.Position.BottomRight, PopupProps.Position.CenterRight -> view.translationX = viewWidth
                PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft, PopupProps.Position.CenterLeft -> view.translationX = -viewWidth
                PopupProps.Position.TopCenter -> view.translationY = -viewHeight
                PopupProps.Position.BottomCenter, PopupProps.Position.Center -> view.translationY = viewHeight
            }
        }

        when (props.animationType) {
            1 -> view.animate().alpha(1f).setDuration(duration).withEndAction { resetAnimationProps(props) }.start()
            2 -> { view.alpha = 1f; applySlide(); view.animate().translationX(0f).translationY(0f).setDuration(duration).withEndAction { resetAnimationProps(props) }.start() }
            3 -> { view.alpha = 1f; applySlide(); view.animate().translationX(0f).translationY(0f).setInterpolator(OvershootInterpolator(1.5f)).setDuration(duration).withEndAction { resetAnimationProps(props) }.start() }
            4 -> { view.scaleX = 0f; view.scaleY = 0f; view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration).withEndAction { resetAnimationProps(props) }.start() }
            5 -> { view.scaleX = 0f; view.scaleY = 0f; view.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(1.5f)).setDuration(duration).withEndAction { resetAnimationProps(props) }.start() }
            6 -> { view.scaleX = 0f; view.scaleY = 0f; view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration).withEndAction { executeTaDa(); resetAnimationProps(props) }.start() }
            7 -> { view.alpha = 1f; view.scaleX = 0.5f; view.scaleY = 0.5f; applySlide(); view.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(duration).withEndAction { resetAnimationProps(props) }.start() }
            8 -> { view.alpha = 1f; view.rotationY = -90f; applySlide(); view.animate().translationX(0f).translationY(0f).rotationY(0f).setDuration(duration).withEndAction { resetAnimationProps() }.start() }
            9 -> { view.alpha = 1f; applySlide(); view.animate().translationX(0f).translationY(0f).setDuration(duration).withEndAction { executeTaDa() }.start() }
            10 -> {
                view.alpha = 0f; view.scaleX = 0f; view.scaleY = 0f
                val metrics = view.resources.displayMetrics
                when (pos) {
                    PopupProps.Position.TopRight -> { view.translationX = metrics.widthPixels.toFloat(); view.translationY = -500f }
                    PopupProps.Position.TopLeft -> { view.translationX = -metrics.widthPixels.toFloat(); view.translationY = -500f }
                    PopupProps.Position.BottomRight -> { view.translationX = metrics.widthPixels.toFloat(); view.translationY = metrics.heightPixels.toFloat() }
                    PopupProps.Position.BottomLeft -> { view.translationX = -metrics.widthPixels.toFloat(); view.translationY = metrics.heightPixels.toFloat() }
                    PopupProps.Position.TopCenter -> { view.translationX = 0f; view.translationY = -metrics.heightPixels.toFloat() }
                    PopupProps.Position.BottomCenter -> { view.translationX = 0f; view.translationY = metrics.heightPixels.toFloat() }
                    PopupProps.Position.CenterLeft -> { view.translationX = -metrics.widthPixels.toFloat(); view.translationY = 0f }
                    PopupProps.Position.CenterRight -> { view.translationX = metrics.widthPixels.toFloat(); view.translationY = 0f }
                    PopupProps.Position.Center -> { view.translationY = metrics.heightPixels.toFloat() }
                }
                view.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(duration).withEndAction { resetAnimationProps() }.start()
            }
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
            view.animate().alpha(0f).setDuration(if (duration > 0) duration else 300).withEndAction(completion).start()
            return
        }

        val pos = props.getPositionEnum()

        fun getSlideX(): Float {
            val margin = view.context.dpToPx(20).toFloat()
            return when (pos) {
                PopupProps.Position.TopRight, PopupProps.Position.BottomRight, PopupProps.Position.CenterRight -> view.width + margin + 100f
                PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft, PopupProps.Position.CenterLeft -> -(view.width + margin + 100f)
                else -> 0f
            }
        }
        fun getSlideY(): Float {
            val margin = view.context.dpToPx(20).toFloat()
            val viewHeight = (if (view.height > 0) view.height.toFloat() else view.context.dpToPx(300).toFloat()) + margin + 100f
            val screenHeight = view.resources.displayMetrics.heightPixels.toFloat()

            return when (pos) {
                PopupProps.Position.TopCenter -> -viewHeight
                PopupProps.Position.BottomCenter -> viewHeight
                PopupProps.Position.Center -> (screenHeight / 2f) + (viewHeight / 2f)
                else -> 0f
            }
        }

        when (props.animationType) {
            1 -> view.animate().alpha(0f).setDuration(duration).withEndAction(completion).start()
            2, 3, 9 -> view.animate().translationX(getSlideX()).translationY(getSlideY()).setDuration(duration).withEndAction(completion).start()
            4, 5, 6 -> view.animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(duration).withEndAction(completion).start()
            7 -> view.animate().translationX(getSlideX()).translationY(getSlideY()).scaleX(0.5f).scaleY(0.5f).setDuration(duration).withEndAction(completion).start()
            8 -> view.animate().translationX(getSlideX()).translationY(getSlideY()).rotationY(-90f).setDuration(duration).withEndAction(completion).start()
            10 -> {
                val metrics = view.resources.displayMetrics
                val (tx, ty) = when (pos) {
                    PopupProps.Position.TopRight -> metrics.widthPixels.toFloat() to -500f
                    PopupProps.Position.TopLeft -> -metrics.widthPixels.toFloat() to -500f
                    PopupProps.Position.BottomRight -> metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
                    PopupProps.Position.BottomLeft -> -metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
                    PopupProps.Position.TopCenter -> 0f to -metrics.heightPixels.toFloat()
                    PopupProps.Position.BottomCenter -> 0f to metrics.heightPixels.toFloat()
                    PopupProps.Position.CenterLeft -> -metrics.widthPixels.toFloat() to 0f
                    PopupProps.Position.CenterRight -> metrics.widthPixels.toFloat() to 0f
                    PopupProps.Position.Center -> 0f to metrics.heightPixels.toFloat()
                }
                view.animate().alpha(0f).translationX(tx).translationY(ty).scaleX(0f).scaleY(0f).setDuration(duration).withEndAction(completion).start()
            }
            else -> view.animate().alpha(0f).setDuration(duration).withEndAction(completion).start()
        }
    }

    private fun executeTaDa() {
        if (isAnimatingOut) return
        view.animate().scaleX(1.1f).scaleY(1.1f).rotation(3f).setDuration(150).withEndAction {
            if (isAnimatingOut) return@withEndAction
            view.animate().rotation(-3f).setDuration(150).withEndAction {
                if (isAnimatingOut) return@withEndAction
                view.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(150).start()
            }.start()
        }.start()
    }

    private fun resetAnimationProps(props: PopupProps? = null) {
        if (isAnimatingOut) return
        view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f; view.translationX = 0f; view.translationY = 0f
        view.rotationY = 0f; view.rotation = 0f
        props?.let {
            android.util.Log.d("PopupAnimator", "[LIFECYCLE] '${it.title ?: it.id}' ANIMATE_IN_END at ${System.currentTimeMillis()}ms")
        }
    }
}
