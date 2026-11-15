package app.noonlet

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import java.util.ArrayList
import org.junit.Assert.*
import org.robolectric.Shadows

internal object EditorTestInteractions {
    fun tap(activity: MainActivity, label: String) {
        tap(requireNotNull(activity.window).decorView, label)
    }

    fun tap(root: View, label: String) {
        var view: TextView? = text(root, label)
        if (view == null) {
            WidgetTestViews.revealAll(root)
            view = text(root, label)
        }
        assertNotNull("Missing label: " + label, view)
        tapView(root, view)
    }

    fun tapView(root: View, view: View?) {
        bringIntoView(root, view)
        clickable(view)!!.performClick()
    }

    fun bringIntoView(root: View, view: View?) {
        revealSection(root, view)
        val scroll: ScrollView? = first(root, ScrollView::class.java)
        if (scroll != null) scroll!!.setSmoothScrollingEnabled(false)
        view!!.requestRectangleOnScreen(Rect(0, 0, view!!.getWidth(), view!!.getHeight()), true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        WidgetTestViews.prepare(root)
        val visible: Rect = Rect()
        assertTrue(
            "Control is not reachable on the real screen: " + view!!,
            view!!.getGlobalVisibleRect(visible),
        )
    }

    fun revealSection(root: View, view: View?) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        var candidate: View? = view
        while (candidate != null) {
            val tag: Any? = candidate!!.getTag()
            if (
                !(tag is String) ||
                    !(tag as String).startsWith("section-content:") ||
                    candidate!!.getVisibility() == View.VISIBLE
            ) {
                candidate =
                    if (candidate!!.getParent() is View) candidate!!.getParent() as View
                    else null
                continue
            }
            val toggle: View =
                root.findViewWithTag<View>(
                    (tag as String).replace("section-content:", "section-toggle:"),
                )
            assertNotNull("Missing disclosure for " + tag, toggle)
            val scroll: ScrollView? = first(root, ScrollView::class.java)
            if (scroll != null) scroll!!.setSmoothScrollingEnabled(false)
            toggle.requestRectangleOnScreen(
                Rect(0, 0, toggle.getWidth(), toggle.getHeight()),
                true,
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "Section disclosure cannot be reached: " + tag,
                toggle.getGlobalVisibleRect(Rect()),
            )
            toggle.performClick()
            candidate =
                if (candidate!!.getParent() is View) candidate!!.getParent() as View else null
        }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        WidgetTestViews.prepare(root)
    }

    fun clickable(child: View?): View? {
        var view: View? = child
        while (!view!!.isClickable() && view!!.getParent() is View) view =
            view!!.getParent() as View
        assertTrue("No action for " + child!!, view!!.isClickable())
        return view
    }

    fun text(root: View, value: String): TextView? {
        val result: MutableList<TextView> = texts(root, value)
        return if (result.isEmpty()) null else result.get(0)
    }

    fun texts(root: View, value: String): MutableList<TextView> {
        val result: MutableList<TextView> = ArrayList()
        if (root is TextView && value.contentEquals((root as TextView).getText()))
            result.add(root as TextView)
        if (root is ViewGroup)
            for (i: Int in 0 until (root as ViewGroup).getChildCount()) result.addAll(
                texts((root as ViewGroup).getChildAt(i), value),
            )
        return result
    }

    fun <T : View> first(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup)
            for (i: Int in 0 until (root as ViewGroup).getChildCount()) {
                val found: T? = first((root as ViewGroup).getChildAt(i), type)
                if (found != null) return found
            }
        return null
    }
}
