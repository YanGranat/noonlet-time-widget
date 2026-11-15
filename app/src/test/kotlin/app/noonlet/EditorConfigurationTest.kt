package app.noonlet

import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorConfigurationTest {
    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        for (name in listOf("noonlet_widgets", "MainActivity", "widget_drafts"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test fun refreshedSectionExposesItsExpandedStateOnce() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val heading = activity.findViewById<View>(android.R.id.content)
                .findViewWithTag<View>("section-toggle:time")
            heading.performClick()
            activity.session.settings.textSize = 61
            activity.refreshSectionSummary(EditorSection.TIME)
            assertEquals("Expanded", heading.stateDescription.toString())
            assertFalse(heading.contentDescription.toString().contains("Expanded", ignoreCase = true))
            assertTrue(heading.contentDescription.toString().contains("61"))
        }
    }

    @Test fun resizingKeepsPlaceSearchAndColorInputInTheirOpenDialogs() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            activity.placePicker.show(true, setOf("Europe/London")) { fail("Resize must not select a place") }
            val places = ShadowAlertDialog.getLatestAlertDialog()
            val search = places.window!!.decorView.findViewWithTag<EditText>("place-search")
            search.setText("Oslo")
            resize(activity, 460)
            assertTrue(places.isShowing)
            assertSame(search, places.window!!.decorView.findViewWithTag<EditText>("place-search"))
            assertEquals("Oslo", search.text.toString())
            places.dismiss()
            ColorPicker.show(activity, "Clock color", Color.WHITE) { fail("Resize must not save a color") }
            val color = ShadowAlertDialog.getLatestAlertDialog()
            val input = descendants(color.window!!.decorView).filterIsInstance<EditText>().single()
            input.setText("112233")
            resize(activity, 480)
            assertTrue(color.isShowing)
            assertEquals("112233", input.text.toString())
        }
    }

    private fun resize(activity: MainActivity, width: Int) {
        val resized = Configuration(activity.resources.configuration).apply { screenWidthDp = width }
        activity.onConfigurationChanged(resized)
    }

    private fun descendants(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) }
        else emptyList()
}
