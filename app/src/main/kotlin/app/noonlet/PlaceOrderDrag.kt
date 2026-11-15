package app.noonlet

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/** Local drag state is committed only after a successful drop. */
internal class PlaceOrderDrag(
    private val list: LinearLayout,
    private val liftColor: Int,
    private val changed: (Int, Int) -> Unit,
) {
    private val items = ArrayList<View>()
    private var source: View? = null
    private var originalIndex = -1
    private var accepted = false
    private var pointerY = 0f
    private val scrollStep = object : Runnable {
        override fun run() {
            if (source == null || !list.isAttachedToWindow) return
            var parent = list.parent
            while (parent != null && parent !is ScrollView) parent = parent.parent
            val scroll = parent as? ScrollView ?: return
            val visible = Rect()
            if (!scroll.getGlobalVisibleRect(visible)) return
            val edge = 48f * list.resources.displayMetrics.density
            val distance = when {
                pointerY < visible.top + edge -> pointerY - visible.top - edge
                pointerY > visible.bottom - edge -> pointerY - visible.bottom + edge
                else -> 0f
            }
            if (distance != 0f) {
                scroll.scrollBy(0, (distance / 4).toInt().coerceIn(-24, 24))
                val location = IntArray(2)
                list.getLocationOnScreen(location)
                place(pointerY - location[1])
            }
            list.postOnAnimation(this)
        }
    }

    init {
        list.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                list.removeCallbacks(scrollStep)
                source?.alpha = 1f
                source = null
                accepted = false
            }
        })
        list.setOnDragListener { _, event ->
            val dragged = source
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> dragged != null && event.localState === dragged
                DragEvent.ACTION_DRAG_LOCATION -> {
                    if (dragged != null) {
                        val location = IntArray(2)
                        list.getLocationOnScreen(location)
                        pointerY = event.y + location[1]
                        place(event.y)
                    }
                    true
                }
                DragEvent.ACTION_DROP -> {
                    accepted = dragged != null
                    accepted
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    if (dragged != null) {
                        val destination = items.indexOf(dragged)
                        dragged.alpha = 1f
                        if (!accepted || !event.result) move(dragged, originalIndex)
                        val commit = accepted && event.result && originalIndex != destination
                        val from = originalIndex
                        source = null
                        accepted = false
                        list.removeCallbacks(scrollStep)
                        list.parent?.requestDisallowInterceptTouchEvent(false)
                        if (commit) changed(from, destination)
                    }
                    true
                }
                else -> dragged != null
            }
        }
    }

    fun attach(row: View, handle: View) {
        items.add(row)
        var downX = 0f
        var downY = 0f
        val slop = ViewConfiguration.get(list.context).scaledTouchSlop
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    list.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (source == null && abs(event.rawY - downY) > slop) {
                        val location = IntArray(2)
                        row.getLocationOnScreen(location)
                        val shadow = object : View.DragShadowBuilder(row) {
                            private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = liftColor }

                            override fun onDrawShadow(canvas: Canvas) {
                                val radius = 10f * row.resources.displayMetrics.density
                                canvas.drawRoundRect(0f, 0f, row.width.toFloat(), row.height.toFloat(), radius, radius, background)
                                row.draw(canvas)
                            }

                            override fun onProvideShadowMetrics(size: Point, touch: Point) {
                                size.set(row.width, row.height)
                                touch.set((downX - location[0]).toInt().coerceIn(0, row.width),
                                    (downY - location[1]).toInt().coerceIn(0, row.height))
                            }
                        }
                        source = row
                        originalIndex = items.indexOf(row)
                        accepted = false
                        if (handle.startDragAndDrop(null, shadow, row, View.DRAG_FLAG_OPAQUE)) {
                            row.alpha = .3f
                            pointerY = event.rawY
                            list.postOnAnimation(scrollStep)
                        }
                        else {
                            source = null
                            list.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (source == null) list.parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) handle.performClick()
                    true
                }
                else -> false
            }
        }
        handle.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                info.isClickable = false
                val index = items.indexOf(row)
                if (index > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AppLocales.text(list.context, R.string.move_up)))
                if (index < items.lastIndex) info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AppLocales.text(list.context, R.string.move_down)))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val index = items.indexOf(row)
                val destination = when (action) {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> index - 1
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> index + 1
                    else -> return super.performAccessibilityAction(host, action, args)
                }
                if (destination !in items.indices) return false
                changed(index, destination)
                return true
            }
        }
    }

    private fun move(row: View, destination: Int) {
        val current = items.indexOf(row)
        if (current == destination || destination !in items.indices) return
        items.removeAt(current)
        items.add(destination, row)
        list.removeView(row)
        list.addView(row, destination)
    }

    private fun place(y: Float) {
        val row = source ?: return
        val index = items.indexOf(row)
        val destination = items.indexOfLast { y > it.top + it.height / 2f }
            .let { if (it < index) it + 1 else it }.coerceIn(0, items.lastIndex)
        move(row, destination)
    }
}
