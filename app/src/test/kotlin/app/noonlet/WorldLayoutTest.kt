package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.util.ArrayList
import java.util.Arrays
import java.util.Calendar
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

/** Checks compact card spacing and full-width city row geometry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorldLayoutTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
    }

    @Test
    @Throws(Exception::class)
    fun wideCardsKeepProportionalGapsAndOuterInsets() {
        val settings: WidgetSettings = WidgetSettings.defaults(true)
        settings.cities = Arrays.copyOf(CITIES, 2)
        settings.style = WorldClockStyle.LIGHT
        val root: View = render(settings, 328, 90)
        val cards: MutableList<View> = WidgetTestViews.find(root, R.id.world_card)
        val first: FloatArray = position(cards.get(0))
        val second: FloatArray = position(cards.get(1))
        assertEquals((8).toFloat(), (first[0]).toFloat(), (.01f).toFloat())
        assertEquals(
            (8).toFloat(),
            (328 - second[0] - cards.get(1).getWidth()).toFloat(),
            (.01f).toFloat(),
        )
        val gap: Float = second[0] - first[0] - cards.get(0).getWidth()
        assertEquals(
            "The card gap keeps a 12:228 proportion to the card width",
            12f / 228,
            gap / cards.get(0).getWidth(),
            .002f,
        )
        assertEquals(228f / 88, cards.get(0).getWidth().toFloat() / cards.get(0).getHeight(), .06f)
        val rows: View = root.findViewById<View>(R.id.world_rows)
        assertEquals(
            "Top and side spacing belong to the same surface",
            (8).toFloat(),
            (first[1] - rows.getTop()).toFloat(),
            (.01f).toFloat(),
        )
        WidgetTestViews.prepare(root)
    }

    @Test
    @Throws(Exception::class)
    fun fourByTwoUsesOneFullWidthStripForEachCity() {
        for (count: Int in 1..4) {
            val settings: WidgetSettings = WidgetSettings.defaults(true)
            settings.cities = Arrays.copyOf(CITIES, count)
            settings.style = WorldClockStyle.LIGHT
            val root: View = render(settings, 328, 174)
            val cards: MutableList<View> = WidgetTestViews.find(root, R.id.world_card)
            assertEquals(count, cards.size)
            for (city: Int in 0 until count) {
                val card: View = cards.get(city)
                val point: FloatArray = position(card)
                assertEquals((0).toFloat(), (point[0]).toFloat(), (.01f).toFloat())
                assertEquals(328, card.getWidth())
                if (city > 0) {
                    val previous: View = cards.get(city - 1)
                    assertEquals(
                        "Rows share one continuous surface",
                        (position(previous)[1] + previous.getHeight()).toFloat(),
                        (point[1]).toFloat(),
                        (.01f).toFloat(),
                    )
                }
                val name: TextView = card.findViewById<TextView>(R.id.city_name)
                val time: TextView = card.findViewById<TextView>(R.id.city_time)
                val date: TextView = card.findViewById<TextView>(R.id.city_date)
                assertEquals(
                    "Wide rows preserve the full selected city name",
                    0,
                    name.getLayout().getEllipsisCount(0),
                )
                assertTrue(
                    "Time stays on the right of the label column",
                    position(time)[0] > position(name)[0] + name.getWidth(),
                )
                assertTrue(
                    "A large allocation must not create enormous clock digits",
                    time.getTextSize() <= 36,
                )
                assertEquals(View.VISIBLE, date.getVisibility())
                assertEquals(0, date.getLayout().getEllipsisCount(0))
                assertTrue(date.getText().toString().contains(" "))
            }
            val dividers: MutableList<View> = WidgetTestViews.find(root, R.id.world_list_divider)
            assertEquals(
                (count - 1).toLong(),
                dividers.stream().filter({ v -> v.getVisibility() == View.VISIBLE }).count(),
            )
            assertEquals(
                "The filled surface keeps a 492:247 aspect ratio",
                492f / 247,
                328f / (cards.get(0).getHeight() * count),
                .06f,
            )
            WidgetTestViews.prepare(root)
        }
    }

    @Test
    fun topologyChangesOnlyWithEnoughWidthAndHeight() {
        val settings: WidgetSettings = WidgetSettings.defaults(true)
        for (count: Int in 1..4) {
            settings.cities = Arrays.copyOf(CITIES, count)
            for (size: IntArray in
                arrayOf<IntArray>(
                    intArrayOf(244, 136, 0),
                    intArrayOf(245, 135, 0),
                    intArrayOf(245, 136, 1),
                    intArrayOf(167, 174, 0),
                    intArrayOf(328, 90, 0),
                    intArrayOf(300, 150, 1),
                    intArrayOf(500, 136, 0),
                    intArrayOf(500, 250, 1),
                    intArrayOf(336, 140, 0),
                    intArrayOf(335, 140, 1),
                )) {
                val root: View = render(settings, size[0], size[1])
                val list: Boolean = root.findViewById<View>(R.id.world_list_divider) != null
                assertEquals(size[0].toString() + "x" + size[1], size[2] == 1, list)
                assertEquals(
                    "Only separated cards have a backdrop",
                    if (size[2] == 1) View.GONE else View.VISIBLE,
                    root.findViewById<View>(R.id.widget_background).visibility,
                )
                if (size[0] == 167) assertFalse("A narrow 2x2 must keep its compact topology", list)
                if (size[0] == 500 && size[1] == 136 && count == 2) {
                    val times: MutableList<View> = WidgetTestViews.find(root, R.id.city_time)
                    assertEquals(
                        "A large 4x1 keeps its cities side by side",
                        position(times.get(0))[1],
                        position(times.get(1))[1],
                        .01f,
                    )
                }
            }
        }
    }

    @Test
    fun joinedRowsKeepTheirOwnContourAndOpacityWithoutASecondFill() {
        for (count in 1..4) {
            val settings =
                WidgetSettings.defaults(true).apply {
                    cities = Arrays.copyOf(CITIES, count)
                    style = WorldClockStyle.LIGHT
                    worldDayBackgroundColor = Color.WHITE
                    worldDayTextColor = Color.TRANSPARENT
                    worldBackdropColor = Color.MAGENTA
                    worldBackdropCornerScale = 30
                    opacity = 50
                }
            for (cardScale in intArrayOf(40, 30)) {
                settings.worldCardCornerScale = cardScale
                settings.outline = true
                val enabled = render(settings, 328, 174)
                assertEquals(
                    View.GONE,
                    enabled.findViewById<View>(R.id.widget_background).visibility,
                )
                settings.outline = false
                val disabled = render(settings, 328, 174)
                assertTrue(
                    "Joined rows must retain the same contour and opacity with either backdrop preference",
                    WidgetTestViews.draw(enabled).sameAs(WidgetTestViews.draw(disabled)),
                )
            }
        }
    }

    @Test
    fun backdropVisibilityIsRestoredWhenTheSameHostReturnsToSeparatedCards() {
        for (count in 1..4) {
            val settings =
                WidgetSettings.defaults(true).apply {
                    cities = Arrays.copyOf(CITIES, count)
                    style = WorldClockStyle.LIGHT
                    worldDayTextColor = Color.TRANSPARENT
                    worldBackdropColor = Color.MAGENTA
                    worldCardCornerScale = 40
                    worldBackdropCornerScale = 30
                }
            val host = FrameLayout(context)
            val root =
                WidgetRenderer.preview(context, -9200, true, settings, 167f, 82f)
                    .apply(context, host)
            measure(host, root, 167, 82)
            for (outline in booleanArrayOf(true, false, true)) {
                settings.outline = outline
                for (size in
                    arrayOf(
                        intArrayOf(328, 174, View.GONE),
                        intArrayOf(328, 82, View.VISIBLE),
                        intArrayOf(167, 174, View.VISIBLE),
                        intArrayOf(328, 174, View.GONE),
                        intArrayOf(167, 82, View.VISIBLE),
                    )) {
                    val update =
                        WidgetRenderer.preview(
                            context,
                            -9200,
                            true,
                            settings,
                            size[0].toFloat(),
                            size[1].toFloat(),
                        )
                    update.reapply(context, root)
                    measure(host, root, size[0], size[1])
                    assertSame(root, host.getChildAt(0))
                    assertEquals(
                        if (outline) size[2] else View.GONE,
                        root.findViewById<View>(R.id.widget_background).visibility,
                    )
                    val freshHost = FrameLayout(context)
                    val fresh = update.apply(context, freshHost)
                    measure(freshHost, fresh, size[0], size[1])
                    assertTrue(
                        "Reapplied world surfaces must match a fresh host at the same size",
                        WidgetTestViews.draw(fresh).sameAs(WidgetTestViews.draw(root)),
                    )
                }
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun listRowsKeepIndependentDayNightFillsAndOnlyOutsideCorners() {
        val settings: WidgetSettings = WidgetSettings.defaults(true)
        settings.cities = arrayOf<String>("UTC", "Etc/GMT-12")
        settings.style = WorldClockStyle.AUTO
        settings.worldDayBackgroundColor = -0xddaa78
        settings.worldNightBackgroundColor = -0x99aabc
        settings.worldDayTextColor = -0xeeddcd
        settings.worldNightTextColor = -0x554434
        settings.opacity = 75
        val root: View = render(settings, 328, 174)
        val cards: MutableList<View> = WidgetTestViews.find(root, R.id.world_card)
        for (city: Int in 0 until cards.size) {
            val card: View = cards.get(city)
            val background: ImageView = card.findViewById<ImageView>(R.id.card_background)
            val hour: Int =
                Calendar.getInstance(TimeZone.getTimeZone(settings.cities[city]))
                    .get(Calendar.HOUR_OF_DAY)
            val day: Boolean = hour >= 7 && hour < 19
            assertEquals(
                if (day) settings.worldDayTextColor else settings.worldNightTextColor,
                (card.findViewById<View>(R.id.city_name) as TextView).getCurrentTextColor(),
            )
            assertEquals(191, background.getImageAlpha())
            val image: Bitmap = WidgetTestViews.draw(background)
            val middle: Int = image.getPixel(image.getWidth() / 2, image.getHeight() / 2)
            assertEquals(191, Color.alpha(middle))
            val expected: Int =
                if (day) settings.worldDayBackgroundColor else settings.worldNightBackgroundColor
            val tint: Int =
                org.robolectric.util.ReflectionHelpers.callInstanceMethod(
                    background.getColorFilter(),
                    "getColor",
                )
            assertEquals(
                "The requested tint is exact before alpha rasterization",
                expected,
                tint,
            )
            assertEquals(
                (Color.red(expected)).toFloat(),
                (Color.red(middle)).toFloat(),
                (1).toFloat(),
            )
            assertEquals(
                (Color.green(expected)).toFloat(),
                (Color.green(middle)).toFloat(),
                (1).toFloat(),
            )
            assertEquals(
                (Color.blue(expected)).toFloat(),
                (Color.blue(middle)).toFloat(),
                (1).toFloat(),
            )
            assertEquals(
                "Outside corners are rounded",
                0,
                Color.alpha(image.getPixel(0, if (city == 0) 0 else image.getHeight() - 1)),
            )
            assertTrue(
                "Adjacent rows meet at straight edges",
                Color.alpha(image.getPixel(0, if (city == 0) image.getHeight() - 1 else 0)) > 180,
            )
        }
        val firstDivider: ImageView = cards.get(0).findViewById<ImageView>(R.id.world_list_divider)
        assertEquals(1, firstDivider.getHeight())
        assertEquals(cards.get(0).getWidth() - 32, firstDivider.getWidth())
        WidgetTestViews.prepare(root)
    }

    @Test
    @Throws(Exception::class)
    fun russianWideRowsShowFullCityNamesAndLiveLongDates() {
        val previous: Locale = Locale.getDefault()
        val phone: Configuration = Configuration(Resources.getSystem().getConfiguration())
        try {
            val russian: Locale = Locale("ru")
            Locale.setDefault(russian)
            val changed: Configuration = Configuration(phone)
            changed.setLocale(russian)
            Resources.getSystem()
                .updateConfiguration(changed, Resources.getSystem().getDisplayMetrics())
            AppLocales.setLanguage(context, "ru")
            val settings: WidgetSettings = WidgetSettings.defaults(true)
            settings.cities = Arrays.copyOf(CITIES, 2)
            settings.style = WorldClockStyle.LIGHT
            val root: View = render(settings, 328, 174)
            for (card: View in WidgetTestViews.find(root, R.id.world_card)) {
                val name: TextView = card.findViewById<TextView>(R.id.city_name)
                val date: TextView = card.findViewById<TextView>(R.id.city_date)
                assertEquals(0, name.getLayout().getEllipsisCount(0))
                assertEquals(0, date.getLayout().getEllipsisCount(0))
                assertTrue(
                    (date as android.widget.TextClock)
                        .getFormat24Hour()
                        .toString()
                        .contains("EEEE"),
                )
                assertTrue(
                    "Russian city names retain their actual letters",
                    name.getText().toString().chars().anyMatch({ c -> c >= 0x0400 && c <= 0x04ff }),
                )
            }
            WidgetTestViews.prepare(root)
        } finally {
            Locale.setDefault(previous)
            Resources.getSystem()
                .updateConfiguration(phone, Resources.getSystem().getDisplayMetrics())
        }
    }

    @Test
    @Throws(Exception::class)
    fun denseListAndCompactStackKeepTimesAndFractionalOffsetsUnclipped() {
        val previous: TimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val settings: WidgetSettings = WidgetSettings.defaults(true)
            settings.cities =
                arrayOf<String>(
                    "Asia/Kathmandu",
                    "America/St_Johns",
                    "Pacific/Chatham",
                    "Australia/Eucla",
                )
            settings.hour24 = false
            settings.style = WorldClockStyle.LIGHT
            for (size: IntArray in
                arrayOf<IntArray>(
                    intArrayOf(245, 136),
                    intArrayOf(328, 174),
                    intArrayOf(167, 174),
                )) {
                val root: View = render(settings, size[0], size[1])
                for (card: View in WidgetTestViews.find(root, R.id.world_card)) {
                    val time: TextView = card.findViewById<TextView>(R.id.city_time)
                    val date: TextView = card.findViewById<TextView>(R.id.city_date)
                    assertTrue(time.getLayout().getLineWidth(0) <= time.getWidth() + 1)
                    assertEquals(View.VISIBLE, date.getVisibility())
                    assertEquals(
                        "Fractional offsets remain complete",
                        0,
                        date.getLayout().getEllipsisCount(0),
                    )
                    assertTrue(
                        date.getText().toString().contains(":45") ||
                            date.getText().toString().contains(":30"),
                    )
                }
                WidgetTestViews.prepare(root)
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    @Config(sdk = [34, 36])
    fun responsiveSizeOptionsChooseTheSameRowTopology() {
        val settings: WidgetSettings = WidgetSettings.defaults(true)
        settings.cities = Arrays.copyOf(CITIES, 2)
        WidgetSettingsStore.save(context, 9300, true, settings)
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        val provider: AppWidgetProviderInfo = AppWidgetProviderInfo()
        provider.provider = ComponentName(context, WorldWidgetProvider::class.java)
        Shadows.shadowOf(manager).addBoundWidget(9300, provider)
        for (size: IntArray in arrayOf<IntArray>(intArrayOf(328, 90), intArrayOf(328, 174))) {
            val options: Bundle = Bundle()
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 167)
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 82)
            val sizes: ArrayList<SizeF> = ArrayList()
            sizes.add(SizeF(size[0].toFloat(), size[1].toFloat()))
            options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes)
            manager.updateAppWidgetOptions(9300, options)
            val host: FrameLayout = FrameLayout(context)
            val root: View = WidgetRenderer.world(context, 9300).apply(context, host)
            measure(host, root, size[0], size[1])
            assertEquals(
                "Responsive size, not legacy fallback bounds, controls the topology",
                size[1] >= 136,
                root.findViewById<View>(R.id.world_list_divider) != null,
            )
        }
    }

    private fun render(settings: WidgetSettings, width: Int, height: Int): View {
        WidgetSettingsStore.save(context, 9200, true, settings)
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        val provider: AppWidgetProviderInfo = AppWidgetProviderInfo()
        provider.provider = ComponentName(context, WorldWidgetProvider::class.java)
        Shadows.shadowOf(manager).addBoundWidget(9200, provider)
        val options: Bundle = Bundle()
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        manager.updateAppWidgetOptions(9200, options)
        val host: FrameLayout = FrameLayout(context)
        val root: View = WidgetRenderer.world(context, 9200).apply(context, host)
        measure(host, root, width, height)
        return root
    }

    private fun measure(host: FrameLayout, root: View, width: Int, height: Int) {
        if (root.parent == null) host.addView(root)
        assertSame(host, root.parent)
        root.layoutParams = FrameLayout.LayoutParams(width, height)
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, width, height)
        WidgetTestViews.prepare(root)
    }

    companion object {
        private val CITIES: Array<String> =
            arrayOf<String>(
                "America/Los_Angeles",
                "Asia/Riyadh",
                "Europe/London",
                "Asia/Hong_Kong",
            )

        private fun position(view: View): FloatArray {
            val point: FloatArray = FloatArray(2)
            var item: View? = view
            while (item != null) {
                point[0] += item!!.getX()
                point[1] += item!!.getY()
                item = if (item!!.getParent() is View) item!!.getParent() as View else null
            }
            return point
        }
    }
}
