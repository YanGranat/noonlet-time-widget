package app.noonlet

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Alarm offsets move both visible children while preserving the other rows' raster positions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AlarmOffsetTest {
    private lateinit var context: Context
    private lateinit var host: ActivityController<Activity>

    @Before
    fun prepare() {
        host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        context = host.get()
        context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        assertEquals(1f, context.resources.displayMetrics.density, 0f)
        assertEquals(1f, context.resources.configuration.fontScale, 0f)
    }

    @After
    fun closeHost() {
        host.close()
    }

    @Test
    fun signedOffsetsMoveIconAndLabelTogetherAndMatchCanonicalRendering() {
        for (orientation in arrayOf("vertical", "horizontal")) {
            val settings = settings(orientation)
            val current = render(settings)
            val baseline = capture(current)
            try {
                for ((dx, dy) in
                    arrayOf(
                        -11 to 0,
                        11 to 0,
                        0 to -9,
                        0 to 9,
                        -7 to 5,
                        8 to -6,
                    )) {
                    settings.alarmOffsetX = dx
                    settings.alarmOffsetY = dy
                    assertTrue(WidgetRenderer.updatePreviewAppearance(current, settings, false))
                    measure(current)
                    val actual = capture(current)
                    val canonical = capture(render(settings))
                    try {
                        val label = "$orientation alarm offset $dx,$dy"
                        assertTranslated(
                            "$label icon bounds",
                            baseline.iconBounds,
                            actual.iconBounds,
                            dx,
                            dy,
                        )
                        assertTranslated(
                            "$label text bounds",
                            baseline.labelBounds,
                            actual.labelBounds,
                            dx,
                            dy,
                        )
                        assertTranslated("$label icon", baseline.icon, actual.icon, dx, dy)
                        assertTranslated("$label text", baseline.label, actual.label, dx, dy)
                        assertEquals("$label changed time pixels", baseline.time, actual.time)
                        assertEquals("$label changed date pixels", baseline.date, actual.date)
                        assertTrue(
                            "$label fast appearance differs from a complete render",
                            canonical.bitmap.sameAs(actual.bitmap),
                        )
                    } finally {
                        actual.bitmap.recycle()
                        canonical.bitmap.recycle()
                    }
                }
            } finally {
                baseline.bitmap.recycle()
            }
        }
    }

    private fun settings(orientation: String) =
        WidgetSettings.defaults(false).apply {
            this.orientation = WidgetOrientation.fromWire(orientation)
            fontFamily = "sans-serif"
            fontWeight = 700
            dateFontWeight = 700
            textSize = 36
            dateSize = 14
            alarmSize = 18
            timeFormat = "'08:24'"
            dateFormat = "'DATE'"
            dateAlignment = WidgetAlignment.RIGHT
            alarmAlignment = WidgetAlignment.LEFT
            timeColor = Color.RED
            dateColor = Color.GREEN
            alarmColor = Color.BLUE
            paddingLeft = 32
            paddingRight = 32
            paddingTop = 32
            paddingBottom = 32
            timeOffsetX = 0
            timeOffsetY = 0
            dateOffsetX = 0
            dateOffsetY = 0
            alarmOffsetX = 0
            alarmOffsetY = 0
            showDate = true
            showAlarm = true
            showNoAlarm = true
            showAlarmIcon = true
            tapAction = "none"
        }

    private fun render(settings: WidgetSettings): View {
        val parent = FrameLayout(context)
        host.get().setContentView(parent)
        val root =
            WidgetRenderer.preview(context, -1, false, settings, WIDTH.toFloat(), HEIGHT.toFloat())
                .apply(context, parent)
        parent.addView(root, FrameLayout.LayoutParams(WIDTH, HEIGHT))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        measure(root)
        assertEquals(
            AppLocales.text(context, R.string.no_alarm),
            root.findViewById<TextView>(R.id.clock_alarm).text.toString(),
        )
        return root
    }

    private fun measure(root: View) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, WIDTH, HEIGHT)
        WidgetTestViews.prepare(root)
    }

    private fun capture(root: View): Frame {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val all = Rect(0, 0, WIDTH, HEIGHT)
        val iconBounds = bounds(root, R.id.clock_alarm_icon)
        val labelBounds = bounds(root, R.id.clock_alarm)
        return Frame(
            bitmap,
            ink("Time", pixels, Color.RED, all, 20),
            ink("Date", pixels, Color.GREEN, all, 20),
            ink("Alarm icon", pixels, Color.BLUE, iconBounds, 8),
            ink("Alarm text", pixels, Color.BLUE, labelBounds, 20),
            iconBounds,
            labelBounds,
        )
    }

    private fun bounds(root: View, id: Int): Rect {
        val child = root.findViewById<View>(id)
        var view = child
        var x = 0f
        var y = 0f
        while (view !== root) {
            x += view.left + view.translationX
            y += view.top + view.translationY
            view = view.parent as View
        }
        return Rect(
            Math.round(x),
            Math.round(y),
            Math.round(x) + child.width,
            Math.round(y) + child.height,
        )
    }

    private fun ink(
        name: String,
        pixels: IntArray,
        color: Int,
        bounds: Rect,
        minimum: Int,
    ): Map<Int, Int> {
        assertTrue(
            "The measured child lies outside the fixture",
            Rect(0, 0, WIDTH, HEIGHT).contains(bounds),
        )
        val result = HashMap<Int, Int>()
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            val index = y * WIDTH + x
            val pixel = pixels[index]
            if (Color.alpha(pixel) == 255 && (pixel and 0x00ffffff) == (color and 0x00ffffff))
                result[index] = pixel
        }
        assertTrue(
            "$name must contain visible opaque ink: ${result.size} in $bounds",
            result.size > minimum,
        )
        return result
    }

    private fun assertTranslated(message: String, baseline: Rect, actual: Rect, dx: Int, dy: Int) {
        val expected = Rect(baseline).apply { offset(dx, dy) }
        assertEquals(message, expected, actual)
    }

    private fun assertTranslated(
        message: String,
        baseline: Map<Int, Int>,
        actual: Map<Int, Int>,
        dx: Int,
        dy: Int,
    ) {
        val expected = baseline.mapKeys { (index, _) -> index + dy * WIDTH + dx }
        assertEquals(message, expected, actual)
    }

    private class Frame(
        val bitmap: Bitmap,
        val time: Map<Int, Int>,
        val date: Map<Int, Int>,
        val icon: Map<Int, Int>,
        val label: Map<Int, Int>,
        val iconBounds: Rect,
        val labelBounds: Rect,
    )

    companion object {
        private const val WIDTH = 400
        private const val HEIGHT = 230
    }
}
