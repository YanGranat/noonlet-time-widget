package app.noonlet

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue

internal object ClockTextGeometry {
    /** Font leading may extend beyond a row while all visible glyphs still fit. */
    fun fittingRatio(context: Context, height: Float, time: String, date: String?,
        timeFace: Typeface, dateFace: Typeface, timeSize: Float, dateSize: Float, gap: Float): Float {
        val metrics = context.resources.displayMetrics
        val available = px(context, height)
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = timeFace }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = dateFace }
        val timeInk = Rect()
        val dateInk = Rect()
        fun fits(ratio: Float): Boolean {
            timePaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, timeSize * ratio, metrics)
            datePaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, dateSize * ratio, metrics)
            val t = timePaint.fontMetricsInt
            val d = datePaint.fontMetricsInt
            val timeHeight = t.descent - t.ascent
            val height = timeHeight + if (date != null) d.descent - d.ascent + px(context, gap * ratio) else 0
            val top = (available - height) / 2
            timePaint.getTextBounds(time, 0, time.length, timeInk)
            timeInk.offset(0, top - t.ascent)
            if (timeInk.top < 0 || timeInk.bottom > available) return false
            if (date != null) {
                datePaint.getTextBounds(date, 0, date.length, dateInk)
                dateInk.offset(0, top + timeHeight + px(context, gap * ratio) - d.ascent)
                if (dateInk.top < 0 || dateInk.bottom > available) return false
            }
            return true
        }
        if (fits(1f)) return 1f
        var low = .01f
        var high = 1f
        repeat(10) {
            val candidate = (low + high) / 2
            if (fits(candidate)) low = candidate else high = candidate
        }
        return low
    }
}
