package app.noonlet

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class SectionFocusTest {
    @Test fun accessibleReorderKeepsInputFocusOnTheMovedPlace() {
        val context = RuntimeEnvironment.getApplication()
        WidgetSettingsStore.saveUnplaced(context, true, WidgetSettings.defaults(true).apply {
            cities = arrayOf("Europe/Oslo", "America/Lima", "Asia/Tokyo")
        })
        Robolectric.buildActivity(MainActivity::class.java, EditorIntents.create(context, true))
            .setup().visible().use { controller ->
                val activity = controller.get()
                val root = activity.findViewById<View>(android.R.id.content)
                root.findViewWithTag<View>("section-toggle:cities").performClick()
                val handle = root.findViewWithTag<View>("city-drag:Europe/Oslo")
                handle.isFocusableInTouchMode = true
                assertTrue(handle.requestFocus())
                val node = handle.createAccessibilityNodeInfo()
                assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
                assertTrue(handle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                assertArrayEquals(arrayOf("America/Lima", "Europe/Oslo", "Asia/Tokyo"), activity.session.settings.cities)
                assertEquals("city-drag:Europe/Oslo", root.findFocus()?.tag)
            }
    }
}
