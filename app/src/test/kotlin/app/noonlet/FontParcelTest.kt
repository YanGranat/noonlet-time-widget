package app.noonlet

import android.content.Context
import android.graphics.Typeface
import android.os.Parcel
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.TextAppearanceSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowProcess

/** Verifies portable fonts with a different receiving process ID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontParcelTest {
    @Test
    @Config(sdk = [28])
    fun androidNineReceivesPortableFamilyAndBoldSpans() {
        val context = RuntimeEnvironment.getApplication() as Context
        val light = crossProcess(WidgetFont.styled(context, "08:42", "sans-serif", 100))
        val bold = crossProcess(WidgetFont.styled(context, "08:42", "sans-serif", 700))
        assertEquals("sans-serif-thin", light.getSpans(0, light.length, TypefaceSpan::class.java).single().family)
        assertTrue(light.getSpans(0, light.length, TextAppearanceSpan::class.java).isEmpty())
        assertEquals(Typeface.BOLD,
            bold.getSpans(0, bold.length, android.text.style.StyleSpan::class.java).single().style)
        val paint = TextPaint()
        for (span in bold.getSpans(0, bold.length, android.text.style.MetricAffectingSpan::class.java))
            span.updateMeasureState(paint)
        assertTrue(paint.typeface.isBold)
    }

    @Test
    fun allNumericWeightsAndFontFamiliesSurviveCrossProcessParcel() {
        val context: Context = RuntimeEnvironment.getApplication()
        val families: Array<String> =
            arrayOf<String>(
                "sans-serif",
                "sans-serif-light",
                "sans-serif-medium",
                "sans-serif-bold",
                "monospace",
                "serif",
            )
        for (family: String in families) {
            var weight: Int = 100
            while (weight <= 900) {
                val portable: Spanned =
                    crossProcess(WidgetFont.styled(context, "HH:mm", family, weight))
                val received: TextAppearanceSpan =
                    portable.getSpans(0, portable.length, TextAppearanceSpan::class.java)[0]
                assertEquals(weight, received.getTextFontWeight())
                val expectedFamily: String =
                    if ("serif".equals(family) || "monospace".equals(family)) family
                    else "sans-serif"
                assertEquals(expectedFamily, received.getFamily())
                assertNull(
                    "Font must not override the configured text color",
                    received.getTextColor(),
                )
                assertNull("Font objects must not cross process boundaries", received.getTypeface())
                val paint: TextPaint = TextPaint()
                assertEquals(
                    WidgetFont.variationSettings(weight),
                    received.getFontVariationSettings(),
                )
                received.updateMeasureState(paint)
                assertEquals(weight, paint.getTypeface().getWeight())
                assertFalse("Numeric weights must not add artificial bold", paint.isFakeBoldText())
                weight += 50
            }
        }
    }

    @Test
    fun typefaceObjectsDoNotTransferBetweenDifferentProcesses() {
        val pattern: SpannableString = SpannableString("HH:mm")
        pattern.setSpan(
            TypefaceSpan(
                Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 900, false),
            ),
            0,
            pattern.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val portable: Spanned = crossProcess(pattern)
        val received: TypefaceSpan =
            portable.getSpans(0, portable.length, TypefaceSpan::class.java)[0]
        assertNull(received.getTypeface())
    }

    @Test
    fun modernRemoteViewsPermitNativeIndependentTranslations() {
        val context: Context = RuntimeEnvironment.getApplication()
        val views: RemoteViews = RemoteViews(context.getPackageName(), R.layout.clock_widget)
        views.setFloat(R.id.clock_time, "setTranslationX", 7f)
        views.setFloat(R.id.clock_time, "setTranslationY", -4f)
        val inflated: View = views.apply(context, FrameLayout(context))
        assertEquals(7f, inflated.findViewById<View>(R.id.clock_time).getTranslationX(), 0f)
        assertEquals(-4f, inflated.findViewById<View>(R.id.clock_time).getTranslationY(), 0f)
    }

    private fun crossProcess(text: CharSequence): Spanned {
        val parcel: Parcel = Parcel.obtain()
        val originalPid: Int = android.os.Process.myPid()
        try {
            TextUtils.writeToParcel(text, parcel, 0)
            parcel.setDataPosition(0)
            ShadowProcess.setPid(originalPid + 10000)
            return TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel) as Spanned
        } finally {
            ShadowProcess.setPid(originalPid)
            parcel.recycle()
        }
    }

    @Test
    @Throws(Exception::class)
    fun presetGlyphsOrderMediumBoldAndWeight500Control() {
        val families: Array<String> =
            arrayOf<String>(
                "sans-serif-light",
                "sans-serif-medium",
                "sans-serif-bold",
                "sans-serif-medium",
            )
        val weights: IntArray = intArrayOf(300, 350, 450, 500)
        val ink: LongArray = LongArray(4)
        for (index: Int in 0..3) ink[index] = glyphInk(families[index], weights[index])
        val evidence: String = java.util.Arrays.toString(ink)
        // Static fallback may select the same outline for 300 and 350; the variable-host test
        // covers interpolation.
        assertTrue("Bold must draw more ink than Medium: " + evidence, ink[2] > ink[1])
        assertTrue(
            "Bold must draw less ink than the weight-500 control: " + evidence,
            ink[2] < ink[3],
        )
    }

    @Throws(Exception::class)
    private fun glyphInk(family: String, weight: Int): Long {
        val portable: Spanned =
            crossProcess(
                WidgetFont.styled(RuntimeEnvironment.getApplication(), "08:58", family, weight),
            )
        val paint: TextPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.setColor(android.graphics.Color.WHITE)
        paint.setTextSize(52f)
        portable
            .getSpans(0, portable.length, TextAppearanceSpan::class.java)[0]
            .updateMeasureState(paint)
        val pixels: android.graphics.Bitmap =
            android.graphics.Bitmap.createBitmap(200, 80, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(pixels).drawText("08:58", 4f, 65f, paint)
        var ink: Long = 0
        for (y: Int in 0..79) for (x: Int in 0..199) ink +=
            android.graphics.Color.alpha(pixels.getPixel(x, y))
        pixels.recycle()
        return ink
    }
}
