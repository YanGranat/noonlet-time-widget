package app.noonlet

import android.content.res.Configuration
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
class WindowGeometryTest {
    @Test fun resizingReflowsExistingEditorWithoutReplacingPreviewOrControls() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val content = activity.findViewById<View>(android.R.id.content)
            val preview = activity.preview.stage!!
            val heading = content.findViewWithTag<View>("section-toggle:time")
            heading.performClick()
            val controls = content.findViewWithTag<View>("section:time")
            val input = EditText(activity).apply {
                setText("retained input")
            }
            val dialog = AppDialogBuilder(activity).setTitle("Input").setView(input).show()
            fun resize(width: Int, height: Int) {
                val configuration = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = width
                    screenHeightDp = height
                    orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                activity.onConfigurationChanged(configuration)
                content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, width, height)
            }
            fun scrollAncestor(view: View): ScrollView? {
                var parent = view.parent
                while (parent is View) {
                    if (parent is ScrollView) return parent
                    parent = parent.parent
                }
                return null
            }
            assertNull(scrollAncestor(preview))
            for ((width, height) in listOf(400 to 470, 1000 to 640, 400 to 850)) {
                if (height == 470) controller.pause()
                resize(width, height)
                if (height == 470) controller.resume().visible()
                assertSame(preview, activity.preview.stage)
                assertSame(controls, content.findViewWithTag("section:time"))
                assertTrue(dialog.isShowing)
                assertEquals("retained input", input.text.toString())
                val scroller = scrollAncestor(controls)!!
                assertTrue(scroller.height > 80)
                if (height < 720) assertSame(scroller, scrollAncestor(preview))
                else assertNull(scrollAncestor(preview))
                if (height < 720) assertTrue(preview.height < height / 2)
                val footer = content.findViewWithTag<View>("action-add-widget")
                assertTrue(footer.isShown)
                val bounds = android.graphics.Rect(0, 0, footer.width, footer.height)
                (content as android.view.ViewGroup).offsetDescendantRectToMyCoords(footer, bounds)
                assertTrue(bounds.top >= 0 && bounds.bottom <= content.height)
            }
            dialog.dismiss()
        }
    }
}
