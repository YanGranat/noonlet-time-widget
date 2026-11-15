package app.noonlet

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.SeekBar
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowAlertDialog

object DialogTestFixtures {
    fun <T : View> first(root: View, kind: Class<T>): T? {
        if (kind.isInstance(root)) return kind.cast(root)
        if (root is ViewGroup)
            for (i: Int in 0 until (root as ViewGroup).getChildCount()) {
                val found: T? = first((root as ViewGroup).getChildAt(i), kind)
                if (found != null) return found
            }
        return null
    }

    fun <T : View> all(root: View, kind: Class<T>): MutableList<T> {
        val result: MutableList<T> = java.util.ArrayList()
        if (kind.isInstance(root))
            result.add(kind.cast(root) ?: error("Expected matching view"))
        if (root is ViewGroup)
            for (i: Int in 0 until (root as ViewGroup).getChildCount()) result.addAll(
                all((root as ViewGroup).getChildAt(i), kind),
            )
        return result
    }

    fun choose(dialog: AlertDialog, position: Int) {
        val list: ListView = dialog.getListView()
        assertNotNull(list)
        list.performItemClick(
            list.getAdapter().getView(position, null, list),
            position,
            list.getAdapter().getItemId(position),
        )
    }

    fun layout(root: View, width: Int, maxHeight: Int): View {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        WidgetTestViews.prepare(root)
        return root
    }

    fun latest(): AlertDialog {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val dialog: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing())
        return dialog
    }

    @Throws(Exception::class)
    fun assertAppearance(dialog: AlertDialog, checkColorChannels: Boolean = false) {
        val background: android.graphics.drawable.Drawable =
            requireNotNull(dialog.window).getDecorView().getBackground()
        assertTrue(
            "Dialog must use the application surface",
            background is android.graphics.drawable.GradientDrawable,
        )
        val surface: android.graphics.drawable.GradientDrawable =
            background as android.graphics.drawable.GradientDrawable
        assertEquals(-0xe6e6e4, requireNotNull(surface.color).defaultColor)
        assertTrue(surface.getCornerRadius() >= 20)
        val width: Int = requireNotNull(dialog.window).getAttributes().width
        val root: View =
            layout(
                requireNotNull(dialog.window).getDecorView(),
                if (width > 0) Math.min(368, width) else 368,
                760,
            )
        assertTrue(root.getHeight() > 0)
        if (checkColorChannels) {
            val channels: MutableList<SeekBar> = all(root, SeekBar::class.java)
            assertEquals(4, channels.size)
            for (channel: SeekBar in channels) {
                val visible: android.graphics.Rect = android.graphics.Rect()
                assertTrue(
                    "Color channel needs an unnecessary scroll",
                    channel.getGlobalVisibleRect(visible),
                )
                assertEquals(channel.getHeight(), visible.height())
            }
        }
        WidgetTestViews.prepare(root)
    }

    /** Waits for an asynchronous fixture result, not for a product latency threshold. */
    fun awaitAppsLoaded(dialog: AlertDialog) {
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val adapter: ListAdapter = dialog.getListView().getAdapter()
            if (
                adapter.getCount() > 0 &&
                    !"Loading apps…".contentEquals(adapter.getItem(0).toString())
            )
                return
            Thread.yield()
        }
        fail("The controlled discovery did not publish a result")
    }
}
