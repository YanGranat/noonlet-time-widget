package app.noonlet

import android.graphics.Point
import android.graphics.Paint
import android.graphics.Rect

internal object AlarmPlacementGeometry {
    data class Line(val width: Int, val height: Int, val ink: Rect)

    fun measure(paint: Paint, text: String, iconSize: Int = 0, iconGap: Int = 0): Line {
        val metrics = paint.fontMetricsInt
        val textHeight = metrics.descent - metrics.ascent
        val height = maxOf(textHeight, iconSize)
        val textX = if (iconSize > 0) iconSize + iconGap else 0
        val ink = Rect().apply {
            paint.getTextBounds(text, 0, text.length, this)
            offset(textX, (height - textHeight) / 2 - metrics.ascent)
            if (iconSize > 0) union(0, (height - iconSize) / 2, iconSize, (height + iconSize) / 2)
        }
        // Rasterized glyph edges can extend one pixel beyond Paint's integer bounds.
        if (!ink.isEmpty) ink.inset(-1, -1)
        return Line(textX + kotlin.math.ceil(paint.measureText(text)).toInt(), height, ink)
    }

    fun closest(viewport: Rect, ink: Rect, preferred: Point,
        obstacles: List<Rect>, clearance: Int): Point? {
        if (ink.isEmpty || ink.width() > viewport.width() || ink.height() > viewport.height()) return null
        val minimumX = viewport.left - ink.left
        val maximumX = viewport.right - ink.right
        val minimumY = viewport.top - ink.top
        val maximumY = viewport.bottom - ink.bottom
        val x = preferred.x.coerceIn(minimumX, maximumX)
        val y = preferred.y.coerceIn(minimumY, maximumY)
        val original = obstacles.filterNot(Rect::isEmpty)
        val requested = Rect(ink).apply { offset(x, y) }
        if (original.none { Rect.intersects(requested, it) }) return Point(x, y)
        val occupied = original.map { Rect(it).apply { inset(-clearance, -clearance) } }
        fun free(left: Int, top: Int): Boolean {
            val placed = Rect(ink).apply { offset(left, top) }
            return occupied.none { Rect.intersects(placed, it) }
        }
        if (free(x, y)) return Point(x, y)
        val columns = linkedSetOf(x, minimumX, maximumX)
        val rows = linkedSetOf(y, minimumY, maximumY)
        for (obstacle in occupied) {
            columns += (obstacle.left - ink.right).coerceIn(minimumX, maximumX)
            columns += (obstacle.right - ink.left).coerceIn(minimumX, maximumX)
            rows += (obstacle.top - ink.bottom).coerceIn(minimumY, maximumY)
            rows += (obstacle.bottom - ink.top).coerceIn(minimumY, maximumY)
        }
        return columns.asSequence().flatMap { left -> rows.asSequence().map { top -> Point(left, top) } }
            .filter { free(it.x, it.y) }
            .minWithOrNull(compareBy<Point> {
                val dx = it.x.toLong() - preferred.x
                val dy = it.y.toLong() - preferred.y
                dx * dx + dy * dy
            }.thenBy { kotlin.math.abs(it.x.toLong() - preferred.x) }
                .thenBy { it.y }.thenBy { it.x })
    }
}
