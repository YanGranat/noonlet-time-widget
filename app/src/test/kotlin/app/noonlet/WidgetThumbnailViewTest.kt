package app.noonlet

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
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
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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

    @Test
    fun weatherSnapshotsStayInsideTheRoundedCardWhenResized() {
        val place = WeatherPlace("Sample Bay", 44_000_000, 3_000_000)
        val now = System.currentTimeMillis()
        File(context.noBackupFilesDir, "weather_cache.json").writeText(
            JSONObject().put("provider", WeatherClient.SOURCE).put("entries", JSONArray().put(
                JSONObject().put("place", place.encode()).put("temperatureC", 18.7)
                    .put("weatherCode", 3).put("isDay", true)
                    .put("validAt", now).put("fetchedAt", now))).toString(), Charsets.UTF_8)
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            for (size in listOf(13, 26)) {
                val settings = WidgetSettings.defaults(false).apply {
                    weatherEnabled = true
                    weatherUseDevice = false
                    weatherPlace = place.encode()
                    weatherTextSize = size
                }
                val thumbnail = WidgetThumbnailView(activity)
                thumbnail.bindConfiguration(SettingsArchive.Configuration(WidgetSize.FOUR_BY_ONE, settings), false)
                activity.setContentView(thumbnail)
                val content = thumbnail.getChildAt(0)
                for (width in listOf(400, 280, 400)) {
                    thumbnail.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.AT_MOST))
                    thumbnail.layout(0, 0, thumbnail.measuredWidth, thumbnail.measuredHeight)
                    val beforeCapture = if (content.parent != null) RectF(content.translationX,
                        content.translationY, content.translationX + content.width * content.scaleX,
                        content.translationY + content.height * content.scaleY) else null
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    val caption = content.findViewById<android.widget.TextView>(R.id.weather_caption)
                    assertTrue(caption.text.toString().contains("19"))
                    assertEquals(1, caption.layout.lineCount)
                    assertEquals(0, caption.layout.getEllipsisCount(0))
                    assertEquals(caption.text.length, caption.layout.getLineEnd(0))
                    assertTrue(caption.layout.getLineWidth(0) <= caption.layout.width)
                    val image = thumbnail.getChildAt(0) as ImageView
                    val bitmap = (image.drawable as BitmapDrawable).bitmap
                    val bounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                    image.imageMatrix.mapRect(bounds)
                    bounds.offset((image.left + image.paddingLeft).toFloat(),
                        (image.top + image.paddingTop).toFloat())
                    beforeCapture?.let {
                        assertEquals(it.left, bounds.left, 1f)
                        assertEquals(it.top, bounds.top, 1f)
                        assertEquals(it.right, bounds.right, 1f)
                        assertEquals(it.bottom, bounds.bottom, 1f)
                    }
                    assertTrue("The snapshot must clear the card's rounded edges", bounds.left >= 4f &&
                        bounds.top >= 4f && bounds.right <= thumbnail.width - 4f &&
                        bounds.bottom <= thumbnail.height - 4f)
                }
            }
        }
    }
}
