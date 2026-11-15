package app.noonlet

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.text.Spanned
import android.text.TextPaint
import android.text.style.TextAppearanceSpan
import java.lang.reflect.Field
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cached descriptions retain the same portable native appearance as fresh resource loading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontAppearanceCacheTest {
    private lateinit var context: CountingContext

    @Before
    @Throws(Exception::class)
    fun prepare() {
        context = CountingContext(RuntimeEnvironment.getApplication())
        val field: Field = WidgetFont::class.java.getDeclaredField("APPEARANCES")
        field.setAccessible(true)
        val cache: MutableMap<*, *> = field.get(null) as MutableMap<*, *>
        synchronized(cache) {
            cache.clear()
        }
    }

    @Test
    fun allAliasesAndWeightsUseExactlyFiftyOnePortableDescriptions() {
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
                val first: TextAppearanceSpan =
                    span(WidgetFont.styled(context, "08:58", family, weight))
                val second: TextAppearanceSpan =
                    span(WidgetFont.styled(context, "different text", family, weight))
                assertSame("Text changes must reuse their immutable description", first, second)
                assertEquals(weight, first.getTextFontWeight())
                assertEquals(WidgetFont.renderFamily(family), first.getFamily())
                assertEquals(WidgetFont.variationSettings(weight), first.getFontVariationSettings())
                assertNull(
                    "A description must not retain a process-local font",
                    first.getTypeface(),
                )
                assertNull("Configured colors remain authoritative", first.getTextColor())
                assertEquals("Configured text sizes remain authoritative", 0, first.getTextSize())
                val paint: TextPaint = TextPaint()
                paint.setColor(-0xedcbaa)
                paint.setTextSize(23f)
                first.updateDrawState(paint)
                assertEquals(-0xedcbaa, paint.getColor())
                assertEquals(23f, paint.getTextSize(), 0f)
                weight += 50
            }
        }
        assertEquals("Three resolved families times seventeen styles", 51, context.resourceLoads)
        assertSame(
            span(WidgetFont.styled(context, "a", "sans-serif-light", 350)),
            span(WidgetFont.styled(context, "b", "sans-serif-bold", 350)),
        )
        assertEquals(51, context.resourceLoads)
    }

    @Test
    fun changedResourceConfigurationInvalidatesOnlyItsDescriptions() {
        val resources: Resources = context.getResources()
        val original: Configuration = Configuration(resources.getConfiguration())
        try {
            val before: TextAppearanceSpan = span(WidgetFont.styled(context, "a", "serif", 450))
            val sibling: CountingContext = CountingContext(context.getBaseContext())
            assertSame(before, span(WidgetFont.styled(sibling, "b", "serif", 450)))
            assertEquals(0, sibling.resourceLoads)
            val changed: Configuration = Configuration(original)
            changed.fontScale = original.fontScale + .1f
            resources.updateConfiguration(changed, resources.getDisplayMetrics())
            val after: TextAppearanceSpan = span(WidgetFont.styled(context, "c", "serif", 450))
            assertNotSame("Configuration-specific resources must be loaded again", before, after)
            assertEquals(2, context.resourceLoads)
            assertEquals(before.getFamily(), after.getFamily())
            assertEquals(before.getTextFontWeight(), after.getTextFontWeight())
            assertEquals(before.getFontVariationSettings(), after.getFontVariationSettings())
        } finally {
            resources.updateConfiguration(original, resources.getDisplayMetrics())
        }
    }

    private fun span(value: CharSequence): TextAppearanceSpan {
        return (value as Spanned).getSpans(0, value.length, TextAppearanceSpan::class.java)[0]
    }

    private class CountingContext(base: Context) : ContextWrapper(base) {
        var resourceLoads: Int = 0

        @Throws(PackageManager.NameNotFoundException::class)
        override fun createPackageContext(packageName: String, flags: Int): Context {
            resourceLoads++
            assertEquals(Context.CONTEXT_RESTRICTED, flags)
            val restricted: Context = super.createPackageContext(packageName, flags)
            assertTrue(restricted.isRestricted())
            return restricted
        }
    }
}
