package app.noonlet

import android.graphics.Rect

internal object ClockSceneGeometry {
    data class Scene(val time: Rect, val date: Rect, val weather: Rect?,
        val alarmLeft: Int, val alarmWidth: Int, val alarmBelow: Int?, val height: Int) {
        fun alarmTop(rowHeight: Int, gap: Int) = alarmBelow?.plus(gap) ?: ((height - rowHeight) / 2)
    }

    fun arrange(width: Int, height: Int, clockWidth: Int, dateWidth: Int,
        timeHeight: Int, dateHeight: Int, weatherHeight: Int, gap: Int,
        horizontal: Boolean, stacked: Boolean, detailsInset: Int): Scene {
        if (horizontal) {
            val details = dateHeight > 0 || weatherHeight > 0
            val left = if (details) clockWidth / 2 + detailsInset else 0
            val column = if (details) maxOf(1, clockWidth / 2 - detailsInset) else clockWidth
            val top = (height - dateHeight - weatherHeight) / 2
            val timeTop = (height - timeHeight) / 2
            return Scene(Rect(0, timeTop,
                if (details) clockWidth / 2 else clockWidth, timeTop + timeHeight),
                Rect(left, top, left + column, top + dateHeight),
                if (weatherHeight > 0) Rect(left, top + dateHeight,
                    left + column, top + dateHeight + weatherHeight) else null,
                left, column, if (details) top + dateHeight + weatherHeight else null, height)
        }
        val spacing = if (dateHeight > 0 || stacked && weatherHeight > 0) gap else 0
        val timeTop = if (stacked) (height - timeHeight - dateHeight - weatherHeight - spacing) / 2
            else (height - timeHeight) / 2 - (dateHeight + spacing) / 2
        val dateTop = if (stacked) timeTop + timeHeight + spacing
            else (height - dateHeight) / 2 + (timeHeight + spacing) / 2
        val weatherTop = if (dateHeight > 0) dateTop + dateHeight else timeTop + timeHeight + spacing
        return Scene(Rect(0, timeTop, clockWidth, timeTop + timeHeight),
            Rect(0, dateTop, dateWidth, dateTop + dateHeight),
            if (stacked && weatherHeight > 0)
                Rect(0, weatherTop, width, weatherTop + weatherHeight) else null,
            0, clockWidth,
            when {
                stacked && weatherHeight > 0 -> weatherTop + weatherHeight
                dateHeight > 0 -> dateTop + dateHeight
                else -> timeTop + timeHeight
            }, height)
    }
}
