package app.noonlet

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextClock
import android.widget.TextView
import java.lang.reflect.Method
import java.time.Instant
import java.util.ArrayList
import java.util.Locale
import java.util.TimeZone
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
class WidgetIntegrationTest {
    @Test
    @Config(sdk = [26, 34, 36])
    fun legacyHostOptionsProvideIndependentPortraitAndLandscapeLayouts() {
        bind(592, false, 150, 110)
        WidgetSettingsStore.save(context, 592, false, WidgetSettings().apply { textSize = 65 })
        manager!!.updateAppWidgetOptions(592, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 220)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 70)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
        })
        val views = WidgetRenderer.clock(context, 592)
        fun apply(orientation: Int, width: Int, height: Int): View {
            RuntimeEnvironment.setQualifiers(if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                "+land" else "+port")
            val controller = Robolectric.buildActivity(Activity::class.java)
            controller.setup().visible()
            val host = controller.get()
            val parent = FrameLayout(host)
            host.setContentView(parent)
            val root = views.apply(host, parent)
            parent.addView(root, FrameLayout.LayoutParams(width, height))
            repeat(2) {
                parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                parent.layout(0, 0, width, height)
                WidgetTestViews.prepare(root)
            }
            return root
        }
        val portrait = apply(android.content.res.Configuration.ORIENTATION_PORTRAIT, 150, 110)
        val landscape = apply(android.content.res.Configuration.ORIENTATION_LANDSCAPE, 220, 70)
        assertTrue(landscape.findViewById<TextClock>(R.id.clock_time).textSize <
            portrait.findViewById<TextClock>(R.id.clock_time).textSize)
        for (clock in find(landscape, R.id.clock_time, TextClock::class.java)) assertFits(landscape, clock)
        for (date in find(landscape, R.id.clock_date, TextClock::class.java)) assertFits(landscape, date)
    }

    @Test
    fun worldCardLeavesItsLiveClockTextAvailableToAccessibility() {
        val settings = WidgetSettings.defaults(true).apply { cities = arrayOf("Asia/Tokyo") }
        val views = WidgetRenderer.preview(context, -31, true, settings, 240f, 90f)
        val root = views.apply(context, FrameLayout(context))
        val card = WidgetTestViews.find(root, R.id.world_card).single()
        assertNull(card.contentDescription)
        assertEquals(View.VISIBLE, card.findViewById<View>(R.id.city_time).visibility)
        assertEquals(View.VISIBLE, card.findViewById<View>(R.id.city_date).visibility)
        assertFalse(card.findViewById<TextClock>(R.id.city_time).format24Hour.isNullOrEmpty())
    }

    private lateinit var context: Context
    private var manager: AppWidgetManager? = null

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE).edit().clear().commit()
        manager = AppWidgetManager.getInstance(context)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.ENGLISH)
    }

    @Test
    fun placedWidgetsKeepIndependentSettings() {
        val first: WidgetSettings = WidgetSettings()
        first.zoneId = "Europe/London"
        first.cities = arrayOf<String>("Europe/London", "Asia/Kathmandu")
        WidgetSettingsStore.save(context, 101, true, first)
        val second: WidgetSettings = WidgetSettings()
        second.zoneId = "Asia/Tokyo"
        second.cities = arrayOf<String>("Asia/Tokyo")
        WidgetSettingsStore.save(context, 102, true, second)
        first.cities[0] = "America/New_York"
        WidgetSettingsStore.save(context, 0, true, first)
        assertArrayEquals(
            arrayOf<String>("Europe/London", "Asia/Kathmandu"),
            WidgetSettingsStore.load(context, 101, true).cities,
        )
        WidgetSettingsStore.delete(context, 101, true)
        assertFalse(WidgetSettingsStore.hasOwnSettings(context, 101, true))
        assertEquals("Asia/Tokyo", WidgetSettingsStore.load(context, 102, true).zoneId)
    }

    @Test
    fun localClockUsesSystemZoneAndChosenHourFormat() {
        val settings: WidgetSettings = WidgetSettings()
        settings.hour24 = false
        settings.timeFormat = "h:mm a"
        settings.showDate = false
        WidgetSettingsStore.save(context, 201, false, settings)
        bind(201, false, 130, 70)
        val view: View = inflate(false, 201, 130, 70)
        val clock: TextClock = view.findViewById<TextClock>(R.id.clock_time)
        assertNull(clock.getTimeZone())
        assertEquals("h:mm a", clock.getFormat12Hour().toString())
        assertEquals("h:mm a", clock.getFormat24Hour().toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.clock_date).getVisibility())
    }

    @Test
    @Throws(Exception::class)
    fun clockFitsSmallAndWideLauncherCells() {
        WidgetSettingsStore.save(context, 202, false, WidgetSettings())
        for (size: IntArray in
            arrayOf<IntArray>(
                intArrayOf(130, 70),
                intArrayOf(150, 90),
                intArrayOf(269, 51),
                intArrayOf(300, 110),
            )) {
            bind(202, false, size[0], size[1])
            val view: View = inflate(false, 202, size[0], size[1])
            assertFits(view, view.findViewById<TextView>(R.id.clock_time))
            assertFits(view, view.findViewById<TextView>(R.id.clock_date))
            WidgetTestViews.prepare(view)
        }
    }

    @Test
    @Throws(Exception::class)
    fun worldLayoutsInflateAllCitiesAtSupportedSizes() {
        val zones: Array<String> =
            arrayOf<String>(
                "America/Los_Angeles",
                "Asia/Riyadh",
                "Europe/London",
                "Asia/Kathmandu",
            )
        for (count: Int in 1..4) {
            val settings: WidgetSettings = WidgetSettings()
            settings.cities = java.util.Arrays.copyOf(zones, count)
            settings.style = WorldClockStyle.LIGHT
            WidgetSettingsStore.save(context, 300 + count, true, settings)
            for (size: IntArray in
                arrayOf<IntArray>(
                    intArrayOf(150, if (count == 1) 80 else 174),
                    intArrayOf(300, if (count <= 2) 90 else 174),
                )) {
                bind(300 + count, true, size[0], size[1])
                val view: View = inflate(true, 300 + count, size[0], size[1])
                val clocks: MutableList<TextClock> =
                    find(view, R.id.city_time, TextClock::class.java)
                assertEquals(count, clocks.size)
                assertEquals(count, find(view, R.id.city_name, TextView::class.java).size)
                for (index: Int in 0 until clocks.size) {
                    assertEquals(zones[index], clocks.get(index).getTimeZone())
                    assertFits(view, clocks.get(index))
                }
                WidgetTestViews.prepare(view)
            }
        }
    }

    @Test
    fun worldOffsetsUseDateSpecificDstAndFractionalOffsets() {
        val january: Long = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()
        val july: Long = Instant.parse("2026-07-15T12:00:00Z").toEpochMilli()
        val utc: TimeZone = TimeZone.getTimeZone("UTC")
        assertEquals(
            "+00",
            WidgetRenderer.offsetLabel(TimeZone.getTimeZone("Europe/London"), utc, january),
        )
        assertEquals(
            "+01",
            WidgetRenderer.offsetLabel(TimeZone.getTimeZone("Europe/London"), utc, july),
        )
        assertEquals(
            "+05:45",
            WidgetRenderer.offsetLabel(TimeZone.getTimeZone("Asia/Kathmandu"), utc, july),
        )
        assertEquals(
            "\u221207",
            WidgetRenderer.offsetLabel(TimeZone.getTimeZone("America/Los_Angeles"), utc, july),
        )
    }

    @Test
    fun compactWorldGridKeepsReadableNamesAndCompleteOffsets() {
        val settings: WidgetSettings = WidgetSettings()
        settings.cities =
            arrayOf<String>("Europe/London", "Asia/Riyadh", "Asia/Tokyo", "Asia/Dubai")
        WidgetSettingsStore.save(context, 350, true, settings)
        bind(350, true, 300, 135)
        val view: View = inflate(true, 350, 300, 135)
        for (name: TextView in find(view, R.id.city_name, TextView::class.java)) {
            assertEquals(1, name.getLayout().getLineCount())
            assertTrue(
                "City names must retain a readable text size",
                ((name.getTextSize() / context.getResources().getDisplayMetrics().density) >= 14),
            )
            if (name.getLayout().getEllipsisCount(0) > 0)
                assertTrue(
                    "A visible city prefix must remain before the ellipsis",
                    name.getLayout().getEllipsisStart(0) >= 2,
                )
        }
        for (date: TextClock in find(view, R.id.city_date, TextClock::class.java)) assertEquals(
            "Date and offset clipped: " + date.getText(),
            0,
            date.getLayout().getEllipsisCount(0),
        )
        for (height: Int in intArrayOf(75, 120)) {
            bind(350, true, 130, height)
            val compact: View = inflate(true, 350, 130, height)
            for (clock: TextClock in
                find(compact, R.id.city_time, TextClock::class.java)) assertFits(compact, clock)
        }
    }

    @Test
    fun restoringOverlappingWidgetIdsPreservesBothConfigurations() {
        val first: WidgetSettings = WidgetSettings()
        first.zoneId = "Asia/Tokyo"
        WidgetSettingsStore.save(context, 801, false, first)
        val second: WidgetSettings = WidgetSettings()
        second.zoneId = "Europe/London"
        WidgetSettingsStore.save(context, 802, false, second)
        val otherProvider: WidgetSettings = WidgetSettings()
        otherProvider.cities = arrayOf<String>("Asia/Kathmandu")
        WidgetSettingsStore.save(context, 801, true, otherProvider)
        bind(802, false, 150, 90)
        bind(803, false, 150, 90)
        ClockWidgetProvider().onRestored(context, intArrayOf(801, 802), intArrayOf(802, 803))
        assertEquals("Asia/Tokyo", WidgetSettingsStore.load(context, 802, false).zoneId)
        assertEquals("Europe/London", WidgetSettingsStore.load(context, 803, false).zoneId)
        assertArrayEquals(
            arrayOf<String>("Asia/Kathmandu"),
            WidgetSettingsStore.load(context, 801, true).cities,
        )
        assertTrue(
            manager!!
                .getAppWidgetOptions(802)
                .getBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED),
        )
    }

    @Test
    fun worldRestoreAlsoHandlesOverlapAndPreservesClockSettings() {
        val first: WidgetSettings = WidgetSettings()
        first.cities = arrayOf<String>("Asia/Tokyo")
        WidgetSettingsStore.save(context, 901, true, first)
        val second: WidgetSettings = WidgetSettings()
        second.cities = arrayOf<String>("Europe/London")
        WidgetSettingsStore.save(context, 902, true, second)
        val otherProvider: WidgetSettings = WidgetSettings()
        otherProvider.zoneId = "Asia/Kathmandu"
        WidgetSettingsStore.save(context, 901, false, otherProvider)
        bind(902, true, 150, 90)
        bind(903, true, 150, 90)
        WorldWidgetProvider().onRestored(context, intArrayOf(901, 902), intArrayOf(902, 903))
        assertArrayEquals(
            arrayOf<String>("Asia/Tokyo"),
            WidgetSettingsStore.load(context, 902, true).cities,
        )
        assertArrayEquals(
            arrayOf<String>("Europe/London"),
            WidgetSettingsStore.load(context, 903, true).cities,
        )
        assertEquals("Asia/Kathmandu", WidgetSettingsStore.load(context, 901, false).zoneId)
        assertTrue(
            manager!!
                .getAppWidgetOptions(903)
                .getBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED),
        )
    }

    @Test
    @Throws(Exception::class)
    fun configurationStartsCancelledAndSavesBeforeReturningOk() {
        bind(401, false, 150, 90)
        val intent: Intent =
            Intent(context, MainActivity::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 401)
        Robolectric.buildActivity(MainActivity::class.java, intent)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    assertEquals(
                        Activity.RESULT_CANCELED,
                        Shadows.shadowOf(activity).getResultCode(),
                    )
                    (activity.session.settings).zoneId = "Asia/Tokyo"
                    WidgetTestViews.completeEditor(activity)
                    assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode())
                    assertEquals(
                        401,
                        Shadows.shadowOf(activity)
                            .getResultIntent()
                            .getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0),
                    )
                    assertEquals("Asia/Tokyo", WidgetSettingsStore.load(context, 401, false).zoneId)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun restoredConfigurationKeepsCapturedDraftInsteadOfLaterDefaults() {
        bind(501, true, 150, 90)
        val snapshot: Bundle = Bundle()
        snapshot.putString("cfg_style", "dark")
        snapshot.putStringArray("cfg_cities", arrayOf<String>("Asia/Kathmandu"))
        snapshot.putInt("cfg_opacity", 75)
        val defaults: WidgetSettings = WidgetSettings()
        defaults.cities = arrayOf<String>("Europe/London")
        WidgetSettingsStore.save(context, 0, true, defaults)
        snapshot.putBoolean("world", true)
        val configure: Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 501)
                .putExtra("world", true)
        Robolectric.buildActivity(MainActivity::class.java, configure)
            .setup(snapshot)
            .use(
                { controller ->
                    WidgetTestViews.completeEditor(controller.get())
                    assertEquals(
                        Activity.RESULT_OK,
                        Shadows.shadowOf(controller.get()).getResultCode(),
                    )
                },
            )
        val pinned: WidgetSettings = WidgetSettingsStore.load(context, 501, true)
        assertArrayEquals(arrayOf<String>("Asia/Kathmandu"), pinned.cities)
        assertEquals(WorldClockStyle.DARK, pinned.style)
        assertEquals(75, pinned.opacity)
    }

    @Test
    @Throws(Exception::class)
    fun responsiveVariantsStayWithinBitmapMemoryBudget() {
        val settings: WidgetSettings = WidgetSettings()
        settings.cities =
            arrayOf<String>("America/New_York", "Asia/Hong_Kong", "Europe/London", "Asia/Riyadh")
        WidgetSettingsStore.save(context, 550, true, settings)
        bind(550, true, 300, 150)
        val sizes: ArrayList<SizeF> = ArrayList()
        for (index: Int in 0..15) sizes.add(
            SizeF((150 + index * 30).toFloat(), (100 + index * 20).toFloat()),
        )
        val options: Bundle = Bundle()
        options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes)
        manager!!.updateAppWidgetOptions(550, options)
        val views: RemoteViews = WidgetRenderer.world(context, 550)
        val estimate: Method = RemoteViews::class.java.getDeclaredMethod("estimateMemoryUsage")
        estimate.setAccessible(true)
        val bytes: Long = (estimate.invoke(views) as Number).toLong()
        assertTrue("Widget bitmap payload too large: " + bytes, bytes <= 1100000)
        assertNotNull(views.apply(context, FrameLayout(context)))
    }

    @Test
    @Throws(Exception::class)
    fun launcherAssignedIdReceivesSavedConfigurationWithoutDefaultsWrites() {
        bind(502, false, 150, 90)
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit()
        val configure: Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 502)
        Robolectric.buildActivity(MainActivity::class.java, configure)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    (activity.session.settings).zoneId = "Asia/Kathmandu"
                    WidgetTestViews.completeEditor(activity)
                    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    val ids: IntArray =
                        manager!!.getAppWidgetIds(
                            ComponentName(context, ClockWidgetProvider::class.java),
                        )
                    assertEquals(1, ids.size)
                    assertEquals(
                        "Asia/Kathmandu",
                        WidgetSettingsStore.load(context, ids[0], false).zoneId,
                    )
                    assertEquals(502, ids[0])
                    assertEquals("", WidgetSettingsStore.load(context, 0, false).zoneId)
                },
            )
    }

    @Test
    fun largeTextRemainsInsideCompactWidgets() {
        RuntimeEnvironment.setFontScale(1.3f)
        WidgetSettingsStore.save(context, 701, false, WidgetSettings())
        bind(701, false, 130, 70)
        val clock: View = inflate(false, 701, 130, 70)
        assertFits(clock, clock.findViewById<TextView>(R.id.clock_time))
        val world: WidgetSettings = WidgetSettings()
        world.cities = arrayOf<String>("America/Los_Angeles", "Asia/Riyadh")
        WidgetSettingsStore.save(context, 702, true, world)
        bind(702, true, 150, 174)
        val cards: View = inflate(true, 702, 150, 174)
        for (text: TextClock in find(cards, R.id.city_time, TextClock::class.java)) assertFits(
            cards,
            text,
        )
    }

    private fun bind(id: Int, world: Boolean, width: Int, height: Int) {
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider =
            ComponentName(
                context,
                if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
            )
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        val options: Bundle = Bundle()
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        manager!!.updateAppWidgetOptions(id, options)
    }

    private fun inflate(world: Boolean, id: Int, width: Int, height: Int): View {
        val parent: FrameLayout = FrameLayout(context)
        val view: View =
            (if (world) WidgetRenderer.world(context, id) else WidgetRenderer.clock(context, id))
                .apply(context, parent)
        parent.addView(view, FrameLayout.LayoutParams(width, height))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, width, height)
        return view
    }

    private fun assertFits(root: View, text: TextView) {
        text.onPreDraw()
        assertTrue("Text has no horizontal space: id=${text.id}, text=${text.text}", text.getWidth() > 0)
        assertTrue("Text has no vertical space", text.getHeight() > 0)
        val bounds: android.graphics.Rect = android.graphics.Rect()
        text.getDrawingRect(bounds)
        (root as ViewGroup).offsetDescendantRectToMyCoords(text, bounds)
        val layout: android.text.Layout = text.getLayout()
        assertNotNull(layout)
        val glyphLeft: Float =
            bounds.left + text.getCompoundPaddingLeft() + layout.getLineLeft(0) - text.getScrollX()
        val glyphRight: Float =
            bounds.left + text.getCompoundPaddingLeft() + layout.getLineRight(0) - text.getScrollX()
        val paint = android.text.TextPaint(text.paint)
        val value = text.text
        if (value is android.text.Spanned)
            value.getSpans(0, value.length, android.text.style.MetricAffectingSpan::class.java)
                .forEach { it.updateMeasureState(paint) }
        val ink = android.graphics.Rect()
        paint.getTextBounds(value.toString(), 0, value.length, ink)
        ink.offset(0, text.baseline - text.scrollY)
        assertTrue("Visible text clipped within its view: $ink", ink.top >= 0 && ink.bottom <= text.height)
        ink.offset(bounds.left, bounds.top)
        assertTrue("Text below widget: $ink", ink.bottom <= root.height + 1)
        assertTrue(
            "Glyphs right of widget: " +
                text.getText() +
                " right=" +
                glyphRight +
                " root=" +
                root.getWidth() +
                " view=" +
                bounds +
                " scroll=" +
                text.getScrollX(),
            glyphRight <= root.getWidth() + 1,
        )
        assertTrue("Text before widget: $ink", glyphLeft >= -1 && ink.top >= -1)
        assertTrue(
            "Clock glyphs clipped: " + text.getText(),
            layout.getLineWidth(0) <= text.getWidth() + 1,
        )
    }

    private fun <T : View> find(view: View, id: Int, type: Class<T>): MutableList<T> {
        val result: MutableList<T> = ArrayList()
        if (view.getId() == id) result.add(requireNotNull(type.cast(view)))
        if (view is ViewGroup) {
            val group: ViewGroup = view as ViewGroup
            for (index: Int in 0 until group.getChildCount()) result.addAll(
                find(group.getChildAt(index), id, type),
            )
        }
        return result
    }
}
