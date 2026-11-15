package app.noonlet

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

internal class AppIcons private constructor(context: Context, private val kind: Kind, color: Int) :
    Drawable() {
    enum class Kind {
        CHEVRON_RIGHT,
        EXPAND_MORE,
        EXPAND_LESS,
        MORE,
        ADD,
        RESET,
    }

    private val size = (24 * context.resources.displayMetrics.density).roundToInt()
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        if (kind == Kind.MORE) {
            paint.style = Paint.Style.FILL
            for (y in intArrayOf(5, 12, 19)) canvas.drawCircle(12f, y.toFloat(), 1.6f, paint)
        } else {
            paint.style = Paint.Style.STROKE
            val path =
                Path().apply {
                    when (kind) {
                        Kind.CHEVRON_RIGHT -> {
                            moveTo(9f, 6f)
                            lineTo(15f, 12f)
                            lineTo(9f, 18f)
                        }
                        Kind.EXPAND_MORE -> {
                            moveTo(6f, 9f)
                            lineTo(12f, 15f)
                            lineTo(18f, 9f)
                        }
                        Kind.EXPAND_LESS -> {
                            moveTo(6f, 15f)
                            lineTo(12f, 9f)
                            lineTo(18f, 15f)
                        }
                        Kind.ADD -> {
                            moveTo(12f, 5f)
                            lineTo(12f, 19f)
                            moveTo(5f, 12f)
                            lineTo(19f, 12f)
                        }
                        Kind.RESET -> {
                            moveTo(5f, 9f)
                            cubicTo(6.3f, 5.5f, 10.4f, 3.8f, 14f, 5.1f)
                            cubicTo(17.8f, 6.4f, 19.8f, 10.5f, 18.5f, 14.3f)
                            cubicTo(17.2f, 18.1f, 13.1f, 20.1f, 9.3f, 18.8f)
                            cubicTo(7.6f, 18.2f, 6.1f, 17f, 5.3f, 15.5f)
                            moveTo(5f, 4.5f)
                            lineTo(5f, 9.5f)
                            lineTo(10f, 9.5f)
                        }
                        Kind.MORE -> Unit
                    }
                }
            canvas.drawPath(path, paint)
        }
        canvas.restoreToCount(saved)
    }

    override fun getIntrinsicWidth() = size

    override fun getIntrinsicHeight() = size

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        fun create(context: Context, kind: Kind, color: Int) = AppIcons(context, kind, color)
    }
}
