package app.noonlet

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Looper
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextClock
import java.lang.reflect.Method
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Models the application's process locale independently of the launcher's phone locale. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewLocaleTest {
    @Test
    @Throws(Exception::class)
    fun phoneSymbolsAndPortableFontsSurviveDifferentApplicationLocales() {
        val context: Context = RuntimeEnvironment.getApplication()
        val previous: Locale = Locale.getDefault()
        val originalPhone: Configuration = Configuration(Resources.getSystem().getConfiguration())
        try {
            for (phone: Locale in
                arrayOf(
                    Locale.ENGLISH,
                    Locale("ru"),
                    Locale("uk"),
                    Locale.GERMAN,
                    Locale("es"),
                    Locale.FRENCH,
                    Locale.ITALIAN,
                    Locale("pl"),
                    Locale("pt"),
                    Locale("ar"),
                )) {
                val app: Locale = if (phone.equals(Locale.ENGLISH)) Locale("ru") else Locale.ENGLISH
                prepare(context, phone, app)
                Robolectric.buildActivity(MainActivity::class.java)
                    .setup()
                    .use(
                        { controller ->
                            val activity: MainActivity = controller.get()
                            val settings: WidgetSettings = settings(activity)
                            settings.zoneId = "America/Los_Angeles"
                            settings.timeFormat = "h:mm:ss a zz"
                            settings.dateFormat = "EEEE, d MMMM yyyy 'o''clock' z"
                            settings.fontFamily = "serif"
                            settings.timeFontWeight = 700
                            settings.dateFontWeight = 800
                            val instant: Instant = Instant.parse("2026-07-06T00:00:59.250Z")
                            setTime(activity, instant)
                            render(activity)
                            val time: TextClock =
                                preview(activity).findViewById<TextClock>(R.id.clock_time)
                            val date: TextClock =
                                preview(activity)
                                    .findViewById<android.widget.TextClock>(R.id.clock_date)
                            assertEquals(
                                nativePhone(settings.timeFormat, instant, settings.zoneId, phone),
                                time.getText().toString(),
                            )
                            assertEquals(
                                nativePhone(settings.dateFormat, instant, settings.zoneId, phone),
                                date.getText().toString(),
                            )
                            assertEquals(700, WidgetTestViews.weight(time))
                            assertEquals(800, WidgetTestViews.weight(date))
                            assertEquals(WidgetFont.typeface("serif", 700), WidgetTestViews.typeface(time))
                            assertEquals(WidgetFont.typeface("serif", 800), WidgetTestViews.typeface(date))
                            assertEquals(
                                "Preview localization must not change the process locale",
                                app,
                                Locale.getDefault(),
                            )
                            WidgetSettingsStore.save(context, 3501, false, settings)
                            val launcher: View =
                                WidgetRenderer.clock(context, 3501)
                                    .apply(context, FrameLayout(context))
                            assertEquals("serif", appearance(launcher.findViewById(R.id.clock_time)).family)
                            assertEquals("serif", appearance(launcher.findViewById(R.id.clock_date)).family)
                            assertEquals(
                                "The placed clock keeps its live native pattern",
                                settings.timeFormat,
                                (launcher.findViewById<TextClock>(R.id.clock_time) as TextClock)
                                    .getFormat24Hour()
                                    .toString(),
                            )
                            assertEquals(
                                settings.dateFormat,
                                (launcher.findViewById<android.widget.TextClock>(R.id.clock_date)
                                        as TextClock)
                                    .getFormat24Hour()
                                    .toString(),
                            )
                        },
                    )
            }
        } finally {
            Resources.getSystem()
                .updateConfiguration(originalPhone, Resources.getSystem().getDisplayMetrics())
            Locale.setDefault(previous)
        }
    }

    @Test
    @Throws(Exception::class)
    fun previewTicksAlignToSecondsOrMinutesAndStopWhilePaused() {
        val context: Context = RuntimeEnvironment.getApplication()
        val previous: Locale = Locale.getDefault()
        val originalPhone: Configuration = Configuration(Resources.getSystem().getConfiguration())
        try {
            prepare(context, Locale.ENGLISH, Locale("ru"))
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val settings: WidgetSettings = settings(activity)
                        settings.zoneId = "Asia/Kathmandu"
                        settings.timeFormat = "HH:mm:ss"
                        var now: Instant = Instant.parse("2026-10-26T18:14:59.250Z")
                        setTime(activity, now)
                        render(activity)
                        var old: TextClock =
                            preview(activity).findViewById<TextClock>(R.id.clock_time)
                        setTime(activity, now.plusMillis(750))
                        idle(750)
                        var current: TextClock =
                            preview(activity).findViewById<TextClock>(R.id.clock_time)
                        assertSame(
                            "A clock tick updates the existing view without reinflation",
                            old,
                            current,
                        )
                        assertEquals(
                            nativePhone(
                                settings.timeFormat,
                                now.plusMillis(750),
                                settings.zoneId,
                                Locale.ENGLISH,
                            ),
                            current.getText().toString(),
                        )
                        controller.pause()
                        old = current
                        val pausedText: String = current.getText().toString()
                        setTime(activity, now.plusSeconds(4))
                        idle(3000)
                        assertSame(
                            "A paused editor must stop its own clock timer",
                            old,
                            preview(activity).findViewById<TextClock>(R.id.clock_time),
                        )
                        assertEquals(pausedText, old.getText().toString())
                        controller.resume()
                        current = preview(activity).findViewById<TextClock>(R.id.clock_time)
                        assertSame(old, current)
                        assertEquals(
                            nativePhone(
                                settings.timeFormat,
                                now.plusSeconds(4),
                                settings.zoneId,
                                Locale.ENGLISH,
                            ),
                            current.getText().toString(),
                        )
                        assertNull(
                            "The preview is always open",
                            activity
                                .getWindow()
                                .getDecorView()
                                .findViewWithTag<View>("preview-toggle"),
                        )
                        assertFalse(
                            activity
                                .getWindow()
                                .getDecorView()
                                .findViewWithTag<View>("preview-sizes")
                                .performClick(),
                        )
                        old = preview(activity).findViewById<TextClock>(R.id.clock_time)
                        setTime(activity, now.plusSeconds(5))
                        idle(1000)
                        current = preview(activity).findViewById<TextClock>(R.id.clock_time)
                        assertSame(
                            "The always-open preview retains its view while ticking",
                            old,
                            current,
                        )
                        assertEquals(
                            nativePhone(
                                settings.timeFormat,
                                now.plusSeconds(5),
                                settings.zoneId,
                                Locale.ENGLISH,
                            ),
                            current.getText().toString(),
                        )
                        settings.timeFormat = "HH:mm 'seconds'"
                        now = Instant.parse("2026-10-26T18:14:30.250Z")
                        setTime(activity, now)
                        render(activity)
                        old = preview(activity).findViewById<TextClock>(R.id.clock_time)
                        val oldColor: Int = old.getCurrentTextColor()
                        settings.timeColor = oldColor xor 0x00ffffff
                        setTime(activity, now.plusSeconds(1))
                        idle(1000)
                        assertSame(
                            "A quoted s must not enable a seconds timer",
                            old,
                            preview(activity).findViewById<TextClock>(R.id.clock_time),
                        )
                        assertEquals(
                            "An accidental seconds refresh would apply this pending model change",
                            oldColor,
                            old.getCurrentTextColor(),
                        )
                        setTime(activity, now.plusMillis(29750))
                        idle(28750)
                        current = preview(activity).findViewById<TextClock>(R.id.clock_time)
                        assertSame(old, current)
                        assertEquals(settings.timeColor, current.getCurrentTextColor())
                        assertEquals(
                            nativePhone(
                                settings.timeFormat,
                                now.plusMillis(29750),
                                settings.zoneId,
                                Locale.ENGLISH,
                            ),
                            current.getText().toString(),
                        )
                        assertEquals(Locale("ru"), Locale.getDefault())
                    },
                )
        } finally {
            Resources.getSystem()
                .updateConfiguration(originalPhone, Resources.getSystem().getDisplayMetrics())
            Locale.setDefault(previous)
        }
    }

    @Test
    @Throws(Exception::class)
    fun reapplicationUpdatesClockPropertiesAndReplacesOnlyAnIncompatibleLayout() {
        val context: Context = RuntimeEnvironment.getApplication()
        val previous: Locale = Locale.getDefault()
        val originalPhone: Configuration = Configuration(Resources.getSystem().getConfiguration())
        try {
            prepare(context, Locale.ENGLISH, Locale("ru"))
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val settings: WidgetSettings = settings(activity)
                        settings.timeFormat = "HH:mm:ss"
                        settings.dateFormat = "EEEE, d MMMM"
                        settings.zoneId = "Asia/Riyadh"
                        val now: Instant = Instant.parse("2026-10-30T23:59:59Z")
                        setTime(activity, now)
                        render(activity)
                        val before: TextClock =
                            preview(activity).findViewById<TextClock>(R.id.clock_time)
                        settings.timeColor = -0x7f2234
                        settings.textSize = 63
                        settings.timeFontWeight = 450
                        settings.timeOffsetX = 7
                        settings.zoneId = "Asia/Kathmandu"
                        setTime(activity, now.plusSeconds(2))
                        refresh(activity)
                        val after: TextClock =
                            preview(activity).findViewById<TextClock>(R.id.clock_time)
                        assertSame("Property changes keep the attached native clock", before, after)
                        assertEquals(settings.timeColor, after.getCurrentTextColor())
                        assertEquals(450, WidgetTestViews.weight(after))
                        assertEquals(
                            nativePhone(
                                settings.timeFormat,
                                now.plusSeconds(2),
                                settings.zoneId,
                                Locale.ENGLISH,
                            ),
                            after.getText().toString(),
                        )
                        val date: TextClock =
                            preview(activity)
                                .findViewById<android.widget.TextClock>(R.id.clock_date)
                        assertEquals(
                            nativePhone(
                                settings.dateFormat,
                                now.plusSeconds(2),
                                settings.zoneId,
                                Locale.ENGLISH,
                            ),
                            date.getText().toString(),
                        )
                        val size = activity.preview.requestedSize()
                        val viewport = preview(activity).viewport(size.width, size.height)
                        val expected: View =
                            WidgetRenderer.preview(activity, -1001, false, settings, viewport.width, viewport.height)
                                .apply(activity, FrameLayout(activity))
                        assertEquals(
                            (expected.findViewById<TextClock>(R.id.clock_time) as TextClock)
                                .getTextSize(),
                            after.getTextSize(),
                            .1f,
                        )
                        settings.weatherEnabled = true
                        refresh(activity)
                        val withWeather: TextClock =
                            preview(activity).findViewById<TextClock>(R.id.clock_time)
                        assertNotSame(
                            "An incompatible resource layout needs a new native view",
                            after,
                            withWeather,
                        )
                        assertEquals(
                            nativePhone(
                                settings.timeFormat,
                                now.plusSeconds(2),
                                settings.zoneId,
                                Locale.ENGLISH,
                            ),
                            withWeather.getText().toString(),
                        )
                    },
                )
        } finally {
            Resources.getSystem()
                .updateConfiguration(originalPhone, Resources.getSystem().getDisplayMetrics())
            Locale.setDefault(previous)
        }
    }

    @Test
    @Throws(Exception::class)
    fun worldReapplicationUsesFreshCityClocksAndFormatsInsideTheSameRoot() {
        val context: Context = RuntimeEnvironment.getApplication()
        val previous: Locale = Locale.getDefault()
        val originalPhone: Configuration = Configuration(Resources.getSystem().getConfiguration())
        try {
            prepare(context, Locale.ENGLISH, Locale.ENGLISH)
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val tabs: java.util.ArrayList<View> = java.util.ArrayList()
                        activity
                            .getWindow()
                            .getDecorView()
                            .findViewsWithText(tabs, "World clock", View.FIND_VIEWS_WITH_TEXT)
                        assertFalse(tabs.isEmpty())
                        assertTrue(tabs.get(0).performClick())
                        val settings: WidgetSettings = settings(activity)
                        settings.cities = arrayOf<String>("America/Los_Angeles", "Asia/Riyadh")
                        settings.hour24 = true
                        val now: Instant = Instant.parse("2026-10-30T23:59:59Z")
                        setTime(activity, now)
                        render(activity)
                        val before: View =
                            requireNotNull(
                                (preview(activity) as WidgetPreviewStage).getWidgetContent(),
                            )
                        assertEquals(2, cityClocks(before).size)
                        settings.cities = arrayOf<String>("Asia/Tokyo")
                        settings.hour24 = false
                        setTime(activity, now.plusSeconds(2))
                        refresh(activity)
                        val after: View =
                            requireNotNull(
                                (preview(activity) as WidgetPreviewStage).getWidgetContent(),
                            )
                        assertSame(
                            "Changing the city set reuses its compatible preview root",
                            before,
                            after,
                        )
                        val clocks: MutableList<TextClock> = cityClocks(after)
                        assertEquals(1, clocks.size)
                        assertEquals("Asia/Tokyo", clocks.get(0).getTimeZone())
                        assertEquals(
                            nativePhone("h:mm a", now.plusSeconds(2), "Asia/Tokyo", Locale.ENGLISH),
                            clocks.get(0).getText().toString(),
                        )
                        assertEquals(Locale.ENGLISH, Locale.getDefault())
                    },
                )
        } finally {
            Resources.getSystem()
                .updateConfiguration(originalPhone, Resources.getSystem().getDisplayMetrics())
            Locale.setDefault(previous)
        }
    }

    private fun cityClocks(root: View): MutableList<TextClock> {
        val clocks: MutableList<TextClock> = java.util.ArrayList()
        if (root is TextClock && root.getId() == R.id.city_time) clocks.add(root as TextClock)
        if (root is ViewGroup)
            for (index: Int in 0 until (root as ViewGroup).getChildCount()) clocks.addAll(
                cityClocks((root as ViewGroup).getChildAt(index)),
            )
        return clocks
    }

    private fun prepare(context: Context, phone: Locale, app: Locale) {
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit()
        context.getSharedPreferences("widget_drafts", 0).edit().clear().commit()
        val config: Configuration = Configuration(Resources.getSystem().getConfiguration())
        config.setLocale(phone)
        Resources.getSystem().updateConfiguration(config, Resources.getSystem().getDisplayMetrics())
        AppLocales.setLanguage(context, app.getLanguage())
        Locale.setDefault(app)
        assertEquals(
            "Fixture must keep a distinct phone locale",
            phone.getLanguage(),
            AppLocales.phoneLocale(context).getLanguage(),
        )
    }

    private fun nativePhone(
        pattern: String,
        instant: Instant,
        zone: String,
        phone: Locale,
    ): String {
        val app: Locale = Locale.getDefault()
        try {
            Locale.setDefault(phone)
            val calendar: Calendar = GregorianCalendar(TimeZone.getTimeZone(zone), phone)
            calendar.setTimeInMillis(instant.toEpochMilli())
            return android.text.format.DateFormat.format(pattern, calendar).toString()
        } finally {
            Locale.setDefault(app)
        }
    }

    private fun appearance(clock: TextClock): TextAppearanceSpan {
        val text: Spanned = clock.getText() as Spanned
        return text.getSpans(0, text.length, TextAppearanceSpan::class.java)[0]
    }

    @Throws(Exception::class)
    private fun settings(activity: MainActivity): WidgetSettings {
        return activity.session.settings
    }

    private fun preview(activity: MainActivity): WidgetPreviewStage =
        requireNotNull(
            activity
                .findViewById<View>(android.R.id.content)
                .findViewWithTag<WidgetPreviewStage>("preview-stage"),
        )

    @Throws(Exception::class)
    private fun setTime(activity: MainActivity, instant: Instant) {
        activity.preview.clock = Clock.fixed(instant, ZoneOffset.UTC)
    }

    @Throws(Exception::class)
    private fun render(activity: MainActivity) {
        val method: Method = MainActivity::class.java.getDeclaredMethod("render")
        method.setAccessible(true)
        method.invoke(activity)
        layout(activity)
    }

    @Throws(Exception::class)
    private fun refresh(activity: MainActivity) {
        activity.preview.refresh(true, null)
        layout(activity)
    }

    private fun layout(activity: MainActivity) {
        val root: View = requireNotNull(activity.window).getDecorView()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 400, 850)
        idle(0)
        WidgetTestViews.prepare(root)
    }

    private fun idle(millis: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }
}
