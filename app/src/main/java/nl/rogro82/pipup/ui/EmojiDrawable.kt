package nl.rogro82.pipup.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

/**
 * Lightweight, zero-bitmap [Drawable] that renders a short emoji or glyph string centered inside
 * a square box of [sizePx] x [sizePx], scaling the font down automatically to prevent overflow.
 */
internal class EmojiDrawable(
    private val glyph: String,
    private val sizePx: Int,
    @ColorInt textColor: Int,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = sizePx * BASE_TEXT_RATIO
        val maxWidth = sizePx * MAX_WIDTH_RATIO
        val measured = measureText(glyph)
        if (measured > maxWidth && measured > 0f) {
            textSize *= maxWidth / measured
        }
    }

    override fun draw(canvas: Canvas) {
        val fm = paint.fontMetrics
        val cx = if (bounds.isEmpty) sizePx / 2f else bounds.exactCenterX()
        val cy = (if (bounds.isEmpty) sizePx / 2f else bounds.exactCenterY()) - (fm.ascent + fm.descent) / 2f
        canvas.drawText(glyph, cx, cy, paint)
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated(
        "Deprecated in Java",
        ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"),
    )
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        private const val BASE_TEXT_RATIO = 0.8f
        private const val MAX_WIDTH_RATIO = 0.95f
    }
}
