package app.noonlet

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fitting uses the launcher's locale and configured zone, independently of the editor's locale. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocaleFitTest {
    @Test
    fun narrowWorldCardsFitLocalizedTwelveHourMonospaceTextAtLargeFontScale() {
        val context = RuntimeEnvironment.getApplication() as Context
        val system = Resources.getSystem()
        val originalConfiguration = Configuration(system.configuration)
        val originalLocale = Locale.getDefault()
        try {
            RuntimeEnvironment.setFontScale(1.8f)
            for (phone in listOf(Locale.forLanguageTag("es-ES"), Locale.forLanguageTag("pt-PT"))) {
                Locale.setDefault(phone)
                system.updateConfiguration(Configuration(system.configuration).apply { setLocale(phone) }, system.displayMetrics)
                val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(phone) })
                val settings = WidgetSettings.defaults(true).apply {
                    cities = arrayOf("Europe/Paris", "Asia/Tokyo", "America/Toronto")
                    fontFamily = "monospace"
                    hour24 = false
                }
                val parent = FrameLayout(localized)
                val root = WidgetRenderer.preview(localized, -42, true, settings, 160f, 140f).apply(localized, parent)
                parent.addView(root, FrameLayout.LayoutParams(160, 140))
                repeat(2) {
                    parent.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(140, View.MeasureSpec.EXACTLY))
                    parent.layout(0, 0, 160, 140)
                    WidgetTestViews.prepare(root)
                }
                for (view in WidgetTestViews.find(root, R.id.city_time)) {
                    val clock = view as TextClock
                    val paint = Paint().apply {
                        typeface = WidgetFont.typeface(settings.fontFamily, settings.fontWeight)
                        textSize = clock.textSize
                    }
                    assertTrue(clock.width > 0)
                    for (sample in ClockPattern.fittingSamples("h:mm a", phone, TimeZone.getTimeZone(clock.timeZone)))
                        assertTrue("Localized time must fit its card", paint.measureText(sample) <= clock.width + 1)
                    assertTrue(clock.paint.measureText(clock.text.toString()) <= clock.width + 1)
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(originalConfiguration.fontScale)
            system.updateConfiguration(originalConfiguration, system.displayMetrics)
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    @Throws(Exception::class)
    fun sampleReuseIsBoundedAndSeparatesLocaleZonePatternAndSampleRange() {
        val cacheField: java.lang.reflect.Field =
            ClockPattern::class.java.getDeclaredField("PATTERN_SAMPLES")
        cacheField.setAccessible(true)
        val cache: MutableMap<*, *> = cacheField.get(null) as MutableMap<*, *>
        cache.clear()
        val original: Array<String> = samples("EEE, d MMM", Locale.ENGLISH, "UTC")
        assertSame(
            "Typography changes must reuse formatted strings",
            original,
            samples("EEE, d MMM", Locale.ENGLISH, "UTC"),
        )
        assertFalse(java.util.Arrays.equals(original, samples("EEE, d MMM", Locale("ru"), "UTC")))
        assertFalse(
            java.util.Arrays.equals(
                samples("HH:mm zz", Locale.ENGLISH, "UTC"),
                samples("HH:mm zz", Locale.ENGLISH, "Asia/Kathmandu"),
            ),
        )
        assertFalse(java.util.Arrays.equals(original, samples("MMMM", Locale.ENGLISH, "UTC")))
        assertEquals(24, samples("HH:mm", Locale.ENGLISH, "UTC").size)
        assertEquals(84, original.size)
        for (index: Int in 0..11) samples("EEE, d MMM '" + index + "'", Locale.ENGLISH, "UTC")
        assertEquals("Custom formats must not grow memory without a bound", 8, cache.size)
    }

    @Test
    @Throws(Exception::class)
    fun domainsFollowUnquotedTokensAndRetainOnlyDistinctStrings() {
        assertEquals(7, samples("EEEE", Locale.ENGLISH, "UTC").size)
        assertEquals(168, samples("EEEE HH:mm", Locale.ENGLISH, "UTC").size)
        assertEquals(24, samples("'EEEE MMM d' HH:mm", Locale.ENGLISH, "UTC").size)
        assertEquals(7, samples("'HH:mm a' EEEE", Locale.ENGLISH, "UTC").size)
        assertEquals(1, samples("'o''clock EEEE HH:mm'", Locale.ENGLISH, "UTC").size)
        assertEquals(12, samples("MMMMMM", Locale.ENGLISH, "UTC").size)
    }

    @Throws(Exception::class)
    private fun samples(pattern: String, locale: Locale, zone: String): Array<String> {
        return ClockPattern.fittingSamples(pattern, locale, TimeZone.getTimeZone(zone))
    }

    @Test
    fun phoneGlyphsFitAndSizesStayStableAcrossOppositeApplicationLocales() {
        val context: Context = RuntimeEnvironment.getApplication()
        val original: Locale = Locale.getDefault()
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
                )) {
                val config: Configuration = Configuration(originalPhone)
                config.setLocale(phone)
                Resources.getSystem()
                    .updateConfiguration(config, Resources.getSystem().getDisplayMetrics())
                assertEquals(phone.getLanguage(), AppLocales.phoneLocale(context).getLanguage())
                for (zone: String in
                    arrayOf<String>("UTC", "Asia/Kathmandu", "America/Los_Angeles")) check(
                    context,
                    phone,
                    zone,
                )
            }
        } finally {
            Resources.getSystem()
                .updateConfiguration(originalPhone, Resources.getSystem().getDisplayMetrics())
            Locale.setDefault(original)
        }
    }

    private fun check(context: Context, phone: Locale, zone: String) {
        var first: FloatArray? = null
        for (app: Locale in arrayOf<Locale>(Locale.ENGLISH, Locale("ru"))) {
            Locale.setDefault(app)
            val settings: WidgetSettings = WidgetSettings()
            settings.zoneId = zone
            settings.fontFamily = "serif"
            settings.timeFormat = "h:mm a zz"
            settings.dateFormat = "EEEE, d MMMM"
            settings.textSize = WidgetSettings.MAX_TIME_SIZE
            settings.dateSize = WidgetSettings.MAX_DATE_SIZE
            settings.timeFontWeight = 700
            settings.dateFontWeight = 500
            WidgetSettingsStore.save(context, -4001, false, settings)
            val view: View = inflate(context, 155, 180)
            val time: TextClock = view.findViewById<TextClock>(R.id.clock_time)
            val date: TextClock = view.findViewById<TextClock>(R.id.clock_date)
            val sizes: FloatArray = floatArrayOf(time.getTextSize(), date.getTextSize())
            if (first == null) first = sizes
            else
                assertArrayEquals(
                    phone.toString() + " / " + zone + ": app language must not change fitting",
                    first,
                    sizes,
                    .001f,
                )
            assertTrue(
                "Native phone time glyphs must fit",
                (nativeMaximum(
                    settings.timeFormat,
                    false,
                    zone,
                    phone,
                    time.getTextSize(),
                    settings.fontFamily,
                    settings.timeFontWeight,
                ) <= 155),
            )
            assertTrue(
                "Native phone weekday/month glyphs must fit",
                (nativeMaximum(
                    settings.dateFormat,
                    true,
                    zone,
                    phone,
                    date.getTextSize(),
                    settings.fontFamily,
                    settings.dateFontWeight,
                ) <= 155),
            )
            assertEquals("Renderer must not mutate the process locale", app, Locale.getDefault())
        }
    }

    private fun inflate(context: Context, width: Int, height: Int): View {
        val parent: FrameLayout = FrameLayout(context)
        val view: View =
            WidgetRenderer.preview(
                    context,
                    -4001,
                    false,
                    WidgetSettingsStore.load(context, -4001, false),
                    width.toFloat(),
                    height.toFloat(),
                )
                .apply(context, parent)
        parent.addView(view, FrameLayout.LayoutParams(width, height))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, width, height)
        WidgetTestViews.prepare(view)
        return view
    }

    private fun nativeMaximum(
        pattern: String,
        date: Boolean,
        zone: String,
        phone: Locale,
        size: Float,
        family: String,
        weight: Int,
    ): Float {
        val app: Locale = Locale.getDefault()
        try {
            Locale.setDefault(phone)
            val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.setTypeface(
                WidgetFont.typeface(family, weight),
            )
            paint.setTextSize(size)
            val calendar: Calendar = Calendar.getInstance(TimeZone.getTimeZone(zone), phone)
            calendar.set(2026, Calendar.SEPTEMBER, 28, 23, 58, 58)
            var width: Float = 0f
            for (index: Int in 0 until (if (date) 84 else 24)) {
                if (date) {
                    calendar.set(Calendar.MONTH, index / 7)
                    calendar.set(Calendar.DAY_OF_MONTH, 22 + index % 7)
                } else calendar.set(Calendar.HOUR_OF_DAY, index)
                width =
                    Math.max(
                        width,
                        paint.measureText(
                            android.text.format.DateFormat.format(pattern, calendar).toString(),
                        ),
                    )
            }
            return width
        } finally {
            Locale.setDefault(app)
        }
    }
}
