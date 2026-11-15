package app.noonlet

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.text.Layout
import android.view.View
import android.widget.ImageView
import android.widget.TextClock
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
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetThumbnailViewTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    fun snapshotIncludesTheWholeLocalizedDateAfterItsNativeTextChanges() {
        val id = 9231
        val manager = AppWidgetManager.getInstance(context)
        val info = AppWidgetProviderInfo().apply {
            provider = BaseWidgetProvider.component(context, false)
            initialLayout = R.layout.clock_widget
        }
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        WidgetSettingsStore.save(context, id, false, WidgetSettings.defaults(false))
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        })
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val thumbnail = WidgetThumbnailView(activity)
            activity.setContentView(thumbnail)
            thumbnail.bind(id, false)
            val content = thumbnail.getChildAt(0)
            val date = content.findViewById<TextClock>(R.id.clock_date)
            date.text = "1"
            thumbnail.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.AT_MOST))
            thumbnail.layout(0, 0, thumbnail.measuredWidth, thumbnail.measuredHeight)
            assertEquals("1", date.text.toString())
            val initialWidth = date.measuredWidth
            val initialContentWidth = content.width
            val initialContentHeight = content.height

            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertNotEquals("1", date.text.toString())
            val desiredWidth = Layout.getDesiredWidth(date.text, date.paint) +
                date.compoundPaddingLeft + date.compoundPaddingRight
            assertTrue("The localized date must be wider than the initial clock value", desiredWidth > initialWidth)
            assertTrue("The snapshot must contain the complete localized date", date.measuredWidth >= desiredWidth)
            assertEquals(0, date.layout.getEllipsisCount(0))
            assertEquals(initialContentWidth, content.width)
            assertEquals(initialContentHeight, content.height)
            assertNull(content.parent)
            assertEquals(1, thumbnail.childCount)
            assertTrue(thumbnail.getChildAt(0) is ImageView)
        }
    }
}
