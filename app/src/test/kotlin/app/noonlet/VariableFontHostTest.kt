package app.noonlet

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Parcel
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.TextAppearanceSpan
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextClock
import java.io.File
import java.util.Arrays
import java.util.HashMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

/** Separate native static-system fallback from a controlled system-variable-font host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34, 36], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VariableFontHostTest {
    @Test
    @Throws(Exception::class)
    fun staticPrimaryReportsAndRendersDiscreteFallback() {
        assertFalse(
            WidgetFont.supportsSmoothWeight(
                "sans-serif-light",
            ),
        )
        probe("08:58", 52, intArrayOf(300, 350, 400, 450, 500), "static-digits", false)
        probe(
            "Пример",
            32,
            intArrayOf(350, 400, 450),
            "static-cyrillic",
            false,
        )
    }

    @Test
    @Throws(Exception::class)
    fun variableSystemDigitsInterpolateAndMatchMeasurement() {
        VariableSystemFontFixture()
            .use(
                { fixture ->
                    assertTrue(
                        WidgetFont.supportsSmoothWeight(
                            "sans-serif-light",
                        ),
                    )
                    probe("08:58", 52, intArrayOf(300, 350, 400, 450, 500), "variable-digits", true)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun variableSystemCyrillicInterpolateAndMatchMeasurement() {
        VariableSystemFontFixture()
            .use(
                { fixture ->
                    assertTrue(
                        WidgetFont.supportsSmoothWeight(
                            "sans-serif-light",
                        ),
                    )
                    probe(
                        "Пример",
                        32,
                        intArrayOf(350, 400, 450),
                        "variable-cyrillic",
                        true,
                    )
                },
            )
    }

    @Throws(Exception::class)
    private fun probe(text: String, size: Int, weights: IntArray, name: String, variable: Boolean) {
        val context: Context = RuntimeEnvironment.getApplication()
        val foreignHost: Context =
            object : ContextWrapper(context) {
                // Older SDK harnesses lack installed foreign APK paths; keep their resource guard
                // explicit.
                // API 36 exercises the actual different-package resource path.
                override fun getPackageName(): String {
                    return if (Build.VERSION.SDK_INT >= 35) "app.noonlet.testhost"
                    else super.getPackageName()
                }

                override fun isRestricted(): Boolean = true
            }
        val ink: LongArray = LongArray(weights.size)
        val noAxisInk: LongArray = LongArray(weights.size)
        for (index: Int in weights.indices) {
            val current: Bitmap = render(context, foreignHost, text, size, weights[index], false)
            val withoutAxis: Bitmap = render(context, foreignHost, text, size, weights[index], true)
            ink[index] = ink(current)
            noAxisInk[index] = ink(withoutAxis)
            current.recycle()
            withoutAxis.recycle()
        }
        val evidence: String =
            (name +
                " withAxis=" +
                Arrays.toString(ink) +
                " withoutAxis=" +
                Arrays.toString(noAxisInk) +
                " axisProbe=" +
                WidgetFont.supportsSmoothWeight("sans-serif-light"))
        val middle: Int = Arrays.binarySearch(weights, 400)
        if (!variable) {
            assertEquals(
                "Static-primary no-axis control must reproduce the plateau: " + evidence,
                noAxisInk[middle],
                noAxisInk[middle + 1],
            )
            assertArrayEquals(
                "Static primary glyphs must retain their supported fallback: " + evidence,
                noAxisInk,
                ink,
            )
            return
        }
        if (Build.VERSION.SDK_INT == 36) {
            assertArrayEquals(
                "API 36 single-variable-family matching already interpolates: " + evidence,
                noAxisInk,
                ink,
            )
        } else {
            assertEquals(
                "API 30/34 single-variable-family matching needs the explicit axis: " + evidence,
                noAxisInk[middle],
                noAxisInk[middle + 1],
            )
        }
        for (index: Int in 1 until weights.size) assertTrue(
            "A native host must distinguish successive weights: " + evidence,
            ink[index] > ink[index - 1],
        )
        val firstStep: Long = ink[middle] - ink[middle - 1]
        val nextStep: Long = ink[middle + 1] - ink[middle]
        assertTrue(
            "400 must be an intermediate stroke: " + evidence,
            Math.max(firstStep, nextStep) < Math.min(firstStep, nextStep) * 2,
        )
    }

    private fun render(
        context: Context,
        foreignHost: Context,
        text: String,
        size: Int,
        weight: Int,
        withoutAxis: Boolean,
    ): Bitmap {
        val sender: RemoteViews = RemoteViews(context.getPackageName(), R.layout.clock_widget)
        var format: CharSequence =
            WidgetFont.styled(context, "'" + text + "'", "sans-serif-light", weight)
        if (withoutAxis) {
            val canonical: Spanned = format as Spanned
            // The no-axis control needs a private copy to avoid mutating
            // the immutable description reused by subsequent real renders in this process.
            val copy: Parcel = Parcel.obtain()
            try {
                TextUtils.writeToParcel(format, copy, 0)
                copy.setDataPosition(0)
                format = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(copy)
            } finally {
                copy.recycle()
            }
            val styled: Spanned = format as Spanned
            val original: TextAppearanceSpan =
                canonical.getSpans(0, canonical.length, TextAppearanceSpan::class.java)[0]
            for (span: TextAppearanceSpan in
                styled.getSpans(0, styled.length, TextAppearanceSpan::class.java)) {
                assertNotSame("The no-axis fixture must own its mutated span", original, span)
                assertEquals(WidgetFont.variationSettings(weight), span.getFontVariationSettings())
                ReflectionHelpers.setField(span, "mFontVariationSettings", null)
            }
            assertEquals(
                "The no-axis control must not contaminate the cached native description",
                WidgetFont.variationSettings(weight),
                original.getFontVariationSettings(),
            )
        }
        sender.setCharSequence(R.id.clock_time, "setFormat12Hour", format)
        sender.setCharSequence(R.id.clock_time, "setFormat24Hour", format)
        sender.setViewVisibility(R.id.clock_date_row, View.GONE)
        sender.setTextViewTextSize(R.id.clock_time, TypedValue.COMPLEX_UNIT_PX, size.toFloat())
        val host: View = crossProcess(sender).apply(foreignHost, FrameLayout(foreignHost))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, 220, 80)
        val clock: TextClock = host.findViewById<TextClock>(R.id.clock_time)
        assertTrue(
            "The foreign host must use a restricted resource context",
            clock.getContext().isRestricted(),
        )
        assertEquals(text, clock.getText().toString())
        val actual: TextPaint = TextPaint(clock.getPaint())
        val displayed: Spanned = clock.getText() as Spanned
        val spans: Array<TextAppearanceSpan> =
            displayed.getSpans(0, displayed.length, TextAppearanceSpan::class.java)
        assertEquals(1, spans.size)
        assertNull("No process-local font object may cross IPC", spans[0].getTypeface())
        assertEquals("sans-serif", spans[0].getFamily())
        spans[0].updateMeasureState(actual)
        if (!withoutAxis) {
            assertEquals(WidgetFont.variationSettings(weight), actual.getFontVariationSettings())
            val measurement: TextPaint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            measurement.setTextSize(size.toFloat())
            measurement.setTypeface(WidgetFont.typeface("sans-serif-light", weight))
            assertEquals(
                "Renderer and host must fit the same glyph widths",
                measurement.measureText(text),
                actual.measureText(text),
                .1f,
            )
        }
        val rendered: Bitmap = Bitmap.createBitmap(220, 80, Bitmap.Config.ARGB_8888)
        host.draw(Canvas(rendered))
        return rendered
    }

    private fun ink(pixels: Bitmap): Long {
        var ink: Long = 0
        for (y: Int in 0 until pixels.getHeight()) for (x: Int in 0 until pixels.getWidth()) ink +=
            Color.alpha(pixels.getPixel(x, y))
        return ink
    }

    /**
     * Test-only platform font configuration. No fixture font is loaded from widget XML or sent by
     * IPC.
     */
    private class VariableSystemFontFixture @Throws(Exception::class) constructor() :
        AutoCloseable {
        private val original: MutableMap<String, Typeface>

        init {
            Typeface.create("sans-serif", Typeface.NORMAL)
            original = ReflectionHelpers.getStaticField(Typeface::class.java, "sSystemFontMap")
            val directory: File = File(System.getProperty("java.io.tmpdir"), "noonlet-check")
            directory.mkdirs()
            val file: File =
                java.nio.file.Files.createTempFile(
                        directory.toPath(),
                        "font-fixture-sdk" + Build.VERSION.SDK_INT + "-",
                        ".ttf",
                    )
                    .toFile()
            VariableFontHostTest::class
                .java!!
                .getResourceAsStream("/clock-font-fixtures/Roboto-VF.ttf")
                .use(
                    { input ->
                        assertNotNull(input)
                        java.nio.file.Files.write(file.toPath(), input.readAllBytes())
                    },
                )
            val variable: Typeface = Typeface.Builder(file).setWeight(400).build()
            assertNotNull(variable)
            val configured: MutableMap<String, Typeface> = HashMap(original)
            configured.put("sans-serif", variable)
            ReflectionHelpers.setStaticField(Typeface::class.java, "sSystemFontMap", configured)
        }

        override fun close() {
            ReflectionHelpers.setStaticField(Typeface::class.java, "sSystemFontMap", original)
        }
    }

    private fun crossProcess(sender: RemoteViews): RemoteViews {
        val parcel: Parcel = Parcel.obtain()
        val originalPid: Int = android.os.Process.myPid()
        try {
            sender.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            ShadowProcess.setPid(originalPid + 10000)
            return RemoteViews.CREATOR.createFromParcel(parcel)
        } finally {
            ShadowProcess.setPid(originalPid)
            parcel.recycle()
        }
    }
}
