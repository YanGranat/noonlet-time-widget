package app.noonlet

import android.content.Context
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

/** The editable time field accepts date tokens, so fitting must cover their changing values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomPatternFitTest {
    @Test
    fun weekdayInTheTimeFieldFitsBeyondTheSampleMonday() {
        val context: Context = RuntimeEnvironment.getApplication()
        val settings: WidgetSettings = WidgetSettings()
        settings.timeFormat = "EEEE"
        settings.textSize = WidgetSettings.MAX_TIME_SIZE
        settings.fontFamily = "serif"
        settings.timeFontWeight = 400
        settings.showDate = false
        settings.tapAction = "none"
        settings.zoneId = "UTC"
        val host: FrameLayout = FrameLayout(context)
        val root: View =
            WidgetRenderer.preview(context, -705, false, settings, 155f, 200f).apply(context, host)
        host.addView(root, FrameLayout.LayoutParams(155, 200))
        val time: TextClock = root.findViewById<TextClock>(R.id.clock_time)
        val wednesday: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ENGLISH)
        wednesday.clear()
        wednesday.set(2026, Calendar.SEPTEMBER, 30, 12, 0, 0)
        val literal: CharSequence =
            ClockPattern.previewLiteral(
                time.getFormat24Hour(),
                wednesday.getTimeInMillis(),
                TimeZone.getTimeZone("UTC"),
                Locale.ENGLISH,
            )
        time.setFormat12Hour(literal)
        time.setFormat24Hour(literal)
        host.measure(
            View.MeasureSpec.makeMeasureSpec(155, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, 155, 200)
        WidgetTestViews.prepare(root)
        assertEquals("Wednesday", time.getText().toString())
        val lineWidth: Float = time.getLayout().getLineWidth(0)
        assertTrue(
            "The next native weekday must fit the same widget",
            lineWidth <= time.getWidth() + 1,
        )
    }
}
