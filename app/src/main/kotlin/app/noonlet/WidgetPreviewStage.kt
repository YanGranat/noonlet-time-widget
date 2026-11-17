package app.noonlet

import android.content.Context
import android.os.Bundle
import android.util.SizeF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Local editor scene; moving its child never changes launcher widget state. */
internal class WidgetPreviewStage(context: Context) : FrameLayout(context) {
    fun interface PoseListener {
        fun onPoseChanged(column: Int, row: Int, dragging: Boolean)
    }

    private var canvas: FrameLayout? = null
    private var cover: View? = null
    private var listener: PoseListener? = null
    private var column = 0
    private var row = 0
    private var spanX = 2
    private var spanY = 1
    private var downX = 0f
    private var downY = 0f
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var startColumn = 0
    private var startRow = 0
    private var logicalWidth = 0
    private var logicalHeight = 0
    private var dragging = false
    private var nativeSize: SizeF? = null

    fun setNativeSize(size: SizeF?) {
        if (nativeSize == size) return
        nativeSize = size
        requestLayout()
    }

    init {
        clipChildren = true
        clipToPadding = true
        clipToOutline = true
    }

    fun setPoseListener(value: PoseListener?) {
        listener = value
    }

    fun isDragging() = dragging

    fun getWidgetContent(): View? = canvas?.getChildAt(0)

