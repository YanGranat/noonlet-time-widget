package app.noonlet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmVisibilityTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    fun hiddenEmptyAlarmMatchesDisabledAlarmGeometryAndPixels() {
        for ((width, height) in listOf(138 to 123, 328 to 174))
            for (orientation in listOf("vertical", "horizontal"))
                for (weather in listOf(false, true))
                    for (date in listOf(false, true)) {
                        val settings = WidgetSettings.defaults(false).apply {
                            this.orientation = WidgetOrientation.fromWire(orientation)
                            weatherEnabled = weather
                            showDate = date
                            timeFormat = "'08:24'"
                            dateFormat = "'Wed, 2 Jan'"
                            tapAction = "none"
                        }
                        val disabled = render(settings, width, height)
                        settings.showAlarm = true
                        val hidden = render(settings, width, height)
                        assertEquals(View.GONE, hidden.findViewById<View>(R.id.clock_alarm_row).visibility)
                        assertEquals(View.GONE, hidden.findViewById<View>(R.id.clock_alarm_icon).visibility)
                        assertEquals("", hidden.findViewById<TextView>(R.id.clock_alarm).text.toString())
                        assertTrue("Hidden alarm must not change the rendered composition",
                            draw(disabled, width, height).sameAs(draw(hidden, width, height)))
                    }
    }

    @Test
    fun explicitEmptyAlarmLabelStillRequiresAlarmVisibility() {
        val settings = WidgetSettings.defaults(false).apply {
            showAlarm = true
            showNoAlarm = true
        }
        val shown = render(settings, 328, 174)
        assertEquals(View.VISIBLE, shown.findViewById<View>(R.id.clock_alarm_row).visibility)
        assertEquals("No alarm", shown.findViewById<TextView>(R.id.clock_alarm).text.toString())
        settings.showAlarm = false
        val disabled = render(settings, 328, 174)
        assertEquals(View.GONE, disabled.findViewById<View>(R.id.clock_alarm_row).visibility)
    }

    @Test
    fun missingKeysDefaultToHiddenAndExplicitChoicesRoundTrip() {
        val defaults = WidgetSettings.defaults(false)
        SettingsCodec.readFrom(defaults, Bundle().apply { putBoolean("cfg_showAlarm", true) })
        assertTrue(defaults.showAlarm)
        assertFalse(defaults.showNoAlarm)
        defaults.showNoAlarm = true
        val snapshot = Bundle()
        SettingsCodec.writeEditorState(defaults, snapshot)
        val restored = WidgetSettings.defaults(false)
        SettingsCodec.readFrom(restored, snapshot)
        assertTrue(restored.showNoAlarm)
        WidgetSettingsStore.save(context, 731, false, restored)
        assertTrue(WidgetSettingsStore.load(context, 731, false).showNoAlarm)
    }

    private fun render(settings: WidgetSettings, width: Int, height: Int): View {
        val root = WidgetRenderer.preview(context, -1, false, settings, width.toFloat(), height.toFloat())
            .apply(context, FrameLayout(context))
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        WidgetTestViews.prepare(root)
        return root
    }

    private fun draw(root: View, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
}
