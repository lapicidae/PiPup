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

    /**
     * Plays the entrance animation for the popup.
     */
    fun animateIn(props: PopupProps) {
        if (view.alpha == 1.0f && view.scaleX == 1.0f && view.translationX == 0f && view.translationY == 0f && isAnimatedIn) {
            return
        }
        isAnimatedIn = true
        val duration = props.animationDuration.toLong()
        resetAnimationProps()
        if (props.animationType == 0 || duration <= 0) return

        view.alpha = 0f
        val pos = props.getPositionEnum()

        fun applySlide() {
            val offset = (if (view.width > 0) view.width.toFloat() else view.context.dpToPx(400).toFloat()) + view.context.dpToPx(20) + 100f
            when (pos) {
                PopupProps.Position.TopRight, PopupProps.Position.BottomRight -> view.translationX = offset
                PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft -> view.translationX = -offset
                PopupProps.Position.Center -> view.translationY = view.resources.displayMetrics.heightPixels.toFloat() / 2f
            }
        }

        when (props.animationType) {
            1 -> view.animate().alpha(1f).setDuration(duration).start()
            2 -> { view.alpha = 1f; applySlide(); view.animate().translationX(0f).translationY(0f).setDuration(duration).start() }
            3 -> { view.alpha = 1f; applySlide(); view.animate().translationX(0f).translationY(0f).setInterpolator(OvershootInterpolator(1.5f)).setDuration(duration).start() }
            4 -> { view.scaleX = 0f; view.scaleY = 0f; view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration).start() }
            5 -> { view.scaleX = 0f; view.scaleY = 0f; view.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(1.5f)).setDuration(duration).start() }
            6 -> { view.scaleX = 0f; view.scaleY = 0f; view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration).withEndAction { executeTaDa() }.start() }
            7 -> { view.alpha = 1f; view.scaleX = 0.5f; view.scaleY = 0.5f; applySlide(); view.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(duration).start() }
            8 -> { view.alpha = 1f; view.rotationY = -90f; applySlide(); view.animate().translationX(0f).translationY(0f).rotationY(0f).setDuration(duration).start() }
            9 -> { view.alpha = 1f; applySlide(); view.animate().translationX(0f).translationY(0f).setDuration(duration).withEndAction { executeTaDa() }.start() }
            10 -> {
                view.alpha = 0f; view.scaleX = 0f; view.scaleY = 0f
                val metrics = view.resources.displayMetrics
                when (pos) {
                    PopupProps.Position.TopRight -> { view.translationX = metrics.widthPixels.toFloat(); view.translationY = -500f }
                    PopupProps.Position.TopLeft -> { view.translationX = -metrics.widthPixels.toFloat(); view.translationY = -500f }
                    PopupProps.Position.BottomRight -> { view.translationX = metrics.widthPixels.toFloat(); view.translationY = metrics.heightPixels.toFloat() }
                    PopupProps.Position.BottomLeft -> { view.translationX = -metrics.widthPixels.toFloat(); view.translationY = metrics.heightPixels.toFloat() }
                    PopupProps.Position.Center -> { view.translationY = metrics.heightPixels.toFloat() }
                }
                view.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(duration).start()
            }
        }
    }

    /**
     * Plays the exit animation for the popup.
     */
    fun animateOut(props: PopupProps, completion: () -> Unit) {
        val duration = props.animationDuration.toLong()
        isAnimatedIn = false
        if (props.animationType == 0 || duration <= 0 || !props.animationExit) {
            view.animate().alpha(0f).setDuration(if (duration > 0) duration else 300).withEndAction(completion).start()
            return
        }

        val pos = props.getPositionEnum()

        fun getSlideX(): Float {
            val margin = view.context.dpToPx(20).toFloat()
            return when (pos) {
                PopupProps.Position.TopRight, PopupProps.Position.BottomRight -> view.width + margin + 100f
                PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft -> -(view.width + margin + 100f)
                else -> 0f
            }
        }
        fun getSlideY() = if (pos == PopupProps.Position.Center) view.resources.displayMetrics.heightPixels.toFloat() / 2f else 0f

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
                    PopupProps.Position.Center -> 0f to metrics.heightPixels.toFloat()
                }
                view.animate().alpha(0f).translationX(tx).translationY(ty).scaleX(0f).scaleY(0f).setDuration(duration).withEndAction(completion).start()
            }
            else -> view.animate().alpha(0f).setDuration(duration).withEndAction(completion).start()
        }
    }

    private fun executeTaDa() {
        view.animate().scaleX(1.1f).scaleY(1.1f).rotation(3f).setDuration(150).withEndAction {
            view.animate().rotation(-3f).setDuration(150).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(150).start()
            }.start()
        }.start()
    }

    private fun resetAnimationProps() {
        view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f; view.translationX = 0f; view.translationY = 0f
        view.rotationY = 0f; view.rotation = 0f
    }
}
