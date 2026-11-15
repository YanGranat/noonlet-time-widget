package app.noonlet

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock
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
@Config(sdk = [26, 34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetCategoryPreviewTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf("noonlet_widgets", "widget_drafts", "widget_configuration", "noonlet_app"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    fun clockCategoryUsesTheUnplacedDraftWithoutChangingAnExistingWidget() {
        val id = 9261
        val manager = AppWidgetManager.getInstance(context)
        Shadows.shadowOf(manager).addBoundWidget(id, AppWidgetProviderInfo().apply {
            provider = BaseWidgetProvider.component(context, false)
            initialLayout = R.layout.clock_widget
        })
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 60)
        })
        WidgetSettingsStore.save(context, id, false,
            WidgetSettings.defaults(false).apply { textSize = 51 })
        WidgetSettingsStore.saveUnplaced(context, false,
            WidgetSettings.defaults(false).apply { textSize = 39 })
        val before = storedSettings()
        val hostOptions = Bundle(manager.getAppWidgetOptions(id))

        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val preview = WidgetThumbnailView(activity).apply { bindCategory(false) }
            assertEquals(0, preview.childCount)
            activity.setContentView(preview)
            measure(preview, 368)
            val content = nativeContent(preview)
            assertEquals(328, content.width)
            assertEquals(82, content.height)
            assertEquals(39f, content.findViewById<TextClock>(R.id.clock_time).textSize, .01f)
            val bitmap = capture(preview)
            assertEquals(328, bitmap.width)
            assertEquals(82, bitmap.height)

        }

        assertEquals(before, storedSettings())
        assertEquals(51, WidgetSettingsStore.load(context, id, false).textSize)
        assertArrayEquals(intArrayOf(id), manager.getAppWidgetIds(BaseWidgetProvider.component(context, false)))
        assertEquals(hostOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
            manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
        assertEquals(hostOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
            manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
    }

    @Test
    fun worldCategoryKeepsBothDraftPlacesReadableAtTheWidePreset() {
        val places = arrayOf("Europe/London", "Asia/Tokyo")
        WidgetSettingsStore.saveUnplaced(context, true,
            WidgetSettings.defaults(true).apply { cities = places })
        val before = storedSettings()
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val preview = WidgetThumbnailView(activity).apply { bindCategory(true) }
            activity.setContentView(preview)
            measure(preview, 368)
            val content = nativeContent(preview)
            val bitmap = capture(preview)
            val labels = descendants(content).filterIsInstance<TextView>().filter { it.id == R.id.city_name }
            assertEquals(listOf("London", "Tokyo"), labels.map { it.text.toString() })
            for (label in labels) {
                val layout = requireNotNull(label.layout)
                assertTrue(label.width > 0 && label.height > 0)
                assertTrue(layout.width > 0 && layout.height > 0)
                assertTrue(layout.width <= label.width - label.compoundPaddingLeft - label.compoundPaddingRight)
                assertTrue(layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                assertEquals(1, layout.lineCount)
                if (layout.getEllipsisCount(0) > 0) assertTrue(layout.getEllipsisStart(0) > 0)
            }
            val clocks = descendants(content).filterIsInstance<TextClock>().filter { it.id == R.id.city_time }
            assertEquals(places.toList(), clocks.map { it.timeZone })
            val dates = descendants(content).filterIsInstance<TextClock>().filter { it.id == R.id.city_date }
            assertEquals(places.size, dates.size)
            for (clock in clocks + dates) {
                assertEquals(View.VISIBLE, clock.visibility)
                assertTrue(clock.width > 0 && clock.height > 0)
                assertEquals(1, clock.layout.lineCount)
                assertEquals(0, clock.layout.getEllipsisCount(0))
                assertTrue(clock.layout.getLineWidth(0) <=
                    clock.width - clock.compoundPaddingLeft - clock.compoundPaddingRight)
            }
            assertEquals(328, bitmap.width)
        }
        assertEquals(before, storedSettings())
    }

    @Test
    fun categoryReattachesWithAFreshDraftAndCancelsItsDetachedCapture() {
        WidgetSettingsStore.saveUnplaced(context, false,
            WidgetSettings.defaults(false).apply { textSize = 39 })
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val parent = FrameLayout(activity)
            activity.setContentView(parent)
            val preview = WidgetThumbnailView(activity).apply { bindCategory(false) }
            parent.addView(preview)
            measure(preview, 368)
            assertEquals(39f, nativeContent(preview).findViewById<TextClock>(R.id.clock_time).textSize, .01f)
            parent.removeView(preview)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, preview.childCount)


            WidgetSettingsStore.saveUnplaced(context, false,
                WidgetSettings.defaults(false).apply { textSize = 44 })
            parent.addView(preview)
            measure(preview, 368)
            assertEquals(44f, nativeContent(preview).findViewById<TextClock>(R.id.clock_time).textSize, .01f)
            capture(preview)
            parent.removeView(preview)
            assertEquals(0, preview.childCount)
        }
    }

    @Test
    fun categoryRetainsNativeResolutionWhenItsViewportBecomesWider() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val preview = WidgetThumbnailView(activity).apply { bindCategory(false) }
            activity.setContentView(preview)
            measure(preview, 256)
            assertEquals(64, preview.height)
            val bitmap = capture(preview)
            assertEquals(328, bitmap.width)
            assertEquals(82, bitmap.height)
            measure(preview, 368)
            assertEquals(82, preview.height)
            assertSame(bitmap, (preview.getChildAt(0) as ImageView).let {
                (it.drawable as BitmapDrawable).bitmap
            })
        }
    }

    @Test
    fun placedBindingKeepsItsHostGeometryAfterACategoryPreview() {
        val id = 9262
        val manager = AppWidgetManager.getInstance(context)
        Shadows.shadowOf(manager).addBoundWidget(id, AppWidgetProviderInfo().apply {
            provider = BaseWidgetProvider.component(context, false)
            initialLayout = R.layout.clock_widget
        })
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        })
        WidgetSettingsStore.save(context, id, false, WidgetSettings.defaults(false))
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val preview = WidgetThumbnailView(activity).apply { bindCategory(false) }
            activity.setContentView(preview)
            measure(preview, 368)
            assertEquals(328, capture(preview).width)
            preview.bind(id, false)
            measure(preview, 368)
            assertEquals(150, nativeContent(preview).width)
            assertEquals(90, nativeContent(preview).height)
            val bitmap = capture(preview)
            assertEquals(150, bitmap.width)
            assertEquals(90, bitmap.height)
            assertEquals(90, preview.height)
        }
    }

    @Test
    fun galleryRebuildsItsPreviewsForTheCurrentTheme() {
        AppPreferences(context).setThemeMode(ThemeMode.DARK)
        Robolectric.buildActivity(WidgetTypeActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            var root = galleryContent(activity)
            val previous = root.findViewWithTag<WidgetThumbnailView>("widget-type-preview:clock")
            assertTrue(previous.getChildAt(0) is ImageView)
            AppPreferences(context).setThemeMode(ThemeMode.LIGHT)
            activity.onConfigurationChanged(Configuration(activity.resources.configuration))
            val updated = galleryContent(activity)
            assertNotSame(root, updated)
            assertEquals(0, previous.childCount)
            root = updated
            for (kind in listOf("clock", "world")) {
                val preview = root.findViewWithTag<WidgetThumbnailView>("widget-type-preview:$kind")
                assertTrue(preview.getChildAt(0) is ImageView)

            }
        }
    }

    private fun nativeContent(preview: WidgetThumbnailView): View =
        preview.getChildAt(0)

    private fun measure(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun capture(preview: WidgetThumbnailView): Bitmap {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, preview.childCount)
        return ((preview.getChildAt(0) as ImageView).drawable as BitmapDrawable).bitmap
    }

    private fun galleryContent(activity: Activity): View {
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 850)
        }
        layout()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        layout()
        return root
    }

    private fun storedSettings() = listOf("noonlet_widgets", "widget_drafts", "widget_configuration")
        .associateWith { context.getSharedPreferences(it, 0).all.toMap() }

    private fun descendants(root: View): List<View> = listOf(root) +
        if (root is ViewGroup) (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) }
        else emptyList()
}
