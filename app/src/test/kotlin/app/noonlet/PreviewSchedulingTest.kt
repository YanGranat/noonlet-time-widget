package app.noonlet

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextClock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
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
    sdk = [34, 36],
    qualifiers = "en-rUS-w400dp-h850dp-mdpi",
    shadows = [CountedLocaleManager::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewSchedulingTest {
    private lateinit var context: Context
    private var manager: AppWidgetManager? = null
    private var previousPaused: Boolean = false
    private var previousDelay: Duration? = null

    @Before
    fun prepare() {
        previousPaused = ShadowChoreographer.isPaused()
        previousDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        context = RuntimeEnvironment.getApplication()
        manager = AppWidgetManager.getInstance(context)
        for (file: String in
            arrayOf<String>(
                "noonlet_widgets",
                "MainActivity",
                "widget_drafts",
                "widget_configuration",
            )) context.getSharedPreferences(file, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        for (id: Int in intArrayOf(CLOCK, SIBLING)) {
            val value: WidgetSettings = WidgetSettings()
            value.zoneId = "UTC"
            WidgetSettingsStore.save(context, id, false, value)
            bind(id)
        }
    }

    @After
    fun restoreFrames() {
        ShadowChoreographer.setFrameDelay(previousDelay)
        ShadowChoreographer.setPaused(previousPaused)
    }

    @Test
    fun aNativeSliderGestureFlushesItsLastValueWhenPaused() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            openTime(activity)
            val bar = requireNotNull(described(layout(activity), "Time size", SeekBar::class.java))
            bar.measure(View.MeasureSpec.makeMeasureSpec(288, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
            bar.layout(0, 0, 288, 48)
            val initial = activity.session.settings.textSize
            val down = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE)) {
                val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bar.width * (if (action == android.view.MotionEvent.ACTION_DOWN) .2f else .7f),
                    bar.height / 2f, 0)
                assertTrue(bar.dispatchTouchEvent(event))
                event.recycle()
                advance(100)
            }
            val pending = activity.session.settings.textSize
            assertNotEquals(initial, pending)
            assertEquals(initial, WidgetSettingsStore.load(context, CLOCK, false).textSize)
            controller.pause()
            assertEquals(pending, WidgetSettingsStore.load(context, CLOCK, false).textSize)
            assertEquals(initial, WidgetSettingsStore.load(context, SIBLING, false).textSize)
        }
    }

    @Test
    fun touchAdjustmentKeepsTheHostUnchangedUntilRelease() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            openTime(activity)
            activity.preview.beginAdjustment()
            for (value in listOf(3, 6, 9)) {
                offset(activity, value)
                advance(100)
                assertEquals(0, WidgetSettingsStore.load(context, CLOCK, false).timeOffsetX)
                assertEquals(value.toFloat(), previewTime(activity).translationX, 0f)
            }
            activity.preview.finishAdjustment()
            assertEquals(9, WidgetSettingsStore.load(context, CLOCK, false).timeOffsetX)
            assertEquals(9f, hostTime(CLOCK).translationX, 0f)
            assertEquals(0, WidgetSettingsStore.load(context, SIBLING, false).timeOffsetX)
        }
    }

    @Test
    @Throws(Exception::class)
    fun severalSliderChangesUseOneEarlyFrameAndOneLaterAtomicCommit() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    openTime(a)
                    setClock(a, Clock.fixed(Instant.parse("2026-10-30T12:34:00Z"), ZoneOffset.UTC))
                    refresh(a, false)
                    CountedLocaleManager.systemReads = 0
                    refresh(a, false)
                    val readsPerFrame: Int = CountedLocaleManager.systemReads
                    assertTrue(
                        "The calibration exercises the real locale snapshots",
                        readsPerFrame > 0 && readsPerFrame <= 2,
                    )
                    CountedLocaleManager.systemReads = 0
                    val previous: TextClock = previewTime(a)
                    val previousFormat: CharSequence = previous.getFormat24Hour()
                    val vanillaFrames: AtomicInteger = AtomicInteger()
                    Choreographer.getInstance()
                        .postFrameCallback({ time -> vanillaFrames.incrementAndGet() })
                    val started: Long = SystemClock.uptimeMillis()
                    for (value: Int in intArrayOf(1, 3, 5, 7, 9)) offset(a, value)
                    assertEquals(0, vanillaFrames.get())
                    assertEquals(0, CountedLocaleManager.systemReads)
                    assertEquals(
                        "Offset candidates stay deferred until the native frame",
                        (0).toFloat(),
                        (previous.getTranslationX()).toFloat(),
                        (0).toFloat(),
                    )
                    assertEquals(0, WidgetSettingsStore.load(context, CLOCK, false).timeOffsetX)
                    assertEquals(
                        (0).toFloat(),
                        (hostTime(CLOCK).getTranslationX()).toFloat(),
                        (0).toFloat(),
                    )
                    advance(33)
                    assertEquals(
                        "The independent native frame callback must run before the commit deadline",
                        1,
                        vanillaFrames.get(),
                    )
                    assertTrue(SystemClock.uptimeMillis() - started < 75)
                    assertEquals(
                        "Moving existing clocks does not format them or query the locale",
                        0,
                        CountedLocaleManager.systemReads,
                    )
                    val shown: TextClock = previewTime(a)
                    assertEquals((9).toFloat(), (shown.getTranslationX()).toFloat(), (0).toFloat())
                    assertSame(previous, shown)
                    assertSame(previousFormat, shown.getFormat24Hour())
                    val frameFormat: CharSequence = shown.getFormat24Hour()
                    assertEquals(
                        "Visible candidates have not crossed the persistent boundary",
                        0,
                        WidgetSettingsStore.load(context, CLOCK, false).timeOffsetX,
                    )
                    assertEquals(
                        (0).toFloat(),
                        (hostTime(CLOCK).getTranslationX()).toFloat(),
                        (0).toFloat(),
                    )
                    advance(43)
                    assertEquals(9, WidgetSettingsStore.load(context, CLOCK, false).timeOffsetX)
                    assertEquals(
                        (9).toFloat(),
                        (hostTime(CLOCK).getTranslationX()).toFloat(),
                        (0).toFloat(),
                    )
                    assertEquals(0, WidgetSettingsStore.load(context, SIBLING, false).timeOffsetX)
                    assertEquals(0, WidgetSettingsStore.load(context, 0, false).timeOffsetX)
                    assertSame(shown, previewTime(a))
                    assertSame(
                        "Committing does not render the already current preview again",
                        frameFormat,
                        shown.getFormat24Hour(),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun pausingBeforeTheFrameFlushesTheWidgetAndCancelsHiddenVisualWork() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    openTime(a)
                    val hidden: TextClock = previewTime(a)
                    val format: CharSequence = hidden.getFormat24Hour()
                    offset(a, 8)
                    assertEquals((0).toFloat(), (hidden.getTranslationX()).toFloat(), (0).toFloat())
                    controller.pause()
                    assertEquals(8, WidgetSettingsStore.load(context, CLOCK, false).timeOffsetX)
                    assertEquals(
                        (8).toFloat(),
                        (hostTime(CLOCK).getTranslationX()).toFloat(),
                        (0).toFloat(),
                    )
                    advance(200)
                    assertSame(hidden, previewTime(a))
                    assertEquals((0).toFloat(), (hidden.getTranslationX()).toFloat(), (0).toFloat())
                    assertSame(format, hidden.getFormat24Hour())
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun immediateChangesSizeAndFullRenderConsumeBothPendingQueues() {
        for (action: String in arrayOf<String>("toggle", "size", "render")) {
            val initial: WidgetSettings = WidgetSettings()
            initial.zoneId = "UTC"
            WidgetSettingsStore.save(context, CLOCK, false, initial)
            BaseWidgetProvider.updateWidget(context, CLOCK, false)
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val a: MainActivity = controller.get()
                        openTime(a)
                        setClock(
                            a,
                            Clock.fixed(Instant.parse("2026-10-30T12:34:00Z"), ZoneOffset.UTC),
                        )
                        offset(a, 6)
                        if ("toggle".equals(action)) {
                            WidgetTestViews.reveal(layout(a), "date")
                            val visible: Switch? =
                                described(layout(a), "Show date", Switch::class.java)
                            visible!!.performClick()
                            assertFalse(visible!!.isChecked())
                        } else if ("size".equals(action))
                            assertTrue(
                                layout(a).findViewWithTag<View>("preview-size:1").performClick(),
                            )
                        else invoke(a, "render")
                        val current: TextClock = previewTime(a)
                        val format: CharSequence = current.getFormat24Hour()
                        assertEquals(
                            (6).toFloat(),
                            (current.getTranslationX()).toFloat(),
                            (0).toFloat(),
                        )
                        assertEquals(6, WidgetSettingsStore.load(context, CLOCK, false).timeOffsetX)
                        CountedLocaleManager.systemReads = 0
                        advance(100)
                        assertEquals(
                            "No superseded preview or commit is left queued after " + action,
                            0,
                            CountedLocaleManager.systemReads,
                        )
                        assertSame(current, previewTime(a))
                        assertSame(format, current.getFormat24Hour())
                    },
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun anExternalSaveWinsAtTheCommitBoundaryRatherThanBeingOverwrittenByTheCandidate() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    openTime(a)
                    val previousRoot: View = layout(a)
                    offset(a, 7)
                    advance(33)
                    assertEquals(
                        (7).toFloat(),
                        (previewTime(a).getTranslationX()).toFloat(),
                        (0).toFloat(),
                    )
                    val external: WidgetSettings = WidgetSettingsStore.load(context, CLOCK, false)
                    external.timeOffsetX = 11
                    external.showDate = false
                    WidgetSettingsStore.save(context, CLOCK, false, external)
                    BaseWidgetProvider.updateWidget(context, CLOCK, false)
                    advance(43)
                    assertNotSame(
                        "An authoritative external revision reconciles all controls",
                        previousRoot,
                        layout(a),
                    )
                    assertEquals(11, WidgetSettingsStore.load(context, CLOCK, false).timeOffsetX)
                    assertEquals(
                        (11).toFloat(),
                        (hostTime(CLOCK).getTranslationX()).toFloat(),
                        (0).toFloat(),
                    )
                    assertEquals(
                        (11).toFloat(),
                        (previewTime(a).getTranslationX()).toFloat(),
                        (0).toFloat(),
                    )
                    assertEquals(
                        51,
                        described(layout(a), "Time horizontal offset", SeekBar::class.java)!!
                            .getProgress(),
                    )
                    WidgetTestViews.reveal(layout(a), "date")
                    assertFalse(described(layout(a), "Show date", Switch::class.java)!!.isChecked())
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun anInitialConfigurationCanDrawImmediatelyWithoutSavingBeforeAdd() {
        val fresh: Int = 25103
        bind(fresh)
        val request: Intent =
            Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setClass(context, MainActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, fresh)
        Robolectric.buildActivity(MainActivity::class.java, request)
            .setup()
            .visible()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    openTime(a)
                    offset(a, 9)
                    advance(33)
                    assertEquals(
                        (9).toFloat(),
                        (previewTime(a).getTranslationX()).toFloat(),
                        (0).toFloat(),
                    )
                    assertFalse(WidgetSettingsStore.hasOwnSettings(context, fresh, false))
                    assertEquals(
                        (0).toFloat(),
                        (hostTime(fresh).getTranslationX()).toFloat(),
                        (0).toFloat(),
                    )
                    advance(43)
                    assertFalse(WidgetSettingsStore.hasOwnSettings(context, fresh, false))
                    assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(a).getResultCode())
                    assertTrue(layout(a).findViewWithTag<View>("action-add-widget").performClick())
                    assertEquals(9, WidgetSettingsStore.load(context, fresh, false).timeOffsetX)
                    assertEquals(
                        (9).toFloat(),
                        (hostTime(fresh).getTranslationX()).toFloat(),
                        (0).toFloat(),
                    )
                    assertEquals(Activity.RESULT_OK, Shadows.shadowOf(a).getResultCode())
                    assertEquals(
                        fresh,
                        Shadows.shadowOf(a)
                            .getResultIntent()
                            .getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0),
                    )
                    assertEquals(0, WidgetSettingsStore.load(context, CLOCK, false).timeOffsetX)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun oneTimestampServesTheLiteralAndItsNextBoundaryAcrossAMinuteChange() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    layout(a)
                    val clock: BoundaryClock = BoundaryClock()
                    setClock(a, clock)
                    a.preview.refresh()
                    assertEquals(
                        "A frame must not sample opposite sides of a minute boundary",
                        1,
                        clock.reads.get(),
                    )
                    assertEquals("12:34", previewTime(a).getText().toString())
                    val nativeHandler: AtomicInteger = AtomicInteger()
                    Handler(Looper.getMainLooper())
                        .postDelayed({ nativeHandler.incrementAndGet() }, 1)
                    // Native traversal barriers defer ordinary Handler work until the paused VSync
                    // runs.
                    // Allow one displayed frame, rather than interpreting a due timer as a drawn
                    // frame.
                    advance(33)
                    assertEquals(
                        "The independent 1ms Handler must pass the same traversal barrier",
                        1,
                        nativeHandler.get(),
                    )
                    assertEquals(
                        "The next minute must not be skipped for a whole minute",
                        "12:35",
                        previewTime(a).getText().toString(),
                    )
                    assertEquals(2, clock.reads.get())
                },
            )
    }

    private class BoundaryClock
    private constructor(val reads: AtomicInteger, private val currentZone: ZoneId) : Clock() {
        constructor() : this(AtomicInteger(), ZoneOffset.UTC) {}

        override fun getZone(): ZoneId = currentZone

        override fun withZone(zone: ZoneId): Clock {
            return BoundaryClock(reads, zone)
        }

        override fun instant(): Instant {
            return Instant.ofEpochMilli(millis())
        }

        override fun millis(): Long {
            return Instant.parse(
                    if (reads.getAndIncrement() == 0) "2026-10-30T12:34:59.999Z"
                    else "2026-10-30T12:35:00.002Z",
                )
                .toEpochMilli()
        }
    }

    private fun bind(id: Int) {
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider = ComponentName(context, ClockWidgetProvider::class.java)
        info.initialLayout = R.layout.clock_widget
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        val size: Bundle = Bundle()
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170)
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 170)
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        manager!!.updateAppWidgetOptions(id, size)
        BaseWidgetProvider.updateWidget(context, id, false)
    }

    private fun hostTime(id: Int): TextClock {
        return Shadows.shadowOf(manager).getViewFor(id).findViewById<TextClock>(R.id.clock_time)
    }

    companion object {
        private val CLOCK: Int = 25101
        private val SIBLING: Int = 25102

        private fun previewTime(a: MainActivity): TextClock {
            return layout(a).findViewById<TextClock>(R.id.clock_time)
        }

        @Throws(Exception::class)
        private fun openTime(a: MainActivity) {
            setClock(a, Clock.fixed(Instant.parse("2026-10-30T12:34:00Z"), ZoneOffset.UTC))
            refresh(a, false)
            val root: View = layout(a)
            if (root.findViewWithTag<View>("section-content:time").getVisibility() != View.VISIBLE)
                assertTrue(root.findViewWithTag<View>("section-toggle:time").performClick())
            layout(a)
        }

        private fun offset(a: MainActivity, value: Int) {
            val bar: SeekBar? = described(layout(a), "Time horizontal offset", SeekBar::class.java)
            assertNotNull(bar)
            val arguments: Bundle = Bundle()
            arguments.putFloat(
                AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                value.toFloat(),
            )
            assertTrue(
                bar!!.performAccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                    arguments,
                ),
            )
        }

        private fun <T : View> described(view: View, value: String, type: Class<T>): T? {
            if (type.isInstance(view) && value.contentEquals(view.getContentDescription()))
                return type.cast(view)
            if (view is ViewGroup)
                for (i: Int in 0 until (view as ViewGroup).getChildCount()) {
                    val found: T? = described((view as ViewGroup).getChildAt(i), value, type)
                    if (found != null) return found
                }
            return null
        }

        private fun layout(a: MainActivity): View {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            repeat(3) {
                val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                root.requestLayout()
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, 400, 850)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (root === a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) &&
                    a.preview.stage?.getWidgetContent() != null) {
                    WidgetTestViews.prepare(root)
                    return root
                }
            }
            error("The editor did not finish its initial layout")
        }

        @Throws(Exception::class)
        private fun setClock(a: MainActivity, clock: Clock) {
            a.preview.clock = clock
        }

        @Throws(Exception::class)
        private fun refresh(a: MainActivity, commit: Boolean) {
            a.preview.refresh(commit, null)
        }

        @Throws(Exception::class)
        private fun invoke(a: MainActivity, name: String) {
            val method = MainActivity::class.java.getDeclaredMethod(name)
            method.isAccessible = true
            method.invoke(a)
        }

        private fun advance(millis: Int) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis.toLong()))
        }
    }
}
