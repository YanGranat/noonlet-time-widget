package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Measures rendered glyph motion on Android 11, where remote translations are unavailable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegacyWidgetTest {
    private lateinit var context: Context
    private var manager: AppWidgetManager? = null

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE).edit().clear().commit()
        manager = AppWidgetManager.getInstance(context)
        Locale.setDefault(Locale.ENGLISH)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val configuration: Configuration = Configuration(context.getResources().getConfiguration())
        configuration.fontScale = 1f
        context
            .getResources()
            .updateConfiguration(configuration, context.getResources().getDisplayMetrics())
        assertEquals(1f, context.getResources().getDisplayMetrics().density, 0f)
        assertEquals(1f, context.getResources().getConfiguration().fontScale, 0f)
    }

    @Test
    fun signedTimeOffsetsMoveActualGlyphsAndLeaveDateUnchanged() {
        for (orientation: String in arrayOf<String>("vertical", "horizontal")) {
            val settings: WidgetSettings = settings(orientation)
            val baseline: Render = render(settings)
            for (offset: IntArray in arrayOf<IntArray>(intArrayOf(7, -4), intArrayOf(-6, 5))) {
                settings.timeOffsetX = offset[0]
                settings.timeOffsetY = offset[1]
                val moved: Render = render(settings)
                assertTranslated(
                    "Time " + orientation,
                    baseline.time,
                    moved.time,
                    offset[0],
                    offset[1],
                )
                assertEquals("Time offset moved date " + orientation, baseline.date, moved.date)
                assertEquals("Time allocation changed", baseline.timeHeight, moved.timeHeight)
                assertEquals("Date allocation changed", baseline.dateHeight, moved.dateHeight)
                assertEquals(
                    0f,
                    moved.root!!.findViewById<View>(R.id.clock_time).getTranslationX(),
                    0f,
                )
                assertEquals(
                    0f,
                    moved.root!!.findViewById<View>(R.id.clock_time).getTranslationY(),
                    0f,
                )
            }
        }
    }

    @Test
    fun signedDateOffsetsMoveActualGlyphsAndLeaveTimeUnchanged() {
        for (orientation: String in arrayOf<String>("vertical", "horizontal")) {
            val settings: WidgetSettings = settings(orientation)
            val baseline: Render = render(settings)
            for (offset: IntArray in arrayOf<IntArray>(intArrayOf(8, 6), intArrayOf(-5, -4))) {
                settings.dateOffsetX = offset[0]
                settings.dateOffsetY = offset[1]
                val moved: Render = render(settings)
                assertTranslated(
                    "Date " + orientation,
                    baseline.date,
                    moved.date,
                    offset[0],
                    offset[1],
                )
                assertEquals("Date offset moved time " + orientation, baseline.time, moved.time)
                assertEquals("Date allocation changed", baseline.dateHeight, moved.dateHeight)
                assertEquals("Time allocation changed", baseline.timeHeight, moved.timeHeight)
                assertEquals(
                    0f,
                    moved.root!!.findViewById<View>(R.id.clock_date).getTranslationX(),
                    0f,
                )
                assertEquals(
                    0f,
                    moved.root!!.findViewById<View>(R.id.clock_date).getTranslationY(),
                    0f,
                )
            }
        }
    }

    private fun settings(orientation: String): WidgetSettings {
        val settings: WidgetSettings = WidgetSettings.defaults(false)
        settings.orientation = WidgetOrientation.fromWire(orientation)
        settings.fontFamily = "sans-serif"
        settings.timeFontWeight = 400
        settings.fontWeight = settings.timeFontWeight
        settings.dateFontWeight = settings.timeFontWeight
        settings.textSize = 36
        settings.dateSize = 14
        settings.timeFormat = "'08:24'"
        settings.dateFormat = "'Mon, 12 Jan'"
        settings.timeColor = Color.RED
        settings.dateColor = Color.GREEN
        settings.paddingBottom = 24
        settings.paddingTop = settings.paddingBottom
        settings.paddingRight = settings.paddingTop
        settings.paddingLeft = settings.paddingRight
        settings.dateAlignment = WidgetAlignment.LEFT
        settings.alignment = settings.dateAlignment
        settings.dateOffsetY = 0
        settings.dateOffsetX = settings.dateOffsetY
        settings.timeOffsetY = settings.dateOffsetX
        settings.timeOffsetX = settings.timeOffsetY
        settings.timeDateGap = 6
        settings.showDate = true
        settings.showAlarm = false
        settings.tapAction = "none"
        return settings
    }

    private fun render(settings: WidgetSettings): Render {
        val id: Int = 720
        WidgetSettingsStore.save(context, id, false, settings)
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider = ComponentName(context, ClockWidgetProvider::class.java)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        val options: Bundle = Bundle()
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, WIDTH)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, WIDTH)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, HEIGHT)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, HEIGHT)
        manager!!.updateAppWidgetOptions(id, options)
        val parent: FrameLayout = FrameLayout(context)
        val root: View = WidgetRenderer.clock(context, id).apply(context, parent)
        parent.addView(root, FrameLayout.LayoutParams(WIDTH, HEIGHT))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, WIDTH, HEIGHT)
        WidgetTestViews.prepare(root)
        val pixels: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(pixels))
        val result: Render = Render()
        result.root = root
        result.time = glyphBounds(pixels, true)
        result.date = glyphBounds(pixels, false)
        result.timeHeight = root.findViewById<View>(R.id.clock_time).getHeight()
        result.dateHeight = root.findViewById<View>(R.id.clock_date).getHeight()
        pixels.recycle()
        return result
    }

    private class Render {
        var root: View? = null
        var time: Rect? = null
        var date: Rect? = null
        var timeHeight: Int = 0
        var dateHeight: Int = 0
    }

    companion object {
        private val WIDTH: Int = 300
        private val HEIGHT: Int = 180

        private fun glyphBounds(bitmap: Bitmap, time: Boolean): Rect {
            val result: Rect = Rect(bitmap.getWidth(), bitmap.getHeight(), -1, -1)
            var count: Int = 0
            for (y: Int in 0 until bitmap.getHeight()) {
                for (x: Int in 0 until bitmap.getWidth()) {
                    val color: Int = bitmap.getPixel(x, y)
                    val matches: Boolean =
                        (Color.alpha(color) > 20 &&
                            Color.blue(color) < 30 &&
                            (if (time) Color.red(color) > 200 && Color.green(color) < 30
                            else Color.green(color) > 200 && Color.red(color) < 30))
                    if (matches) {
                        result.left = Math.min(result.left, x)
                        result.top = Math.min(result.top, y)
                        result.right = Math.max(result.right, x + 1)
                        result.bottom = Math.max(result.bottom, y + 1)
                        count++
                    }
                }
            }
            assertTrue(
                "No " + (if (time) "time" else "date") + " glyph pixels were rendered",
                count > 20,
            )
            return result
        }

        private fun assertTranslated(
            label: String,
            baseline: Rect?,
            moved: Rect?,
            dx: Int,
            dy: Int,
        ) {
            var label = label
            label += " " + baseline + " -> " + moved + " offset=" + dx + "," + dy
            assertEquals(label + " X", baseline!!.left + dx, moved!!.left)
            assertEquals(label + " Y", baseline!!.top + dy, moved!!.top)
            assertEquals(label + " changed glyph width", baseline!!.width(), moved!!.width())
            assertEquals(label + " changed glyph height", baseline!!.height(), moved!!.height())
        }
    }
}
