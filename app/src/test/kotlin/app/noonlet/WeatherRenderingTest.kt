package app.noonlet

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import java.io.File
import java.time.Duration
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherRenderingTest {
    private lateinit var application: Context
    private var previousStore: WeatherStore? = null
    private val directories = mutableListOf<File>()
    private val place = WeatherPlace("Riverton", 49_000_000, 7_000_000)

    @Before
    fun prepare() {
        application = RuntimeEnvironment.getApplication()
        previousStore = WeatherRepository.store
        application.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        AppLocales.setLanguage(application, "en")
    }

    @After
    fun closeFixtures() {
        WeatherRepository.store = previousStore
        for (directory in directories) {
            assertEquals(application.cacheDir.canonicalFile, directory.canonicalFile.parentFile)
            directory.deleteRecursively()
        }
    }

    @Test
    fun disabledClockAndWorldDoNotOpenWeatherStorage() {
        val context =
            object : ContextWrapper(application) {
                override fun getApplicationContext(): Context = this

                override fun getNoBackupFilesDir(): File =
                    throw AssertionError("Weather storage was opened by an unrelated widget")
            }
        for (horizontal in listOf(false, true)) {
            val settings =
                WidgetSettings().apply {
                    orientation = if (horizontal) WidgetOrientation.HORIZONTAL else WidgetOrientation.VERTICAL
                }
            val root = render(context, settings, 167)
            assertNull(root.findViewById<View>(R.id.weather_panel))
            assertTrue(root.findViewById<View>(R.id.clock_time) is TextClock)
            assertTrue(WidgetRenderer.updatePreviewAppearance(root, settings, false))
        }
        val world = WidgetSettings.defaults(true).apply { weatherEnabled = true }
        val payload = WidgetRenderer.preview(context, -1, true, world, 328f, 82f)
        assertNull(
            payload.apply(context, FrameLayout(context)).findViewById<View>(R.id.weather_panel),
        )
    }

    @Test
    fun narrowAndWideVerticalLayoutsKeepTheWholeTemperatureAndSeparateClockInk() {
        for (language in listOf("en", "de")) {
            AppLocales.setLanguage(application, language)
            for (width in listOf(125, 167, 203, 247, 328)) {
                val context = fixture(code = 0, temperature = -12.8)
                val settings = settings()
                val root = render(context, settings, width)
                val caption = root.findViewById<TextView>(R.id.weather_caption)
                assertTrue(caption.text.toString().endsWith("-13°C"))
                assertEquals(1, caption.lineCount)
                assertEquals(0, caption.layout.getEllipsisCount(0))
                assertTrue(
                    caption.paint.measureText(caption.text.toString()) <= caption.width + .5f,
                )
                val time = root.findViewById<TextClock>(R.id.clock_time)
                assertTrue("Compact clock digits must remain readable", time.textSize >= 36f)
                val timeBounds = bounds(root, R.id.clock_time)
                val weatherBounds = bounds(root, R.id.weather_caption)
                if (width >= 160)
                    assertTrue(timeBounds.right + time.translationX <= weatherBounds.left)
                else assertTrue(timeBounds.bottom <= weatherBounds.top)
                assertTrue(weatherBounds.right <= width)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.weather_icon).visibility)
                if (width == 328)
                    assertTrue(
                        caption.text
                            .toString()
                            .contains(AppLocales.text(context, R.string.weather_clear)),
                    )
                if (width <= 167) assertEquals("-13°C", caption.text.toString())
            }
        }
    }

    @Test
    fun offsetAlarmFitsWithoutResizingClockOrWeather() {
        val context = fixture()
        val settings = settings().apply {
            alarmAlignment = WidgetAlignment.RIGHT
            alarmOffsetY = -24
        }
        val withoutAlarm = render(context, settings, 328)
        settings.showAlarm = true
        settings.showNoAlarm = true
        val withAlarm = render(context, settings, 328)
        for (id in listOf(R.id.clock_time, R.id.clock_date, R.id.weather_caption)) {
            assertEquals(
                "Text size for $id",
                withoutAlarm.findViewById<TextView>(id).textSize,
                withAlarm.findViewById<TextView>(id).textSize,
                .01f,
            )
            assertEquals("Position for $id", bounds(withoutAlarm, id), bounds(withAlarm, id))
        }
        assertEquals(bounds(withoutAlarm, R.id.weather_icon), bounds(withAlarm, R.id.weather_icon))
        assertVisibleAlarm(withAlarm)
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h850dp-xxhdpi")
    fun offsetAlarmKeepsUnobstructedTypographyAcrossAllocationsAndFontScales() {
        val previousScale = RuntimeEnvironment.getFontScale()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            for (scale in listOf(1f, 1.3f)) {
                RuntimeEnvironment.setFontScale(scale)
                val context = fixture()
                for (width in listOf(203, 328)) for (height in listOf(52, 82, 174)) {
                    for (date in listOf(false, true)) for (icon in listOf(false, true)) {
                        val settings = settings().apply {
                            showDate = date
                            showAlarmIcon = icon
                            alarmOffsetY = -30
                            alarmOffsetX = -3
                            alarmAlignment = WidgetAlignment.RIGHT
                        }
                        val withoutAlarm = render(context, settings, width, height)
                        val pixelWidth = withoutAlarm.width
                        val pixelHeight = withoutAlarm.height
                        (withoutAlarm.parent as android.view.ViewGroup).removeView(withoutAlarm)
                        activity.get().setContentView(withoutAlarm)
                        measure(withoutAlarm, pixelWidth, pixelHeight)
                        val dateInk = if (date) inkBounds(withoutAlarm, R.id.clock_date) else null
                        settings.showAlarm = true
                        settings.showNoAlarm = true
                        val withAlarm = render(context, settings, width, height)
                        (withAlarm.parent as android.view.ViewGroup).removeView(withAlarm)
                        activity.get().setContentView(withAlarm)
                        measure(withAlarm, pixelWidth, pixelHeight)
                        for (id in listOf(R.id.clock_time, R.id.weather_caption)) {
                            assertEquals(
                                "$width x $height, font=$scale, date=$date, icon=$icon, view=$id, " +
                                    "primary=${inkBounds(withoutAlarm, R.id.clock_time)}, alarm=${visualBounds(withAlarm, R.id.clock_alarm_row)}",
                                withoutAlarm.findViewById<TextView>(id).textSize,
                                withAlarm.findViewById<TextView>(id).textSize,
                                .01f,
                            )
                        }
                        val before = withoutAlarm.findViewById<TextView>(R.id.clock_date).textSize
                        val after = withAlarm.findViewById<TextView>(R.id.clock_date).textSize
                        if (after + .01f < before) {
                            val alarm = visualBounds(withAlarm, R.id.clock_alarm_row)
                            assertNotNull(dateInk)
                            assertTrue(Rect.intersects(requireNotNull(dateInk), alarm))
                            assertFalse(Rect.intersects(inkBounds(withAlarm, R.id.clock_date), alarm))
                        } else assertEquals(before, after, .01f)
                        assertVisibleAlarm(withAlarm, "$width x $height, font=$scale, date=$date, icon=$icon")
                    }
                }
            }
        } finally {
            activity.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(previousScale)
        }
    }

    @Test
    fun independentAlarmOffsetsMoveTheMeasuredRowWithoutReflowingOtherViews() {
        val context = fixture()
        val settings = settings().apply {
            showAlarm = true
            showNoAlarm = true
            alarmAlignment = WidgetAlignment.RIGHT
            alarmOffsetY = -30
            alarmOffsetX = -9
            weatherTextFormat = WeatherTextFormat.TEMPERATURE
        }
        val original = render(context, settings, 328)
        assertNotNull(original.findViewById<View>(R.id.clock_alarm_position))
        settings.alarmOffsetX += 4
        settings.alarmOffsetY += 6
        val moved = render(context, settings, 328)
        assertNotNull(moved.findViewById<View>(R.id.clock_alarm_position))
        for (id in listOf(R.id.clock_alarm, R.id.clock_alarm_icon, R.id.clock_alarm_row)) {
            val expected = visualBounds(original, id).apply { offset(4, 6) }
            assertEquals("Alarm translation for $id", expected, visualBounds(moved, id))
        }
        for (id in listOf(R.id.clock_time, R.id.clock_date, R.id.weather_icon, R.id.weather_caption)) {
            assertEquals("Unchanged view $id", bounds(original, id), bounds(moved, id))
        }
        assertVisibleAlarm(original)
        assertVisibleAlarm(moved)
    }

    @Test
    fun offsetAlarmLeavesDateTouchAreaAvailable() {
        val context = fixture()
        val settings = settings().apply {
            showAlarm = true
            showNoAlarm = true
            alarmAlignment = WidgetAlignment.RIGHT
            alarmOffsetY = -24
            alarmOffsetX = -5
            advancedTap = true
            timeTapAction = "none"
            dateTapAction = "none"
            alarmTapAction = "settings"
        }
        val id = 922
        WidgetSettingsStore.save(context, id, false, settings)
        val manager = AppWidgetManager.getInstance(context)
        Shadows.shadowOf(manager).addBoundWidget(id, AppWidgetProviderInfo().apply {
            provider = ComponentName(context, ClockWidgetProvider::class.java)
        })
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 328)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 82)
        })
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val parent = FrameLayout(activity.get())
            activity.get().setContentView(parent)
            val root = WidgetRenderer.clock(context, id).apply(context, parent)
            parent.addView(root, FrameLayout.LayoutParams(328, 82))
            measure(root, 328, 82)
            assertNotNull(root.findViewById<View>(R.id.clock_alarm_position))
            val date = visualBounds(root, R.id.clock_date)
            val alarm = visualBounds(root, R.id.clock_alarm)
            assertFalse(visualBounds(root, R.id.clock_alarm_row).contains(date.centerX(), alarm.centerY()))
            tap(root, date.centerX(), alarm.centerY())
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            tap(root, alarm.centerX(), alarm.centerY())
            val intent = checkNotNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertEquals(id, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            settings.dateTapAction = "settings"
            settings.alarmTapAction = "none"
            WidgetSettingsStore.save(context, id, false, settings)
            WidgetRenderer.clock(context, id).reapply(context, root)
            measure(root, 328, 82)
            tap(root, date.centerX(), date.centerY())
            val dateIntent = checkNotNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertEquals(MainActivity::class.java.name, dateIntent.component?.className)
            assertEquals(id, dateIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun dateAppearanceDoesNotChangeWeatherTextOrArtwork() {
        val context = fixture()
        val settings = settings()
        val original = render(context, settings, 328)
        settings.dateSize = 9
        settings.dateFontWeight = 800
        settings.dateColor = android.graphics.Color.RED
        settings.dateOffsetX = 25
        settings.dateOffsetY = 10
        settings.fontFamily = "serif"
        val changed = render(context, settings, 328)
        val before = original.findViewById<TextView>(R.id.weather_caption)
        val after = changed.findViewById<TextView>(R.id.weather_caption)
        assertEquals(before.textSize, after.textSize, .01f)
        assertEquals(before.currentTextColor, after.currentTextColor)
        assertEquals(before.layout.getLineWidth(0), after.layout.getLineWidth(0), .01f)
        assertEquals(bounds(original, R.id.weather_caption), bounds(changed, R.id.weather_caption))
        assertEquals(bounds(original, R.id.weather_icon), bounds(changed, R.id.weather_icon))
    }

    @Test
    fun weatherFormattingAndPositionApplyWithoutChangingStoredDateStyle() {
        val context = fixture()
        val settings = settings().apply {
            weatherTextSize = 18
            weatherColor = android.graphics.Color.CYAN
            weatherTextFormat = WeatherTextFormat.TEMPERATURE
            weatherIconSize = 60
            weatherIconGap = 5
        }
        val root = render(context, settings, 328, 174)
        val caption = root.findViewById<TextView>(R.id.weather_caption)
        assertEquals("20°C", caption.text.toString())
        assertEquals(android.graphics.Color.CYAN, caption.currentTextColor)
        assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, context.resources.displayMetrics), caption.textSize, .01f)
        settings.weatherOffsetX = -7
        settings.weatherOffsetY = 5
        val moved = render(context, settings, 328, 174)
        assertEquals(visualTop(root, root.findViewById(R.id.weather_icon)) + 5,
            visualTop(moved, moved.findViewById(R.id.weather_icon)), .01f)
        assertEquals(bounds(root, R.id.clock_date), bounds(moved, R.id.clock_date))
        assertEquals(16, settings.dateSize)
        assertEquals(android.graphics.Color.WHITE, settings.dateColor)
    }

    @Test
    fun alarmCanReachTheBottomEdgeWithoutResizingPrimaryContent() {
        val context = fixture()
        for (family in listOf("sans-serif-medium", "serif", "monospace")) {
            for (icon in listOf(false, true)) {
                val settings = settings().apply {
                    fontFamily = family
                    showAlarm = true
                    showNoAlarm = true
                    showAlarmIcon = icon
                    alarmOffsetY = -30
                }
                val original = render(context, settings, 328)
                settings.alarmOffsetY += original.height - visualBounds(original, R.id.clock_alarm_row).bottom
                val atEdge = render(context, settings, 328)
                assertEquals(
                    "Primary size at the exact alarm boundary, $family / $icon",
                    original.findViewById<TextView>(R.id.clock_time).textSize,
                    atEdge.findViewById<TextView>(R.id.clock_time).textSize,
                    .01f,
                )
                assertEquals(atEdge.height, visualBounds(atEdge, R.id.clock_alarm_row).bottom)
                assertVisibleAlarm(atEdge)
            }
        }
    }

    @Test
    fun nonoverlappingAlarmKeepsClockAndDateSizesWithoutWeather() {
        val context = fixture()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            for (width in listOf(167, 328)) {
                var referenceTime: Float? = null
                var referenceDate: Float? = null
                for (height in listOf(82, 174)) {
                    val settings = settings().apply {
                        weatherEnabled = false
                        textSize = 48
                        showAlarmIcon = false
                        alarmAlignment = WidgetAlignment.RIGHT
                        alarmOffsetY = -24
                    }
                    val baseline = render(context, settings, width, height)
                    settings.showAlarm = true
                    settings.showNoAlarm = true
                    val withAlarm = render(context, settings, width, height)
                    (withAlarm.parent as android.view.ViewGroup).removeView(withAlarm)
                    activity.get().setContentView(withAlarm)
                    measure(withAlarm, width, height)
                    for (id in listOf(R.id.clock_time, R.id.clock_date)) {
                        assertEquals("$width / $height / $id",
                            baseline.findViewById<TextView>(id).textSize,
                            withAlarm.findViewById<TextView>(id).textSize, .01f)
                        val primary = inkBounds(withAlarm, id)
                        val alarm = inkBounds(withAlarm, R.id.clock_alarm)
                        assertFalse("$width / $height: $primary intersects $alarm",
                            Rect.intersects(primary, alarm))
                    }
                    val time = withAlarm.findViewById<TextView>(R.id.clock_time).textSize
                    val date = withAlarm.findViewById<TextView>(R.id.clock_date).textSize
                    referenceTime?.let { assertEquals(it, time, .01f) }
                    referenceDate?.let { assertEquals(it, date, .01f) }
                    referenceTime = time
                    referenceDate = date
                }
            }
        } finally { activity.pause().stop().destroy() }
    }

    @Test
    fun positionedAlarmSizeDoesNotChangePrimaryTypography() {
        val context = fixture()
        for (height in listOf(74, 82, 90)) for (offset in -30..-18) {
            val settings = settings().apply {
                showAlarm = true
                showNoAlarm = true
                alarmAlignment = WidgetAlignment.CENTER
                alarmOffsetY = offset
                dateFormat = "'D'"
            }
            val original = render(context, settings, 328, height)
            for (size in 16..20) {
                settings.alarmSize = size
                val resized = render(context, settings, 328, height)
                for (id in listOf(R.id.clock_time, R.id.clock_date, R.id.weather_caption)) {
                    assertEquals("$height / $offset / $size / $id",
                        original.findViewById<TextView>(id).textSize,
                        resized.findViewById<TextView>(id).textSize, .01f)
                }
                assertEquals(bounds(original, R.id.weather_icon), bounds(resized, R.id.weather_icon))
                val alarm = resized.findViewById<TextView>(R.id.clock_alarm)
                assertEquals(alarm.layout.height, alarm.height)
                assertEquals(0, alarm.layout.getEllipsisCount(0))
            }
        }
    }

    @Test
    fun frameworkHostSwitchesAlarmPlacementWithoutStaleRows() {
        val context = fixture()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val info = AppWidgetProviderInfo().apply {
                provider = ComponentName(context, ClockWidgetProvider::class.java)
                initialLayout = R.layout.clock_widget
            }
            ReflectionHelpers.setField(info, "providerInfo", context.packageManager.getReceiverInfo(info.provider, 0))
            val host = AppWidgetHostView(context)
            host.setAppWidget(923, info)
            Shadow.directlyOn<Any, AppWidgetHostView>(
                host, AppWidgetHostView::class.java, "setAppWidget",
                ClassParameter.from(Int::class.javaPrimitiveType, 923),
                ClassParameter.from(AppWidgetProviderInfo::class.java, info),
            )
            activity.get().setContentView(host)
            host.setPadding(0, 0, 0, 0)
            val settings = settings()
            settings.showNoAlarm = true
            for (offset in listOf<Int?>(null, -24, -30, 0, -24, null)) {
                settings.showAlarm = offset != null
                settings.alarmOffsetY = offset ?: 0
                val payload = WidgetRenderer.preview(context, -1, false, settings, 328f, 82f)
                Shadow.directlyOn<Any, AppWidgetHostView>(
                    host, AppWidgetHostView::class.java, "updateAppWidget",
                    ClassParameter.from(android.widget.RemoteViews::class.java, payload),
                )
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                measure(host, 328, 82)
                val root = checkNotNull(host.findViewById<View>(R.id.widget_root))
                assertEquals(1, WidgetTestViews.find(root, R.id.clock_time).size)
                assertEquals(1, WidgetTestViews.find(root, R.id.clock_alarm_row).size)
                assertEquals(offset != null, root.findViewById<View>(R.id.clock_alarm_row).visibility == View.VISIBLE)
                if (offset != null) assertVisibleAlarm(root)
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun unshiftedAlarmRemainsFullyMeasuredAcrossAllocations() {
        val context = fixture()
        for (height in listOf(52, 82, 174)) {
            val root = render(context, settings().apply { showAlarm = true; showNoAlarm = true }, 328, height)
            assertTrue(root.findViewById<TextView>(R.id.clock_time).textSize > 0)
            assertTrue(root.findViewById<TextView>(R.id.clock_date).textSize > 0)
            assertVisibleAlarm(root)
        }
    }

    @Test
    fun unshiftedAlarmUsesFreeSpaceWithoutResizingPrimaryText() {
        val context = fixture(code = 0, temperature = 17.0)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val operation = clockAlarm(context, 32001,
            Intent(context, UpdateReceiver::class.java).setAction("PLACEMENT_TEST_ALARM"),
            PendingIntent.FLAG_IMMUTABLE)
        alarms.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(1893568740000L, operation), operation)
        try {
            for (weather in listOf(false, true)) for (height in listOf(82, 90, 174)) {
                val settings = settings().apply {
                    weatherEnabled = weather
                    timeFormat = "'08:24'"
                    dateFormat = "'Fri, 8 Aug'"
                    alarmAlignment = WidgetAlignment.CENTER
                    alarmSize = 16
                    alarmOffsetX = 0
                    alarmOffsetY = 0
                }
                val baseline = render(context, settings, 328, height)
                settings.showAlarm = true
                settings.showNoAlarm = true
                val enabled = render(context, settings, 328, height)
                (enabled.parent as android.view.ViewGroup).removeView(enabled)
                activity.get().setContentView(enabled)
                measure(enabled, 328, height)
                for (id in listOf(R.id.clock_time, R.id.clock_date)) {
                    assertEquals("$weather / $height / $id",
                        baseline.findViewById<TextView>(id).textSize,
                        enabled.findViewById<TextView>(id).textSize, .01f)
                    assertFalse(Rect.intersects(inkBounds(enabled, id),
                        visualBounds(enabled, R.id.clock_alarm_row)))
                }
                assertVisibleAlarm(enabled)
            }
        } finally {
            alarms.cancel(operation)
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun automaticAlarmPlacementChecksShiftedDateInkBeforeUsingFreeSpace() {
        val context = fixture()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val operation = clockAlarm(context, 32002,
            Intent(context, UpdateReceiver::class.java).setAction("COLLISION_TEST_ALARM"),
            PendingIntent.FLAG_IMMUTABLE)
        alarms.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(1893568740000L, operation), operation)
        try {
            val settings = settings().apply {
                weatherEnabled = false
                timeFormat = "'08:24'"
                dateFormat = "'D'"
                dateAlignment = WidgetAlignment.CENTER
                alarmSize = 15
            }
            val baseline = render(context, settings, 328, 90)
            settings.showAlarm = true
            settings.showNoAlarm = true
            val collision = render(context, settings, 328, 90)
            (collision.parent as android.view.ViewGroup).removeView(collision)
            activity.get().setContentView(collision)
            measure(collision, 328, 90)
            for (id in listOf(R.id.clock_time, R.id.clock_date)) {
                assertEquals(baseline.findViewById<TextView>(id).textSize,
                    collision.findViewById<TextView>(id).textSize, .01f)
                assertFalse(Rect.intersects(inkBounds(collision, id),
                    inkBounds(collision, R.id.clock_alarm)))
            }
            settings.dateOffsetX = -40
            val separated = render(context, settings, 328, 90)
            (separated.parent as android.view.ViewGroup).removeView(separated)
            activity.get().setContentView(separated)
            measure(separated, 328, 90)
            for (id in listOf(R.id.clock_time, R.id.clock_date)) {
                assertEquals(baseline.findViewById<TextView>(id).textSize,
                    separated.findViewById<TextView>(id).textSize, .01f)
                assertFalse(Rect.intersects(inkBounds(separated, id),
                    visualBounds(separated, R.id.clock_alarm_row)))
            }
            assertVisibleAlarm(separated)
        } finally {
            alarms.cancel(operation)
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun horizontalAlarmDoesNotResizeTheSeparateTimeColumn() {
        val context = fixture()
        val settings = settings().apply {
            weatherEnabled = false
            orientation = WidgetOrientation.HORIZONTAL
            dateSize = 35
            alarmSize = 64
        }
        val baseline = render(context, settings, 328, 52)
        settings.showAlarm = true
        settings.showNoAlarm = true
        val enabled = render(context, settings, 328, 52)
        assertEquals(baseline.findViewById<TextView>(R.id.clock_time).textSize,
            enabled.findViewById<TextView>(R.id.clock_time).textSize, .01f)
        assertVisibleAlarm(enabled)
    }

    @Test
    fun compactAlarmFitsCollidingTextAndKeepsUnobstructedTime() {
        val context = fixture()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val operation = clockAlarm(context, 32003,
            Intent(context, UpdateReceiver::class.java).setAction("COMPACT_TEST_ALARM"),
            PendingIntent.FLAG_IMMUTABLE)
        alarms.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(1893568740000L, operation), operation)
        try {
            for (weather in listOf(false, true)) for (height in listOf(82, 174)) {
                val settings = WidgetSettings.defaults(false).apply {
                    timeFormat = "'08:24'"
                    dateFormat = "'Fri, 8 Aug'"
                    weatherEnabled = weather
                    weatherUseDevice = false
                    weatherPlace = place.encode()
                }
                val baseline = render(context, settings, 170, height)
                (baseline.parent as android.view.ViewGroup).removeView(baseline)
                activity.get().setContentView(baseline)
                measure(baseline, 170, height)
                val timeInk = inkBounds(baseline, R.id.clock_time)
                settings.showAlarm = true
                settings.showNoAlarm = true
                val enabled = render(context, settings, 170, height)
                (enabled.parent as android.view.ViewGroup).removeView(enabled)
                activity.get().setContentView(enabled)
                measure(enabled, 170, height)
                if (!weather) {
                    assertEquals(baseline.findViewById<TextView>(R.id.clock_time).textSize,
                        enabled.findViewById<TextView>(R.id.clock_time).textSize, .01f)
                    assertEquals(timeInk, inkBounds(enabled, R.id.clock_time))
                }
                for (id in listOf(R.id.clock_time, R.id.clock_date))
                    assertFalse(Rect.intersects(inkBounds(enabled, id),
                        visualBounds(enabled, R.id.clock_alarm_row)))
                assertVisibleAlarm(enabled)
            }
        } finally {
            alarms.cancel(operation)
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun stackedWeatherKeepsTheAlarmBelowTheWeatherLine() {
        val context = fixture()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val settings = WidgetSettings.defaults(false).apply {
                timeFormat = "'08:24'"
                dateFormat = "'Fri, 8 Aug'"
                showAlarm = true
                showNoAlarm = true
                weatherEnabled = true
                weatherUseDevice = false
                weatherPlace = place.encode()
            }
            val root = render(context, settings, 120, 174)
            (root.parent as android.view.ViewGroup).removeView(root)
            activity.get().setContentView(root)
            measure(root, 120, 174)
            val alarm = visualBounds(root, R.id.clock_alarm_row)
            for (id in listOf(R.id.clock_time, R.id.clock_date, R.id.weather_caption))
                assertFalse(Rect.intersects(inkBounds(root, id), alarm))
            assertVisibleAlarm(root)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    private fun tap(root: View, x: Int, y: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, x.toFloat(), y.toFloat(), 0)
            try {
                root.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertVisibleAlarm(root: View, case: String = "") {
        val alarm = root.findViewById<TextView>(R.id.clock_alarm)
        val ink = inkBounds(root, R.id.clock_alarm)
        assertTrue("$case: Alarm glyphs must fit vertically: $ink in ${root.height}",
            ink.top >= 0 && ink.bottom <= root.height)
        if (root.findViewById<View>(R.id.clock_alarm_icon).visibility == View.VISIBLE) {
            val icon = inkBounds(root, R.id.clock_alarm_icon)
            assertTrue("$case: Alarm icon must fit vertically: $icon", icon.top >= 0 && icon.bottom <= root.height)
        }
        assertTrue("Alarm text must be measured fully", alarm.height >= alarm.layout.height)
        val bitmap = WidgetTestViews.draw(alarm)
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Alarm must contain visible text", pixels.count { it ushr 24 != 0 } > 10)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun dateOffsetsMoveOnlyTheDateAndWeatherSurvivesHiddenDate() {
        val context = fixture()
        val settings = settings()
        val original = render(context, settings, 328)
        val weatherBounds = bounds(original, R.id.weather_caption)
        settings.dateOffsetX = -10
        settings.dateOffsetY = 24
        val moved = render(context, settings, 328)
        assertEquals(weatherBounds, bounds(moved, R.id.weather_caption))
        settings.showDate = false
        val hidden = render(context, settings, 328)
        assertEquals(View.GONE, hidden.findViewById<View>(R.id.clock_date_row).visibility)
        assertEquals(View.VISIBLE, hidden.findViewById<View>(R.id.weather_panel).visibility)
        assertTrue(hidden.findViewById<TextView>(R.id.weather_caption).text.contains("20°C"))
        assertTrue(hidden.findViewById<TextClock>(R.id.clock_time).textSize >= 50f)
    }

    @Test
    fun clockFontChoiceDoesNotMoveWeatherWithinTheAllocation() {
        val context = fixture()
        for (width in listOf(203, 328)) {
            val reference = render(context, settings(), width)
        for (family in listOf("sans-serif-medium", "serif", "monospace")) {
                val root = render(context, settings().apply { fontFamily = family }, width)
                assertEquals(
                    "$family at ${width}dp",
                    visualBaseline(reference, R.id.weather_caption),
                    visualBaseline(root, R.id.weather_caption),
                    0f,
                )
            }
        }
    }

    @Test
    fun shortVerticalAllocationsKeepWeatherCaptionBelowDateSize() {
        val previousScale = RuntimeEnvironment.getFontScale()
        try {
            for (scale in listOf(1f, 1.3f)) {
                RuntimeEnvironment.setFontScale(scale)
                for (height in listOf(52, 82)) {
                    val root = render(fixture(), settings(), 328, height)
                    val caption = root.findViewById<TextView>(R.id.weather_caption)
                    val date = root.findViewById<TextView>(R.id.clock_date)
                    assertTrue(caption.textSize <= date.textSize)
                    assertEquals(0, caption.layout.getEllipsisCount(0))
                    assertEquals(
                        "Weather baseline at font scale $scale in ${height}dp",
                        visualBaseline(root, R.id.clock_date),
                        visualBaseline(root, R.id.weather_caption),
                        1f,
                    )
                    val icon = root.findViewById<ImageView>(R.id.weather_icon)
                    assertTrue(visualTop(root, icon) >= 0)
                    assertTrue(visualTop(root, icon) + icon.height <= root.height)
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(previousScale)
        }
    }

    @Test
    fun sideIconRetainsItsBoundsAcrossWidthChanges() {
        val context = fixture()
        val expected = Bitmap.createBitmap(52, 52, Bitmap.Config.ARGB_8888)
        val artwork = checkNotNull(context.getDrawable(R.drawable.weather_partly_cloudy_day))
        artwork.setBounds(0, 0, 52, 52)
        artwork.draw(Canvas(expected))
        val expectedPixels = IntArray(52 * 52)
        expected.getPixels(expectedPixels, 0, 52, 0, 0, 52, 52)
        for (width in listOf(203, 224, 244, 245, 247, 328)) {
            val root = render(context, settings(), width)
            val icon = root.findViewById<ImageView>(R.id.weather_icon)
            assertEquals(52, icon.width)
            assertEquals(52, icon.height)
            assertEquals(0, icon.paddingBottom)
            val actualPixels = IntArray(52 * 52)
            WidgetTestViews.draw(icon).getPixels(actualPixels, 0, 52, 0, 0, 52, 52)
            assertTrue(
                "The icon must retain its normalized content bounds at ${width}dp",
                expectedPixels.contentEquals(actualPixels),
            )
        }
    }

    @Test
    fun weatherArtworkKeepsTransparentEdgesAndDistinctNightLayers() {
        val context = fixture(isDay = false)
        val icons =
            listOf(
                R.drawable.weather_clear_day,
                R.drawable.weather_clear_night,
                R.drawable.weather_partly_cloudy_day,
                R.drawable.weather_partly_cloudy_night,
                R.drawable.weather_cloudy,
                R.drawable.weather_fog,
                R.drawable.weather_rain,
                R.drawable.weather_sleet,
                R.drawable.weather_snow,
                R.drawable.weather_thunderstorm,
            )
        for (resource in icons) {
            val bitmap = Bitmap.createBitmap(51, 51, Bitmap.Config.ARGB_8888)
            checkNotNull(context.getDrawable(resource)).apply {
                setBounds(0, 0, 51, 51)
                draw(Canvas(bitmap))
            }
            for (edge in 0 until 51) {
                assertEquals(0, bitmap.getPixel(edge, 0) ushr 24)
                assertEquals(0, bitmap.getPixel(edge, 50) ushr 24)
                assertEquals(0, bitmap.getPixel(0, edge) ushr 24)
                assertEquals(0, bitmap.getPixel(50, edge) ushr 24)
            }
        }
        val root = render(context, settings(), 328)
        val bitmap = WidgetTestViews.draw(root.findViewById<ImageView>(R.id.weather_icon))
        var moon = 0
        var cloud = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (pixel ushr 24 < 200) continue
            val red = (pixel ushr 16) and 255
            val green = (pixel ushr 8) and 255
            val blue = pixel and 255
            if (red > 200 && green > 140 && blue < green - 30) moon++
            if (blue > red + 5 && green > red && red > 120) cloud++
        }
        assertTrue("The night moon must remain visually separate from its cloud", moon >= 30)
        assertTrue("The cloud must retain a filled foreground", cloud >= 200)

        val crescent = Bitmap.createBitmap(51, 51, Bitmap.Config.ARGB_8888)
        checkNotNull(context.getDrawable(R.drawable.weather_clear_night)).apply {
            setBounds(0, 0, 51, 51)
            draw(Canvas(crescent))
        }
        val visible =
            (0 until 51).flatMap { y ->
                (0 until 51).filter { x -> crescent.getPixel(x, y) ushr 24 >= 200 }
                    .map { x -> x to y }
            }
        assertTrue("The night symbol must contain visible artwork", visible.isNotEmpty())
        val left = visible.minOf { it.first }
        val right = visible.maxOf { it.first }
        val middle = (visible.minOf { it.second } + visible.maxOf { it.second }) / 2
        assertTrue(
            "The crescent must retain an open inner contour",
            crescent.getPixel(left + (right - left) * 3 / 4, middle) ushr 24 < 30,
        )
        assertTrue(
            "The crescent must retain a visible side at its midpoint",
            (left..right).any { x -> crescent.getPixel(x, middle) ushr 24 >= 200 },
        )
    }

    @Test
    @Config(sdk = [34, 36])
    fun nonlinearFontScalingKeepsNativeBaselinesAlignedAndIconsInsideTheAllocation() {
        val originalScale = RuntimeEnvironment.getFontScale()
        try {
            RuntimeEnvironment.setFontScale(1.3f)
            val metrics = application.resources.displayMetrics
            val control =
                TextView(application).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                }
            assertEquals(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30f, metrics),
                control.textSize,
                .01f,
            )
            assertTrue(
                "The native fixture must apply a nonlinear SP curve",
                kotlin.math.abs(control.textSize - 30f * metrics.scaledDensity) > metrics.density,
            )
            for (width in listOf(203, 328)) {
                val context = fixture()
                val root = render(context, settings(), width)
                assertEquals(
                    visualBaseline(root, R.id.clock_date),
                    visualBaseline(root, R.id.weather_caption),
                    1f,
                )
                val caption = root.findViewById<TextView>(R.id.weather_caption)
                assertTrue(caption.text.contains("20°C"))
                assertEquals(0, caption.layout.getEllipsisCount(0))
                val icon = root.findViewById<ImageView>(R.id.weather_icon)
                val iconTop = visualTop(root, icon)
                assertTrue(iconTop >= 0)
                assertTrue(iconTop + icon.height <= root.height)
                if (width >= 245) {
                    val maximumIcon = visualBaseline(root, R.id.clock_date) - caption.baseline
                    if (maximumIcon < 51f) assertTrue(icon.width <= maximumIcon + 1f)
                }
            }
            AppLocales.setLanguage(application, "uk")
            val emptyContext = fixture(snapshot = false)
            markLoading(emptyContext)
            val empty = render(emptyContext, settings(), 167)
            val caption = empty.findViewById<TextView>(R.id.weather_caption)
            assertEquals(
                AppLocales.text(emptyContext, R.string.weather_loading_short),
                caption.text.toString(),
            )
            assertWholeWords(caption, "uk at font scale 1.3")
            assertEquals(View.GONE, empty.findViewById<View>(R.id.weather_icon).visibility)
        } finally {
            RuntimeEnvironment.setFontScale(originalScale)
        }
    }

    @Test
    fun positiveAndNegativeDateOffsetsDoNotReflowTimeOrWeather() {
        for (width in listOf(203, 328)) {
            val context = fixture()
            val settings = settings()
            val original = render(context, settings, width)
            val timeSize = original.findViewById<TextClock>(R.id.clock_time).textSize
            val caption = original.findViewById<TextView>(R.id.weather_caption).text.toString()
            val weatherBounds = bounds(original, R.id.weather_caption)
            val weatherBaseline = visualBaseline(original, R.id.weather_caption)
            val iconBounds = bounds(original, R.id.weather_icon)
            for (x in listOf(-24, 0, 24, 40)) for (y in listOf(-24, 24)) {
                settings.dateOffsetX = x
                settings.dateOffsetY = y
                val moved = render(context, settings, width)
                assertEquals(timeSize, moved.findViewById<TextClock>(R.id.clock_time).textSize, 0f)
                assertEquals(
                    caption,
                    moved.findViewById<TextView>(R.id.weather_caption).text.toString(),
                )
                assertEquals(weatherBounds, bounds(moved, R.id.weather_caption))
                assertEquals(iconBounds, bounds(moved, R.id.weather_icon))
                assertEquals(weatherBaseline, visualBaseline(moved, R.id.weather_caption), 0f)
            }
        }
    }

    @Test
    fun horizontalWeatherSharesDetailsWithoutTakingAThirdClockColumn() {
        for (width in listOf(167, 203, 247, 328)) {
            val context = fixture()
            val settings =
                settings().apply {
                    orientation = WidgetOrientation.HORIZONTAL
                    showAlarm = true
                    showNoAlarm = true
                }
            val root = render(context, settings, width)
            val time = root.findViewById<View>(R.id.clock_time_row)
            val details = root.findViewById<View>(R.id.clock_date_row)
            assertEquals(width / 2f, time.width.toFloat(), 1f)
            assertEquals(width / 2f - 8, details.width.toFloat(), 1f)
            assertTrue(visualBounds(root, R.id.clock_date).left >= width / 2)
            val caption = root.findViewById<TextView>(R.id.weather_caption)
            assertTrue(caption.text.contains("20°C"))
            assertEquals(1, caption.lineCount)
            assertEquals(0, caption.layout.getEllipsisCount(0))
            assertTrue(bounds(root, R.id.weather_panel).bottom <= root.height)
            assertTrue(inkBounds(root, R.id.clock_alarm).bottom <= root.height)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.clock_alarm_row).visibility)
        }
    }

    @Test
    fun cachedWeatherRetainsOneLineAndMissingDataHasNoWeatherIconOrTemperature() {
        AppLocales.setLanguage(application, "de")
        val staleContext = fixture(age = Duration.ofHours(2).toMillis())
        val stale = render(staleContext, settings(), 328)
        val caption = stale.findViewById<TextView>(R.id.weather_caption)
        assertFalse(
            caption.text.contains(AppLocales.text(staleContext, R.string.weather_stale_short)),
        )
        assertTrue(
            caption.contentDescription
                .toString()
                .contains(AppLocales.text(staleContext, R.string.weather_stale_short)),
        )
        assertTrue(caption.text.contains("20°C"))
        assertEquals(1, caption.lineCount)

        val emptyContext = fixture(snapshot = false)
        markLoading(emptyContext)
        val loading = render(emptyContext, settings(), 203)
        assertEquals(
            AppLocales.text(emptyContext, R.string.weather_loading_short),
            loading.findViewById<TextView>(R.id.weather_caption).text.toString(),
        )
        assertEquals(View.GONE, loading.findViewById<View>(R.id.weather_icon).visibility)
        assertFalse(loading.findViewById<TextView>(R.id.weather_caption).text.contains("°C"))

        val unavailableSettings = settings().apply { weatherPlace = "" }
        val unavailable = render(emptyContext, unavailableSettings, 203)
        assertEquals(
            AppLocales.text(emptyContext, R.string.weather_no_data),
            unavailable.findViewById<TextView>(R.id.weather_caption).text.toString(),
        )
    }

    @Test
    fun cachedWeatherAgeDoesNotChangeCaptionOrIconGeometry() {
        for (width in listOf(125, 167, 203, 247, 328)) {
            val fresh = render(fixture(), settings(), width)
            val cached = render(fixture(age = Duration.ofHours(2).toMillis()), settings(), width)
            val freshCaption = fresh.findViewById<TextView>(R.id.weather_caption)
            val cachedCaption = cached.findViewById<TextView>(R.id.weather_caption)
            assertEquals(freshCaption.text.toString(), cachedCaption.text.toString())
            assertEquals(freshCaption.textSize, cachedCaption.textSize, 0f)
            assertEquals(1, cachedCaption.lineCount)
            assertEquals(bounds(fresh, R.id.weather_caption), bounds(cached, R.id.weather_caption))
            assertEquals(bounds(fresh, R.id.weather_icon), bounds(cached, R.id.weather_icon))
        }
    }

    @Test
    fun anIdleEmptyCacheHasNoLoadingStateOrInventedTemperature() {
        val context = fixture(snapshot = false)
        val settings = settings()
        val state = WeatherRepository.state(context, WeatherRequest.capture(settings))
        assertEquals(WeatherStatus.UNAVAILABLE, state.status)
        assertNull(state.failureStatus)
        assertNull(state.snapshot)
        for (width in listOf(167, 328)) {
            val root = render(context, settings, width)
            val caption = root.findViewById<TextView>(R.id.weather_caption)
            assertEquals(AppLocales.text(context, R.string.weather_no_data), caption.text.toString())
            assertEquals(AppLocales.text(context, R.string.weather_unavailable), caption.contentDescription.toString())
            assertFalse(caption.text.contains("°C"))
            assertEquals(View.GONE, root.findViewById<View>(R.id.weather_icon).visibility)
            assertWholeWords(caption, "Idle weather at ${width}dp")
        }
    }

    @Test
    fun missingLocationPermissionIsExplainedWithoutAnInventedCondition() {
        val context = fixture(snapshot = false)
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        val root = render(context, settings().apply { weatherUseDevice = true }, 328)
        assertEquals(
            AppLocales.text(context, R.string.weather_no_data),
            root.findViewById<TextView>(R.id.weather_caption).text.toString(),
        )
        assertEquals(
            AppLocales.text(context, R.string.weather_permission_required),
            root.findViewById<TextView>(R.id.weather_caption).contentDescription.toString(),
        )
        assertEquals(View.GONE, root.findViewById<View>(R.id.weather_icon).visibility)
    }

    @Test
    fun compactEmptyStatesKeepWholeWordsAndDetailedAccessibleDescriptions() {
        for (language in AppLocales.supportedTags) {
            AppLocales.setLanguage(application, language)
            for (width in listOf(125, 167)) {
                val context = fixture(snapshot = false)
                markLoading(context)
                for (loading in listOf(true, false)) {
                    val settings = settings().apply { if (!loading) weatherPlace = "" }
                    val root = render(context, settings, width)
                    val caption = root.findViewById<TextView>(R.id.weather_caption)
                    assertEquals(
                        AppLocales.text(
                            context,
                            if (loading) R.string.weather_loading_short
                            else R.string.weather_no_data,
                        ),
                        caption.text.toString(),
                    )
                    assertEquals(
                        AppLocales.text(
                            context,
                            if (loading) R.string.weather_loading else R.string.weather_unavailable,
                        ),
                        caption.contentDescription.toString(),
                    )
                    assertWholeWords(caption, "$language at ${width}dp")
                    assertEquals(View.GONE, root.findViewById<View>(R.id.weather_icon).visibility)
                    assertFalse(caption.text.contains("°C"))
                }
            }
        }
    }

    @Test
    fun retainedWeatherWithRevokedLocationPermissionKeepsItsAgeInTheAccessibleDescription() {
        val context = fixture(age = Duration.ofHours(3).toMillis())
        File(context.noBackupFilesDir, "weather_location.json")
            .writeText(place.encode(), Charsets.UTF_8)
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        val root = render(context, settings().apply { weatherUseDevice = true }, 328)
        val caption = root.findViewById<TextView>(R.id.weather_caption)
        assertTrue(caption.text.contains("20°C"))
        assertFalse(caption.text.contains(AppLocales.text(context, R.string.weather_stale_short)))
        assertTrue(
            caption.contentDescription
                .toString()
                .contains(AppLocales.text(context, R.string.weather_stale_short)),
        )
        assertEquals(1, caption.lineCount)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.weather_icon).visibility)
    }

    @Test
    fun conditionCodesKeepTheirDescriptionsAndArtwork() {
        for ((codes, label, artwork) in
            listOf(
                Triple(intArrayOf(0), R.string.weather_clear, R.drawable.weather_clear_day),
                Triple(intArrayOf(1), R.string.weather_mainly_clear, R.drawable.weather_partly_cloudy_day),
                Triple(intArrayOf(2), R.string.weather_partly_cloudy, R.drawable.weather_partly_cloudy_day),
                Triple(intArrayOf(3), R.string.weather_overcast, R.drawable.weather_cloudy),
                Triple(intArrayOf(45, 48), R.string.weather_fog, R.drawable.weather_fog),
                Triple(intArrayOf(51, 53, 55), R.string.weather_drizzle, R.drawable.weather_rain),
                Triple(intArrayOf(56, 57), R.string.weather_freezing_drizzle, R.drawable.weather_sleet),
                Triple(intArrayOf(61, 63, 65), R.string.weather_rain, R.drawable.weather_rain),
                Triple(intArrayOf(66, 67), R.string.weather_freezing_rain, R.drawable.weather_sleet),
                Triple(intArrayOf(71, 73, 75), R.string.weather_snow, R.drawable.weather_snow),
                Triple(intArrayOf(77), R.string.weather_snow_grains, R.drawable.weather_snow),
                Triple(intArrayOf(80, 81, 82), R.string.weather_rain_showers, R.drawable.weather_rain),
                Triple(intArrayOf(85, 86), R.string.weather_snow_showers, R.drawable.weather_snow),
                Triple(intArrayOf(95, 97), R.string.weather_thunderstorm, R.drawable.weather_thunderstorm),
                Triple(intArrayOf(96, 99), R.string.weather_thunderstorm_hail, R.drawable.weather_thunderstorm),
            )) {
            for (code in codes) for (isDay in listOf(true, false)) {
                val context = fixture(code = code, isDay = isDay)
                val root = render(context, settings(), 328)
                val caption = root.findViewById<TextView>(R.id.weather_caption)
                assertEquals(
                    "WMO $code, day=$isDay",
                    "${AppLocales.text(context, label)}  20°C",
                    caption.contentDescription.toString(),
                )
                val expectedResource =
                    when {
                        !isDay && artwork == R.drawable.weather_clear_day ->
                            R.drawable.weather_clear_night
                        !isDay && artwork == R.drawable.weather_partly_cloudy_day ->
                            R.drawable.weather_partly_cloudy_night
                        else -> artwork
                    }
                val icon = root.findViewById<ImageView>(R.id.weather_icon)
                assertEquals(View.VISIBLE, icon.visibility)
                assertTrue(icon.width > 0 && icon.height > 0)
                val expected = Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888)
                val actual = WidgetTestViews.draw(icon)
                try {
                    checkNotNull(context.getDrawable(expectedResource)).apply {
                        setBounds(0, 0, expected.width, expected.height)
                        draw(Canvas(expected))
                    }
                    assertTrue("WMO $code, day=$isDay artwork", expected.sameAs(actual))
                } finally {
                    expected.recycle()
                    actual.recycle()
                }
            }
        }
    }

    @Test
    fun weatherChangesRequireCompletePreviewRendering() {
        val context = fixture()
        val settings = settings()
        val root = render(context, settings, 328)
        assertFalse(WidgetRenderer.updatePreviewAppearance(root, settings, false))
        settings.weatherEnabled = false
        assertFalse(WidgetRenderer.updatePreviewAppearance(root, settings, false))
        val plain = render(context, settings, 328)
        assertNull(plain.findViewById<View>(R.id.weather_panel))
        assertTrue(WidgetRenderer.updatePreviewAppearance(plain, settings, false))
    }

    @Test
    fun nativeClocksContinueToAdvanceInsideWeatherComposition() {
        val context = fixture()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val parent = FrameLayout(activity.get())
            activity.get().setContentView(parent)
            val settings = settings().apply { timeFormat = "HH:mm" }
            val root =
                WidgetRenderer.preview(context, -1, false, settings, 328f, 82f)
                    .apply(context, parent)
            parent.addView(root, FrameLayout.LayoutParams(328, 82))
            measure(root, 328, 82)
            val clock = root.findViewById<TextClock>(R.id.clock_time)
            val before = clock.text.toString()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            context.sendBroadcast(Intent(Intent.ACTION_TIME_TICK))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertNotEquals(before, clock.text.toString())
            assertEquals("HH:mm", clock.format24Hour.toString())
            assertTrue(root.findViewById<View>(R.id.clock_date) is TextClock)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(sdk = [36])
    fun oneCacheReadServesEveryResponsiveAlternativeAndConfiguredTapSurvives() {
        val context = fixture()
        val settings = settings().apply { tapAction = "settings" }
        WeatherRepository.state(context, WeatherRequest.capture(settings))
        context.reads = 0
        val id = 920
        WidgetSettingsStore.save(context, id, false, settings)
        val manager = AppWidgetManager.getInstance(context)
        val info =
            AppWidgetProviderInfo().apply {
                provider = ComponentName(context, ClockWidgetProvider::class.java)
            }
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        manager.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    ArrayList((0..15).map { SizeF(203f + it * 9, 82f + it * 2) }),
                )
            },
        )
        context.reads = 0
        val payload = WidgetRenderer.clock(context, id)
        assertEquals(1, context.reads)
        val parent = FrameLayout(context)
        val root = payload.apply(context, parent)
        assertTrue(root.performClick())
        assertTrue(root.findViewById<View>(R.id.clock_time).performClick())
        val intent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(id, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    @Test
    fun temperatureUnitsReuseTheCanonicalCacheAndKeepWidgetColorsIndependentOfAppTheme() {
        val context = fixture(temperature = -.4)
        val preferences = AppPreferences(context)
        val settings = settings().apply { weatherColor = 0xffd47821.toInt() }
        val cache = File(context.noBackupFilesDir, "weather_cache.json")
        val original = cache.readBytes()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            preferences.setThemeMode(theme)
            for ((unit, suffix) in listOf(
                TemperatureUnit.CELSIUS to "0°C",
                TemperatureUnit.FAHRENHEIT to "31°F",
            )) {
                preferences.setTemperatureUnit(unit)
                for (width in listOf(167, 247, 328)) {
                    val root = render(context, settings, width)
                    val caption = root.findViewById<TextView>(R.id.weather_caption)
                    assertTrue(caption.text.toString().endsWith(suffix))
                    assertTrue(caption.contentDescription.toString().contains(suffix))
                    assertEquals(settings.weatherColor, caption.currentTextColor)
                    assertEquals(0, caption.layout.getEllipsisCount(0))
                }
                assertArrayEquals(original, cache.readBytes())
                assertEquals(-.4, WeatherRepository.state(context, WeatherRequest.capture(settings)).snapshot!!.temperatureC, 0.0)
            }
        }
    }

    @Test
    @Config(sdk = [36])
    fun responsiveAlternativesCaptureTheUnitOnce() {
        val source = fixture(temperature = 20.2)
        var unitReads = 0
        val context = object : ContextWrapper(source) {
            override fun getApplicationContext(): Context = this

            override fun getNoBackupFilesDir(): File = source.noBackupFilesDir

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = super.getSharedPreferences(name, mode)
                if (name != "noonlet_app") return delegate
                return object : SharedPreferences by delegate {
                    override fun getString(key: String?, fallback: String?): String? =
                        if (key == "temperature_unit") {
                            unitReads++
                            if (unitReads == 1) "fahrenheit" else "celsius"
                        } else delegate.getString(key, fallback)
                }
            }
        }
        val id = 921
        WidgetSettingsStore.save(context, id, false, settings())
        val manager = AppWidgetManager.getInstance(context)
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, ClockWidgetProvider::class.java)
        }
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                ArrayList((0..15).map { SizeF(203f + it * 9, 82f + it * 2) }))
        })
        val payload = WidgetRenderer.clock(context, id)
        assertEquals(1, unitReads)
        val root = payload.apply(context, FrameLayout(context))
        assertTrue(root.findViewById<TextView>(R.id.weather_caption).text.toString().endsWith("68°F"))
        assertEquals(1, unitReads)
    }

    @Test
    fun largeArtworkKeepsClockTextWithinItsAllocatedColumn() {
        val context = fixture()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            for (width in listOf(203, 224, 328)) {
                val root = render(context, settings().apply {
                    textSize = 65
                    weatherTextFormat = WeatherTextFormat.TEMPERATURE
                    weatherIconSize = 80
                }, width, 174)
                (root.parent as android.view.ViewGroup).removeView(root)
                activity.get().setContentView(root)
                measure(root, width, 174)
                val time = root.findViewById<TextView>(R.id.clock_time)
                assertTrue("$width: ${time.layout.getLineWidth(0)} exceeds ${time.width}",
                    time.layout.getLineWidth(0) <= time.width + 1)
                assertFalse(Rect.intersects(inkBounds(root, R.id.clock_time),
                    visualBounds(root, R.id.weather_icon)))
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun artworkChangesRemeasureAnExistingWidgetInBothDirections() {
        val context = fixture()
        val styled = settings().apply { weatherTextFormat = WeatherTextFormat.TEMPERATURE }
        val root = render(context, styled, 328, 82)
        for (size in listOf(16, 80, 32, 64, 51)) {
            styled.weatherIconSize = size
            WidgetRenderer.preview(context, -1, false, styled, 328f, 82f).reapply(context, root)
            measure(root, 328, 82)
            val icon = root.findViewById<ImageView>(R.id.weather_icon)
            val caption = root.findViewById<TextView>(R.id.weather_caption)
            val expected = minOf(size, 82 - caption.height)
            assertTrue("Icon $size: ${icon.width} / $expected", icon.width in expected - 1..expected)
            assertEquals(icon.width, icon.height)
            assertTrue(visualBounds(root, R.id.weather_caption).bottom <= 82)
        }
    }

    @Test
    fun requestedArtworkSizeUsesAvailableSpaceIndependentlyOfCaptionWidth() {
        val context = fixture()
        for (width in listOf(224, 328)) for (height in listOf(82, 174)) {
            var previous = 0
            for (size in listOf(16, 32, 51, 64, 80)) {
                val styled = settings().apply {
                    weatherTextFormat = WeatherTextFormat.TEMPERATURE
                    weatherIconSize = size
                }
                val root = render(context, styled, width, height)
                val icon = root.findViewById<ImageView>(R.id.weather_icon)
                val caption = root.findViewById<TextView>(R.id.weather_caption)
                val expected = minOf(size, height - caption.height)
                assertTrue("$width x $height, icon $size: ${icon.width} / $expected",
                    icon.width in expected - 1..expected)
                assertEquals(icon.width, icon.height)
                assertTrue(icon.width >= previous)
                assertTrue(visualBounds(root, R.id.weather_icon).top >= 0)
                assertTrue(visualBounds(root, R.id.weather_caption).bottom <= height)
                previous = icon.width
            }
        }
    }

    private fun markLoading(context: Context) {
        val queued = ArrayDeque<Runnable>()
        val store = WeatherStore(context, java.util.concurrent.Executor { queued.add(it) },
            weatherClient { throw AssertionError("A pending rendering fixture must not access the network") })
        val request = WeatherRequest.capture(settings())
        val pending = store.refresh(request)
        assertEquals(1, queued.size)
        assertFalse(pending.isDone)
        assertEquals(WeatherStatus.LOADING, store.state(request).status)
        WeatherRepository.store = store
    }

    @Test
    fun aSeparateWeatherCaptionDoesNotResizeTheDate() {
        val context = fixture()
        val settings = settings().apply {
            dateFormat = "'Wednesday, 18 September'"
            dateSize = 24
            weatherOffsetY = -24
        }
        val withoutWeather = render(context, settings.apply { weatherEnabled = false }, 368, 174)
        val preferred = withoutWeather.findViewById<TextView>(R.id.clock_date).textSize
        settings.weatherEnabled = true
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
          for (size in listOf(10, 13, 18, 24, 30)) {
            settings.weatherTextSize = size
            val root = render(context, settings, 368, 174)
            val pixelWidth = root.width
            val pixelHeight = root.height
            (root.parent as android.view.ViewGroup).removeView(root)
            controller.get().setContentView(root)
            measure(root, pixelWidth, pixelHeight)
            val date = inkBounds(root, R.id.clock_date)
            assertFalse(Rect.intersects(date, inkBounds(root, R.id.weather_caption)))
            assertFalse(Rect.intersects(date, visualBounds(root, R.id.weather_icon)))
            assertEquals("Independent date size with weather size $size", preferred,
                root.findViewById<TextView>(R.id.clock_date).textSize, .01f)
          }
        }
    }

    @Test
    fun aLargerHostKeepsTheAlarmAlignedWithThePrimaryText() {
        val context = fixture()
        val oldAlarm = android.provider.Settings.System.getString(context.contentResolver, DeviceAlarmClock.SETTING)
        android.provider.Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, "Wed 08:26")
        try {
            Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
                val settings = settings().apply { textSize = 44; dateSize = 14; showAlarm = true; alarmOffsetY = -24 }
                val expected = render(context, settings, 328, 94)
                val expectedGap = visualBaseline(expected, R.id.clock_alarm) - visualBaseline(expected, R.id.clock_date)
                val root = WidgetRenderer.preview(context, -1, false, settings, 328f, 82f)
                    .apply(context, FrameLayout(context))
                controller.get().setContentView(root)
                measure(root, px(context, 328), px(context, 94))
                assertEquals(expectedGap,
                    visualBaseline(root, R.id.clock_alarm) - visualBaseline(root, R.id.clock_date), 1f)
                assertEquals(expected.findViewById<TextView>(R.id.clock_alarm).textSize,
                    root.findViewById<TextView>(R.id.clock_alarm).textSize, .1f)
            }
        } finally {
            android.provider.Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, oldAlarm)
        }
    }

    private fun settings() =
        WidgetSettings().apply {
            alarmSize = 16
            alarmOffsetX = 0
            alarmOffsetY = 0
            weatherEnabled = true
            weatherUseDevice = false
            weatherPlace = place.encode()
            timeFormat = "'08:47'"
            dateFormat = "'Mon, 12 Jan'"
        }

    private fun fixture(
        code: Int = 2,
        temperature: Double = 20.2,
        age: Long = 0,
        snapshot: Boolean = true,
        isDay: Boolean = true,
    ): FixtureContext {
        val directory = File(application.cacheDir, "weather-render-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        directories += directory
        if (snapshot) {
            val timestamp = System.currentTimeMillis() - age
            File(directory, "weather_cache.json")
                .writeText(
                    JSONObject()
                        .put("provider", WeatherClient.SOURCE)
                        .put(
                            "entries",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("place", place.encode())
                                        .put("temperatureC", temperature)
                                        .put("weatherCode", code)
                                        .put("isDay", isDay)
                                        .put("validAt", timestamp)
                                        .put("fetchedAt", timestamp),
                                ),
                        )
                        .toString(),
                    Charsets.UTF_8,
                )
        }
        return FixtureContext(application, directory)
    }

    private class FixtureContext(context: Context, private val directory: File) :
        ContextWrapper(context) {
        var reads = 0

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File {
            reads++
            return directory
        }
    }

    private fun render(context: Context, settings: WidgetSettings, width: Int, height: Int = 82): View {
        val parent = FrameLayout(context)
        val root =
            WidgetRenderer.preview(context, -1, false, settings, width.toFloat(), height.toFloat())
                .apply(context, parent)
        val density = context.resources.displayMetrics.density
        val pixelWidth = kotlin.math.round(width * density).toInt()
        val pixelHeight = kotlin.math.round(height * density).toInt()
        parent.addView(root, FrameLayout.LayoutParams(pixelWidth, pixelHeight))
        measure(root, pixelWidth, pixelHeight)
        return root
    }

    private fun measure(root: View, width: Int, height: Int) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        WidgetTestViews.prepare(root)
    }

    private fun bounds(root: View, id: Int): Rect {
        val view = root.findViewById<View>(id)
        val rect = Rect(0, 0, view.width, view.height)
        (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(view, rect)
        return rect
    }

    private fun visualBaseline(root: View, id: Int): Float {
        val text = root.findViewById<TextView>(id)
        return text.baseline + visualTop(root, text)
    }

    private fun inkBounds(root: View, id: Int): Rect {
        val view = root.findViewById<View>(id)
        val bitmap = WidgetTestViews.draw(view)
        val ink = Rect()
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) ushr 24 > 32) ink.union(x, y, x + 1, y + 1)
            }
        } finally { bitmap.recycle() }
        assertFalse("Visible text for $id", ink.isEmpty)
        val box = visualBounds(root, id)
        ink.offset(box.left, box.top)
        return ink
    }

    private fun visualBounds(root: View, id: Int): Rect {
        val child = root.findViewById<View>(id)
        var view: View = child
        var x = 0f
        var y = 0f
        while (view !== root) {
            x += view.left + view.translationX
            y += view.top + view.translationY
            view = view.parent as View
            x -= view.scrollX
            y -= view.scrollY
        }
        val left = kotlin.math.round(x).toInt()
        val top = kotlin.math.round(y).toInt()
        return Rect(left, top, left + child.width, top + child.height)
    }

    private fun visualTop(root: View, target: View): Float {
        var result = 0f
        var view: View = target
        while (view !== root) {
            result += view.top + view.translationY
            val parent = view.parent as View
            result -= parent.scrollY
            view = parent
        }
        return result
    }

    private fun assertWholeWords(caption: TextView, description: String) {
        for (line in 0 until caption.lineCount) {
            val end = caption.layout.getLineEnd(line)
            if (end in 1 until caption.text.length)
                assertTrue(
                    "$description must keep words intact",
                    caption.text[end - 1].isWhitespace() || caption.text[end].isWhitespace(),
                )
        }
    }
}
