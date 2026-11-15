package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextClock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [26, 30, 36],
    qualifiers = "en-rUS-w400dp-h850dp-mdpi",
    shadows = [CountedLocaleManager::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewOffsetTest {
    private lateinit var context: Context
    private var previousPaused: Boolean = false
    private var previousDelay: Duration? = null

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name: String in
            arrayOf<String>(
                "noonlet_widgets",
                "MainActivity",
                "widget_drafts",
                "widget_configuration",
            )) context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        previousPaused = ShadowChoreographer.isPaused()
        previousDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After
    fun restoreFrames() {
        ShadowChoreographer.setFrameDelay(previousDelay)
        ShadowChoreographer.setPaused(previousPaused)
    }

    @Test
    fun fastOffsetsMatchCanonicalFramesAndPreserveNativeTypography() {
        for (orientation: String in arrayOf<String>("vertical", "horizontal")) for (sign: Int in
            intArrayOf(-1, 1)) {
            val settings: WidgetSettings = WidgetSettings()
            settings.orientation = WidgetOrientation.fromWire(orientation)
            settings.showAlarm = true
            settings.showNoAlarm = true
            settings.timeFormat = "'08:07'"
            settings.dateFormat = "'Fri, 30 Oct'"
            settings.dateOffsetY = 0
            settings.dateOffsetX = settings.dateOffsetY
            settings.timeOffsetY = settings.dateOffsetX
            settings.timeOffsetX = settings.timeOffsetY
            val current: View = render(settings, 328, 174)
            val retained: Typography = Typography(current)
            fun position(id: Int): Pair<Float, Float> {
                var view = current.findViewById<View>(id)
                var x = 0f
                var y = 0f
                while (view !== current) {
                    x += view.x
                    y += view.y
                    view = view.parent as View
                }
                return x to y
            }
            val beforeTime = position(R.id.clock_time)
            val beforeDate = position(R.id.clock_date)
            settings.timeOffsetX = sign * 7
            settings.timeOffsetY = sign * -11
            settings.dateOffsetX = sign * -13
            settings.dateOffsetY = sign * 17
            settings.alarmOffsetX = sign * 19
            settings.alarmOffsetY = sign * -5
            assertTrue(WidgetRenderer.updatePreviewAppearance(current, settings, false))
            retained.assertUnchanged(current)
            measure(current, 328, 174)
            retained.assertUnchanged(current)
            assertEquals(beforeTime.first + sign * 7, position(R.id.clock_time).first, .01f)
            assertEquals(beforeTime.second - sign * 11, position(R.id.clock_time).second, .01f)
            assertEquals(beforeDate.first - sign * 13, position(R.id.clock_date).first, .01f)
            assertEquals(beforeDate.second + sign * 17, position(R.id.clock_date).second, .01f)
            val canonical: View = render(settings, 328, 174)
            for (id: Int in
                intArrayOf(
                    R.id.clock_content,
                    R.id.clock_time_row,
                    R.id.clock_date_row,
                    R.id.clock_time,
                    R.id.clock_date,
                    R.id.clock_alarm_row,
                    R.id.clock_alarm_icon,
                    R.id.clock_alarm,
                )) {
                val actual: View = current.findViewById<View>(id)
                val expected: View = canonical.findViewById<View>(id)
                assertEquals(expected.getLeft(), actual.getLeft())
                assertEquals(expected.getTop(), actual.getTop())
                assertEquals(expected.getRight(), actual.getRight())
                assertEquals(expected.getBottom(), actual.getBottom())
            }
            assertTrue(
                "The complete frame, including all three rows and signed offsets, matches the provider",
                WidgetTestViews.draw(canonical).sameAs(WidgetTestViews.draw(current)),
            )
        }
    }

    @Test
    fun incompleteClockHierarchiesAreRejectedBeforeAnyOffsetChanges() {
        for (missing in
            intArrayOf(R.id.clock_date_row, R.id.clock_alarm_row, R.id.clock_alarm_icon,
                R.id.clock_time_position, R.id.clock_date_position, R.id.clock_alarm_position)) {
            val settings = WidgetSettings().apply { dateOffsetX = 0 }
            val current = render(settings, 167, 82)
            current.findViewById<View>(missing).id = View.NO_ID
            settings.timeOffsetX = 40
            settings.timeOffsetY = -40
            settings.alarmOffsetX = -40
            settings.alarmOffsetY = 40
            assertFalse(WidgetRenderer.updatePreviewAppearance(current, settings, false))
            val time = current.findViewById<View>(R.id.clock_time)
            val row = current.findViewById<View>(R.id.clock_time_row)
            assertEquals(0f, time.translationX, 0f)
            assertEquals(0f, time.translationY, 0f)
            assertEquals(0, row.paddingLeft)
            assertEquals(0, row.paddingTop)
        }
    }

    @Test
    @Throws(Exception::class)
    fun sixOffsetControlsShareAnEarlyFrameInEitherOrderAndSaveTogether() {
        for (alarmFirst: Boolean in booleanArrayOf(false, true)) {
            bind()
            val intent: Intent =
                Intent(context, MainActivity::class.java)
                    .setData(Uri.parse("noonlet://edit/false/" + ID))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, ID)
            Robolectric.buildActivity(MainActivity::class.java, intent)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        activity.preview.clock = Clock.fixed(Instant.parse("2026-10-30T12:34:00Z"), ZoneOffset.UTC)
                        activity.preview.refresh(false, null)
                        var root: View = layout(activity)
                        open(root, "alarm")
                        open(root, "time")
                        open(root, "date")
                        advance(33)
                        root = layout(activity)
                        val stage: WidgetPreviewStage =
                            root.findViewWithTag<WidgetPreviewStage>("preview-stage")
                        val content: View = requireNotNull(stage.getWidgetContent())
                        val retained: Typography = Typography(content)
                        CountedLocaleManager.systemReads = 0
                        if (alarmFirst) {
                            progress(root, "Alarm horizontal offset", 19)
                            progress(root, "Alarm vertical offset", -5)
                        }
                        progress(root, "Time horizontal offset", 7)
                        progress(root, "Time vertical offset", -11)
                        progress(root, "Date horizontal offset", -13)
                        progress(root, "Date vertical offset", 17)
                        if (!alarmFirst) {
                            progress(root, "Alarm horizontal offset", 19)
                            progress(root, "Alarm vertical offset", -5)
                        }
                        advance(33)
                        assertSame(root, layout(activity))
                        assertSame(content, stage.getWidgetContent())
                        retained.assertUnchanged(content)
                        assertEquals(
                            "Offset candidates do not enter native font formatting",
                            0,
                            CountedLocaleManager.systemReads,
                        )
                        val expected: WidgetSettings = WidgetSettings()
                        expected.timeOffsetX = 7
                        expected.timeOffsetY = -11
                        expected.dateOffsetX = -13
                        expected.dateOffsetY = 17
                        expected.alarmOffsetX = 19
                        expected.alarmOffsetY = -5
                        assertOffsets(content, expected)
                        assertEquals(0, WidgetSettingsStore.load(context, ID, false).timeOffsetX)
                        advance(43)
                        val saved: WidgetSettings = WidgetSettingsStore.load(context, ID, false)
                        assertEquals(7, saved.timeOffsetX)
                        assertEquals(-11, saved.timeOffsetY)
                        assertEquals(-13, saved.dateOffsetX)
                        assertEquals(17, saved.dateOffsetY)
                        assertEquals(19, saved.alarmOffsetX)
                        assertEquals(-5, saved.alarmOffsetY)
                        assertOffsets(
                            Shadows.shadowOf(AppWidgetManager.getInstance(context)).getViewFor(ID),
                            saved,
                        )
                        assertSame(content, stage.getWidgetContent())
                        retained.assertUnchanged(content)
                    },
                )
        }
    }

    private fun render(settings: WidgetSettings, width: Int, height: Int): View {
        val parent: FrameLayout = FrameLayout(context)
        val root: View =
            WidgetRenderer.preview(
                    context,
                    -1100,
                    false,
                    settings,
                    width.toFloat(),
                    height.toFloat(),
                )
                .apply(context, parent)
        parent.addView(root, FrameLayout.LayoutParams(width, height))
        measure(root, width, height)
        return root
    }

    private fun bind() {
        val settings: WidgetSettings = WidgetSettings()
        settings.dateOffsetY = 0
        settings.dateOffsetX = settings.dateOffsetY
        settings.timeOffsetY = settings.dateOffsetX
        settings.timeOffsetX = settings.timeOffsetY
        WidgetSettingsStore.save(context, ID, false, settings)
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider = ComponentName(context, ClockWidgetProvider::class.java)
        info.initialLayout = R.layout.clock_widget
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(ID, info)
        BaseWidgetProvider.updateWidget(context, ID, false)
    }

    private class Typography(root: View) {
        val time: TextClock
        val date: TextClock
        val time12: CharSequence
        val time24: CharSequence
        val date12: CharSequence
        val date24: CharSequence
        val timeFont: Typeface
        val dateFont: Typeface
        val timeSize: Float
        val dateSize: Float

        init {
            time = root.findViewById<TextClock>(R.id.clock_time)
            date = root.findViewById<TextClock>(R.id.clock_date)
            time12 = time.getFormat12Hour()
            time24 = time.getFormat24Hour()
            date12 = date.getFormat12Hour()
            date24 = date.getFormat24Hour()
            timeFont = time.getTypeface()
            dateFont = date.getTypeface()
            timeSize = time.getTextSize()
            dateSize = date.getTextSize()
        }

        fun assertUnchanged(root: View) {
            assertSame(time, root.findViewById<View>(R.id.clock_time))
            assertSame(date, root.findViewById<View>(R.id.clock_date))
            assertSame(time12, time.getFormat12Hour())
            assertSame(time24, time.getFormat24Hour())
            assertSame(date12, date.getFormat12Hour())
            assertSame(date24, date.getFormat24Hour())
            assertSame(timeFont, time.getTypeface())
            assertSame(dateFont, date.getTypeface())
            assertEquals(timeSize, time.getTextSize(), 0f)
            assertEquals(dateSize, date.getTextSize(), 0f)
        }
    }

    companion object {
        private val ID: Int = 27101

        private fun measure(root: View, width: Int, height: Int) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
            WidgetTestViews.prepare(root)
        }

        private fun assertOffsets(root: View, settings: WidgetSettings) {
            assertNotNull(root)
            val density: Float = root.getResources().getDisplayMetrics().density
            val x: IntArray =
                intArrayOf(
                    Math.round(settings.timeOffsetX * density),
                    Math.round(settings.dateOffsetX * density),
                    Math.round(settings.alarmOffsetX * density),
                )
            val y: IntArray =
                intArrayOf(
                    Math.round(settings.timeOffsetY * density),
                    Math.round(settings.dateOffsetY * density),
                    if (settings.showAlarm && root.findViewById<View>(R.id.clock_alarm_position) != null) 0
                    else Math.round(settings.alarmOffsetY * density),
                )
            val text: IntArray = intArrayOf(R.id.clock_time, R.id.clock_date, R.id.clock_alarm_row)
            val rows: IntArray =
                intArrayOf(R.id.clock_time_row, R.id.clock_date_row, R.id.clock_alarm_row)
            for (index: Int in 0..1) {
                val position = root.findViewById<View>(intArrayOf(R.id.clock_time_position,
                    R.id.clock_date_position, R.id.clock_alarm_position)[index])
                val view: View =
                    if (Build.VERSION.SDK_INT < 31 && position != null) position
                    else root.findViewById<View>(
                        if (Build.VERSION.SDK_INT >= 31) text[index] else rows[index],
                    )
                if (Build.VERSION.SDK_INT >= 31) {
                    assertEquals(x[index].toFloat(), view.getTranslationX(), 0f)
                    assertEquals(y[index].toFloat(), view.getTranslationY(), 0f)
                } else {
                    assertEquals(x[index], view.getPaddingLeft())
                    if (position == null) assertEquals(y[index], view.getPaddingTop())
                    assertEquals(-x[index], view.getPaddingRight())
                    assertEquals(-view.paddingTop, view.getPaddingBottom())
                }
            }
        }

        private fun layout(activity: MainActivity): View {
            val root: View =
                (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
            measure(root, 400, 850)
            return root
        }

        private fun open(root: View, section: String) {
            if (
                root.findViewWithTag<View>("section-content:" + section).getVisibility() !=
                    View.VISIBLE
            )
                assertTrue(root.findViewWithTag<View>("section-toggle:" + section).performClick())
        }

        private fun progress(root: View, description: String, value: Int) {
            val slider: SeekBar? = slider(root, description)
            assertNotNull(description, slider)
            val arguments: Bundle = Bundle()
            arguments.putFloat(
                AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                value.toFloat(),
            )
            assertTrue(
                slider!!.performAccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                    arguments,
                ),
            )
        }

        private fun slider(root: View, description: String): SeekBar? {
            if (root is SeekBar && description.contentEquals(root.getContentDescription()))
                return root as SeekBar
            if (root is ViewGroup)
                for (index: Int in 0 until (root as ViewGroup).getChildCount()) {
                    val result: SeekBar? =
                        slider((root as ViewGroup).getChildAt(index), description)
                    if (result != null) return result
                }
            return null
        }

        private fun advance(millis: Int) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis.toLong()))
        }
    }
}
