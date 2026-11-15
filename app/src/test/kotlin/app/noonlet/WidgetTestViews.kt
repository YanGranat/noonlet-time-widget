package app.noonlet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

object WidgetTestViews {
    internal fun completeEditor(activity: MainActivity) {
        val root = activity.findViewById<View>(android.R.id.content)
        val action = root.findViewWithTag<View>("action-add-widget")
            ?: root.findViewWithTag<View>("action-save-changes")
        check(requireNotNull(action).performClick())
    }

    internal fun selectWorldTab(activity: MainActivity) {
        val label = activity.ui.tr(R.string.world_widget_name)
        fun find(view: View): View? {
            if (view is TextView && view.text.toString() == label && view.isClickable) return view
            if (view is ViewGroup) for (i in 0 until view.childCount)
                find(view.getChildAt(i))?.let { return it }
            return null
        }
        check(requireNotNull(find(activity.findViewById(android.R.id.content))).performClick())
    }

    fun prepare(view: View) {
        if (view is TextView) view.onPreDraw()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) prepare(view.getChildAt(index))
        }
    }

    fun weight(view: TextView): Int = typeface(view).weight

    fun typeface(view: TextView): android.graphics.Typeface {
        val text = view.text as Spanned
        val paint = TextPaint()
        for (span in text.getSpans(0, text.length, MetricAffectingSpan::class.java))
            span.updateMeasureState(paint)
        return paint.typeface
    }

    fun reveal(root: View, key: String): View {
        val content = root.findViewWithTag<View>("section-content:$key") ?: return root
        if (content.visibility != View.VISIBLE)
            requireNotNull(root.findViewWithTag<View>("section-toggle:$key")).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return root
    }

    fun revealAll(root: View): View {
        val keys = mutableListOf<String>()
        fun collect(view: View) {
            (view.tag as? String)?.takeIf { it.startsWith("section-content:") }
                ?.let { keys.add(it.substringAfter(':')) }
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(root)
        keys.forEach { reveal(root, it) }
        return root
    }

    fun draw(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    fun find(root: View, id: Int): MutableList<View> {
        val result = mutableListOf<View>()
        if (root.id == id) result.add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) result.addAll(find(root.getChildAt(index), id))
        }
        return result
    }
}
