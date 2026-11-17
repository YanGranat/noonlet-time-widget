package app.noonlet

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypographyFitTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun fittedPatternsUseTheSamePixelConversionAsTextViews() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val settings = WidgetSettings()
            val batch = WidgetRenderBatch(context, -1, false, settings, false)
            val zone = TimeZone.getTimeZone("UTC")
            for (width in listOf(75f, 110f, 180f)) {
                val size = fitPattern(context, "HH:mm", 450, 65f, width, Locale.ENGLISH, zone, batch)
                val view = TextView(context).apply { textSize = size }
                val paint = measurePaint(context, 450, size, batch)
                assertEquals(view.textSize, paint.textSize, .01f)
                val maximum = ClockPattern.fittingSamples("HH:mm", Locale.ENGLISH, zone).maxOf(paint::measureText)
                assertTrue("Text must fit the available width", maximum <= width * context.resources.displayMetrics.density)
            }
        }
    }

    @Test fun explicitWidgetGeometryKeepsItsDirectionInAnRtlHost() {
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ar"))
            setLayoutDirection(Locale.forLanguageTag("ar"))
        }
        val rtl = context.createConfigurationContext(configuration)
        for (world in listOf(false, true)) {
            val settings = WidgetSettings.defaults(world)
            val views = WidgetRenderer.preview(rtl, -1, world, settings, 300f, 100f)
            val parent = FrameLayout(rtl).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val view = views.apply(rtl, parent)
            parent.addView(view)
            parent.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, 300, 100)
            assertEquals(View.LAYOUT_DIRECTION_LTR, view.findViewById<View>(R.id.widget_root).layoutDirection)
        }
    }
}
