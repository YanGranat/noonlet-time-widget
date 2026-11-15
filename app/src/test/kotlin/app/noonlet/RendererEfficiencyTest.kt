package app.noonlet

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.ArrayList
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

/** Exercises the real renderer while counting the external work it requests. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30, 34, 36],
    qualifiers = "en-rUS-w400dp-h850dp-mdpi",
    shadows = [RendererEfficiencyTest.CountingAlarmManager::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RendererEfficiencyTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        CountingAlarmManager.reads = 0
    }

    @Test
    fun directDraftPreviewNeverLoadsOrWritesWidgetPreferences() {
        val stale: WidgetSettings = WidgetSettings()
        stale.zoneId = "Europe/London"
        WidgetSettingsStore.save(context, -701, false, stale)
        val draft: WidgetSettings = WidgetSettings()
        draft.zoneId = "Asia/Tokyo"
        draft.timeColor = -0xedcbaa
        draft.tapAction = "none"
        val noPreferences: Context =
            object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    throw AssertionError("Draft preview requested preferences: " + name)
                }
            }
        val view: View =
            WidgetRenderer.preview(noPreferences, -701, false, draft, 167f, 82f)
                .apply(context, FrameLayout(context))
        val time: TextClock = view.findViewById<TextClock>(R.id.clock_time)
        assertEquals("Asia/Tokyo", time.getTimeZone())
        assertEquals(draft.timeColor, time.getCurrentTextColor())
        assertEquals("Europe/London", WidgetSettingsStore.load(context, -701, false).zoneId)
    }

    @Test
    fun hiddenAlarmDoesNotLookUpOrMeasureSystemAlarmState() {
        val settings: WidgetSettings = WidgetSettings()
        settings.tapAction = "none"
        settings.showAlarm = false
        // An invalid alarm pattern must remain irrelevant while its line is hidden.
        settings.alarmFormat = "invalid alarm pattern"
        val noAlarm: Context =
            object : ContextWrapper(context) {
                override fun getSystemService(name: String): Any? {
                    if (Context.ALARM_SERVICE.equals(name))
                        throw AssertionError("Hidden alarm lookup")
                    return super.getSystemService(name)
                }
            }
        WidgetRenderer.preview(noAlarm, -702, false, settings, 167f, 82f)
        assertEquals(0, CountingAlarmManager.reads)
    }

    @Test
    @Config(sdk = [34, 36])
    fun sixteenResponsiveLayoutsReadOneAlarmSnapshot() {
        val id: Int = 703
        val settings: WidgetSettings = WidgetSettings()
        settings.showAlarm = true
        settings.showNoAlarm = true
        settings.alarmFormat = "HH:mm"
        settings.tapAction = "none"
        WidgetSettingsStore.save(context, id, false, settings)
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        val provider: AppWidgetProviderInfo = AppWidgetProviderInfo()
        provider.provider = ComponentName(context, ClockWidgetProvider::class.java)
        Shadows.shadowOf(manager).addBoundWidget(id, provider)
        val sizes: ArrayList<SizeF> = ArrayList()
        for (index: Int in 0..15) sizes.add(
            SizeF((167 + index * 10).toFloat(), (82 + index * 10).toFloat()),
        )
        val options: Bundle = Bundle()
        options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes)
        manager.updateAppWidgetOptions(id, options)
        CountingAlarmManager.reads = 0
        val complete: android.widget.RemoteViews = WidgetRenderer.clock(context, id)
        val variants: MutableList<android.widget.RemoteViews> =
            ReflectionHelpers.getField(complete, "mSizedRemoteViews")
        assertEquals(16, variants.size)
        assertEquals(
            "All alternatives must share one system alarm snapshot",
            1,
            CountingAlarmManager.reads,
        )
        for (variant: android.widget.RemoteViews in variants) {
            val view: View = variant.apply(context, FrameLayout(context))
            assertEquals(
                "No alarm",
                (view.findViewById<View>(R.id.clock_alarm) as TextView).getText().toString(),
            )
        }
        settings.showAlarm = false
        WidgetSettingsStore.save(context, id, false, settings)
        CountingAlarmManager.reads = 0
        WidgetRenderer.clock(context, id)
        assertEquals(
            "Hidden alarms stay absent even with sixteen layouts",
            0,
            CountingAlarmManager.reads,
        )
    }

    @Test
    fun directDraftMatchesThePersistedRendererGeometryAndTypography() {
        for (world: Boolean in booleanArrayOf(false, true)) {
            val settings: WidgetSettings = WidgetSettings.defaults(world)
            settings.cities = arrayOf<String>("Asia/Kathmandu", "Europe/London")
            settings.style = WorldClockStyle.LIGHT
            settings.fontFamily = "serif"
            settings.fontWeight = 450
            settings.timeFontWeight = 700
            settings.tapAction = "none"
            settings.timeFormat = "'08:58'"
            settings.dateFormat = "'Wed, 30 Sep'"
            val id = if (world) 705 else 704
            WidgetSettingsStore.save(context, id, world, settings)
            val manager = AppWidgetManager.getInstance(context)
            val provider = AppWidgetProviderInfo().apply {
                this.provider = ComponentName(
                    context,
                    if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
                )
            }
            Shadows.shadowOf(manager).addBoundWidget(id, provider)
            for (size: IntArray in
                arrayOf<IntArray>(
                    intArrayOf(167, 82),
                    intArrayOf(328, 82),
                    intArrayOf(167, 174),
                    intArrayOf(328, 174),
                )) {
                manager.updateAppWidgetOptions(
                    id,
                    Bundle().apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size[0])
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size[1])
                    },
                )
                val persisted = layout(
                    if (world) WidgetRenderer.world(context, id) else WidgetRenderer.clock(context, id),
                    size,
                )
                val direct: View =
                    layout(
                        WidgetRenderer.preview(
                            context,
                            -704,
                            world,
                            settings,
                            size[0].toFloat(),
                            size[1].toFloat(),
                        ),
                        size,
                    )
                assertEquals(snapshot(persisted), snapshot(direct))
            }
        }
    }

    private fun layout(remote: android.widget.RemoteViews, size: IntArray): View {
        val host: FrameLayout = FrameLayout(context)
        val view: View = remote.apply(context, host)
        host.addView(view, FrameLayout.LayoutParams(size[0], size[1]))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(size[0], View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size[1], View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, size[0], size[1])
        WidgetTestViews.prepare(view)
        return view
    }

    private fun snapshot(view: View): MutableList<String> {
        val result: MutableList<String> = ArrayList()
        var state: String =
            (view.getId().toString() +
                ":" +
                view.getVisibility() +
                ":" +
                view.getLeft() +
                ":" +
                view.getTop() +
                ":" +
                view.getWidth() +
                ":" +
                view.getHeight() +
                ":" +
                view.getTranslationX() +
                ":" +
                view.getTranslationY() +
                ":" +
                view.getPaddingLeft() +
                ":" +
                view.getPaddingTop() +
                ":" +
                view.getPaddingRight() +
                ":" +
                view.getPaddingBottom())
        if (view is TextView) {
            val text: TextView = view as TextView
            state += ":" + text.getTextSize() + ":" + text.getCurrentTextColor()
            if (text.getText() is android.text.Spanned && text.getText().length > 0)
                state += ":" + WidgetTestViews.weight(text)
        }
        if (view is TextClock) {
            val clock: TextClock = view as TextClock
            state +=
                ":" +
                    clock.getTimeZone() +
                    ":" +
                    clock.getFormat12Hour() +
                    ":" +
                    clock.getFormat24Hour()
        }
        result.add(state)
        if (view is ViewGroup)
            for (index: Int in 0 until (view as ViewGroup).getChildCount()) result.addAll(
                snapshot((view as ViewGroup).getChildAt(index)),
            )
        return result
    }

    @Implements(AlarmManager::class)
    class CountingAlarmManager {
        protected val nextAlarmClock: AlarmManager.AlarmClockInfo?
            @Implementation
            get() {
                reads++
                return null
            }

        companion object {
            var reads: Int = 0
        }
    }
}