    fun viewport(widthDp: Int, heightDp: Int): SizeF {
        nativeSize?.let { return it }
        val zoom = if (width > 0 && height > 0) maxOf(1f, scale()) else 1f
        val density = resources.displayMetrics.density
        return SizeF((dp(widthDp.toFloat()) * zoom).roundToInt() / density,
            (dp(heightDp.toFloat()) * zoom).roundToInt() / density)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val availableWidth =
            if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) {
                dp(COLUMNS * PITCH_X)
            } else MeasureSpec.getSize(widthSpec)
        val width = resolveSize(maxOf(suggestedMinimumWidth, availableWidth), widthSpec)
        val contentWidth = maxOf(0, width - paddingLeft - paddingRight)
        val naturalHeight =
            paddingTop +
                paddingBottom +
                (contentWidth * (ROWS * PITCH_Y - ROW_GAP) / (COLUMNS * PITCH_X)).roundToInt()
        val windowHeight = resources.configuration.screenHeightDp
        val preferredHeight = if (windowHeight < 720)
            minOf(naturalHeight, dp(maxOf(112, windowHeight - EDITOR_RESERVED_HEIGHT).toFloat())) else naturalHeight
        val height = resolveSize(maxOf(suggestedMinimumHeight, preferredHeight), heightSpec)
        canvas?.let { board ->
            val zoom = maxOf(1f, scale(width, height))
            val contentWidth = nativeSize?.let { dp(it.width) } ?: (logicalWidth * zoom).roundToInt()
            val contentHeight = nativeSize?.let { dp(it.height) } ?: (logicalHeight * zoom).roundToInt()
            if (board.layoutParams.width != contentWidth || board.layoutParams.height != contentHeight)
                board.layoutParams = LayoutParams(contentWidth, contentHeight)
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    fun showWidget(
        widget: View,
        widthDp: Int,
        heightDp: Int,
        columns: Int,
        rows: Int,
        x: Int,
        y: Int,
    ) {
        cancelDrag()
        logicalWidth = dp(widthDp.toFloat())
        logicalHeight = dp(heightDp.toFloat())
        val viewport = viewport(widthDp, heightDp)
        val contentWidth = dp(viewport.width)
        val contentHeight = dp(viewport.height)
        spanX = columns
        spanY = rows
        column = x.coerceIn(0, maximumColumn())
        row = y.coerceIn(0, maximumRow())
        disableActions(widget)
        widget.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val existingCanvas = canvas
        if (existingCanvas != null) {
            if (existingCanvas.getChildAt(0) !== widget) {
                existingCanvas.removeViewAt(0)
                existingCanvas.addView(widget, 0, LayoutParams(-1, -1))
            }
            if (
                existingCanvas.layoutParams.width != contentWidth ||
                    existingCanvas.layoutParams.height != contentHeight
            ) {
                existingCanvas.layoutParams = LayoutParams(contentWidth, contentHeight)
            }
            positionWidget()
            updateDescription()
            notifyPose()
            return
        }

        removeAllViews()
        val board =
            FrameLayout(context).apply {
                tag = "preview-widget"
                pivotX = 0f
                pivotY = 0f
            }
        canvas = board
        addView(board, LayoutParams(contentWidth, contentHeight))
        board.addView(widget, LayoutParams(-1, -1))
        val target =
            View(context).apply {
                tag = "preview-drag-target"
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
            }
        cover = target
        updateDescription()
        target.setOnClickListener {}
        target.setOnTouchListener { view, event -> drag(view, event) }
        target.setOnKeyListener { _, key, event ->
            event.action == KeyEvent.ACTION_DOWN && moveByKey(key)
        }
        target.accessibilityDelegate =
            object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (column > 0)
                        info.addAction(
                            action(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT,
                                AppLocales.text(context, R.string.preview_move_left),
                            ),
                        )
                    if (column < maximumColumn())
                        info.addAction(
                            action(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT,
                                AppLocales.text(context, R.string.preview_move_right),
                            ),
                        )
                    if (row > 0)
                        info.addAction(
                            action(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP,
                                AppLocales.text(context, R.string.preview_move_up),
                            ),
                        )
                    if (row < maximumRow())
                        info.addAction(
                            action(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN,
                                AppLocales.text(context, R.string.preview_move_down),
                            ),
                        )
                }

                override fun performAccessibilityAction(
                    host: View,
                    id: Int,
                    args: Bundle?,
                ): Boolean =
                    when (id) {
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id ->
                            moveBy(-1, 0)
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id ->
                            moveBy(1, 0)
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id ->
                            moveBy(0, -1)
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id ->
                            moveBy(0, 1)
                        else -> super.performAccessibilityAction(host, id, args)
                    }
            }
        board.addView(target, LayoutParams(-1, -1))
        positionWidget()
        notifyPose()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionWidget()
    }

    override fun onDetachedFromWindow() {
        cancelDrag()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) cancelDrag()
    }

    fun cancelDrag() {
        if (!dragging) return
        dragging = false
        dragOffsetX = 0f
        dragOffsetY = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
        positionWidget()
        notifyPose()
    }

    private fun drag(target: View, event: MotionEvent): Boolean =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (canvas == null || maximumColumn() == 0 && maximumRow() == 0) false
                else {
                    target.requestFocus()
                    dragging = true
                    downX = event.rawX
                    downY = event.rawY
                    startColumn = column
                    startRow = row
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    notifyPose()
                    true
                }
            }
            MotionEvent.ACTION_MOVE ->
                if (!dragging) false
                else {
                    moveFromPointer(event)
                    true
                }
            MotionEvent.ACTION_UP ->
                if (!dragging) false
                else {
                    moveFromPointer(event)
                    column =
                        (startColumn + (dragOffsetX / (px(PITCH_X) * scale())).roundToInt())
                            .coerceIn(0, maximumColumn())
                    row =
                        (startRow + (dragOffsetY / (px(PITCH_Y) * scale())).roundToInt()).coerceIn(
                            0,
                            maximumRow(),
                        )
                    target.performClick()
                    updateDescription()
                    cancelDrag()
                    true
                }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelDrag()
                true
            }
            else -> true
        }

    private fun moveByKey(key: Int) =
        when (key) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveBy(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveBy(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> moveBy(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveBy(0, 1)
            else -> false
        }

    private fun moveBy(dx: Int, dy: Int): Boolean {
        if (canvas == null) return false
        cancelDrag()
        return moveTo(column + dx, row + dy)
    }

    private fun moveFromPointer(event: MotionEvent) {
        val pitchX = px(PITCH_X) * scale()
        val pitchY = px(PITCH_Y) * scale()
        dragOffsetX =
            (event.rawX - downX).coerceIn(
                -startColumn * pitchX,
                (maximumColumn() - startColumn) * pitchX,
            )
        dragOffsetY =
            (event.rawY - downY).coerceIn(-startRow * pitchY, (maximumRow() - startRow) * pitchY)
        positionWidget()
    }

    private fun moveTo(x: Int, y: Int): Boolean {
        val nextX = x.coerceIn(0, maximumColumn())
        val nextY = y.coerceIn(0, maximumRow())
        if (column == nextX && row == nextY) return false
        column = nextX
        row = nextY
        positionWidget()
        updateDescription()
        notifyPose()
        return true
    }

    private fun positionWidget() {
        val board = canvas ?: return
        if (width <= 0 || height <= 0) return
        val scale = scale()
        // Scale the placement grid, while content retains native text and offset units.
        val renderScale = if (nativeSize == null) minOf(1f, scale)
            else minOf(1f, (spanX * px(PITCH_X) * scale) / maxOf(1, board.width),
                (height - paddingTop - paddingBottom).toFloat() / maxOf(1, board.height))
        board.scaleX = renderScale
        board.scaleY = renderScale
        fun anchor(value: Float) = if (scale >= 1f) value.roundToInt().toFloat() else value
        val inset = if (nativeSize == null) (spanX * px(PITCH_X) - logicalWidth) * scale / 2
            else (spanX * px(PITCH_X) * scale - board.width * renderScale) / 2
        board.x = anchor(boardLeft() + column * px(PITCH_X) * scale + inset) + dragOffsetX
        board.y = (anchor(boardTop() + row * px(PITCH_Y) * scale) + dragOffsetY)
            .coerceAtMost(maxOf(paddingTop.toFloat(), height - paddingBottom - board.height * renderScale))
        invalidate()
    }

    private fun scale(width: Int = this.width, height: Int = this.height) =
        minOf(
            maxOf(1, width - paddingLeft - paddingRight) / px(COLUMNS * PITCH_X),
            maxOf(1, height - paddingTop - paddingBottom) / px(ROWS * PITCH_Y - ROW_GAP),
        )

    private fun boardLeft() =
        paddingLeft + (width - paddingLeft - paddingRight - px(COLUMNS * PITCH_X) * scale()) / 2

    private fun boardTop() =
        paddingTop +
            (height - paddingTop - paddingBottom - px(ROWS * PITCH_Y - ROW_GAP) * scale()) / 2

    private fun maximumColumn() = COLUMNS - spanX

    private fun maximumRow() = ROWS - spanY

    private fun notifyPose() {
        listener?.onPoseChanged(column, row, dragging)
    }

    private fun px(value: Float) = value * resources.displayMetrics.density

    private fun dp(value: Float) = px(value).roundToInt()

    private fun updateDescription() {
        cover?.contentDescription =
            if (maximumColumn() == 0 && maximumRow() == 0) {
                AppLocales.text(context, R.string.preview_fills_area)
            } else {
                AppLocales.text(context, R.string.preview_position, column + 1, row + 1)
            }
    }

    companion object {
        internal const val COLUMNS = 4
        internal const val ROWS = 2
        private const val PITCH_X = 83.5f
        private const val PITCH_Y = 92f
        private const val ROW_GAP = 10f
        // Keep navigation, size controls and a settings row visible in a short window.
        private const val EDITOR_RESERVED_HEIGHT = 336

        private fun action(action: AccessibilityNodeInfo.AccessibilityAction, label: String) =
            AccessibilityNodeInfo.AccessibilityAction(action.id, label)

        private fun disableActions(view: View) {
            view.setOnClickListener(null)
            view.isClickable = false
            view.isLongClickable = false
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            if (view is ViewGroup)
                for (index in 0 until view.childCount) disableActions(view.getChildAt(index))
        }
    }
}
