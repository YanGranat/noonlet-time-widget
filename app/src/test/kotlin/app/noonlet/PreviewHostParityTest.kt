package app.noonlet

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
@Config(sdk = [26, 34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewHostParityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val timestamp = Instant.parse("2031-02-18T09:23:00Z").toEpochMilli()

    @Before fun prepare() {
        check(SystemClock.setCurrentTimeMillis(timestamp))
        for (name in listOf("noonlet_widgets", "MainActivity", "widget_drafts", "noonlet_app"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        EditorPreferences(context).selectPreviewSize(false, WidgetSize.FOUR_BY_ONE.ordinal)
        WidgetSettingsStore.saveUnplaced(context, false, WidgetSettings.defaults(false).apply {
            showAlarm = true
            weatherEnabled = true
            weatherUseDevice = false
        })
        Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, "Wed 08:26")
    }

    @After fun cleanup() {
        Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, null)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun visibleTypographyMatchesANativeHostAtTheDisplayedDimensions() = verify(400)

    @Test
    @Config(qualifiers = "en-rUS-w460dp-h850dp-mdpi")
    fun widerEditorsKeepNativeTypographyAndRelativeBaselines() = verify(460)

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h850dp-xhdpi")
    fun denseDisplaysKeepNativeTypographyAndRelativeBaselines() = verify(400)

    @Test fun enlargedSystemTextKeepsNativeTypographyAndRelativeBaselines() {
        RuntimeEnvironment.setFontScale(1.3f)
        verify(400)
    }

    @Test fun aPlacedWidgetUsesItsLauncherDimensionsInsteadOfTheNominalGrid() {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WideClockWidgetProvider::class.java)
            initialLayout = R.layout.clock_widget
        }
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(5190, info)
        manager.updateAppWidgetOptions(5190, android.os.Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 306)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 520)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 64)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 94)
        })
        val settings = WidgetSettingsStore.loadUnplaced(context, false)
        WidgetSettingsStore.save(context, 5190, false, settings)
        val request = EditorIntents.edit(context, 5190, false)
        Robolectric.buildActivity(MainActivity::class.java, request).setup().visible().use { controller ->
            val activity = controller.get()
            activity.preview.clock = Clock.fixed(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC)
            activity.preview.refresh(false)
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            layout(root, 400, 850)
            val stage = root.findViewWithTag<WidgetPreviewStage>("preview-stage")
            val preview = stage.getWidgetContent()!!
            assertEquals(306, preview.width)
            assertEquals(94, preview.height)
            val host = WidgetRenderer.clock(activity, 5190).apply(activity, FrameLayout(activity))
            activity.findViewById<ViewGroup>(android.R.id.content).addView(host, FrameLayout.LayoutParams(306, 94))
            layout(host, 306, 94)
            for (id in listOf(R.id.clock_time, R.id.clock_date, R.id.clock_alarm, R.id.weather_caption)) {
                val actual = host.findViewById<TextView>(id)
                val shown = preview.findViewById<TextView>(id)
                assertEquals(actual.textSize, shown.textSize, .1f)
                assertEquals(left(actual, host), left(shown, preview), .5f)
                if (actual.visibility == View.VISIBLE)
                    assertEquals(baseline(actual, host), baseline(shown, preview), .5f)
            }
        }
    }

    private fun verify(width: Int) {
        val editor = Robolectric.buildActivity(MainActivity::class.java)
        editor.get().preview.clock = Clock.fixed(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC)
        editor.setup().visible().use { controller ->
            val activity = controller.get()
            val density = activity.resources.displayMetrics.density
            val widthPx = (width * density).roundToInt()
            val heightPx = (850 * density).roundToInt()
            var root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            layout(root, widthPx, heightPx)
            for (world in listOf(false, true)) {
                if (world) {
                    WidgetTestViews.selectWorldTab(activity)
                    root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    layout(root, widthPx, heightPx)
                }
                for (size in WidgetSize.entries) {
                    root.findViewWithTag<View>("preview-size:${size.ordinal}").performClick()
                    activity.preview.refresh(false)
                    layout(root, widthPx, heightPx)
                    val stage = root.findViewWithTag<WidgetPreviewStage>("preview-stage")
                    val board = stage.findViewWithTag<FrameLayout>("preview-widget")
                    val displayedWidth = (board.width * board.scaleX).roundToInt()
                    val displayedHeight = (board.height * board.scaleY).roundToInt()
                    val host = WidgetRenderer.preview(activity, -1, world, activity.session.settings,
                        displayedWidth / density, displayedHeight / density)
                        .apply(activity, FrameLayout(activity))
                    val hostParent = activity.findViewById<ViewGroup>(android.R.id.content)
                    hostParent.addView(host, FrameLayout.LayoutParams(displayedWidth, displayedHeight))
                    layout(host, displayedWidth, displayedHeight)
                    val ids = if (world) listOf(R.id.city_time, R.id.city_name, R.id.city_date)
                        else listOf(R.id.clock_time, R.id.clock_date, R.id.clock_alarm, R.id.weather_caption)
                    for (id in ids) {
                        val shown = stage.getWidgetContent()!!.findViewById<TextView>(id)
                        val actual = host.findViewById<TextView>(id)
                        assertNotNull("Preview element $id / ${size.label}", shown)
                        assertNotNull("Host element $id / ${size.label}", actual)
                        assertEquals(actual.visibility, shown.visibility)
                        if (actual.visibility != View.VISIBLE) continue
                        assertEquals("Displayed text for $id at width $width / ${size.label}",
                            actual.text.toString(), shown.text.toString())
                        assertEquals("Displayed text size for $id at width $width / ${size.label}",
                            actual.textSize, shown.textSize * board.scaleX, .1f)
                        assertEquals("Horizontal position for $id at width $width / ${size.label}",
                            left(actual, host), left(shown, stage.getWidgetContent()!!), .5f)
                        assertEquals("Text baseline for $id at width $width / ${size.label}",
                            baseline(actual, host), baseline(shown, stage.getWidgetContent()!!), .5f)
                    }
                    hostParent.removeView(host)
                }
            }
        }
    }

    private fun layout(view: View, width: Int, height: Int) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        WidgetTestViews.prepare(view)
    }

    private fun left(target: View, root: View): Float {
        var result = 0f
        var view = target
        while (view !== root) {
            result += view.x
            view = view.parent as View
        }
        return result
    }

    private fun baseline(text: TextView, root: View): Float {
        var position = text.baseline.toFloat()
        var view: View = text
        while (view !== root) {
            position += view.y
            view = view.parent as View
        }
        return position
    }
}
