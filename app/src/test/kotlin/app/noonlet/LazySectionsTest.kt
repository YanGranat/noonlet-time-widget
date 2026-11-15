package app.noonlet

import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class LazySectionsTest {
    @Test
    fun collapsedSectionsCreateControlsOnceOnDemandWithoutReplacingTheHeader() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = activity.findViewById<View>(android.R.id.content)
            val content = root.findViewWithTag<ViewGroup>("section-content:time")
            val header = root.findViewWithTag<View>("section-toggle:time")
            assertEquals(0, content.childCount)
            header.performClick()
            assertTrue(content.childCount > 0)
            val first = content.getChildAt(0)
            header.performClick()
            assertEquals(View.GONE, content.visibility)
            header.performClick()
            assertSame(header, root.findViewWithTag<View>("section-toggle:time"))
            assertSame(first, content.getChildAt(0))
            assertEquals(View.VISIBLE, content.visibility)
        }
    }
}
