package app.noonlet

import android.graphics.Paint
import android.graphics.Typeface
import java.util.HashMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** The measurement cache belongs to one operation, rather than the mutable system font map. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderFontScopeTest {
    @Test
    @Throws(Exception::class)
    fun oneBatchReusesItsThreeExactWeightsWithoutRounding() {
        val settings: WidgetSettings = WidgetSettings()
        settings.fontWeight = 375
        settings.timeFontWeight = 725
        settings.dateFontWeight = 525
        val batch: WidgetRenderBatch = batch(settings)
        val detail: Typeface = font(batch, 375)
        val time: Typeface = font(batch, 725)
        val date: Typeface = font(batch, 525)
        assertSame(detail, font(batch, 375))
        assertSame(time, font(batch, 725))
        assertSame(date, font(batch, 525))
        assertEquals(375, detail.getWeight())
        assertEquals(725, time.getWeight())
        assertEquals(525, date.getWeight())
        settings.timeFontWeight = settings.fontWeight
        settings.dateFontWeight = settings.fontWeight
        val sameWeight: WidgetRenderBatch = batch(settings)
        assertSame(font(sameWeight, settings.fontWeight), font(sameWeight, settings.timeFontWeight))
        assertSame(font(sameWeight, settings.fontWeight), font(sameWeight, settings.dateFontWeight))
    }

    @Test
    @Throws(Exception::class)
    fun theNextBatchObservesAnActualSystemFontMapChange() {
        Typeface.create("sans-serif", Typeface.NORMAL)
        val original: MutableMap<String, Typeface> =
            ReflectionHelpers.getStaticField(Typeface::class.java, "sSystemFontMap")
        val settings: WidgetSettings = WidgetSettings()
        settings.fontFamily = "sans-serif"
        settings.timeFontWeight = 400
        settings.fontWeight = settings.timeFontWeight
        settings.dateFontWeight = settings.timeFontWeight
        val before: WidgetRenderBatch = batch(settings)
        val first: Typeface = font(before, 400)
        try {
            val replacement: MutableMap<String, Typeface> = HashMap(original)
            replacement.put("sans-serif", Typeface.create("serif", Typeface.NORMAL))
            ReflectionHelpers.setStaticField(Typeface::class.java, "sSystemFontMap", replacement)
            assertSame("An operation must keep its own consistent face", first, font(before, 400))
            val after: Typeface = font(batch(settings), 400)
            assertNotSame("A new operation must not reuse the previous map's font", first, after)
            assertTrue(
                "The fixture must change actual native glyph advances",
                Math.abs(width(first) - width(after)) > .1f,
            )
            val direct: Typeface = WidgetFont.typeface("sans-serif", 400)
            assertEquals(width(direct), width(after), .01f)
        } finally {
            ReflectionHelpers.setStaticField(Typeface::class.java, "sSystemFontMap", original)
        }
    }

    private fun batch(settings: WidgetSettings) = WidgetRenderBatch(
        RuntimeEnvironment.getApplication(), 1, false, settings, false)

    private fun font(batch: WidgetRenderBatch, weight: Int) = batch.typeface(weight)

    private fun width(typeface: Typeface): Float {
        val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.setTypeface(typeface)
        paint.setTextSize(40f)
        return paint.measureText("08:58 Riyadh")
    }
}
