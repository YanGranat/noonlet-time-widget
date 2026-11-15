package app.noonlet

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import java.io.File
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmPlacementTest {
    private lateinit var application: Context
    private var previousStore: WeatherStore? = null
    private val directories = mutableListOf<File>()
    private val place = WeatherPlace("Riverton", 49_000_000, 7_000_000)

    @Before fun prepare() {
        application = RuntimeEnvironment.getApplication()
        previousStore = WeatherRepository.store
        application.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        AppLocales.setLanguage(application, "en")
    }

    @After fun closeFixtures() {
        WeatherRepository.store = previousStore
        for (directory in directories) {
            assertEquals(application.cacheDir.canonicalFile, directory.canonicalFile.parentFile)
            directory.deleteRecursively()
        }
    }

    @Test
    fun reapplyingAlarmVisibilityKeepsItsPositionAndDateClearance() {
        val context = fixture()
        val manager = context.getSystemService(AlarmManager::class.java)
        val alarm = clockAlarm(context, 8731, Intent("app.noonlet.TEST_ALARM"),
            PendingIntent.FLAG_IMMUTABLE)
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 3_600_000, alarm), alarm)
        assertNotNull(SystemAlarmClock.triggerTime(context))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            for ((width, height) in listOf(328 to 82, 400 to 94, 328 to 174)) {
                val settings = settings().apply {
                    showAlarm = true
                    alarmSize = 14
                    alarmOffsetX = -4
                    alarmOffsetY = -24
                }
                val parent = FrameLayout(context)
                activity.get().setContentView(parent)
                fun payload() = WidgetRenderer.preview(context, -1, false, settings,
                    width.toFloat(), height.toFloat(), WidgetFont.Scope.LOCAL)
                val root = payload().apply(context, parent)
                parent.addView(root, FrameLayout.LayoutParams(width, height))
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                measure(root, width, height)
                val baseline = visualBounds(root, R.id.clock_alarm)
                val date = visualBounds(root, R.id.clock_date)
                assertFalse("Alarm intersects date at $width x $height",
                    Rect.intersects(inkBounds(root, R.id.clock_alarm), inkBounds(root, R.id.clock_date)))
                repeat(3) {
                    settings.showNoAlarm = !settings.showNoAlarm
                    payload().reapply(context, root)
                    measure(root, width, height)
                    assertEquals("Empty-label preference moved a real alarm", baseline,
                        visualBounds(root, R.id.clock_alarm))
                    settings.showAlarm = false
                    payload().reapply(context, root)
                    measure(root, width, height)
                    settings.showAlarm = true
                    payload().reapply(context, root)
                    measure(root, width, height)
                    assertEquals("Alarm visibility moved the restored row", baseline,
                        visualBounds(root, R.id.clock_alarm))
                    assertEquals("Alarm visibility moved the date", date,
                        visualBounds(root, R.id.clock_date))
                }
                val fresh = payload().apply(context, FrameLayout(context))
                parent.addView(fresh, FrameLayout.LayoutParams(width, height))
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                measure(fresh, width, height)
                assertEquals("Reapplied alarm differs from fresh inflation", baseline,
                    visualBounds(fresh, R.id.clock_alarm))
            }
        } finally {
            activity.close()
            manager.cancel(alarm)
            alarm.cancel()
        }
    }

    @Test
    fun positionedAlarmAvoidsClockAndDateInkAcrossSignedOffsets() {
        val context = fixture()
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { activity ->
            for (weather in listOf(false, true))
                for (orientation in WidgetOrientation.entries)
                for (width in listOf(167, 328))
                for (height in listOf(82, 174))
                    for (alignment in WidgetAlignment.entries)
                        for (x in listOf(-30, 0, 30))
                            for (y in listOf(-40, -24, 0, 30)) {
                                val settings = settings().apply {
                                    this.orientation = orientation
                                    weatherEnabled = weather
                                    showAlarm = true
                                    showNoAlarm = true
                                    alarmAlignment = alignment
                                    alarmOffsetX = x
                                    alarmOffsetY = y
                                }
                                val root = render(context, settings, width, height)
                                (root.parent as android.view.ViewGroup).removeView(root)
                                activity.get().setContentView(root)
                                Shadows.shadowOf(Looper.getMainLooper()).idle()
                                measure(root, width, height)
                                val label = "$weather / $orientation / $width x $height / $alignment / $x,$y"
                                val alarm = inkBounds(root, R.id.clock_alarm)
                                val icon = inkBounds(root, R.id.clock_alarm_icon)
                                for (id in listOf(R.id.clock_time, R.id.clock_date)) {
                                    val primary = inkBounds(root, id)
                                    assertFalse("$label: alarm $alarm overlaps $id $primary",
                                        Rect.intersects(alarm, primary))
                                    assertFalse("$label: alarm icon $icon overlaps $id $primary",
                                        Rect.intersects(icon, primary))
                                }
                                if (weather) for (id in listOf(R.id.weather_caption, R.id.weather_icon)) {
                                    val obstacle = inkBounds(root, id)
                                    assertFalse("$label: alarm overlaps weather $id",
                                        Rect.intersects(alarm, obstacle))
                                    assertFalse("$label: alarm icon overlaps weather $id",
                                        Rect.intersects(icon, obstacle))
                                }
                                assertTrue("$label: alarm $alarm outside $width x $height", Rect(0, 0, width, height).contains(alarm))
                                assertTrue("$label: icon $icon outside $width x $height", Rect(0, 0, width, height).contains(icon))
                            }
        }
    }

    @Test
    fun enlargedSystemTextKeepsAlarmInkClear() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        try { positionedAlarmAvoidsClockAndDateInkAcrossSignedOffsets() }
        finally { org.robolectric.RuntimeEnvironment.setFontScale(1f) }
    }

    @Test
    fun appearanceUpdatesMatchFreshGeometryAcrossAlarmOffsets() {
        val context = fixture()
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { activity ->
            for (orientation in WidgetOrientation.entries) {
                val settings = settings().apply {
                    weatherEnabled = false
                    this.orientation = orientation
                    showAlarm = true
                    showNoAlarm = true
                }
                val parent = FrameLayout(context)
                activity.get().setContentView(parent)
                fun payload() = WidgetRenderer.preview(context, -1, false, settings,
                    328f, 94f, WidgetFont.Scope.LOCAL)
                val root = payload().apply(context, parent)
                parent.addView(root, FrameLayout.LayoutParams(328, 94))
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                measure(root, 328, 94)
                for (x in listOf(-30, 0, 30)) for (y in listOf(-40, -24, 0, 30)) {
                    settings.alarmOffsetX = x
                    settings.alarmOffsetY = y
                    if (!WidgetRenderer.updatePreviewAppearance(root, settings, false)) payload().reapply(context, root)
                    measure(root, 328, 94)
                    val fresh = payload().apply(context, parent)
                    parent.addView(fresh, FrameLayout.LayoutParams(328, 94))
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    measure(fresh, 328, 94)
                    val expected = WidgetTestViews.draw(fresh)
                    val actual = WidgetTestViews.draw(root)
                    try { assertTrue("$orientation / $x,$y", actual.sameAs(expected)) }
                    finally { expected.recycle(); actual.recycle(); parent.removeView(fresh) }
                }
            }
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

}
