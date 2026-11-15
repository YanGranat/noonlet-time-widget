package app.noonlet

import android.graphics.Bitmap
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewFontTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun localAndPortableTextHaveTheSameNativeGlyphsAndMeasurements() {
        for (family in listOf("sans-serif", "serif", "monospace")) {
            for (weight in listOf(300, 350, 375, 400, 450, 525, 725)) {
                for (text in listOf("08:47", "Mon, 12 Jan", "Місто")) {
                    fun image(scope: WidgetFont.Scope): Bitmap {
                        val view = TextView(context).apply {
                            this.text = WidgetFont.styled(context, text, family, weight, scope)
                            textSize = 34f
                            setTextColor(android.graphics.Color.WHITE)
                            includeFontPadding = false
                        }
                        view.measure(View.MeasureSpec.makeMeasureSpec(380, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, 380, 80)
                        WidgetTestViews.prepare(view)
                        return WidgetTestViews.draw(view)
                    }
                    val portable = image(WidgetFont.Scope.PORTABLE)
                    val local = image(WidgetFont.Scope.LOCAL)
                    try { assertTrue("$family / $weight / $text", portable.sameAs(local)) }
                    finally { portable.recycle(); local.recycle() }
                }
            }
        }
    }

    @Test
    fun localFormatsUseCachedFacesWhileLauncherFormatsRemainPortable() {
        val settings = WidgetSettings()
        val parent = FrameLayout(context)
        for (scope in WidgetFont.Scope.entries) {
            val root = WidgetRenderer.preview(context, -1, false, settings, 328f, 82f, scope)
                .apply(context, parent)
            val format = root.findViewById<TextClock>(R.id.clock_time).format24Hour as Spanned
            assertEquals(scope == WidgetFont.Scope.PORTABLE,
                format.getSpans(0, format.length, TextAppearanceSpan::class.java).isNotEmpty())
            assertEquals(scope == WidgetFont.Scope.LOCAL,
                format.getSpans(0, format.length, TypefaceSpan::class.java).isNotEmpty())
        }
        for (weight in listOf(350, 375, 400, 450)) {
            val first = WidgetFont.typeface("sans-serif", weight)
            assertSame(first, WidgetFont.typeface("sans-serif-medium", weight))
            assertEquals(weight, first.weight)
        }
    }
}
