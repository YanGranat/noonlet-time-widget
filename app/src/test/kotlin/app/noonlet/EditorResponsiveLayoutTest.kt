package app.noonlet

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorResponsiveLayoutTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf("noonlet_app", "noonlet_widgets", "MainActivity", "widget_drafts", "widget_configuration"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h850dp-mdpi")
    fun largeTextKeepsPreviewSizesAndBothTabsFullyVisible() {
        RuntimeEnvironment.setFontScale(1.3f)
        AppLocales.setLanguage(context, "ru")
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val root = layout(controller.get(), 320)
            assertPreviewSizes(root)
            val tabs = root.findViewWithTag<LinearLayout>("widget-type-tabs")
            val clock = tabs.getChildAt(0) as TextView
            val world = tabs.getChildAt(1) as TextView
            assertEquals(clock.height, world.height)
            assertTrue(world.lineCount >= 2)
            for (index in 0 until tabs.childCount) {
                val tab = tabs.getChildAt(index) as TextView
                assertTrue("Tab text must fit its measured height",
                    tab.layout.height <= tab.height - tab.paddingTop - tab.paddingBottom)
                for (line in 0 until tab.lineCount)
                    assertEquals(0, tab.layout.getEllipsisCount(line))
            }
            assertTrue(tabs.height > 48)
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w1280dp-h800dp-land-mdpi")
    fun wideTallWindowsKeepControlsScrollableAndThePrimaryActionVisible() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val root = layout(controller.get(), 1280, 800)
            val scroll = requireNotNull(DialogTestFixtures.first(root, android.widget.ScrollView::class.java))
            assertTrue(scroll.height >= 160)
            val action = root.findViewWithTag<View>("action-add-widget")
            val visible = android.graphics.Rect()
            assertTrue(action.getGlobalVisibleRect(visible))
            assertEquals(action.height, visible.height())
            assertTrue(scroll.getChildAt(0).height > scroll.height)
        }
    }

    @Test
    fun safeInsetsIncludeTheDisplayCutoutWithoutAddingTheSystemBarsTwice() {
        val root = View(context)
        val insets = android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(0, 24, 0, 18))
            .setInsets(android.view.WindowInsets.Type.displayCutout(), android.graphics.Insets.of(38, 0, 0, 0))
            .build()
        AppInsets.apply(root, insets)
        assertEquals(38, root.paddingLeft)
        assertEquals(24, root.paddingTop)
        assertEquals(18, root.paddingBottom)
    }

    @Test
    fun standardTextKeepsCompactPreviewAndTabGeometry() {
        RuntimeEnvironment.setFontScale(1f)
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val root = layout(controller.get(), 400)
            assertPreviewSizes(root)
            val tabs = root.findViewWithTag<LinearLayout>("widget-type-tabs")
            assertEquals(48, tabs.height)
            for (index in 0 until tabs.childCount) {
                val tab = tabs.getChildAt(index) as TextView
                assertEquals(40, tab.height)
                assertEquals(1, tab.lineCount)
            }
        }
    }

    private fun assertPreviewSizes(root: View) {
        assertNull(root.findViewWithTag<View>("preview-label"))
        val card = root.findViewWithTag<LinearLayout>("preview-card")
        val stage = root.findViewWithTag<View>("preview-stage")
        val sizes = root.findViewWithTag<LinearLayout>("preview-sizes")
        assertSame(card, sizes.parent)
        assertEquals(stage.bottom, sizes.top)
        assertEquals(card.width, sizes.width)
        assertEquals(28, sizes.height)
        assertEquals(4, sizes.childCount)
        for (index in 0 until sizes.childCount) {
            val size = sizes.getChildAt(index) as TextView
            assertEquals(24, size.height)
            assertEquals(1, size.lineCount)
            assertEquals(0, size.layout.getEllipsisCount(0))
            assertTrue(size.paint.measureText(size.text.toString()) <= size.width)
        }
    }

    private fun layout(activity: MainActivity, width: Int, height: Int = 850): View {
        val root = activity.window.decorView
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            WidgetTestViews.prepare(root)
        }
        return root
    }
}
